package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.text.Regex

/** A Spotify playlist's page id in the detail stack: this plus the playlist id (or [SpotifyLibrary.LIKED_ID]). */
const val SPOTIFY_PAGE_PREFIX = "spotify:playlist:"

/** A Spotify album's page id in the shared detail stack. */
const val SPOTIFY_ALBUM_PAGE_PREFIX = "spotify:album:"

/** A Spotify artist's page id in the shared detail stack. */
const val SPOTIFY_ARTIST_PAGE_PREFIX = "spotify:artist:"

/** Log tag for the diagnostics the library prints when a fetch fails. */
private const val TAG = "BitChord"

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val imageUrl: String?,
)

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Int,
    val imageUrl: String?,
)

data class SpotifyAlbum(
    val id: String,
    val name: String,
    val artist: String?,
    val imageUrl: String?,
    val year: String?,
)

data class SpotifyArtist(
    val id: String,
    val name: String,
    val imageUrl: String?,
)

data class SpotifyHomeShelf(
    val title: String,
    val items: List<SpotifyHomeItem>,
)

data class SpotifyHomeItem(
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val browseId: String,
)

object SpotifyLibrary {
    /** The web player's bearer, and its client token when there is one. */
    class Tokens(val bearer: String, val clientToken: String?)

    /**
     * Where the tokens come from: the phone mints them in a WebView, the desktop in JavaFX's.
     * Null tokens mean signed out or expired.
     */
    fun interface Auth {
        suspend fun tokens(): Tokens?
    }

    /** Installed by each application at start-up; until then every call fails as signed out. */
    @Volatile
    var auth: Auth? = null

    /**
     * The `sp_dc` cookie behind the session, installed by the app from the
     * user's pasted/WebView-captured value. MetroFuse-proven: endpoints that
     * 403 a bare bearer accept it once the cookie rides along, so it travels
     * with every request alongside the minted token.
     */
    @Volatile
    var cookieProvider: (() -> String?)? = null

