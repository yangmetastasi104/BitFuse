package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.canvas.SpotifyCanvas
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Puts the track being played here on the account's Spotify, too.
 *
 * MetroFuse's trick, ported: the app registers itself as a Spotify Connect
 * device ("Web Player"), asks that device to play the track, and then keeps
 * its playhead in sync. Spotify therefore shows the song as playing on that
 * device and feeds it into "Recently played" — even though the audio actually
 * came from another provider.
 *
 * The pieces, in order:
 *  1. `apresolve` gives the dealer + spclient hosts.
 *  2. A dealer websocket yields the connection id.
 *  3. `POST /track-playback/v1/devices` registers the device; the account
 *     then sees it in the device list.
 *  4. `POST /connect-state/v1/player/command/...` asks it to play the track;
 *     the dealer pushes back the playback state (state machine, playback id).
 *  5. `PUT /track-playback/v1/devices/{id}/state` reports start, pause,
 *     resume, seek and finalize.
 *  6. `melody/v1/msg/batch` gets the playback start / stats events.
 *
 * Honest limits: these are Spotify's internal endpoints, undocumented and
 * free to change or to answer 429. Everything here is best-effort: a failure
 * only means the account does not show the track, never that playback stops.
 * Toggleable with [AppSettings.spotifyScrobbleEnabled].
 */
object SpotifyConnectPresence {

    private const val TAG = "BitChord"

    private const val APRESOLVE_URL = "https://apresolve.spotify.com/?type=dealer-g2&type=spclient"
    private const val BATCH_URL = "https://gew1-spclient.spotify.com/melody/v1/msg/batch"

    private const val CLIENT_VERSION = "0.0.0"
    private const val PLATFORM = "web_player windows 10;chrome 148.0.0.0;desktop"
    private const val SDK_ID = "harmony:4.72.0"
    private const val DEVICE_MODEL = "harmony-4.72.0-web-player"
    private const val DEVICE_NAME = "BitFuse"
    private const val DEVICE_CLIENT_ID = "spotify-web-player"
    private const val BITRATE = 128_000

    private const val DEVICE_TTL_MS = 45 * 60 * 1000L
    private const val DEALER_TIMEOUT_MS = 10_000L
    private const val MIN_DURATION_MS = 30_000L

    /**
     * When the play is handed to Spotify's history. Spotify's own web player
     * reports a track once it has been listened to for half its length, or 50
     * seconds, whichever comes first. Without that report the device shows a
     * track briefly and the account never records it, which is what "syncs
     * sometimes" looked like.
     */
    private const val THRESHOLD_CAP_MS = 50_000L
    private const val THRESHOLD_MIN_MS = 30_000L
    private const val THRESHOLD_RETRY_MS = 15_000L
    private const val RATE_LIMIT_FALLBACK_MS = 60_000L
    private const val UNMATCHED_TTL_MS = 60_000L

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/148.0.0.0 Safari/537.36"
    private const val REFERER = "https://open.spotify.com/"
    private const val ORIGIN = "https://open.spotify.com"

    private val DEALER_CONNECTION_REGEX =
        Regex("""hm://pusher/(?:[^/]+/)?connections/([^/?#]+)""")

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceMutex = Mutex()
    private val random = SecureRandom()
    private val dealerClient = Http.client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var device: Device? = null
    @Volatile private var session: Session? = null
    @Volatile private var blockedUntilMs = 0L
    @Volatile private var pendingVideoId: String? = null

    /** Bumped on every track change; a stale search result is thrown away. */
    private val generation = AtomicInteger(0)

    /** Rows Spotify had no defensible match for, retried after a while. */
    private val unmatched = ConcurrentHashMap<String, Long>()

    private val _active = MutableStateFlow(false)

    /** True while Spotify considers a BitFuse device to be playing. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _failing = MutableStateFlow(false)

    /**
     * True when the last attempt to hand a track to Spotify failed - no
     * catalogue match, a refused state, an expired token - and it hasn't been
     * followed by one that worked.
     *
     * The player's Spotify light reads this: a connection that is up is not the
     * same claim as a play that landed, and the interesting failure is the
     * second one. Nothing here is per-track state the UI needs to own; the log
     * for the track that failed says what happened.
     */
    val failing: StateFlow<Boolean> = _failing.asStateFlow()

    // ---- entry points, called from the playback service ------------------

