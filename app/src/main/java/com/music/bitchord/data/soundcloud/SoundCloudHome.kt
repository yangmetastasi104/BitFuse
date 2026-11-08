package com.music.bitchord.data.soundcloud

import android.content.Context
import android.util.Log
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Small SoundCloud v2 client for the Home feed. It discovers the public client id
 * from SoundCloud's own web app and never embeds or ships a third-party key. */
object SoundCloudHome {
    private const val TAG = "SoundCloudHome"
    private const val PREFS = "soundcloud_session_encrypted"
    private const val TOKEN = "oauth_token"
    private const val CLIENT_ID = "client_id"
    private const val HOME_SELECTED = "home_selected"
    private const val BASE = "https://api-v2.soundcloud.com"
    private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    private val appVersionPattern = Regex("""app_?version["']?\s*[:=]\s*["']([0-9]{8,})["']""", RegexOption.IGNORE_CASE)
    @Volatile private var cachedAppVersion: String? = null
    private val prefsLock = Any()
    @Volatile private var exploreShelvesCache: List<HomeShelf> = emptyList()

    fun token(context: Context): String = preferences(context).getString(TOKEN, "").orEmpty()
    fun isConnected(context: Context): Boolean = token(context).isNotBlank()
    fun isHomeSelected(context: Context): Boolean = preferences(context).getBoolean(HOME_SELECTED, false)
    fun setHomeSelected(context: Context, selected: Boolean) {
        preferences(context).edit().putBoolean(HOME_SELECTED, selected).apply()
    }
    fun saveSession(context: Context, token: String, clientId: String) {
        preferences(context).edit().putString(TOKEN, token).putString(CLIENT_ID, clientId).apply()
    }
    fun clearSession(context: Context) { preferences(context).edit().clear().apply() }
    private fun preferences(context: Context) = synchronized(prefsLock) {
        EncryptedPrefs.open(context, PREFS, "soundcloud_session_plain")
    }

    suspend fun load(context: Context): List<HomeShelf> = withContext(Dispatchers.IO) {
        val authToken = token(context)
        val clientId = preferences(context).getString(CLIENT_ID, "").orEmpty().ifBlank { discoverClientId() }
        if (preferences(context).getString(CLIENT_ID, "").isNullOrBlank()) {
            preferences(context).edit().putString(CLIENT_ID, clientId).apply()
        }
        val shelves = mutableListOf<HomeShelf>()
        if (authToken.isNotBlank()) {
            val personalized = runCatching { loadPersonalized(authToken, clientId) }
                .onFailure { Log.w(TAG, "Personalized SoundCloud feed unavailable; loading public sections") }
                .getOrDefault(emptyList())
            shelves += personalized
        }
        val public = runCatching { loadPublic(clientId, authToken) }
            .onFailure { Log.w(TAG, "Public SoundCloud sections failed", it) }
            .getOrDefault(emptyList())
        public.forEach { candidate ->
            if (shelves.none { it.title.equals(candidate.title, ignoreCase = true) }) shelves += candidate
        }
        if (shelves.isEmpty()) error("SoundCloud did not return any feed sections")
        shelves
    }

    suspend fun search(context: Context, query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val clientId = getClientId(context)
        val token = token(context)
        val rows = safeTracks("search/tracks", token, clientId, mapOf("q" to query, "limit" to "50"))
        songs(rows).map { SearchResult.Track(it) }
    }

    data class PlaybackStream(val url: String, val headers: Map<String, String>)