    private const val GQL = "https://api-partner.spotify.com/pathfinder/v2/query"
    // Spotify rotates these persisted-query hashes. Values below are the
    // verified hashes published by Meld's 2026-11-02 registry.
    private const val LIBRARY = "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"
    /** The page size the library walk asks for; the web player asks for the same 50. */
    private const val PAGE_SIZE = 50
    /** Ceiling on that walk, so a pathological library cannot spin forever. */
    private const val PAGE_SAFETY_LIMIT = 1_000
    private const val PLAYLIST = "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf"
    /** The pseudo-playlist id for the Liked Songs collection, which has no playlist uri. */
    const val LIKED_ID = "liked"
    /** Spotify's own Liked Songs artwork, the purple heart; the collection has no cover of its own. */
    private const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
    private const val LIKED = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    /** The phone's canvas client sent this one; kept so its requests look unchanged. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"

    // GraphQL persisted-query hashes. Spotify rotates these; when they go stale
    // every libraryV3 / fetchPlaylist call 400s. Update them here when that
    // happens (check Spotify's web-player JS bundles for the current values).
    @Volatile private var libraryHash = "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"
    @Volatile private var playlistHash = "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf"
    @Volatile private var likedHash = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    @Volatile private var homeHash = "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1"

    /** Refreshes persisted-query hashes from Meld's public daily registry. */
    suspend fun refreshHashes() = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://francescograzioso.github.io/Meld/spotify-gql-hashes.json")
                .header("User-Agent", USER_AGENT)
                .build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching
                response.body?.string().orEmpty()
            }
            val operations = json.parseToJsonElement(body).jsonObject["operations"]?.jsonObject ?: return@runCatching
            operations["libraryV3"]?.jsonObject?.str("hash")?.let { libraryHash = it }
            operations["fetchPlaylist"]?.jsonObject?.str("hash")?.let { playlistHash = it }
            operations["fetchLibraryTracks"]?.jsonObject?.str("hash")?.let { likedHash = it }
            operations["home"]?.jsonObject?.str("hash")?.let { homeHash = it }
        }.onFailure { TrackLog.w(TAG, "Spotify hash registry refresh failed: ${it.message}") }
    }

    /** Spotify's personalized landing feed, including Daily Mix and new releases. */
    suspend fun homeShelves(limit: Int = 10): List<SpotifyHomeShelf> = withContext(Dispatchers.IO) {
        val variables = buildJsonObject {
            put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER")
            put("timeZone", java.util.TimeZone.getDefault().id)
            put("sp_t", "")
            put("facet", "")
            put("sectionItemsLimit", limit)
            put("includeEpisodeContentRatingsV2", false)
        }
        val root = gql("home", homeHash, variables, authHeaders())
        val sections = root.obj("data")?.obj("home")?.obj("sectionContainer")
            ?.obj("sections")?.arr("items").orEmpty()
        sections.mapNotNull { sectionElement ->
            val section = sectionElement.jsonObject
            val data = section.obj("data") ?: return@mapNotNull null
            val title = data.obj("title")?.str("transformedLabel")
                ?: data.obj("title")?.str("translatedBaseText")
                ?: data.obj("title")?.str("text")
                ?: return@mapNotNull null
            val items = section.obj("sectionItems")?.arr("items").orEmpty().mapNotNull { itemElement ->
                val content = itemElement.jsonObject.obj("content") ?: return@mapNotNull null
                val wrapper = content.str("__typename") ?: return@mapNotNull null
                val item = content.obj("data") ?: return@mapNotNull null
                val uri = item.str("uri") ?: return@mapNotNull null
                when (wrapper) {
                    "PlaylistResponseWrapper" -> SpotifyHomeItem(
                        title = item.str("name").orEmpty(),
                        subtitle = item.obj("ownerV2")?.obj("data")?.str("name").orEmpty(),
                        imageUrl = item.obj("images")?.arr("items")?.firstOrNull()?.jsonObject
                            ?.arr("sources")?.firstOrNull()?.jsonObject?.str("url"),
                        browseId = "spotify:playlist:${uri.substringAfterLast(":")}",
                    )
                    "AlbumResponseWrapper" -> SpotifyHomeItem(
                        title = item.str("name").orEmpty(),
                        subtitle = item.obj("artists")?.arr("items")?.firstOrNull()?.jsonObject
                            ?.obj("profile")?.str("name").orEmpty(),
                        imageUrl = item.obj("coverArt")?.arr("sources")?.firstOrNull()?.jsonObject?.str("url"),
                        browseId = "spotify:album:${uri.substringAfterLast(":")}",
                    )
                    "ArtistResponseWrapper" -> SpotifyHomeItem(
                        title = item.obj("profile")?.str("name").orEmpty(),
                        subtitle = "Artist",
                        imageUrl = item.obj("visuals")?.obj("avatarImage")?.arr("sources")
                            ?.firstOrNull()?.jsonObject?.str("url"),
                        browseId = "spotify:artist:${uri.substringAfterLast(":")}",
                    )
                    else -> null
                }
            }
            items.takeIf { it.isNotEmpty() }?.let { SpotifyHomeShelf(title, it) }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** Every playlist in the library, however many pages it takes. */
    suspend fun playlists(retries: Int = 3): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val headers = try {
            authHeaders()
        } catch (e: Exception) {
            TrackLog.w(TAG, "Spotify playlists auth failed: ${e.message ?: e.javaClass.simpleName}")
            return@withContext emptyList()
        }
        val collected = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        var firstPage = true
        while (collected.size < PAGE_SAFETY_LIMIT) {
            val page = try {
                playlistPage(headers, offset, retries)
            } catch (e: Exception) {
                // A refusal on the very first page is a failed sync, not an
                // empty library. Swallowing it here returned just the Liked
                // Songs stub, which then overwrote a good synced list with a
                // single row — how a 429 became "there is no Spotify here".
                if (firstPage) throw e
                TrackLog.w(TAG, "Spotify playlists page failed: ${e.message ?: e.javaClass.simpleName}")
                break
            }
            firstPage = false
            collected += page.first
            offset += page.third
            if (page.third == 0 || page.third < PAGE_SIZE) break
        }
        // Folders can surface the same playlist twice (once at the root, once
        // through a folder it was also added to), and Spotify answers with the
        // row in both places. Dedupe on the id the page stack keys on.
        val unique = collected.distinctBy { it.id }
        // Liked Songs is not a playlist to Spotify's library query, so it is
        // always offered first rather than depending on it turning up there.
        listOf(SpotifyPlaylist(id = LIKED_ID, name = "Liked Songs", owner = null, imageUrl = LIKED_COVER)) + unique
    }

    /**
     * The listener's saved albums. Paged the same way as [playlists]: same
     * `libraryV3` endpoint, just filtered to "Albums".
     */
    suspend fun albums(retries: Int = 3): List<SpotifyAlbum> = withContext(Dispatchers.IO) {
        val headers = try {
            authHeaders()
        } catch (e: Exception) {
            TrackLog.w(TAG, "Spotify albums auth failed: ${e.message ?: e.javaClass.simpleName}")
            return@withContext emptyList()
        }
        val collected = mutableListOf<SpotifyAlbum>()
        var offset = 0
        var firstPage = true
        while (collected.size < PAGE_SAFETY_LIMIT) {
            val page = try {
                albumPage(headers, offset, retries)
            } catch (e: Exception) {
                if (firstPage) throw e
                TrackLog.w(TAG, "Spotify albums page failed: ${e.message ?: e.javaClass.simpleName}")
                break
            }
            firstPage = false
            collected += page.first
            offset += page.third
            if (page.third == 0 || page.third < PAGE_SIZE) break
        }
        collected.distinctBy { it.id }
    }

    /** REST fallback for saved albums. The Web API shape is more stable than the
     * rotating libraryV3 persisted query and works with the cookie-minted bearer. */
    suspend fun savedAlbums(limit: Int = 50, retries: Int = 1): List<SpotifyAlbum> =
        webApiAlbums("https://api.spotify.com/v1/me/albums?limit=$limit&offset=0", retries)

    /** Saved tracks (Spotify calls this collection Liked Songs). */
    suspend fun savedTracks(limit: Int = 50, retries: Int = 1): List<SpotifyTrack> =
        webApiTracks(
            url = "https://api.spotify.com/v1/me/tracks?limit=$limit&offset=0",
            label = "saved tracks",
            retries = retries,
            parser = ::parseSavedTracks,
        )

    /** The user's playlists through the stable Web API endpoint. */
    suspend fun userPlaylists(limit: Int = 50, retries: Int = 1): List<SpotifyPlaylist> =
        withContext(Dispatchers.IO) {
            val headers = authHeaders()
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists?limit=$limit&offset=0")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            val text = withRateLimitRetry(maxAttempts = retries) {
                Http.client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.code == 429) throw SpotifyRateLimitedException(
                        response.header("Retry-After")?.toLongOrNull() ?: 2L,
                    )
                    if (!response.isSuccessful) throw IllegalStateException("Spotify playlists failed (${response.code})")
                    payload
                }
            }
            parseWebApiPlaylists(json.parseToJsonElement(text).jsonObject)
        }

    private suspend fun webApiAlbums(url: String, retries: Int): List<SpotifyAlbum> =
        withContext(Dispatchers.IO) {
            val headers = authHeaders()
            val request = Request.Builder().url(url)
                .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
            val text = withRateLimitRetry(maxAttempts = retries) {
                Http.client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.code == 429) throw SpotifyRateLimitedException(
                        response.header("Retry-After")?.toLongOrNull() ?: 2L,
                    )
                    if (!response.isSuccessful) throw IllegalStateException("Spotify albums failed (${response.code})")
                    payload
                }
            }
            parseSavedAlbums(json.parseToJsonElement(text).jsonObject)
        }

    private suspend fun albumPage(
        headers: Map<String, String>,
        offset: Int,
        retries: Int,
    ): Triple<List<SpotifyAlbum>, Int, Int> {
        val variables = buildJsonObject {
            putJsonArray("filters") { add("Albums") }
            put("order", null as String?)
            put("textFilter", "")
            putJsonArray("features") { add("LIKED_SONGS") }
            put("limit", PAGE_SIZE)
            put("offset", offset)
            put("flatten", false)
            putJsonArray("expandedFolders") {}
            put("folderUri", null as String?)
            put("includeFoldersWhenFlattening", true)
        }
        return parseAlbumPage(gql("libraryV3", libraryHash, variables, headers, retries))
    }

    /**
     * The listener's followed artists, same endpoint filtered to "Artists".
     */
    suspend fun followedArtists(retries: Int = 3): List<SpotifyArtist> = withContext(Dispatchers.IO) {
        val headers = try {
            authHeaders()
        } catch (e: Exception) {
            TrackLog.w(TAG, "Spotify artists auth failed: ${e.message ?: e.javaClass.simpleName}")
            return@withContext emptyList()
        }
        val collected = mutableListOf<SpotifyArtist>()
        var offset = 0
        var firstPage = true
        while (collected.size < PAGE_SAFETY_LIMIT) {
            val page = try {
                artistPage(headers, offset, retries)
            } catch (e: Exception) {
                if (firstPage) throw e
                TrackLog.w(TAG, "Spotify artists page failed: ${e.message ?: e.javaClass.simpleName}")
                break
            }
            firstPage = false
            collected += page.first
            offset += page.third
            if (page.third == 0 || page.third < PAGE_SIZE) break
        }
        collected.distinctBy { it.id }
    }

    private suspend fun artistPage(
        headers: Map<String, String>,
        offset: Int,
        retries: Int,
    ): Triple<List<SpotifyArtist>, Int, Int> {
        val variables = buildJsonObject {
            putJsonArray("filters") { add("Artists") }
            put("order", null as String?)
            put("textFilter", "")
            putJsonArray("features") { add("LIKED_SONGS") }
            put("limit", PAGE_SIZE)
            put("offset", offset)
            put("flatten", false)
            putJsonArray("expandedFolders") {}
            put("folderUri", null as String?)
            put("includeFoldersWhenFlattening", true)
        }
        return parseArtistPage(gql("libraryV3", libraryHash, variables, headers, retries))
    }

    /**
     * Every track of a playlist, however long. [onPage] gets the running list
     * after each page, so a thousand-song playlist fills in as it loads
     * rather than appearing all at once at the end.
     */
    suspend fun tracks(
        playlistId: String,
        onPage: (List<SpotifyTrack>) -> Unit = {},
    ): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val headers = try {
            authHeaders()
        } catch (e: Exception) {
            TrackLog.w(TAG, "Spotify tracks auth failed: ${e.message ?: e.javaClass.simpleName}")
            return@withContext emptyList()
        }
        val collected = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val (items, total, raw) = try {
                if (playlistId == LIKED_ID) {
                    likedPage(headers, offset)
                } else {
                    trackPage(headers, playlistId, offset)
                }
            } catch (e: Exception) {
                TrackLog.w(TAG, "Spotify tracks page failed: ${e.message ?: e.javaClass.simpleName}")
                break
            }
            collected += items
            offset += raw
            onPage(collected.toList())
            if (raw == 0 || offset >= total) break
        }
        collected
    }

    private suspend fun playlistPage(
        headers: Map<String, String>,
        offset: Int,
        retries: Int,
    ): Triple<List<SpotifyPlaylist>, Int, Int> {
        val variables = buildJsonObject {
            putJsonArray("filters") { add("Playlists") }
            put("order", null as String?)
            put("textFilter", "")
            putJsonArray("features") {
                add("LIKED_SONGS")
                add("YOUR_EPISODES_V2")
                add("PRERELEASES")
                add("EVENTS")
            }
            put("limit", PAGE_SIZE)
            put("offset", offset)
            // Folders are how a real library is organised, and these two flags
            // are what decides whether their contents exist at all. Flattening
            // *without* including folder contents (flatten = true,
            // includeFoldersWhenFlattening = false) hands back only the
            // playlists sitting at the top level: a library kept in folders
            // came back nearly empty, which read as a failed sync rather than
            // as a query that never mentioned them. MetroFuse's combination -
            // do not flatten the roots, let the server expand folders while
            // flattening - is the one that returns everything.
            put("flatten", false)
            putJsonArray("expandedFolders") {}
            put("folderUri", null as String?)
            put("includeFoldersWhenFlattening", true)
        }
        return parsePlaylistPage(gql("libraryV3", libraryHash, variables, headers, retries))
    }

    private suspend fun trackPage(
        headers: Map<String, String>,
        playlistId: String,
        offset: Int,
    ): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", offset)
            put("limit", 100)
            put("enableWatchFeedEntrypoint", false)
        }
        return parseTrackPage(gql("fetchPlaylist", playlistHash, variables, headers))
    }

    /**
     * The playlist's cover at its largest. The library list only carries a
     * thumbnail, which looks soft once it fills a playlist page's header, so
     * the page asks for the real image after it opens.
     */
    suspend fun cover(playlistId: String): String? = withContext(Dispatchers.IO) {
        if (playlistId == LIKED_ID) return@withContext LIKED_COVER
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", 0)
            put("limit", 1)
            put("enableWatchFeedEntrypoint", false)
        }
        val root = gql("fetchPlaylist", playlistHash, variables, authHeaders())
        largestSource(root.obj("data")?.obj("playlistV2")?.obj("images"))
    }

    /**
     * Spotify's editorial picks (`GET /v1/browse/featured-playlists`):
     * Today's Top Hits, RapCaviar and the like. Public data — the web-player
     * bearer minted from the user's own cookie reads it with no extra scopes.
     * Feeds the merged Home feed, MetroFuse-style.
     */
    suspend fun featuredPlaylists(limit: Int = 20, retries: Int = 3): List<SpotifyPlaylist> =
        withContext(Dispatchers.IO) {
            val headers = try {
                authHeaders()
            } catch (e: Exception) {
                TrackLog.w(TAG, "Spotify featured playlists auth failed: ${e.message ?: e.javaClass.simpleName}")
                return@withContext emptyList()
            }
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/browse/featured-playlists?limit=$limit")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            val text = withRateLimitRetry(maxAttempts = retries) {
                Http.client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        throw SpotifyRateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 2L)
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Spotify featured playlists failed (${response.code})")
                    }
                    payload
                }
            }
            parseFeaturedPlaylists(json.parseToJsonElement(text).jsonObject)
        }

    /**
     * Fallback when featured-playlists answers empty: playlists of one browse
     * category (`GET /v1/browse/categories/{id}/playlists`), the same
     * `{playlists: {items}}` shape as featured.
     */
    suspend fun categoryPlaylists(categoryId: String, limit: Int = 20, retries: Int = 3): List<SpotifyPlaylist> =
        withContext(Dispatchers.IO) {
            val headers = try {
                authHeaders()
            } catch (e: Exception) {
                TrackLog.w(TAG, "Spotify category playlists auth failed: ${e.message ?: e.javaClass.simpleName}")
                return@withContext emptyList()
            }
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/browse/categories/$categoryId/playlists?limit=$limit")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            val text = withRateLimitRetry(maxAttempts = retries) {
                Http.client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        throw SpotifyRateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 2L)
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Spotify category playlists failed (${response.code})")
                    }
                    payload
                }
            }
            parseFeaturedPlaylists(json.parseToJsonElement(text).jsonObject)
        }

    /**
     * The listener's most-played tracks (`GET /v1/me/top/tracks`). Same
     * full-track objects as saved tracks, so the same parser reads them.
     * Feeds the "Your top songs" Home shelf, MetroFuse-style.
     */
    suspend fun topTracks(limit: Int = 20, timeRange: String = "short_term", retries: Int = 3): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            val headers = try {
                authHeaders()
            } catch (e: Exception) {
                TrackLog.w(TAG, "Spotify top tracks auth failed: ${e.message ?: e.javaClass.simpleName}")
                return@withContext emptyList()
            }
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/top/tracks?limit=$limit&time_range=$timeRange")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            val text = withRateLimitRetry(maxAttempts = retries) {
                Http.client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        throw SpotifyRateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 2L)
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Spotify top tracks failed (${response.code})")
                    }
                    payload
                }
            }
            parseTopTracks(json.parseToJsonElement(text).jsonObject)
        }

    /**
     * Recently played (`GET /v1/me/player/recently-played`): items wrapping
     * full track objects, the same shape as saved tracks.
     */
    suspend fun recentlyPlayed(limit: Int = 20, retries: Int = 3): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            val headers = try {
                authHeaders()
            } catch (e: Exception) {
                TrackLog.w(TAG, "Spotify recently played auth failed: ${e.message ?: e.javaClass.simpleName}")
                return@withContext emptyList()
            }
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/recently-played?limit=$limit")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            val text = withRateLimitRetry(maxAttempts = retries) {
                Http.client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        throw SpotifyRateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 2L)
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Spotify recently played failed (${response.code})")
                    }
                    payload
                }
            }
            parseWrappedTracks(json.parseToJsonElement(text).jsonObject)
        }

    /** Tracks of a Spotify album, opened inside BitFuse rather than in Spotify. */
    suspend fun albumTracks(albumId: String, retries: Int = 3): List<SpotifyTrack> =
        webApiTracks(
            url = "https://api.spotify.com/v1/albums/$albumId/tracks?limit=50&market=from_token",
            label = "album tracks",
            retries = retries,
            parser = ::parseTopTracks,
        )

    /** An artist's Spotify top tracks, opened inside BitFuse and resolved by its providers. */
    suspend fun artistTopTracks(artistId: String, retries: Int = 3): List<SpotifyTrack> =
        webApiTracks(
            url = "https://api.spotify.com/v1/artists/$artistId/top-tracks?market=from_token",
            label = "artist top tracks",
            retries = retries,
            parser = ::parseArtistTopTracks,
        )

    private suspend fun webApiTracks(
        url: String,
        label: String,
        retries: Int,
        parser: (JsonObject) -> List<SpotifyTrack>,
    ): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val headers = authHeaders()
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val text = withRateLimitRetry(maxAttempts = retries) {
            Http.client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (response.code == 429) {
                    throw SpotifyRateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 2L)
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("Spotify $label failed (${response.code})")
                }
                payload
            }
        }
        parser(json.parseToJsonElement(text).jsonObject)
    }

    private suspend fun likedPage(headers: Map<String, String>, offset: Int): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("offset", offset)
            put("limit", 100)
        }
        return parseLikedPage(gql("fetchLibraryTracks", likedHash, variables, headers))
    }

    private suspend fun authHeaders(): Map<String, String> {
        val tokens = auth?.tokens()
            ?: throw IllegalStateException("Spotify sign-in expired")
        return buildMap {
            put("Authorization", "Bearer ${tokens.bearer}")
            put("Accept", "application/json")
            put("app-platform", "WebPlayer")
            put("Origin", "https://open.spotify.com")
            put("Referer", "https://open.spotify.com/")
            put("User-Agent", USER_AGENT)
            tokens.clientToken?.let { put("Client-Token", it) }
            // MetroFuse-proven: the session cookie travels with the request,
            // not just inside the minted token. Endpoints that 403 a bare
            // bearer accept it once `sp_dc` rides along.
            cookieProvider?.invoke()?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }
    }

    private suspend fun gql(
        operation: String,
        hash: String,
        variables: JsonObject,
        headers: Map<String, String>,
        retries: Int = 3,
    ): JsonObject {
        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", operation)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }
        val request = Request.Builder()
            .url(GQL)
            .post(body.toString().toRequestBody(mediaType))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val text = withRateLimitRetry(maxAttempts = retries) {
            Http.client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (response.code == 429) {
                    throw SpotifyRateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 2L)
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("Spotify $operation failed (${response.code})")
                }
                payload
            }
        }
        val root = json.parseToJsonElement(text).jsonObject
        val error = root.arr("errors")?.firstOrNull()?.jsonObject?.str("message")
        if (!error.isNullOrBlank()) throw IllegalStateException(error)
        return root
    }
}