    /**
     * A new track became the current one. Duration comes from the player when
     * it already knows it, otherwise from the row's `m:ss` text.
     */
    fun onTrackStart(song: Song?, durationMs: Long?) {
        if (!enabled()) {
            shutDown()
            return
        }
        if (song == null) {
            stopSilently()
            return
        }
        val duration = durationMs?.takeIf { it > 0L } ?: durationFromText(song.durationText)
        if (duration == null || duration < MIN_DURATION_MS) {
            stopSilently()
            return
        }

        // A row that came from Spotify (or was already matched) resolves here,
        // without a search.
        val knownUri = SpotifySession.spotifyUriFor(song)
        val knownId = knownUri?.spotifyTrackId()
        if (knownUri != null && knownId != null) {
            openSession(knownUri, knownId, duration, song.videoId)
            return
        }

        val current = session
        if (current != null && current.songKey == song.videoId && !current.finalized) {
            current.durationMs = maxOf(current.durationMs, duration)
            return
        }
        if (pendingVideoId == song.videoId) return
        if (recentlyUnmatched(song.videoId)) return

        // Everything else — a song searched inside BitFuse — has no Spotify
        // identity yet: search the catalogue for it, then open the session.
        stopSilently()
        pendingVideoId = song.videoId
        val mark = generation.incrementAndGet()
        scope.launch {
            try {
                TrackLog.d(
                    TAG,
                    "Spotify Connect: matching \"${song.title}\" / \"${song.artist}\"",
                    about = song.videoId,
                )
                val result = SpotifyCanvas.searchTrackUri(
                    title = song.title,
                    artist = song.artist,
                    durationMs = duration,
                    cookieHeader = cookie(),
                )
                val found = result.uri
                val foundId = found?.spotifyTrackId()
                if (generation.get() != mark) return@launch
                if (found == null || foundId == null) {
                    unmatched[song.videoId] = System.currentTimeMillis()
                    if (unmatched.size > 500) {
                        val cutoff = System.currentTimeMillis() - UNMATCHED_TTL_MS
                        unmatched.entries.removeAll { it.value < cutoff }
                    }
                    TrackLog.d(
                        TAG,
                        "Spotify Connect: no Spotify match for \"${song.title}\" / \"${song.artist}\" (${result.detail})",
                        about = song.videoId,
                    )
                    _failing.value = true
                    return@launch
                }
                TrackLog.d(
                    TAG,
                    "Spotify Connect: matched \"${song.title}\" -> $found (${result.detail})",
                    about = song.videoId,
                )
                SpotifySession.noteResolved(foundId, song.videoId)
                openSession(found, foundId, duration, song.videoId)
            } catch (t: Throwable) {
                TrackLog.w(
                    TAG,
                    "Spotify Connect match failed: ${t.message}",
                    about = song.videoId,
                )
                _failing.value = true
            } finally {
                if (generation.get() == mark) pendingVideoId = null
            }
        }
    }

    private fun openSession(uri: String, trackId: String, duration: Long, songKey: String) {
        // A known identity cancels any search still in flight for a previous row.
        generation.incrementAndGet()
        pendingVideoId = null
        val current = session
        if (current != null && current.trackUri == uri && !current.finalized) {
            current.durationMs = maxOf(current.durationMs, duration)
            return
        }
        session = null
        finalize(current)

        val fresh = Session(
            trackUri = uri,
            trackId = trackId,
            songKey = songKey,
            durationMs = duration,
            stateMachineId = randomStateMachineId(),
            stateId = randomHex(16),
            playbackId = UUID.randomUUID().toString(),
        )
        session = fresh
        scope.launch { begin(fresh) }
    }

    private fun recentlyUnmatched(videoId: String): Boolean {
        val stamp = unmatched[videoId] ?: return false
        if (System.currentTimeMillis() - stamp < UNMATCHED_TTL_MS) return true
        unmatched.remove(videoId)
        return false
    }

    /** Play/pause from the player. Sends the matching presence state. */
    fun onIsPlayingChanged(isPlaying: Boolean) {
        val current = session ?: return
        if (!current.startReported || current.finalized) return
        val paused = !isPlaying
        if (paused) current.freezePosition() else current.resumePosition()
        if (paused) pauseThresholdTimer(current) else startThresholdTimer(current)
        if (current.lastControlPaused == paused) return
        current.lastControlPaused = paused
        current.paused = paused
        val positionMs = current.currentPosition()
        val previousMs = positionMs
        scope.launch {
            val dev = device ?: return@launch
            val cookie = cookie() ?: return@launch
            val token = token() ?: return@launch
            sendState(
                dev = dev,
                cookie = cookie,
                token = token,
                target = current,
                operation = if (paused) "pause" else "resume",
                debugSource = if (paused) "pause" else "resume",
                positionMs = positionMs,
                previousPositionMs = previousMs,
                includePlaybackStats = false,
                pausedOverride = paused,
            )
        }
    }

    /** A seek inside the current track. Sub-second jitter is ignored. */
    fun onSeek(positionMs: Long, isPlaying: Boolean) {
        val current = session ?: return
        if (!current.startReported || current.finalized) return
        val previousMs = current.currentPosition()
        current.moveTo(positionMs, isPlaying)
        if (abs(positionMs - previousMs) < 1_000L) return
        current.paused = !isPlaying
        current.lastControlPaused = !isPlaying
        scope.launch {
            val dev = device ?: return@launch
            val cookie = cookie() ?: return@launch
            val token = token() ?: return@launch
            sendState(
                dev = dev,
                cookie = cookie,
                token = token,
                target = current,
                operation = "seek",
                debugSource = "seek",
                positionMs = positionMs,
                previousPositionMs = previousMs,
                includePlaybackStats = false,
                pausedOverride = !isPlaying,
            )
        }
    }

    /** Track ended, skipped or playback stopped. Closes the presence session. */
    fun onTrackStop() {
        stopSilently()
    }

