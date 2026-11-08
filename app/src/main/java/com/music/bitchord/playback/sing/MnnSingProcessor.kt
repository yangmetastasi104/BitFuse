package com.music.bitchord.playback.sing

import android.content.Context
import android.util.Log
import com.music.bitchord.playback.audio.AudioBlock
import java.io.File

/** MNN-only MDX-Net stem engine used by the Media3 audio-processor adapter. */
class MnnSingProcessor(private val context: Context) {
    companion object {
        private const val TAG = "BitFuseSing"
        const val AUDIO_BLOCK_FRAMES = 4096
        private const val MAX_SAMPLES = AUDIO_BLOCK_FRAMES * 4 * 2
    }

    @Volatile var status: String = "Motore MNN non inizializzato"
        private set
    val isReady: Boolean get() = engine != null && backend != null

    private var sampleRate = 0
    private var engine: AiStemSeparationEngine? = null
    private var backend: MnnInferenceBackend? = null
    private var downSampler: StftEngine.NativeSincResampler? = null
    private var upSampler: StftEngine.NativeSincResampler? = null
    private val input44k = FloatArray(MAX_SAMPLES)
    private val output44k = FloatArray(MAX_SAMPLES)
    private val outputAtDeviceRate = FloatArray(MAX_SAMPLES)
    private val inputBlock = AudioBlock(2, AUDIO_BLOCK_FRAMES)
    private val outputBlock = AudioBlock(2, AUDIO_BLOCK_FRAMES)
    private var fedFrames44k = 0L
    private var lastReportedPreRoll = -1

    /** Prepares the sole licensed model/backend. Called only for the audible Media3 route. */
    @Synchronized
    fun configure(sampleRate: Int, channelCount: Int): Boolean {
        if (engine != null && this.sampleRate == sampleRate) return true
        close()
        this.sampleRate = sampleRate
        if (channelCount != 2 || sampleRate <= 0) {
            status = "Sing richiede PCM stereo"
            SingControl.setStatus(status)
            return false
        }
        return runCatching {
            val info = SingModelInfo()
            // Stick to the backend already observed working on this handset.
            // NNAPI has not been validated here; stability takes priority until
            // the native teardown race has been eliminated and playback retested.
            val newBackend = MnnInferenceBackend(modelFile(), threads = 6, target = "cpu", modelInfo = info)
            val newEngine = AiStemSeparationEngine(newBackend).apply {
                preserveSide = false
                twoWaySlider = false
                ecoMode = true
            }
            backend = newBackend
            engine = newEngine
            if (sampleRate != 44100) {
                downSampler = StftEngine.NativeSincResampler(sampleRate, 44100)
                upSampler = StftEngine.NativeSincResampler(44100, sampleRate)
            }
            status = "Pronto · ${newBackend.shortProviderName}"
            SingControl.setStatus(status)
            Log.i(TAG, "Prepared MDX-Net MNN FP16 at ${sampleRate}Hz (${newBackend.shortProviderName}, thermal pacing on)")
            true
        }.getOrElse { error ->
            status = "MNN non disponibile: ${error.message ?: error.javaClass.simpleName}"
            SingControl.setStatus(status)
            Log.e(TAG, status, error)
            close()
            false
        }
    }

    fun canAcceptInput(deviceFrames: Int): Boolean {
        val current = engine ?: return false
        val frames44k = if (sampleRate == 44100) deviceFrames
            else ((deviceFrames.toLong() * 44100L) / sampleRate).toInt().coerceAtLeast(1)
        return current.canAcceptInput(frames44k)
    }

    /** Converts and queues one PCM block. False means leave the Media3 input untouched. */
    @Synchronized
    fun feed(block: AudioBlock): Boolean {
        val current = engine ?: return false
        if (!current.active) current.activate()
        val frames44k = if (sampleRate == 44100) {
            block.samples.copyInto(input44k, endIndex = block.frameCount * 2)
            block.frameCount
        } else {
            downSampler?.resample(block.samples, block.frameCount, input44k) ?: 0
        }
        if (frames44k <= 0) return false
        current.pushInput(input44k, frames44k)
        fedFrames44k += frames44k
        if (current.availableVocalFrames <= 0L) {
            val percent = ((fedFrames44k * 100L) / AiStemSeparationEngine.CHUNK_SAMPLES).toInt().coerceIn(0, 99)
            if (percent / 5 != lastReportedPreRoll / 5) {
                lastReportedPreRoll = percent
                status = "Pre-roll MNN $percent%"
                SingControl.setStatus(status)
            }
        }
        return true
    }

    val availableStemFrames44k: Long get() = engine?.availableVocalFrames ?: 0L
    val availableOriginalFrames44k: Long get() = engine?.availableInputFrames ?: 0L