/** The playlists kept, the library's total, and how many entries the page held before filtering. */
internal fun parsePlaylistPage(root: JsonObject): Triple<List<SpotifyPlaylist>, Int, Int> {
    val library = root.obj("data")?.obj("me")?.obj("libraryV3")
        ?: throw IllegalStateException("Spotify library response was empty")
    val rawItems = library.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("item") ?: return@mapNotNull null
        val typeName = wrapper.str("__typename").orEmpty()
        if (!typeName.contains("Playlist", ignoreCase = true)) return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        if (data.str("__typename") != "Playlist") return@mapNotNull null
        val uri = wrapper.str("_uri") ?: return@mapNotNull null
        val name = data.str("name").orEmpty()
        if (name.isBlank()) return@mapNotNull null
        SpotifyPlaylist(
            id = uri.substringAfterLast(":"),
            name = name,
            owner = data.obj("ownerV2")?.obj("data")?.str("name"),
            imageUrl = coverUrl(data.obj("images")),
        )
    }
    return Triple(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

internal fun parseAlbumPage(root: JsonObject): Triple<List<SpotifyAlbum>, Int, Int> {
    val library = root.obj("data")?.obj("me")?.obj("libraryV3")
        ?: throw IllegalStateException("Spotify library response was empty")
    val rawItems = library.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        runCatching {
            val wrapper = element.jsonObject.obj("item") ?: return@runCatching null
            val data = wrapper.obj("data") ?: return@runCatching null
            val dataType = data.str("__typename").orEmpty()
            if (!dataType.contains("Album", ignoreCase = true)) return@runCatching null
            val uri = wrapper.str("_uri") ?: return@runCatching null
            val name = data.str("name").orEmpty()
            if (name.isBlank()) return@runCatching null
            val artistsRaw = data.obj("artists")?.arr("items") ?: data.arr("artists")
            val artistName = artistsRaw
                ?.firstOrNull()?.jsonObject?.let { it.obj("data") ?: it }
                ?.let {
                    it.obj("profile")?.str("name")
                        ?: it.str("name")
                        ?: it.obj("profile")?.str("name")
                }
            SpotifyAlbum(
                id = uri.substringAfterLast(":"),
                name = name,
                artist = artistName,
                imageUrl = coverUrl(data.obj("coverArt") ?: data.obj("images")),
                year = data.str("date")?.take(4) ?: data.str("releaseDate")?.take(4),
            )
        }.getOrNull()
    }
    return Triple(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

internal fun parseArtistPage(root: JsonObject): Triple<List<SpotifyArtist>, Int, Int> {
    val library = root.obj("data")?.obj("me")?.obj("libraryV3")
        ?: throw IllegalStateException("Spotify library response was empty")
    val rawItems = library.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        runCatching {
            val wrapper = element.jsonObject.obj("item") ?: return@runCatching null
            val data = wrapper.obj("data") ?: return@runCatching null
            val dataType = data.str("__typename").orEmpty()
            if (!dataType.contains("Artist", ignoreCase = true)) return@runCatching null
            val uri = wrapper.str("_uri") ?: return@runCatching null
            val name = data.obj("profile")?.str("name") ?: data.str("name").orEmpty()
            if (name.isBlank()) return@runCatching null
            SpotifyArtist(
                id = uri.substringAfterLast(":"),
                name = name,
                imageUrl = coverUrl(data.obj("visuals")?.obj("avatarImage") ?: data.obj("images")),
            )
        }.getOrNull()
    }
    return Triple(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The tracks kept, the playlist's total, and how many entries the page held before filtering. */
internal fun parseTrackPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val content = root.obj("data")?.obj("playlistV2")?.obj("content")
        ?: throw IllegalStateException("Spotify playlist response was empty")
    val rawItems = content.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val data = element.jsonObject.obj("itemV2")?.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = data.str("uri") ?: data.str("_uri") ?: return@mapNotNull null
        val artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }.joinToString(", ")
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = artist,
            album = album?.str("name"),
            durationMs = data.obj("duration")?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, content.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The Liked Songs page: `me.library.tracks`, each entry wrapping the track under `track`. */
internal fun parseLikedPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val tracks = root.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
        ?: throw IllegalStateException("Spotify liked songs response was empty")
    val rawItems = tracks.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("track") ?: return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
                artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
            }.joinToString(", "),
            album = album?.str("name"),
            durationMs = (data.obj("trackDuration") ?: data.obj("duration"))?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, tracks.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The featured-playlists page: each entry is a playlist with a nested `images` list. */
internal fun parseFeaturedPlaylists(root: JsonObject): List<SpotifyPlaylist> =
    root.obj("playlists")?.arr("items").orEmpty().mapNotNull { element ->
        val o = element.jsonObject
        val id = o.str("id") ?: return@mapNotNull null
        SpotifyPlaylist(
            id = id,
            name = o.str("name") ?: "Untitled",
            owner = o.obj("owner")?.str("display_name"),
            imageUrl = o.arr("images")?.firstOrNull()?.jsonObject?.str("url"),
        )
    }

/** The top-tracks page: full track objects, the same shape as saved tracks. */
internal fun parseTopTracks(root: JsonObject): List<SpotifyTrack> =
    root.arr("items").orEmpty().mapNotNull { element -> parseWebApiTrack(element.jsonObject) }

/** The recently-played page: each entry wraps the full track under `track`. */
internal fun parseWrappedTracks(root: JsonObject): List<SpotifyTrack> =
    root.arr("items").orEmpty().mapNotNull { element ->
        val track = element.jsonObject.obj("track") ?: return@mapNotNull null
        parseWebApiTrack(track)
    }

/** `/v1/me/tracks` wraps each track under `item`. */
internal fun parseSavedTracks(root: JsonObject): List<SpotifyTrack> =
    root.arr("items").orEmpty().mapNotNull { element ->
        element.jsonObject.obj("track")?.let(::parseWebApiTrack)
    }

/** `/v1/me/albums` wraps each saved album under `album`. */
internal fun parseSavedAlbums(root: JsonObject): List<SpotifyAlbum> =
    root.arr("items").orEmpty().mapNotNull { element ->
        val album = element.jsonObject.obj("album") ?: return@mapNotNull null
        val id = album.str("id") ?: return@mapNotNull null
        val name = album.str("name") ?: return@mapNotNull null
        SpotifyAlbum(
            id = id,
            name = name,
            artist = album.arr("artists").orEmpty().firstOrNull()?.jsonObject?.str("name"),
            imageUrl = album.arr("images")?.firstOrNull()?.jsonObject?.str("url"),
            year = album.str("release_date")?.take(4),
        )
    }

internal fun parseWebApiPlaylists(root: JsonObject): List<SpotifyPlaylist> =
    root.arr("items").orEmpty().mapNotNull { element ->
        val item = element.jsonObject
        val id = item.str("id") ?: return@mapNotNull null
        SpotifyPlaylist(
            id = id,
            name = item.str("name") ?: return@mapNotNull null,
            owner = item.obj("owner")?.str("display_name"),
            imageUrl = item.arr("images")?.firstOrNull()?.jsonObject?.str("url"),
        )
    }

/** Artist top-tracks wraps the same full Web API track objects under `tracks`. */
internal fun parseArtistTopTracks(root: JsonObject): List<SpotifyTrack> =
    root.arr("tracks").orEmpty().mapNotNull { element -> parseWebApiTrack(element.jsonObject) }

/** A full track as the official Web API returns it, or null when it lacks an id or a name. */
private fun parseWebApiTrack(o: JsonObject): SpotifyTrack? {
    val id = o.str("id") ?: return null
    val title = o.str("name") ?: return null
    val artist = o.arr("artists").orEmpty().mapNotNull { artist ->
        artist.jsonObject.str("name")?.takeIf { it.isNotBlank() }
    }.joinToString(", ")
    val album = o.obj("album")
    return SpotifyTrack(
        id = id,
        title = title,
        artist = artist,
        album = album?.str("name"),
        durationMs = o.int("duration_ms") ?: 0,
        imageUrl = album?.arr("images")?.firstOrNull()?.jsonObject?.str("url"),
    )
}

/** Thrown when Spotify rate-limits us (HTTP 429), carrying its `Retry-After`. */
class SpotifyRateLimitedException(val retryAfterSec: Long) :
    java.io.IOException("Spotify rate limited, retry after $retryAfterSec s")

/**
 * Retries [block] on [SpotifyRateLimitedException], honoring the server's
 * `Retry-After` (via [rateLimitDelayMs], capped at 30s) and using exponential
 * backoff when it's absent. Any other exception passes straight through.
 *
 * [maxAttempts] = 1 means "ask once and report the refusal": the Home feed
 * uses that, because a 429 there has to leave the shelves that are already
 * there alone rather than stall the whole feed for a minute waiting on a
 * retry that is very likely to be refused again.
 */
internal suspend fun <T> withRateLimitRetry(maxAttempts: Int = 3, block: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: SpotifyRateLimitedException) {
            if (attempt >= maxAttempts - 1) throw e
            delay(rateLimitDelayMs(e.retryAfterSec, attempt))
            attempt++
        }
    }
}