    /** Session cookie changed or the feature was turned off. */
    fun shutDown() {
        stopSilently()
        _failing.value = false
        deviceMutex.tryLock()
        runCatching {
            device?.close()
            device = null
        }
        runCatching { deviceMutex.unlock() }
    }

    // ---- session lifecycle ------------------------------------------------

    private fun stopSilently() {
        generation.incrementAndGet()
        pendingVideoId = null
        val current = session
        session = null
        finalize(current)
        _active.value = false
    }

    private fun finalize(current: Session?) {
        if (current == null) return
        current.finalized = true
        current.thresholdJob?.cancel()
        current.thresholdJob = null
        if (!current.startReported) return
        val positionMs = current.finalPosition()
        scope.launch {
            val dev = device ?: return@launch
            val cookie = cookie() ?: return@launch
            val token = token() ?: return@launch
            sendBatchFinalize(cookie, token, current, positionMs)
            sendState(
                dev = dev,
                cookie = cookie,
                token = token,
                target = current,
                operation = "finalize",
                debugSource = "track_data_finalized",
                positionMs = positionMs,
                previousPositionMs = positionMs,
                includePlaybackStats = true,
                pausedOverride = null,
            )
            _active.value = false
        }
    }

    /**
     * Arms the report that actually puts the track in the account's history.
     *
     * Half the track or 50 seconds, whichever is shorter, matching what the
     * Spotify web player sends. Pausing banks the remaining time; resuming
     * continues from there (see [pauseThresholdTimer]).
     */
    private fun startThresholdTimer(target: Session) {
        if (target.finalized || target.thresholdReported || !target.startReported) return
        if (!target.thresholdArmed) {
            if (target.durationMs < MIN_DURATION_MS) return
            target.thresholdRemainingMs =
                minOf(target.durationMs / 2, THRESHOLD_CAP_MS).coerceAtLeast(0L)
            target.thresholdArmed = true
        }
        if (target.thresholdJob?.isActive == true) return
        if (target.thresholdRemainingMs <= 0L) {
            reportThreshold(target)
            return
        }
        target.thresholdStartedAtMs = System.currentTimeMillis()
        val wait = target.thresholdRemainingMs
        target.thresholdJob = scope.launch {
            delay(wait)
            reportThreshold(target)
        }
    }

    private fun pauseThresholdTimer(target: Session) {
        target.thresholdJob?.cancel()
        target.thresholdJob = null
        if (target.thresholdStartedAtMs != 0L) {
            target.thresholdRemainingMs -= System.currentTimeMillis() - target.thresholdStartedAtMs
            if (target.thresholdRemainingMs < 0L) target.thresholdRemainingMs = 0L
            target.thresholdStartedAtMs = 0L
        }
    }

    private fun reportThreshold(target: Session) {
        if (session !== target || target.finalized || target.thresholdReported) return
        scope.launch {
            val dev = device ?: return@launch
            val cookie = cookie() ?: return@launch
            val token = token() ?: return@launch
            val positionMs = target.currentPosition()
            val reportPositionMs = maxOf(positionMs, THRESHOLD_MIN_MS).let { position ->
                if (target.durationMs > 0L) position.coerceAtMost(target.durationMs) else position
            }
            val accepted = sendState(
                dev, cookie, token, target,
                operation = "threshold",
                debugSource = "played_threshold_reached",
                positionMs = reportPositionMs,
                previousPositionMs = positionMs,
                includePlaybackStats = false,
                pausedOverride = false,
            )
            if (accepted) {
                target.thresholdReported = true
                TrackLog.d(
                    TAG,
                    "Spotify Connect: play reported for ${target.trackUri} at ${reportPositionMs / 1000}s",
                    about = target.songKey,
                )
            } else if (session === target && !target.finalized) {
                // One more try later: the state may have been re-created by
                // Spotify and the stale ids are only good for one rejection.
                target.thresholdJob = null
                target.thresholdRemainingMs = THRESHOLD_RETRY_MS
                target.thresholdStartedAtMs = 0L
                startThresholdTimer(target)
            }
        }
    }