    /** Resolve a canonical permalink through SoundCloud's API v2, following MetroFuse's stream flow. */
    suspend fun resolvePlayback(context: Context, permalink: String): PlaybackStream = withContext(Dispatchers.IO) {
        require(permalink.startsWith("https://soundcloud.com/", ignoreCase = true)) { "Invalid SoundCloud track URL" }
        val clientId = getClientId(context)
        val appVersion = getAppVersion()
        val authToken = token(context)
        val trackUrl = apiUrl("resolve", clientId, buildMap {
            put("url", permalink)
            appVersion?.let { put("app_version", it) }
        })
        val track = JSONObject(get(trackUrl, authToken))
        val transcodings = track.optJSONObject("media")?.optJSONArray("transcodings")
            ?: error("SoundCloud returned no playable streams")
        val candidates = (0 until transcodings.length())
            .mapNotNull(transcodings::optJSONObject)
            .filter { row ->
                val format = row.optJSONObject("format")
                format?.optString("protocol") in setOf("progressive", "hls") &&
                    format?.optString("mime_type").orEmpty().lowercase().let {
                        it.contains("mpeg") || it.contains("mp3") || it.contains("aac") || it.contains("mp4")
                    } && row.optString("url").isNotBlank()
            }
        val selected = candidates.firstOrNull { it.optJSONObject("format")?.optString("protocol") == "progressive" }
            ?: candidates.firstOrNull()
            ?: error("SoundCloud has no supported audio transcoding")
        val transcodingUrl = selected.optString("url")
        val streamRequestUrl = buildString {
            append(transcodingUrl)
            append(if ('?' in transcodingUrl) '&' else '?')
            append("client_id=").append(encode(clientId))
            track.optString("track_authorization").takeIf(String::isNotBlank)?.let {
                append("&track_authorization=").append(encode(it))
            }
            appVersion?.let { append("&app_version=").append(encode(it)) }
        }
        val streamJson = JSONObject(get(streamRequestUrl, authToken))
        val mediaUrl = streamJson.optString("url").takeIf(String::isNotBlank)
            ?: error("SoundCloud transcoding returned no media URL")
        PlaybackStream(
            mediaUrl,
            mapOf(
                "User-Agent" to BROWSER_USER_AGENT,
                "Accept" to "audio/*,*/*;q=0.8",
                "Accept-Encoding" to "identity",
                "Referer" to "https://soundcloud.com/",
                "Origin" to "https://soundcloud.com",
            ),
        )
    }