/** Honors `Retry-After` when present (capped at 30s), otherwise exponential backoff, also capped. */
internal fun rateLimitDelayMs(retryAfterSec: Long?, attempt: Int): Long {
    val seconds = retryAfterSec ?: (1L shl attempt.coerceAtMost(5))
    return seconds.coerceIn(0L, 30L) * 1000L
}

/**
 * The widest source of an image's first entry. Spotify lists a playlist's
 * sizes in no fixed order — a 60px thumbnail can come last — so the list is
 * read by width rather than by position.
 */
private fun largestSource(images: JsonObject?): String? {
    // Spotify's cover shapes vary: some nest sources under `items[0].sources`
    // (playlist images), others expose `sources` directly (coverArt), and the
    // avatar image nests under `visuals.avatarImage`. Try every shape.
    val itemSources = images?.arr("items")?.firstOrNull()?.jsonObject?.arr("sources")
    val directSources = images?.arr("sources")
    val sources = itemSources ?: directSources ?: return null
    return sources
        .mapNotNull { source ->
            val url = source.jsonObject.str("url") ?: return@mapNotNull null
            url to (source.jsonObject.int("width") ?: 0)
        }
        .maxByOrNull { it.second }
        ?.first
}

private fun coverUrl(images: JsonObject?): String? = largestSource(images)

private fun JsonArray.lastUrl(): String? =
    mapNotNull { it.jsonObject.str("url") }.lastOrNull()

private fun JsonObject.obj(key: String): JsonObject? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.arr(key: String): JsonArray? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

private fun JsonObject.str(key: String): String? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
