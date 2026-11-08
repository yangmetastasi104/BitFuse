package com.music.bitchord.playback.sing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object SingControl {
    private val _enabled = MutableStateFlow(false)
    // Sing is a karaoke/vocal-removal effect: start with the vocal stem fully
    // removed. At 100% the blend is the untouched original, which made the
    // default toggle appear to do nothing.
    private val _vocalLevel = MutableStateFlow(0f)
    private val _status = MutableStateFlow("Motore non inizializzato")
    val enabled = _enabled.asStateFlow()
    val vocalLevel = _vocalLevel.asStateFlow()
    val status = _status.asStateFlow()

    fun setEnabled(value: Boolean) { _enabled.value = value }
    fun setVocalLevel(value: Float) { _vocalLevel.value = value.coerceIn(0f, 1f) }
    fun setStatus(value: String) { _status.value = value }
}
