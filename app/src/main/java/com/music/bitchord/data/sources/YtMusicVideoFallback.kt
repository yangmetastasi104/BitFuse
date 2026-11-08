package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.innertube.YtmNetwork
import kotlinx.coroutines.CancellationException

/**
 * YouTube Music as an audio provider - for music videos only, and only after
 * every configured provider has already missed.
 *
 * This build switches YouTube Music playback off at startup: ordinary tracks
 * must be served by an add-on. That is the right default for a lossless
 * library, but it leaves one hole. A music video that exists *only* as a
 * YouTube upload has no provider copy to match, so the queue had nothing to
 * play and skipped it.
 *
 * So the switch is not turned back on globally: [YtmNetwork.asLastResort] opens
 * it for the length of one resolve, and only the video branch calls it. A
 * plain song no provider has still fails exactly as it did before.
 */
object YtMusicVideoFallback {

    private const val TAG = "BitChord"

    /**
     * A stream for [videoId] from YouTube Music itself, or null when it cannot
     * be extracted.
     *
     * The headers come from [StreamResolver.mediaHeadersFor] because a
     * googlevideo URL is bound to the client that minted it: fetched under a
     * different `User-Agent` it answers 403, which on screen looks exactly like
     * the failure this fallback exists to avoid.
     */
    suspend fun stream(videoId: String): SourceStream? {
        if (videoId.isBlank()) return null
        return try {
            // The resolver checks this switch too; only the scoped last-resort
            // window may bypass it for an actual video after all add-ons miss.
            YtmNetwork.asLastResort {
                val url = StreamResolver.resolve(videoId)
                val headers = StreamResolver.mediaHeadersFor(url)
                TrackLog.d(TAG, "video fallback: served by YouTube Music", about = videoId)
                SourceStream(url = url, headers = headers, format = StreamFormat())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(
                TAG,
                "video fallback missed for $videoId: ${e.javaClass.simpleName}: ${e.message}",
                e,
                about = videoId,
            )
            null
        }
    }
}
