package com.music.bitchord.data.innertube

import java.io.IOException

/**
 * Separate switches for YouTube Music catalog traffic vs playback traffic.
 *
 * - **Catalog** (`youtubei` browse/search/account/library reads, visitor and
 *   session fetches, lyrics/transcript lookups): metadata only. Enabled
 *   wherever the app shows a catalog — Android and desktop alike.
 * - **Playback** (InnerTubeX/NewPipe extraction, `googlevideo` probes and
 *   fetches, stats pings, PoToken minting): audio bytes. Disabled on Android,
 *   where every play must resolve through the enabled add-on/provider
 *   sources; the desktop app leaves it on.
 *
 * Failing closed (throwing [DisabledException]) rather than returning empty
 * is deliberate: `YtMusicRepository.call` wraps everything in `Result`, and
 * the source layer treats a throw as "this source missed", so screens degrade
 * to explicit empty/error states instead of silently showing nothing.
 */
object YtmNetwork {
    class DisabledException(message: String) : IOException(message)

    /** Catalog reads allowed. The Android app leaves this on. */
    @Volatile
    var catalogEnabled: Boolean = true

    /**
     * Audio resolution allowed. The Android app sets this false
     * unconditionally at startup — not a user setting, nothing in the UI can
     * turn it back on.
     */
    @Volatile
    var playbackEnabled: Boolean = true

    /** Backwards-compatible master switch: false disables both. */
    @Volatile
    var enabled: Boolean = true

    /** Throws [DisabledException] unless catalog traffic is allowed. */
    fun requireCatalog() {
        if (!enabled || !catalogEnabled) {
            throw DisabledException("YouTube Music catalog is disabled in this build")
        }
    }

    /**
     * Last-resort resolves currently allowed past [playbackEnabled].
     *
     * Raised only by [asLastResort] and only while its block runs. A counter
     * rather than a bare flag so two overlapping last resorts cannot close the
     * window under each other.
     */
    private val lastResortDepth = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Runs [block] with playback allowed even where this build switched it off.
     *
     * This is the seam the video last resort uses: a music video whose
     * providers all missed is resolved by YouTube Music itself, once, while
     * everything else in the app still sees playback disabled. Nothing else in
     * this build asks for a YouTube stream during that window - downloads are
     * off the same switch and AutoMix's analysis source is disabled - so
     * raising it cannot quietly put YouTube back under ordinary tracks.
     */
    suspend fun <T> asLastResort(block: suspend () -> T): T {
        lastResortDepth.incrementAndGet()
        try {
            return block()
        } finally {
            lastResortDepth.decrementAndGet()
        }
    }

    /** Throws [DisabledException] unless playback traffic is allowed. */
    fun requirePlayback() {
        if (isPlaybackDisabled()) {
            throw DisabledException(
                "YouTube Music playback is disabled in this build; " +
                    "configure an add-on source instead",
            )
        }
    }

    /** True when catalog traffic is switched off. */
    fun isCatalogDisabled(): Boolean = !enabled || !catalogEnabled

    /** True when playback traffic is switched off - except inside [asLastResort]. */
    fun isPlaybackDisabled(): Boolean = !enabled || (!playbackEnabled && lastResortDepth.get() == 0)

    /** True when YouTube Music traffic is switched off. */
    fun isDisabled(): Boolean = !enabled
}