    private suspend fun begin(target: Session) {
        runCatching {
            if (!enabled() || isBackedOff()) return
            val cookie = cookie() ?: run {
                _failing.value = true
                return
            }
            val token = token() ?: run {
                TrackLog.w(TAG, "Spotify Connect: no web token")
                _failing.value = true
                return
            }
            val dev = ensureDevice(cookie, token) ?: run {
                _failing.value = true
                return
            }
            val state = requestPlaybackState(dev, cookie, token, target) ?: run {
                TrackLog.w(
                    TAG,
                    "Spotify Connect: no playback state for ${target.trackUri}",
                    about = target.songKey,
                )
                _failing.value = true
                return
            }
            if (session !== target || target.finalized) return
            target.applyPlaybackState(state)

            if (!sendBatchStart(cookie, token, target)) return
            val beforeLoadAccepted = sendState(
                dev, cookie, token, target,
                operation = "before-track-load",
                debugSource = "before_track_load",
                positionMs = 0L,
                previousPositionMs = 0L,
                includePlaybackStats = false,
                pausedOverride = null,
            )
            val startAccepted = sendState(
                dev, cookie, token, target,
                operation = "start",
                debugSource = "started_playing",
                positionMs = minOf(1_004L, target.durationMs.coerceAtLeast(0L)),
                previousPositionMs = 0L,
                includePlaybackStats = false,
                pausedOverride = null,
            )
            if (!beforeLoadAccepted || !startAccepted) {
                TrackLog.w(
                    TAG,
                    "Spotify Connect: Spotify refused the start state for ${target.trackUri} " +
                        "(before=$beforeLoadAccepted, start=$startAccepted)",
                    about = target.songKey,
                )
                _failing.value = true
                return
            }
            target.startReported = true
            target.lastControlPaused = target.paused
            _failing.value = false
            _active.value = true
            TrackLog.d(
                TAG,
                "Spotify Connect: ${target.trackUri} is now playing on \"$DEVICE_NAME\"",
                about = target.songKey,
            )
            if (target.paused) {
                val positionMs = target.currentPosition()
                sendState(
                    dev, cookie, token, target,
                    operation = "pause",
                    debugSource = "pause",
                    positionMs = positionMs,
                    previousPositionMs = positionMs,
                    includePlaybackStats = false,
                    pausedOverride = true,
                )
            } else {
                startThresholdTimer(target)
            }
        }.onFailure {
            TrackLog.w(TAG, "Spotify Connect start failed: ${it.message}", about = target.songKey)
            _failing.value = true
        }
    }

    // ---- device registration ---------------------------------------------

    private suspend fun ensureDevice(cookie: String, token: String): Device? =
        deviceMutex.withLock {
            val now = System.currentTimeMillis()
            val cookieHash = cookie.hashCode()
            device?.let { existing ->
                if (!existing.closed &&
                    existing.cookieHash == cookieHash &&
                    now - existing.createdAtMs < DEVICE_TTL_MS
                ) {
                    return@withLock existing
                }
            }
            device?.close()
            device = null

            runCatching {
                val endpoints = resolveEndpoints()
                val connection = connectDealer(endpoints.dealerUrl, token)
                registerDevice(cookie, token, cookieHash, endpoints.webgateUrl, connection)
            }.onFailure {
                TrackLog.w(TAG, "Spotify Connect device registration failed: ${it.message}")
            }.getOrNull()?.also { device = it }
        }

    private fun resolveEndpoints(): Endpoints {
        val request = Request.Builder()
            .url(APRESOLVE_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("apresolve HTTP ${response.code}")
            val root = json.parseToJsonElement(response.body.string()).jsonObjectOrNull()
                ?: error("apresolve body")
            val dealer = root.arrayOf("dealer-g2")
                ?.firstOrNull()
                ?.asStringOrNull()
                ?: "dealer.g2.spotify.com"
            val webgate = root.arrayOf("spclient")
                ?.firstOrNull()
                ?.asStringOrNull()
                ?: "spclient.wg.spotify.com"
            return Endpoints("https://$dealer", "https://$webgate")
        }
    }