    /** Add or remove a track from the signed-in SoundCloud account's likes. */
    suspend fun setTrackLiked(context: Context, permalink: String, liked: Boolean) = withContext(Dispatchers.IO) {
        val authToken = token(context).takeIf(String::isNotBlank) ?: error("Sign in to SoundCloud to like tracks")
        val clientId = getClientId(context)
        val track = JSONObject(get(apiUrl("resolve", clientId, mapOf("url" to permalink)), authToken))
        val trackId = track.optString("id").takeIf(String::isNotBlank) ?: error("Could not identify SoundCloud track")
        val user = JSONObject(get(apiUrl("me", clientId, emptyMap()), authToken))
        val userId = user.optString("id").takeIf(String::isNotBlank) ?: error("Could not identify SoundCloud account")
        val endpoint = apiUrl("users/$userId/track_likes/$trackId", clientId, emptyMap())
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.requestMethod = if (liked) "PUT" else "DELETE"
        connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "OAuth $authToken")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("SoundCloud like request failed (HTTP $code): ${body.take(180)}")
            }
            connection.inputStream?.close()
        } finally {
            connection.disconnect()
        }
        val prefs = preferences(context)
        val likedUrls = prefs.getString("liked_track_urls", "").orEmpty().split('\n').filter(String::isNotBlank).toMutableSet()
        if (liked) likedUrls += permalink else likedUrls -= permalink
        prefs.edit().putString("liked_track_urls", likedUrls.joinToString("\n")).apply()
    }

    fun isTrackLiked(context: Context, permalink: String): Boolean =
        permalink in preferences(context).getString("liked_track_urls", "").orEmpty().split('\n')

    suspend fun loadLibrary(context: Context): LibraryPage = withContext(Dispatchers.IO) {
        val authToken = token(context).takeIf(String::isNotBlank) ?: error("Sign in to SoundCloud to view your library")
        val clientId = getClientId(context)
        val me = JSONObject(get("$BASE/me?client_id=${encode(clientId)}", authToken))
        val userId = me.optString("id").takeIf(String::isNotBlank) ?: error("Could not read the SoundCloud account")
        val liked = safeTracks("users/$userId/track_likes", authToken, clientId)
        val likedPermalinks = buildList {
            for (i in 0 until liked.length()) liked.optJSONObject(i)?.optString("permalink_url")
                ?.takeIf(String::isNotBlank)?.let(::add)
        }
        preferences(context).edit().putString("liked_track_urls", likedPermalinks.joinToString("\n")).apply()
        val uploaded = safeTracks("users/$userId/tracks", authToken, clientId)
        val playlists = safeCollection("users/$userId/playlists", authToken, clientId)
        val history = safeTracks("me/play-history/tracks", authToken, clientId)
        val shelves = mutableListOf<HomeShelf>()
        shelf("Recently played", history)?.let(shelves::add)
        shelf("Your likes", liked)?.let(shelves::add)
        shelf("Your tracks", uploaded)?.let(shelves::add)
        shelves += playlistShelves("Your playlists", playlists, authToken, clientId, maxPlaylists = 50)
        LibraryPage(
            likedSongs = songs(liked),
            librarySongs = songs(uploaded),
            shelves = shelves,
        )
    }

    suspend fun loadExplore(context: Context): List<MoodGenreSection> = withContext(Dispatchers.IO) {
        val clientId = getClientId(context)
        val authToken = token(context)
        val shelves = mutableListOf<HomeShelf>()
        if (authToken.isNotBlank()) shelves += runCatching { loadPersonalized(authToken, clientId) }.getOrDefault(emptyList())
        shelves += loadPublic(clientId, authToken)
        val distinct = shelves.distinctBy { it.title.lowercase() }
        exploreShelvesCache = distinct
        val categories = distinct.map { shelf ->
            MoodGenre(
                title = shelf.title,
                browseId = "soundcloud:explore:${shelf.title}",
                params = null,
                thumbnailUrl = shelf.items.firstNotNullOfOrNull { it.thumbnailUrl },
            )
        }
        if (categories.isEmpty()) emptyList() else listOf(MoodGenreSection("SoundCloud", categories))
    }

    suspend fun loadExploreCategory(context: Context, title: String): List<HomeShelf> = withContext(Dispatchers.IO) {
        val match = exploreShelvesCache.firstOrNull { it.title.equals(title, ignoreCase = true) }
        if (match != null) listOf(match) else {
            load(context).filter { it.title.equals(title, ignoreCase = true) }
        }
    }

    private suspend fun getClientId(context: Context): String {
        val stored = preferences(context).getString(CLIENT_ID, "").orEmpty()
        if (stored.isNotBlank()) return stored
        return discoverClientId().also { preferences(context).edit().putString(CLIENT_ID, it).apply() }
    }

    private fun songs(rows: JSONArray): List<Song> = buildList {
        for (index in 0 until rows.length()) {
            val track = rows.optJSONObject(index) ?: continue
            if (!isPlayableTrack(track)) continue
            val permalink = track.optString("permalink_url")
            val artist = track.optJSONObject("publisher_metadata")?.optString("artist")
                ?.takeIf(String::isNotBlank) ?: track.optJSONObject("user")?.optString("username").orEmpty()
            val art = track.optJSONObject("artwork")?.optString("url")?.takeIf(String::isNotBlank)
                ?: track.optString("artwork_url").takeIf(String::isNotBlank)
            add(Song(permalink, track.optString("title", "Untitled"), artist, art?.replace("-large.", "-t500x500.")))
        }
    }

    private suspend fun loadPersonalized(authToken: String, clientId: String): List<HomeShelf> = coroutineScope {
        val streamJob = async { safeTracks("me/stream", authToken, clientId) }
        val likesJob = async { safeTracks("me/track_likes", authToken, clientId) }
        val historyJob = async { safeTracks("me/play-history/tracks", authToken, clientId) }
        val recommendationsJob = async { safeTracks("me/track-recommendations", authToken, clientId) }
        val mixesJob = async { safeCollection("me/personalized-mixes", authToken, clientId) }
        val systemMixesJob = async { safeCollection("system-playlists", authToken, clientId) }
        val selectionsJob = async { safeCollection("mixed-selections", authToken, clientId, mapOf("linked_partitioning" to "1")) }
        val result = mutableListOf<HomeShelf>()
        streamJob.await().let { shelf("Your Stream", it)?.let(result::add) }
        likesJob.await().let { shelf("Your likes", it)?.let(result::add) }
        historyJob.await().let { shelf("Recently played", it)?.let(result::add) }
        recommendationsJob.await().let { shelf("SoundCloud Recommends", it)?.let(result::add) }
        result += playlistShelves("Made for you", mixesJob.await(), authToken, clientId)
        result += playlistShelves("SoundCloud mixes", systemMixesJob.await(), authToken, clientId)
        result += mixedSelectionShelves(selectionsJob.await(), authToken, clientId, maxSections = 14)
        result
    }

    private suspend fun loadPublic(clientId: String, authToken: String): List<HomeShelf> = coroutineScope {
        val top = async { safeTracks("charts", "", clientId, mapOf("kind" to "top", "genre" to "soundcloud:genres:all-music", "region" to "soundcloud:regions:all-countries")) }
        val trending = async { safeTracks("charts", "", clientId, mapOf("kind" to "trending", "genre" to "soundcloud:genres:all-music", "region" to "soundcloud:regions:all-countries")) }
        val selections = async { safeCollection("mixed-selections", "", clientId, mapOf("linked_partitioning" to "1")) }
        val shelves = mutableListOf<HomeShelf>()
        top.await().let { shelf("Top 50: All Music", it)?.let(shelves::add) }
        trending.await().let { shelf("Trending: All Music", it)?.let(shelves::add) }
        shelves += mixedSelectionShelves(selections.await(), authToken, clientId, maxSections = 14)
        if (shelves.size < 5) {
            listOf(
                "Curated by SoundCloud" to "SoundCloud",
                "Made for you" to "Daily Drops Weekly Wave",
                "Discover with Stations" to "artist station",
                "Albums for you" to "album",
            ).forEach { (section, query) ->
                val playlists = safeCollection("search/playlists", "", clientId, mapOf("q" to query, "limit" to "12"))
                shelves += playlistShelves(section, playlists, authToken, clientId, maxPlaylists = 4)
            }
        }
        shelves
    }

    private suspend fun mixedSelectionShelves(
        collection: JSONArray,
        authToken: String,
        clientId: String,
        maxSections: Int,
    ): List<HomeShelf> = coroutineScope {
        val result = mutableListOf<HomeShelf>()
        for (index in 0 until collection.length()) {
            if (result.size >= maxSections) break
            val selection = collection.optJSONObject(index) ?: continue
            val title = selection.optString("title").takeIf(String::isNotBlank)
                ?: selection.optString("description").takeIf(String::isNotBlank) ?: continue
            val contents = selection.optJSONObject("items")?.optJSONArray("collection")
                ?: selection.optJSONObject("items")?.optJSONArray("items")
                ?: selection.optJSONArray("items") ?: JSONArray()
            val tracks = JSONArray()
            val playlists = JSONArray()
            collectFeedObjects(contents, tracks, playlists)
            shelf(title, tracks)?.let(result::add)
            result += playlistShelves(title, playlists, authToken, clientId, maxPlaylists = 1)
        }
        result
    }

    private suspend fun playlistShelves(
        section: String,
        playlists: JSONArray,
        authToken: String,
        clientId: String,
        maxPlaylists: Int = 8,
    ): List<HomeShelf> = coroutineScope {
        val entries = (0 until playlists.length()).mapNotNull { playlists.optJSONObject(it) }
            .mapNotNull { obj -> obj.optJSONObject("playlist") ?: obj.takeIf { isPlaylist(it) } }
            .distinctBy { it.optString("id") }
            .take(maxPlaylists)
        entries.map { playlist -> async {
            val title = playlist.optString("title").takeIf(String::isNotBlank) ?: return@async null
            val trackArray = playlist.optJSONArray("tracks") ?: JSONArray()
            var playable = JSONArray().also { dest ->
                for (i in 0 until trackArray.length()) {
                    val row = trackArray.optJSONObject(i) ?: continue
                    dest.put(row.optJSONObject("track") ?: row)
                }
            }
            val id = playlist.optString("id")
            if (playable.length() == 0 && id.isNotBlank()) {
                playable = runCatching { safeCollection("playlists/$id/tracks", authToken, clientId, mapOf("limit" to "30")) }
                    .getOrDefault(JSONArray())
            }
            shelf("$section · $title", playable)
        } }.mapNotNull { it.await() }
    }

    private fun collectFeedObjects(value: Any?, tracks: JSONArray, playlists: JSONArray) {
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) collectFeedObjects(value.opt(i), tracks, playlists)
            is JSONObject -> when {
                isPlayableTrack(value) -> tracks.put(value)
                isPlaylist(value) -> playlists.put(value)
                value.optJSONObject("track")?.let(::isPlayableTrack) == true -> tracks.put(value.optJSONObject("track"))
                value.optJSONObject("playlist")?.let(::isPlaylist) == true -> playlists.put(value.optJSONObject("playlist"))
                else -> {
                    val keys = value.keys()
                    while (keys.hasNext()) collectFeedObjects(value.opt(keys.next()), tracks, playlists)
                }
            }
        }
    }

    private fun isPlaylist(obj: JSONObject) = obj.optString("kind").let { it == "playlist" || it == "system-playlist" } ||
        (obj.optJSONArray("tracks") != null && obj.has("title") && obj.has("id"))

    private fun isPlayableTrack(obj: JSONObject) = obj.optString("kind").equals("track", true) &&
        obj.optBoolean("streamable", true) && obj.optString("permalink_url").startsWith("https://soundcloud.com/") &&
        obj.optString("policy") !in setOf("BLOCK", "SNIP")

    private fun safeTracks(path: String, authToken: String, clientId: String, params: Map<String, String> = emptyMap()): JSONArray =
        runCatching { requestTracks(apiUrl(path, clientId, params + ("limit" to "50")), authToken) }
            .onFailure { Log.w(TAG, "SoundCloud section request failed: $path") }
            .getOrDefault(JSONArray())

    private fun safeCollection(path: String, authToken: String, clientId: String, params: Map<String, String> = emptyMap()): JSONArray =
        runCatching { JSONObject(get(apiUrl(path, clientId, params + ("limit" to "50")), authToken)).optJSONArray("collection") ?: JSONArray() }
            .onFailure { Log.w(TAG, "SoundCloud collection request failed: $path") }
            .getOrDefault(JSONArray())

    private fun apiUrl(path: String, clientId: String, params: Map<String, String>): String {
        val query = linkedMapOf("client_id" to clientId).apply { putAll(params) }
            .entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return "$BASE/$path?$query"
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private suspend fun discoverClientId(): String = coroutineScope {
        val html = get("https://soundcloud.com/")
        val clientIdPattern = Regex("""client_?id[\"']?\s*[:=]\s*[\"']([A-Za-z0-9]{20,})[\"']""", RegexOption.IGNORE_CASE)
        clientIdPattern.find(html)?.groupValues?.getOrNull(1)?.let { return@coroutineScope it }
        val scriptUrls = Regex("""https://[a-z0-9-]+\.sndcdn\.com/[^\"'<>!]+\.js""")
            .findAll(html).map { it.value }.distinct().take(16).toList()
        scriptUrls.map { url -> async { runCatching { get(url) }.getOrDefault("") } }
            .awaitAll()
            .firstNotNullOfOrNull { script -> clientIdPattern.find(script)?.groupValues?.getOrNull(1) }
            ?: error("Could not discover SoundCloud client id")
    }

    private suspend fun getAppVersion(): String? {
        cachedAppVersion?.let { return it }
        return withContext(Dispatchers.IO) {
            cachedAppVersion?.let { return@withContext it }
            runCatching {
                val html = get("https://soundcloud.com/")
                appVersionPattern.find(html)?.groupValues?.getOrNull(1)
                    ?: Regex("""https://[a-z0-9-]+\.sndcdn\.com/[^"'<>!]+\.js""")
                        .findAll(html).map { it.value }.distinct().take(16)
                        .mapNotNull { script -> runCatching { appVersionPattern.find(get(script))?.groupValues?.getOrNull(1) }.getOrNull() }
                        .firstOrNull()
            }.getOrNull()?.also { cachedAppVersion = it }
        }
    }

    private fun requestTracks(url: String, token: String, chart: Boolean = false): JSONArray {
        val json = JSONObject(get(url, token))
        val collection = if (chart) json.optJSONArray("collection") else json.optJSONArray("collection") ?: JSONArray()
        val tracks = JSONArray()
        for (i in 0 until collection.length()) {
            val row = collection.optJSONObject(i) ?: continue
            tracks.put(if (row.has("track")) row.optJSONObject("track") ?: continue else row)
        }
        return tracks
    }

    private fun shelf(title: String, tracks: JSONArray): HomeShelf? {
        val items = buildList {
            for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            if (!isPlayableTrack(track)) continue
                val permalink = track.optString("permalink_url").takeIf { it.startsWith("https://soundcloud.com/") } ?: continue
                val user = track.optJSONObject("user")
                val art = track.optJSONObject("artwork")?.optString("url")?.takeIf(String::isNotBlank)
                    ?: user?.optJSONObject("avatar")?.optString("url")
                    ?: track.optString("artwork_url").takeIf(String::isNotBlank)
                add(ShelfItem(
                    title = track.optString("title", "Untitled"),
                    subtitle = user?.optString("username").orEmpty(),
                    thumbnailUrl = art?.replace("-large.", "-t500x500."),
                    videoId = permalink,
                    browseId = null,
                ))
            }
        }
        return items.takeIf { it.isNotEmpty() }?.let { HomeShelf(title = title, items = it) }
    }

    private fun get(url: String, token: String = ""): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Referer", "https://soundcloud.com/")
        connection.setRequestProperty("Origin", "https://soundcloud.com")
        if (token.isNotBlank()) connection.setRequestProperty("Authorization", "OAuth $token")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("SoundCloud HTTP $code")
            return body
        } finally { connection.disconnect() }
    }
}
