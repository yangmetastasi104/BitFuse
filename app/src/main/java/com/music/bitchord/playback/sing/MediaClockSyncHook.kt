package com.music.bitchord.playback.sing

/** Kept for the shared streaming engine; BitFuse uses Media3's native clock. */
object MediaClockSyncHook {
    @Volatile var isBuffering: Boolean = false
    @Volatile var bufferingOffsetUs: Long = 0L
    @Volatile var latencyOffsetUs: Long = 0L
}
