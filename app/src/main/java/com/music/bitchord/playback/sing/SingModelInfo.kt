package com.music.bitchord.playback.sing

/** Metadata required by the MDX-Net streaming pipeline. */
data class SingModelInfo(
    val id: String = "uvr_mdxnet_1_9703_mnn_fp16",
    val targetStem: String = "vocal",
    val networkType: String = "stft",
    val sampleRate: Int = 44100,
    val blockSize: Int = 4096,
    val lookaheadSize: Int = 16384,
    val numBins: Int = 2048,
    val numFrames: Int = 256,
    val fftSize: Int = 6144,
    val compensation: Float = 1.035f,
    val hopSize: Int = 1024,
    val chunkSamples: Int = 261120,
    val crossfadeSamples: Int = 44100,
    val hopSamples: Int = 217020,
    val engine: String = "mnn",
    val precision: String = "FP16",
    val fileName: String = "UVR_MDXNET_1_9703_fp16.mnn",
    val keepsBackingVocals: Boolean = false,
) {
    val isInstrumental: Boolean get() = targetStem.equals("instrumental", ignoreCase = true)
    val isTimeDomain: Boolean get() = networkType.equals("time_domain", ignoreCase = true)
    val isVr3Band: Boolean get() = networkType.equals("vr_3band", ignoreCase = true)
}