    private suspend fun connectDealer(dealerUrl: String, token: String): DealerConnection {
        val deferred = CompletableDeferred<DealerConnection>()
        val queue = CommandQueue()
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        val request = Request.Builder()
            .url("$dealerUrl?access_token=$encodedToken")
            .build()
        var socket: WebSocket? = null
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseDealerCommand(text)?.let(queue::offer)
                parseConnectionId(text)?.let { id ->
                    deferred.complete(DealerConnection(id, webSocket, queue))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!deferred.isCompleted) deferred.completeExceptionally(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(IllegalStateException("dealer closed early"))
                }
            }
        }
        socket = dealerClient.newWebSocket(request, listener)
        return withTimeoutOrNull(DEALER_TIMEOUT_MS) { deferred.await() } ?: run {
            socket.close(1000, "connect-timeout")
            error("dealer connection id timed out")
        }
    }

    private fun registerDevice(
        cookie: String,
        token: String,
        cookieHash: Int,
        webgateUrl: String,
        connection: DealerConnection,
    ): Device {
        val deviceId = randomHex(20)
        val observerId = registerObserver(cookie, token, webgateUrl, deviceId, connection.id)
        val payload = buildJsonObject {
            put("device", deviceJson(deviceId))
            put("outro_endcontent_snooping", false)
            put("connection_id", connection.id)
            put("client_version", SDK_ID)
            put("volume", 65_535)
        }
        val request = Request.Builder()
            .url("$webgateUrl/track-playback/v1/devices")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("App-Platform", "WebPlayer")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .header("Cookie", cookie)
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        Http.client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                connection.socket.close(1000, "device-rejected")
                if (response.code == 429) noteRateLimit(response)
                error("device HTTP ${response.code}: ${body.take(160)}")
            }
            val initialSequence = runCatching {
                json.parseToJsonElement(body).jsonObjectOrNull()?.numberOf("initial_seq_num")
            }.getOrNull()?.toInt() ?: 0
            return Device(
                cookieHash = cookieHash,
                deviceId = deviceId,
                webgateUrl = webgateUrl,
                connectionId = connection.id,
                observerDeviceId = observerId,
                socket = connection.socket,
                commandQueue = connection.queue,
                initialSequenceNumber = initialSequence,
            )
        }
    }

    private fun registerObserver(
        cookie: String,
        token: String,
        webgateUrl: String,
        deviceId: String,
        connectionId: String,
    ): String? = runCatching {
        val observerId = "hobs_$deviceId".take(40)
        val payload = buildJsonObject {
            put("member_type", "CONNECT_STATE")
            putJsonObject("device") {
                putJsonObject("device_info") {
                    putJsonObject("capabilities") {
                        put("can_be_player", false)
                        put("hidden", true)
                        put("needs_full_player_state", true)
                    }
                }
            }
        }
        val request = Request.Builder()
            .url("$webgateUrl/connect-state/v1/devices/$observerId")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("X-Spotify-Connection-Id", connectionId)
            .header("App-Platform", "WebPlayer")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .header("Cookie", cookie)
            .header("Authorization", "Bearer $token")
            .put(payload.toString().toRequestBody(mediaType))
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (response.code == 429) noteRateLimit(response)
            if (response.isSuccessful) observerId else null
        }
    }.getOrNull()

    private fun deviceJson(deviceId: String): JsonObject = buildJsonObject {
        put("brand", "SpotifyHarmonyGeneric")
        putJsonObject("capabilities") {
            put("change_volume", true)
            put("enable_play_token", true)
            put("supports_file_media_type", true)
            put("play_token_lost_behavior", "pause")
            put("disable_connect", false)
            put("audio_podcasts", true)
            put(
                "manifest_formats",
                buildJsonArray {
                    add(JsonPrimitive("file_ids_mp3"))
                    add(JsonPrimitive("file_urls_mp3"))
                    add(JsonPrimitive("file_ids_mp4"))
                    add(JsonPrimitive("file_ids_mp4_dual"))
                },
            )
            put("supports_preferred_media_type", true)
            put("supports_playback_offsets", true)
            put("supports_playback_speed", true)
        }
        put("device_id", deviceId)
        put("device_type", "computer")
        putJsonObject("metadata") { }
        put("model", DEVICE_MODEL)
        put("name", DEVICE_NAME)
        put("platform_name", PLATFORM)
        put("platform_identifier", PLATFORM)
        put("is_group", false)
        put("correlation_id", randomHex(16))
        put("client_version", SDK_ID)
    }

    // ---- playback state ---------------------------------------------------

    private suspend fun requestPlaybackState(
        dev: Device,
        cookie: String,
        token: String,
        target: Session,
    ): PlaybackState? {
        val contexts = listOf(
            target.trackUri,
            "spotify:station:track:${target.trackId}",
        )
        for (context in contexts) {
            val commandWait = dev.commandQueue.next()
            if (!sendPlayCommand(dev, cookie, token, target.trackUri, context)) continue
            val state = withTimeoutOrNull(DEALER_TIMEOUT_MS) {
                var wait = commandWait
                while (true) {
                    val parsed = parsePlaybackState(wait.await(), target.trackId)
                    if (parsed != null) return@withTimeoutOrNull parsed
                    wait = dev.commandQueue.next()
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
            if (state != null) return state
        }
        return null
    }

    private fun sendPlayCommand(
        dev: Device,
        cookie: String,
        token: String,
        trackUri: String,
        contextUri: String,
    ): Boolean {
        val payload = buildJsonObject {
            putJsonObject("command") {
                put("endpoint", "play")
                putJsonObject("context") {
                    put("uri", contextUri)
                    put("url", "context://$contextUri")
                }
                putJsonObject("play_origin") {
                    put("feature_identifier", "web-player")
                    put("feature_version", SDK_ID)
                }
                putJsonObject("options") {
                    put("license", "")
                    putJsonObject("skip_to") { put("track_uri", trackUri) }
                    put("initially_paused", false)
                }
                putJsonObject("logging_params") {
                    put("page_instance_ids", JsonArray(emptyList()))
                    put("interaction_ids", JsonArray(emptyList()))
                    put("command_id", randomHex(16))
                }
            }
        }
        val request = Request.Builder()
            .url("${dev.webgateUrl}/connect-state/v1/player/command/from/${dev.deviceId}/to/${dev.deviceId}")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("App-Platform", "WebPlayer")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .header("Cookie", cookie)
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (response.code == 429) noteRateLimit(response)
                if (!response.isSuccessful &&
                    response.code in listOf(400, 401, 403, 404, 409)
                ) {
                    clearDevice(dev)
                }
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    private suspend fun sendState(
        dev: Device,
        cookie: String,
        token: String,
        target: Session,
        operation: String,
        debugSource: String,
        positionMs: Long,
        previousPositionMs: Long,
        includePlaybackStats: Boolean,
        pausedOverride: Boolean?,
    ): Boolean {
        val payload = buildJsonObject {
            val paused = pausedOverride ?: target.paused
            put("seq_num", dev.nextSequence())
            put("debug_source", debugSource)
            put("previous_position", previousPositionMs.coerceAtLeast(0L))
            putJsonObject("state_ref") {
                put("state_machine_id", target.stateMachineId)
                put("state_id", target.stateId)
                put("paused", paused)
            }
            putJsonObject("sub_state") {
                put("playback_speed", if (paused) 0 else 1)
                put("position", positionMs.coerceAtLeast(0L))
                put("duration", target.durationMs.coerceAtLeast(0L))
                put("media_type", "AUDIO")
                put("bitrate", BITRATE)
                put("audio_quality", "HIGH")
                put("format", "file_ids_mp4")
                put("is_video_on", false)
            }
            if (includePlaybackStats) {
                putJsonObject("playback_stats") {
                    put("ms_total_est", target.durationMs.coerceAtLeast(0L))
                    put("ms_metadata_duration", 0)
                    put("ms_manifest_latency", 0)
                    put("ms_latency", 98)
                }
            }
        }
        val request = Request.Builder()
            .url("${dev.webgateUrl}/track-playback/v1/devices/${dev.deviceId}/state")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("App-Platform", "WebPlayer")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .header("Cookie", cookie)
            .header("Authorization", "Bearer $token")
            .put(payload.toString().toRequestBody(mediaType))
            .build()

        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                val body = response.body.string()
                when {
                    response.isSuccessful -> {
                        parseUpdatedState(body)?.let(target::applyPlaybackState)
                        true
                    }
                    response.code == 429 -> {
                        noteRateLimit(response)
                        false
                    }
                    response.code == 410 && operation == "finalize" -> true
                    response.code in listOf(400, 401, 403, 404, 409) -> {
                        clearDevice(dev)
                        false
                    }
                    else -> {
                        TrackLog.w(TAG, "Spotify Connect $operation rejected: HTTP ${response.code}")
                        false
                    }
                }
            }
        }.onFailure {
            TrackLog.w(TAG, "Spotify Connect $operation failed: ${it.message}")
            clearDevice(dev)
        }.getOrDefault(false)
    }

    // ---- melody batch -----------------------------------------------------

    private suspend fun sendBatchStart(cookie: String, token: String, target: Session): Boolean {
        val event = buildJsonObject {
            put("type", "jssdk_playback_start")
            putJsonObject("message") {
                put("play_track", target.trackUri)
                put("file_id", "")
                put("playback_id", target.playbackId)
                put("session_id", target.sessionId)
                put("ms_start_position", 0)
                put("initially_paused", false)
                put("client_id", DEVICE_CLIENT_ID)
                put("correlation_id", target.correlationId)
                put("feature_identifier", "web-player")
            }
        }
        return sendBatch(cookie, token, listOf(event))
    }

    private suspend fun sendBatchFinalize(
        cookie: String,
        token: String,
        target: Session,
        positionMs: Long,
    ) {
        val stats = buildJsonObject {
            put("type", "jssdk_playback_stats")
            putJsonObject("message") {
                put("play_track", target.trackUri)
                put("file_id", "")
                put("playback_id", target.playbackId)
                put("internal_play_id", target.playbackId)
                put("memory_cached", false)
                put("persistent_cached", false)
                put("audio_format", "")
                put("video_format", "")
                put("manifest_id", "")
                put("protected", false)
                put("key_system", "")
                put("key_system_impl", "")
                put("urls_json", "[]")
                put("start_time", target.startedAtMs)
                put("end_time", target.startedAtMs + positionMs.coerceAtLeast(0L))
                put("external_start_time", target.startedAtMs)
                put("ms_play_latency", 0)
                put("ms_init_latency", 0)
                put("ms_head_latency", 0)
                put("ms_first_bytes_latency", 0)
                put("ms_manifest_latency", 0)
                put("ms_resolve_latency", 0)
                put("ms_played", positionMs.coerceAtLeast(0L))
                put("ms_nominal_played", positionMs.coerceAtLeast(0L))
                put("ms_file_duration", target.durationMs)
                put("ms_actual_duration", target.durationMs)
                put("ms_metadata_duration", 0)
                put("ms_start_position", 0)
                put("ms_end_position", positionMs.coerceAtLeast(0L))
                put("ms_playing_time", positionMs.coerceAtLeast(0L))
                put("ms_total_play_time", positionMs.coerceAtLeast(0L))
                put("ms_played_visible", positionMs.coerceAtLeast(0L))
                put("n_stalls", 0)
                put("audio_start_bitrate", BITRATE)
                put("start_bitrate", BITRATE)
                put("time_weighted_bitrate", BITRATE)
            }
        }
        sendBatch(cookie, token, listOf(stats))
    }

    private suspend fun sendBatch(
        cookie: String,
        token: String,
        events: List<JsonObject>,
    ): Boolean {
        val payload = buildJsonObject {
            put("client_version", CLIENT_VERSION)
            put("platform", PLATFORM)
            put("sdk_id", SDK_ID)
            put("messages", JsonArray(events))
        }
        val request = Request.Builder()
            .url(BATCH_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Content-Type", "application/json")
            .header("App-Platform", "WebPlayer")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .header("Cookie", cookie)
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (response.code == 429) noteRateLimit(response)
                response.isSuccessful
            }
        }.onFailure {
            TrackLog.w(TAG, "Spotify Connect batch failed: ${it.message}")
        }.getOrDefault(false)
    }

    // ---- dealer parsing ---------------------------------------------------

    private fun parseConnectionId(text: String): String? = runCatching {
        val root = json.parseToJsonElement(text).jsonObjectOrNull() ?: return@runCatching null
        if (root.stringOf("type") != "message") return@runCatching null
        root.objectOf("headers")?.stringOf("Spotify-Connection-Id")
            ?: root.stringOf("uri")?.let { uri ->
                DEALER_CONNECTION_REGEX.find(uri)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { URLDecoder.decode(it, "UTF-8") }
            }
    }.getOrNull()

    private fun parseDealerCommand(text: String): JsonObject? = runCatching {
        val root = json.parseToJsonElement(text).jsonObjectOrNull() ?: return@runCatching null
        if (root.stringOf("type") != "message") return@runCatching null
        if (root.stringOf("uri") != "hm://track-playback/v1/command") return@runCatching null
        val payload = root.arrayOf("payloads")?.firstOrNull()?.jsonObjectOrNull() ?: return@runCatching null
        if (payload.stringOf("type") != "replace_state") return@runCatching null
        payload
    }.getOrNull()

    private fun parsePlaybackState(command: JsonObject, expectedTrackId: String): PlaybackState? {
        val machine = command.objectOf("state_machine") ?: return null
        val ref = command.objectOf("state_ref") ?: return null
        return playbackStateFrom(machine, ref, expectedTrackId)
    }

    private fun parseUpdatedState(body: String): PlaybackState? = runCatching {
        val root = json.parseToJsonElement(body).jsonObjectOrNull() ?: return@runCatching null
        val machine = root.objectOf("state_machine") ?: return@runCatching null
        val ref = root.objectOf("updated_state_ref") ?: return@runCatching null
        playbackStateFrom(machine, ref, null)
    }.getOrNull()

    private fun playbackStateFrom(
        machine: JsonObject,
        ref: JsonObject,
        expectedTrackId: String?,
    ): PlaybackState? {
        val stateIndex = ref.numberOf("state_index")?.toInt() ?: return null
        val state = machine.arrayOf("states")?.getOrNull(stateIndex)?.jsonObjectOrNull() ?: return null
        val machineId = machine.stringOf("state_machine_id") ?: return null
        val stateId = state.stringOf("state_id") ?: return null

        val trackIndex = state.numberOf("track")?.toInt()
        val track = trackIndex
            ?.let { machine.arrayOf("tracks")?.getOrNull(it)?.jsonObjectOrNull() }
        val currentTrackId = track?.stateMachineTrackId()
        if (expectedTrackId != null && currentTrackId != expectedTrackId) return null

        val durationMs = state.numberOf("duration_override")
            ?: track?.objectOf("metadata")?.numberOf("duration")
        val playbackId = track?.objectOf("logData")?.stringOf("playbackId")
            ?: track?.objectOf("log_data")?.stringOf("playback_id")
        return PlaybackState(
            stateMachineId = machineId,
            stateId = stateId,
            paused = ref.booleanOf("paused") ?: false,
            durationMs = durationMs,
            playbackId = playbackId,
        )
    }

    // ---- support ----------------------------------------------------------

    private fun enabled(): Boolean =
        AppSettings.spotifyScrobbleEnabled.value &&
            AppSettings.spotifyConnected.value &&
            AppSettings.spotifySpdcToken.value.isNotBlank()

    private fun cookie(): String? =
        AppSettings.spotifyCookieHeader.value.takeIf { it.isNotBlank() }
            ?: SpotifyCookie.normalize(AppSettings.spotifySpdcToken.value)?.let { "sp_dc=$it" }

    private suspend fun token(): String? =
        runCatching { SpotifyToken.accessToken() }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun isBackedOff(): Boolean = System.currentTimeMillis() < blockedUntilMs

    private fun noteRateLimit(response: Response) {
        val retryAfterMs = response.header("Retry-After")
            ?.toLongOrNull()
            ?.times(1000L)
            ?: RATE_LIMIT_FALLBACK_MS
        blockedUntilMs = System.currentTimeMillis() + retryAfterMs.coerceIn(30_000L, 15 * 60_000L)
        TrackLog.w(TAG, "Spotify Connect rate limited, backing off ${retryAfterMs / 1000}s")
    }

    private fun clearDevice(target: Device? = null) {
        val current = device
        if (target == null || current === target) {
            current?.close()
            device = null
        } else {
            target.close()
        }
        _active.value = false
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { synchronized(random) { random.nextBytes(it) } }

    private fun randomHex(size: Int): String =
        randomBytes(size).joinToString("") { "%02x".format(it) }

    private fun randomStateMachineId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString {
            append("Ch")
            repeat(28) { append(chars[synchronized(random) { random.nextInt(chars.length) }]) }
        }
    }

    private fun durationFromText(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (parts.isEmpty()) return null
        var total = 0
        for (part in parts) total = total * 60 + part
        return total.takeIf { it > 0 }?.times(1000L)
    }

    // ---- models -----------------------------------------------------------

    private data class Endpoints(val dealerUrl: String, val webgateUrl: String)

    private class DealerConnection(
        val id: String,
        val socket: WebSocket,
        val queue: CommandQueue,
    )

    private class CommandQueue {
        private val pending = ArrayDeque<JsonObject>()
        private var waiting: CompletableDeferred<JsonObject>? = null

        @Synchronized
        fun next(): CompletableDeferred<JsonObject> {
            pending.removeFirstOrNull()?.let { return CompletableDeferred(it) }
            return CompletableDeferred<JsonObject>().also { waiting = it }
        }

        @Synchronized
        fun offer(command: JsonObject) {
            val waiter = waiting
            if (waiter != null) {
                waiting = null
                if (waiter.complete(command)) return
            }
            pending.addLast(command)
        }
    }

    private class Device(
        val cookieHash: Int,
        val deviceId: String,
        val webgateUrl: String,
        val connectionId: String,
        val observerDeviceId: String?,
        private val socket: WebSocket,
        val commandQueue: CommandQueue,
        initialSequenceNumber: Int,
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        private val sequence = AtomicInteger(initialSequenceNumber)

        @Volatile
        var closed: Boolean = false
            private set

        fun nextSequence(): Int = sequence.incrementAndGet()

        fun close() {
            closed = true
            runCatching { socket.close(1000, "device-refresh") }
        }
    }

    private data class PlaybackState(
        val stateMachineId: String,
        val stateId: String,
        val paused: Boolean,
        val durationMs: Long?,
        val playbackId: String?,
    )

    private class Session(
        val trackUri: String,
        val trackId: String,
        val songKey: String,
        @Volatile var durationMs: Long,
        @Volatile var stateMachineId: String,
        @Volatile var stateId: String,
        @Volatile var playbackId: String,
        val startedAtMs: Long = System.currentTimeMillis(),
    ) {
        val sessionId: String = UUID.randomUUID().toString()
        val correlationId: String = UUID.randomUUID().toString()
        @Volatile var paused: Boolean = false
        @Volatile var lastControlPaused: Boolean? = null
        @Volatile var startReported: Boolean = false
        @Volatile var finalized: Boolean = false

        /** Playback progress handed to Spotify once the threshold is crossed. */
        @Volatile var thresholdArmed: Boolean = false
        @Volatile var thresholdReported: Boolean = false
        @Volatile var thresholdRemainingMs: Long = 0L
        @Volatile var thresholdStartedAtMs: Long = 0L
        @Volatile var thresholdJob: Job? = null

        private var anchorPositionMs: Long = 0L
        @Volatile private var anchorWallMs: Long = startedAtMs

        @Synchronized
        fun currentPosition(): Long {
            val elapsed = if (anchorWallMs > 0L) {
                anchorPositionMs + (System.currentTimeMillis() - anchorWallMs)
            } else {
                anchorPositionMs
            }
            return if (durationMs > 0L) elapsed.coerceIn(0L, durationMs) else elapsed.coerceAtLeast(0L)
        }

        @Synchronized
        fun freezePosition() {
            anchorPositionMs = currentPosition()
            anchorWallMs = 0L
        }

        @Synchronized
        fun resumePosition() {
            if (anchorWallMs == 0L) anchorWallMs = System.currentTimeMillis()
        }

        @Synchronized
        fun moveTo(positionMs: Long, playing: Boolean) {
            anchorPositionMs =
                if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)
            anchorWallMs = if (playing) System.currentTimeMillis() else 0L
        }

        @Synchronized
        fun finalPosition(): Long {
            val positionMs = currentPosition()
            return if (durationMs > 1_000L && positionMs >= durationMs - 250L) {
                durationMs - 250L
            } else {
                positionMs
            }
        }

        fun applyPlaybackState(state: PlaybackState) {
            stateMachineId = state.stateMachineId
            stateId = state.stateId
            paused = state.paused
            state.durationMs?.takeIf { it > 0L }?.let { durationMs = it }
            state.playbackId?.takeIf { it.isNotBlank() }?.let { playbackId = it }
        }
    }
}