    /** Renders only frames for which the neural stem is already available. */
    @Synchronized
    fun renderSing(maxDeviceFrames: Int, level: Float): Int {
        val current = engine ?: return 0
        val available = current.availableVocalFrames
        if (available <= 0L) return 0
        val request44k = if (sampleRate == 44100) {
            minOf(maxDeviceFrames.toLong(), available).toInt()
        } else {
            minOf(
                available,
                ((maxDeviceFrames.toLong() * 44100L) / sampleRate).coerceAtLeast(1L),
            ).toInt()
        }
        if (request44k <= 0) return 0
        val rendered44k = current.popVocalAndBlend(output44k, request44k, level.coerceIn(0f, 1f))
        if (rendered44k <= 0) return 0
        status = "MNN separazione attiva · ${current.availableVocalFrames / 44} ms in coda"
        SingControl.setStatus(status)
        if (sampleRate == 44100) {
            outputBlock.samples.fill(0f, 0, rendered44k * 2)
            output44k.copyInto(outputBlock.samples, endIndex = rendered44k * 2)
            outputBlock.setFrameCount(rendered44k)
            return rendered44k
        }
        val atDeviceRate = upSampler?.resample(output44k, rendered44k, outputAtDeviceRate) ?: 0
        if (atDeviceRate <= 0) return 0
        outputAtDeviceRate.copyInto(outputBlock.samples, endIndex = atDeviceRate * 2)
        outputBlock.setFrameCount(atDeviceRate)
        return atDeviceRate
    }

    /** Original PCM is drained in order when the listener switches Sing off mid-preroll. */
    @Synchronized
    fun renderOriginal(maxDeviceFrames: Int): Int {
        val current = engine ?: return 0
        val available = current.availableInputFrames
        if (available <= 0L) return 0
        val request44k = if (sampleRate == 44100) minOf(maxDeviceFrames.toLong(), available).toInt()
        else minOf(available, ((maxDeviceFrames.toLong() * 44100L) / sampleRate).coerceAtLeast(1L)).toInt()
        if (request44k <= 0) return 0
        val rendered44k = current.popOriginal(output44k, request44k)
        if (rendered44k <= 0) return 0
        if (sampleRate == 44100) {
            output44k.copyInto(outputBlock.samples, endIndex = rendered44k * 2)
            outputBlock.setFrameCount(rendered44k)
            return rendered44k
        }
        val atDeviceRate = upSampler?.resample(output44k, rendered44k, outputAtDeviceRate) ?: 0
        if (atDeviceRate <= 0) return 0
        outputAtDeviceRate.copyInto(outputBlock.samples, endIndex = atDeviceRate * 2)
        outputBlock.setFrameCount(atDeviceRate)
        return atDeviceRate
    }

    fun copyToOutput(): AudioBlock = outputBlock

    fun feedBlock(): AudioBlock = inputBlock

    @Synchronized
    fun resetStream() {
        engine?.reset()
        downSampler?.reset()
        upSampler?.reset()
        fedFrames44k = 0L
        lastReportedPreRoll = -1
    }

    @Synchronized
    fun deactivateIfDrained() {
        if (availableOriginalFrames44k == 0L) {
            engine?.deactivate()
            downSampler?.reset()
            upSampler?.reset()
        }
    }

    fun signalEndOfStream() { engine?.onEndOfStream() }

    val isEnded: Boolean get() = engine?.isEnded() ?: true

    fun awaitWorker(timeoutMs: Long = 16L) { engine?.awaitProgress(timeoutMs) }

    @Synchronized
    fun close() {
        val oldEngine = engine
        val oldBackend = backend
        val oldDownSampler = downSampler
        val oldUpSampler = upSampler
        engine = null
        backend = null
        downSampler = null
        upSampler = null

        oldEngine?.shutdown()
        Thread({
            val workerStopped = oldEngine?.shutdownAndWait() ?: true
            if (!workerStopped) {
                // Never free MNN/STFT state while a JNI call may still be using it.
                Log.e(TAG, "Sing worker did not stop cleanly; keeping native handles alive to avoid use-after-free")
                return@Thread
            }
            runCatching { oldBackend?.close() }
                .onFailure { Log.e(TAG, "Failed to close MNN backend", it) }
            runCatching { oldDownSampler?.close() }
                .onFailure { Log.e(TAG, "Failed to close Sing downsampler", it) }
            runCatching { oldUpSampler?.close() }
                .onFailure { Log.e(TAG, "Failed to close Sing upsampler", it) }
        }, "SingNativeCleanup").apply {
            isDaemon = true
            start()
        }
    }

    private fun modelFile(): File {
        val directory = File(context.filesDir, "sing-models").apply { mkdirs() }
        val file = File(directory, SingModelInfo().fileName)
        if (!file.exists() || file.length() < 1_000_000L) {
            context.assets.open("sing/${SingModelInfo().fileName}").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }

}
