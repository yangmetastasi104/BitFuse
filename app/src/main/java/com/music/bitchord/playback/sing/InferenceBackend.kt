package com.music.bitchord.playback.sing



interface InferenceBackend : AutoCloseable {
    val providerName: String
    val shortProviderName: String
    val modelInfo: SingModelInfo

    val numBins: Int
        get() = modelInfo.numBins

    fun run(stftInput: FloatArray, vocalOutput: FloatArray) {
        throw UnsupportedOperationException("STFT inference not supported by this backend")
    }

    fun runTimeDomain(inPcm: FloatArray, outPcm: FloatArray, frameCount: Int) {
        throw UnsupportedOperationException("Time-domain inference not supported by this backend")
    }

    fun resetStates() {}
}