// ---- small JSON helpers (file-private) ------------------------------------

private fun JsonObject.stringOf(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.numberOf(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

private fun JsonObject.booleanOf(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.lowercase()?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

private fun JsonObject.objectOf(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arrayOf(key: String): JsonArray? = this[key] as? JsonArray

private fun String.spotifyTrackId(): String? =
    when {
        startsWith("spotify:track:", ignoreCase = true) -> substringAfterLast(':')
        contains("open.spotify.com/track/", ignoreCase = true) ->
            substringAfter("open.spotify.com/track/", "")
                .substringBefore('?')
                .substringBefore('/')
        else -> null
    }?.takeIf { it.matches(Regex("^[A-Za-z0-9]{22}$")) }

private fun JsonObject.stateMachineTrackId(): String? =
    listOfNotNull(
        stringOf("uri"),
        stringOf("track_uri"),
        stringOf("trackUri"),
        stringOf("id"),
        objectOf("metadata")?.stringOf("uri"),
        objectOf("metadata")?.stringOf("track_uri"),
        objectOf("metadata")?.stringOf("trackUri"),
        objectOf("metadata")?.stringOf("entity_uri"),
        objectOf("metadata")?.stringOf("entityUri"),
    ).firstNotNullOfOrNull { it.spotifyTrackId() }

private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
