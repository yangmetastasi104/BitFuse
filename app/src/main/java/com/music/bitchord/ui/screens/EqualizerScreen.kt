package com.music.bitchord.ui.screens

import com.music.bitchord.ui.icons.SfIcons
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.music.bitchord.ui.components.IosSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.EqualizerMode
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.EqualizerPreset
import com.music.bitchord.playback.FilterKind
import com.music.bitchord.playback.ParametricBand
import com.music.bitchord.playback.ParametricLayout
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import android.content.Intent
import android.media.audiofx.AudioEffect
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The app's own equaliser, in the two shapes people have already been taught to
 * read one in.
 *
 * **Dynamic** is a tone pad: one puck over a dot grid, tilting the spectrum
 * left-to-right and shaping the middle of it bottom-to-top, with Broad and
 * Focused choosing how wide each move is. Two numbers and a puck, for the
 * majority of people who want it warmer or brighter and have no interest in
 * which decibel went where.
 *
 * **Manual** is the seven-band graphic equaliser, with a preset list over it.
 *
 * **Parametric** is the same idea with the filters unbolted: a bank of ten or
 * nineteen bands, each with its own filter type (PK/LSQ/HSQ), frequency, width
 * (Q) and gain. Values can be entered from the keypad or adjusted by touch.
 *
 * All three drive the same filters — see [EqLayout] — so the tab is a way of
 * describing a curve rather than a different piece of audio machinery, and
 * switching between them glides rather than clicks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** Opens the list of preset curves as a page — see [EqualizerPresetPage]. */
    onOpenPresets: () -> Unit = {},
) {
    val context = LocalContext.current
    val sessionId by AppSettings.audioSessionId.collectAsStateWithLifecycle()
    val enabled by AppSettings.equalizerEnabled.collectAsStateWithLifecycle()
    val mode by AppSettings.equalizerMode.collectAsStateWithLifecycle()
    val toneX by AppSettings.equalizerToneX.collectAsStateWithLifecycle()
    val toneY by AppSettings.equalizerToneY.collectAsStateWithLifecycle()
    val focused by AppSettings.equalizerFocused.collectAsStateWithLifecycle()
    val balance by AppSettings.equalizerBalance.collectAsStateWithLifecycle()
    val bands by AppSettings.equalizerBands.collectAsStateWithLifecycle()
    val preset by AppSettings.equalizerPreset.collectAsStateWithLifecycle()
    val parametricCount by AppSettings.equalizerParametricBands.collectAsStateWithLifecycle()
    val parametric by AppSettings.equalizerParametric.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()

    val haptics = rememberHaptics()
    var editingBand by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.equalizer))
        SettingsGroup(
            footer = stringResource(R.string.equalizer_footer),
            topSpacing = 0.dp,
        ) {
            SettingsRow(
                icon = SfIcons.Tune,
                title = stringResource(R.string.equalizer),
                trailing = {
                    IosSwitch(
                        checked = enabled,
                        onCheckedChange = {
                            haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff)
                            AppSettings.setEqualizerEnabled(it)
                        },
                    )
                },
                onClick = {
                    haptics.play(if (!enabled) Haptic.ToggleOn else Haptic.ToggleOff)
                    AppSettings.setEqualizerEnabled(!enabled)
                },
            )
        }

        // Off, the whole apparatus below reads as unavailable rather than
        // merely idle. Each control is told so itself rather than having its
        // touches swallowed by a layer above: the pad and the faders all claim
        // the gesture with `awaitFirstDown(requireUnconsumed = false)` — they
        // have to, or the scrolling page would steal every vertical drag — so
        // an ancestor's consumption never reaches them. Consuming on the
        // Initial pass to force it took the page's own scrolling down too.
        val settingsAlpha by animateFloatAsState(
            targetValue = if (enabled) 1f else 0.38f,
            animationSpec = tween(220),
            label = "equalizerEnabled",
        )
        Column(modifier = Modifier.graphicsLayer { alpha = settingsAlpha }) {
            Spacer(Modifier.height(18.dp))
            SegmentedControl(
                options = EqualizerMode.entries.map { it.localizedLabel() },
                selectedIndex = EqualizerMode.entries.indexOf(mode),
                onSelect = { AppSettings.setEqualizerMode(EqualizerMode.entries[it]) },
                enabled = enabled,
                modifier = Modifier.padding(horizontal = GROUP_INSET),
            )

            when (mode) {
                EqualizerMode.DYNAMIC -> {
                    SettingsGroup(
                        header = stringResource(R.string.equalizer_tone),
                        footer = stringResource(
                            if (focused) R.string.equalizer_focused_footer else R.string.equalizer_broad_footer,
                        ),
                    ) {
                        TonePad(
                            x = toneX,
                            y = toneY,
                            onChange = { newX, newY -> AppSettings.setEqualizerTone(newX, newY) },
                            enabled = enabled,
                        )
                        ToneReadout(x = toneX, y = toneY)
                        RowDivider()
                        ChoiceRow(
                            label = stringResource(R.string.equalizer_broad),
                            selected = !focused,
                            onClick = { AppSettings.setEqualizerFocused(false) },
                            enabled = enabled,
                        )
                        RowDivider()
                        ChoiceRow(
                            label = stringResource(R.string.equalizer_focused),
                            selected = focused,
                            onClick = { AppSettings.setEqualizerFocused(true) },
                            enabled = enabled,
                        )
                    }
                }

                EqualizerMode.MANUAL -> {
                    SettingsGroup {
                        SettingsRow(
                            icon = SfIcons.GraphicEq,
                            title = stringResource(R.string.equalizer_preset),
                            value = preset.localizedLabel(),
                            onClick = onOpenPresets,
                            enabled = enabled,
                        )
                    }
                    SettingsGroup(
                        header = stringResource(R.string.equalizer_bands),
                        footer = stringResource(R.string.equalizer_bands_footer),
                    ) {
                        BandSliders(
                            bands = bands,
                            onChange = { AppSettings.setEqualizerBands(it) },
                            enabled = enabled,
                        )
                        RowDivider()
                        DestructiveRow(
                            label = stringResource(R.string.equalizer_reset),
                            enabled = enabled,
                        ) {
                            haptics.play(Haptic.Select)
                            AppSettings.setEqualizerPreset(EqualizerPreset.FLAT)
                        }
                    }
                }

                EqualizerMode.PARAMETRIC -> {
                    SettingsGroup(
                        header = stringResource(R.string.equalizer_parametric_count),
                        footer = stringResource(R.string.equalizer_parametric_count_footer),
                    ) {
                        ChoiceRow(
                            label = stringResource(R.string.equalizer_bands_10),
                            selected = parametricCount == ParametricLayout.SMALL_BANDS,
                            onClick = { AppSettings.setEqualizerParametricBandCount(ParametricLayout.SMALL_BANDS) },
                            enabled = enabled,
                        )
                        RowDivider()
                        ChoiceRow(
                            label = stringResource(R.string.equalizer_bands_19),
                            selected = parametricCount == ParametricLayout.MAX_BANDS,
                            onClick = { AppSettings.setEqualizerParametricBandCount(ParametricLayout.MAX_BANDS) },
                            enabled = enabled,
                        )
                    }
                    SettingsGroup(
                        header = stringResource(R.string.equalizer_parametric_bands),
                        footer = stringResource(R.string.equalizer_parametric_footer),
                    ) {
                        parametric.forEachIndexed { index, band ->
                            if (index > 0) RowDivider()
                            ParametricBandRow(
                                index = index,
                                band = band,
                                enabled = enabled,
                                onChange = { AppSettings.setEqualizerParametricBand(index, it) },
                                onEdit = { editingBand = index },
                            )
                        }
                        RowDivider()
                        DestructiveRow(
                            label = stringResource(R.string.equalizer_reset),
                            enabled = enabled,
                        ) {
                            haptics.play(Haptic.Select)
                            AppSettings.resetEqualizerParametric()
                        }
                    }
                }
            }

            SettingsGroup(
                header = stringResource(R.string.equalizer_balance),
                footer = stringResource(R.string.equalizer_balance_footer),
            ) {
                BalanceControl(
                    balance = balance,
                    onChange = { AppSettings.setEqualizerBalance(it) },
                    enabled = enabled,
                )
            }

            // A stream that never gets decoded to linear PCM — a passthrough or
            // offload bitstream — reaches the output as the bytes it arrived
            // as, and there is no sample for a filter to touch. Said plainly
            // here rather than left as a dead control, because the alternative
            // is a screen that responds to every touch and changes no sound.
            //
            // This tested `actualEncoding == ENCODING_PCM_FLOAT` for a long
            // while, which was true of the old pipeline and has been wrong
            // since the DSP chain moved upstream into [PrecisionAudioSink].
            // Float output does not affect the equaliser at all; it only drops
            // Media3's *own* processors, which is a claim about silence
            // skipping and belongs on that setting, not this screen.
            if (!outputStatus.dspAvailable) {
                SettingsGroup(footer = stringResource(R.string.equalizer_bitstream_footer)) {
                    SettingsRow(
                        icon = SfIcons.Warning,
                        title = stringResource(R.string.equalizer_bitstream_title),
                        subtitle = stringResource(R.string.equalizer_bitstream_subtitle),
                    )
                }
            }

        }

        // Outside the dimmed block on purpose: the device's own panel works
        // whether or not ours is switched on, and greying out a route to
        // somebody else's equaliser would be claiming ground we do not hold.
        SettingsGroup(footer = stringResource(R.string.system_equalizer_footer)) {
            SettingsRow(
                icon = SfIcons.GraphicEq,
                title = stringResource(R.string.system_equalizer),
                subtitle = stringResource(R.string.system_equalizer_subtitle),
                onClick = { openEqualizer(context, sessionId) },
            )
        }

        Spacer(Modifier.height(32.dp))
    }

    // Only ever opened from a row that exists, and the bank can change under it
    // (a band count chosen by another hand, a settings restore), so the sheet
    // re-reads its band every composition instead of capturing one.
    val editing = editingBand
    if (editing != null && editing < parametric.size) {
        ModalBottomSheet(
            onDismissRequest = { editingBand = null },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            ParametricBandSheet(
                index = editing,
                band = parametric[editing],
                onChange = { AppSettings.setEqualizerParametricBand(editing, it) },
            )
        }
    }
}

// ---- Tone pad --------------------------------------------------------------

/**
 * The dot grid and its puck.
 *
 * Drawn rather than composed: eleven by eleven is 121 dots, and 121 layout
 * nodes re-measured on every frame of a drag is a stutter on the exact gesture
 * that has to feel direct. One [Canvas] redraws them for the cost of the
 * circles themselves.
 *
 * The puck snaps to whole steps as the finger moves, with a tick at each one,
 * and springs between them rather than teleporting — a control that jumps
 * between dots reads as the touch being dropped, and the spring is what says it
 * was caught.
 */
@Composable
private fun TonePad(
    x: Int,
    y: Int,
    enabled: Boolean,
    onChange: (Int, Int) -> Unit,
) {
    val haptics = rememberHaptics()
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val puckColor = MaterialTheme.colorScheme.background
    val shadowColor = Color.Black.copy(alpha = 0.16f)
    val steps = EqLayout.TONE_STEPS

    // The gesture block below is keyed on something that never changes, so it is
    // started once and captures whatever was in scope then. Read through these
    // and a second gesture begins from where the puck actually is, rather than
    // from where it was the first time this screen composed.
    val latestX by rememberUpdatedState(x)
    val latestY by rememberUpdatedState(y)
    val currentOnChange by rememberUpdatedState(onChange)

    // Spring, not tween: the puck has to keep up with a fast drag and still
    // settle rather than arrive and stop dead.
    val animatedX by animateFloatAsState(
        targetValue = x.toFloat(),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh),
        label = "tonePadX",
    )
    val animatedY by animateFloatAsState(
        targetValue = y.toFloat(),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh),
        label = "tonePadY",
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET, vertical = 18.dp)
            .height(PAD_HEIGHT)
            .pointerInput(steps, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    // Claimed on the way down, before the scrolling parent gets
                    // to count this as the start of a fling. Everything in this
                    // screen scrolls; a pad that only took the gesture once it
                    // had moved would lose every vertical drag to the page.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var lastX = latestX
                    var lastY = latestY
                    fun report(offset: Offset) {
                        val (newX, newY) = stepAt(offset, size.toSize(), PUCK_RADIUS.toPx(), steps)
                        if (newX != lastX || newY != lastY) {
                            lastX = newX
                            lastY = newY
                            haptics.play(Haptic.Tick)
                            currentOnChange(newX, newY)
                        }
                    }
                    report(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (change.positionChanged()) {
                            change.consume()
                            report(change.position)
                        }
                    }
                }
            },
    ) {
        val inset = PUCK_RADIUS.toPx()
        val usableWidth = size.width - inset * 2
        val usableHeight = size.height - inset * 2
        val columns = steps * 2
        val dotRadius = DOT_RADIUS.toPx()

        for (column in 0..columns) {
            for (row in 0..columns) {
                // The row and column through dead centre are drawn up, so the
                // origin is findable without a reading — the same job the
                // detent does on a balance slider.
                val onAxis = column == steps || row == steps
                drawCircle(
                    color = dotColor.copy(alpha = if (onAxis) 0.55f else 0.3f),
                    radius = dotRadius,
                    center = Offset(
                        inset + usableWidth * column / columns,
                        inset + usableHeight * row / columns,
                    ),
                )
            }
        }

        val puckCentre = Offset(
            inset + usableWidth * (animatedX + steps) / columns,
            inset + usableHeight * (steps - animatedY) / columns,
        )
        // Three soft rings instead of a blur: a real shadow pass on a view this
        // small costs more than the circles it would be shading.
        for (ring in 3 downTo 1) {
            drawCircle(
                color = shadowColor.copy(alpha = shadowColor.alpha / ring),
                radius = inset + ring * 2f,
                center = puckCentre.copy(y = puckCentre.y + ring),
            )
        }
        drawCircle(color = puckColor, radius = inset, center = puckCentre)
    }
}

/** Which step the finger is over, clamped to the grid. */
private fun stepAt(offset: Offset, size: Size, inset: Float, steps: Int): Pair<Int, Int> {
    val usableWidth = (size.width - inset * 2).coerceAtLeast(1f)
    val usableHeight = (size.height - inset * 2).coerceAtLeast(1f)
    val fractionX = ((offset.x - inset) / usableWidth).coerceIn(0f, 1f)
    val fractionY = ((offset.y - inset) / usableHeight).coerceIn(0f, 1f)
    val x = (fractionX * steps * 2).roundToInt() - steps
    // Screen coordinates grow downwards and the control does not: up is more.
    val y = steps - (fractionY * steps * 2).roundToInt()
    return x to y
}

/** The two numbers under the pad: how far it is tilted, and how far contoured. */
@Composable
private fun ToneReadout(x: Int, y: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = SfIcons.Waves,
            contentDescription = stringResource(R.string.equalizer_tilt),
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = signed(x),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(22.dp))
        Icon(
            imageVector = SfIcons.ShowChart,
            contentDescription = stringResource(R.string.equalizer_contour),
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = signed(y),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Zero has no sign; anything else wears one, so the direction reads at a glance. */
private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

/** A row that is one of a set, ticked when it is the one in force. */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                if (!selected) {
                    haptics.play(Haptic.Select)
                    onClick()
                }
            }
            .padding(horizontal = ROW_INSET, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = SfIcons.Check,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ---- Manual bands ----------------------------------------------------------

/**
 * Seven vertical faders, drawn.
 *
 * Material's `Slider` rotated a quarter turn keeps its horizontal touch
 * geometry and its horizontal thumb, which is why every Compose equaliser built
 * that way feels wrong under the finger. These are a track, a fill and a knob
 * on a canvas, taking the gesture on first touch the way [TonePad] does.
 *
 * The fill runs from the centre rather than the bottom because the value it is
 * showing is signed: a band at −6 dB and a band at +6 dB are equal and opposite,
 * and a bar growing from the floor would draw them as a short one and a tall
 * one.
 */
@Composable
private fun BandSliders(
    bands: List<Float>,
    enabled: Boolean,
    onChange: (List<Float>) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET - 4.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        EqLayout.MANUAL_BANDS_HZ.forEachIndexed { index, hz ->
            val value = bands.getOrElse(index) { 0f }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = formatGain(value),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (abs(value) < 0.05f) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                BandFader(
                    enabled = enabled,
                    value = value,
                    onChange = { updated ->
                        onChange(bands.toMutableList().also { it[index] = updated })
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatFrequency(hz),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BandFader(
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val haptics = rememberHaptics()
    val trackColor = MaterialTheme.colorScheme.outline
    val fillColor = MaterialTheme.colorScheme.primary
    val knobColor = MaterialTheme.colorScheme.background
    val range = EqLayout.MANUAL_RANGE_DB
    // See [TonePad]: the gesture block outlives the composition that made it.
    // [onChange] too, and that one matters most: the caller's lambda closes
    // over the whole band list, so a stale copy writes every *other* band
    // back to whatever it was when this fader first composed.
    val latest by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)

    Canvas(
        modifier = Modifier
            .width(FADER_WIDTH)
            .height(FADER_HEIGHT)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var last = latest
                    fun report(offset: Offset) {
                        val inset = KNOB_RADIUS.toPx()
                        val usable = (size.height - inset * 2).coerceAtLeast(1f)
                        val fraction = ((offset.y - inset) / usable).coerceIn(0f, 1f)
                        val raw = (1f - fraction * 2f) * range
                        val snapped = snapGain(raw, range)
                        if (abs(snapped - last) > 0.001f) {
                            // A tick per step, and the step at zero is the one
                            // people are aiming for when they undo a band.
                            haptics.play(Haptic.Tick)
                            last = snapped
                            currentOnChange(snapped)
                        }
                    }
                    report(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (change.positionChanged()) {
                            change.consume()
                            report(change.position)
                        }
                    }
                }
            },
    ) {
        val inset = KNOB_RADIUS.toPx()
        val usable = size.height - inset * 2
        val centreY = inset + usable / 2
        val trackWidth = TRACK_WIDTH.toPx()
        val centreX = size.width / 2
        val knobY = inset + usable * (1f - (value / range + 1f) / 2f)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(centreX - trackWidth / 2, inset),
            size = Size(trackWidth, usable),
            cornerRadius = CornerRadius(trackWidth / 2),
        )
        if (abs(value) > 0.05f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(centreX - trackWidth / 2, minOf(centreY, knobY)),
                size = Size(trackWidth, abs(centreY - knobY)),
                cornerRadius = CornerRadius(trackWidth / 2),
            )
        }
        // The zero line, drawn over the track so it survives a full-scale fill.
        drawLine(
            color = trackColor.copy(alpha = 0.9f),
            start = Offset(centreX - trackWidth, centreY),
            end = Offset(centreX + trackWidth, centreY),
            strokeWidth = 1.dp.toPx(),
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.18f),
            radius = inset + 1.5f,
            center = Offset(centreX, knobY + 1.5f),
        )
        drawCircle(color = knobColor, radius = inset, center = Offset(centreX, knobY))
    }
}

/**
 * Half-decibel steps, with a wider one at zero.
 *
 * Flat is the value a band gets dragged back to far more often than any other,
 * and it is the one value a finger cannot hit reliably on a 150 dp travel — so
 * it is given a detent to fall into rather than a coordinate to find.
 */
private fun snapGain(raw: Float, range: Float): Float {
    if (abs(raw) < ZERO_DETENT_DB) return 0f
    return ((raw * 2f).roundToInt() / 2f).coerceIn(-range, range)
}

private fun formatGain(value: Float): String = when {
    abs(value) < 0.05f -> "0"
    value > 0 -> "+" + trimGain(value)
    else -> trimGain(value)
}

/** "+6" rather than "+6.0", but "+6.5" when the half matters. */
private fun trimGain(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.05f) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", value)
    }

/** "60", "2.5k", "14k" — the labels a hardware equaliser prints. */
private fun formatFrequency(hz: Float): String = when {
    hz < 1_000f -> hz.roundToInt().toString()
    hz % 1_000f == 0f -> "${(hz / 1_000f).roundToInt()}k"
    else -> String.format(Locale.getDefault(), "%.1fk", hz / 1_000f)
}

// ---- Parametric bank -------------------------------------------------------

/**
 * One band of the parametric bank: where it sits, how much it lifts or cuts,
 * and the gain's slider underneath.
 *
 * Gain gets the permanent control because it is what a band is for. Centre and
 * width live in [ParametricBandSheet]: they are chosen once and then left
 * alone, and nineteen rows carrying three live sliders each would be a wall of
 * tracks where a list of bands belongs.
 */
@Composable
private fun ParametricBandRow(
    index: Int,
    band: ParametricBand,
    enabled: Boolean,
    onChange: (ParametricBand) -> Unit,
    onEdit: () -> Unit,
) {
    val haptics = rememberHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) {
                    haptics.play(Haptic.Select)
                    onEdit()
                }
                .padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.equalizer_band, index + 1),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(
                    R.string.equalizer_band_shape,
                    formatFrequency(band.frequencyHz),
                    formatQ(band.q),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatGain(band.gainDb),
                style = MaterialTheme.typography.bodyLarge,
                color = if (abs(band.gainDb) < 0.05f) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Slider(
            value = band.gainDb,
            onValueChange = { onChange(band.withGain(snapGain(it, ParametricLayout.RANGE_DB))) },
            valueRange = -ParametricLayout.RANGE_DB..ParametricLayout.RANGE_DB,
            // Forty-eight decibels of travel in half-decibel steps.
            steps = 95,
            enabled = enabled,
        )
    }
}

/**
 * The band's filter shape, frequency, Q and gain. Sliders remain available for
 * touch; the numeric fields also accept direct keypad input.
 */
@Composable
private fun ParametricBandSheet(
    index: Int,
    band: ParametricBand,
    onChange: (ParametricBand) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.equalizer_band, index + 1),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
            Text(
                text = "Filter type",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterKind.entries.forEach { kind ->
                    FilterChip(
                        selected = band.kind == kind,
                        onClick = { onChange(band.withKind(kind)) },
                        label = { Text(kind.parametricLabel()) },
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ParametricNumberField(
                    label = "Hz",
                    unit = "Hz",
                    value = band.frequencyHz,
                    keyboardType = KeyboardType.Number,
                    min = ParametricLayout.MIN_HZ,
                    max = ParametricLayout.MAX_HZ,
                    modifier = Modifier.weight(1f),
                    onValue = { onChange(band.withFrequency(it)) },
                )
                ParametricNumberField(
                    label = "Q",
                    unit = "",
                    value = band.q,
                    keyboardType = KeyboardType.Decimal,
                    min = ParametricLayout.MIN_Q,
                    max = ParametricLayout.MAX_Q,
                    modifier = Modifier.weight(1f),
                    onValue = { onChange(band.withQ(it)) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ParametricNumberField(
                    label = "Gain",
                    unit = "dB",
                    value = abs(band.gainDb),
                    keyboardType = KeyboardType.Decimal,
                    min = 0f,
                    max = ParametricLayout.RANGE_DB,
                    modifier = Modifier.weight(1f),
                    onValue = {
                        val signed = if (band.gainDb < 0f) -it else it
                        onChange(band.withGain(signed))
                    },
                )
                FilterChip(
                    selected = band.gainDb >= 0f,
                    onClick = { onChange(band.withGain(abs(band.gainDb))) },
                    label = { Text("+") },
                )
                FilterChip(
                    selected = band.gainDb < 0f,
                    onClick = { onChange(band.withGain(-abs(band.gainDb))) },
                    label = { Text("−") },
                )
            }
            SliderLabel(
                label = stringResource(R.string.equalizer_band_frequency),
                value = formatFrequency(band.frequencyHz) + " Hz",
            )
            Slider(
                value = frequencyPosition(band.frequencyHz),
                onValueChange = { onChange(band.withFrequency(snapFrequency(frequencyAt(it)))) },
                valueRange = 0f..1f,
            )
            SliderLabel(
                label = stringResource(R.string.equalizer_band_q),
                value = formatQ(band.q),
            )
            Slider(
                value = qPosition(band.q),
                onValueChange = { onChange(band.withQ(snapQ(qAt(it)))) },
                valueRange = 0f..1f,
            )
            Text(
                text = stringResource(R.string.equalizer_band_footer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            )
        }
    }
}

/** Numeric entry for a band parameter; values update from the keypad as well as the sliders. */
@Composable
private fun ParametricNumberField(
    label: String,
    unit: String,
    value: Float,
    keyboardType: KeyboardType,
    min: Float,
    max: Float,
    modifier: Modifier,
    onValue: (Float) -> Unit,
) {
    var text by remember(label) { mutableStateOf(formatEntry(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() || it == '-' || it == '.' || it == ',' }
            text = filtered
            filtered.replace(',', '.').toFloatOrNull()?.let { parsed ->
                if (parsed.isFinite() && parsed in min..max) onValue(parsed)
            }
        },
        modifier = modifier,
        label = { Text(label) },
        suffix = if (unit.isNotEmpty()) ({ Text(unit) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

private fun formatEntry(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.01f) value.roundToInt().toString()
    else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

private fun FilterKind.parametricLabel(): String = when (this) {
    FilterKind.BELL -> "PK"
    FilterKind.LOW_SHELF -> "LSQ"
    FilterKind.HIGH_SHELF -> "HSQ"
}

@Composable
private fun SliderLabel(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 0 at 20 Hz and 1 at 20 kHz, so the thumb moves evenly by ear. */
private fun frequencyPosition(hz: Float): Float {
    val clamped = hz.coerceIn(ParametricLayout.MIN_HZ, ParametricLayout.MAX_HZ)
    return (ln(clamped / ParametricLayout.MIN_HZ) / ln(ParametricLayout.MAX_HZ / ParametricLayout.MIN_HZ))
        .coerceIn(0f, 1f)
}

private fun frequencyAt(position: Float): Float =
    ParametricLayout.MIN_HZ *
        (ParametricLayout.MAX_HZ / ParametricLayout.MIN_HZ).pow(position.coerceIn(0f, 1f))

/**
 * Rounds to something a person would say: whole hertz below 100, then 5 Hz,
 * 50 Hz and 100 Hz steps. A centre reported as "3473 Hz" reads as noise.
 */
private fun snapFrequency(hz: Float): Float = when {
    hz < 100f -> hz.roundToInt().toFloat()
    hz < 1_000f -> (hz / 5f).roundToInt() * 5f
    hz < 10_000f -> (hz / 50f).roundToInt() * 50f
    else -> (hz / 100f).roundToInt() * 100f
}.coerceIn(ParametricLayout.MIN_HZ, ParametricLayout.MAX_HZ)

private fun qPosition(q: Float): Float {
    val clamped = q.coerceIn(ParametricLayout.MIN_Q, ParametricLayout.MAX_Q)
    return (ln(clamped / ParametricLayout.MIN_Q) / ln(ParametricLayout.MAX_Q / ParametricLayout.MIN_Q))
        .coerceIn(0f, 1f)
}

private fun qAt(position: Float): Float =
    ParametricLayout.MIN_Q *
        (ParametricLayout.MAX_Q / ParametricLayout.MIN_Q).pow(position.coerceIn(0f, 1f))

/** Two decimals is how Q is printed everywhere else in audio. */
private fun snapQ(q: Float): Float =
    ((q * 100f).roundToInt() / 100f).coerceIn(ParametricLayout.MIN_Q, ParametricLayout.MAX_Q)

private fun formatQ(value: Float): String = String.format(Locale.getDefault(), "%.2f", value)

// ---- Balance ---------------------------------------------------------------

/**
 * L and R with the trim between them, and a detent at centre for the same
 * reason [snapGain] has one: dead centre is where this spends its life, and it
 * is the one position a drag cannot land on by aim alone.
 */
@Composable
private fun BalanceControl(
    balance: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val haptics = rememberHaptics()
    val trackColor = MaterialTheme.colorScheme.outline
    val fillColor = MaterialTheme.colorScheme.primary
    val knobColor = MaterialTheme.colorScheme.background
    // See [TonePad]: the gesture block outlives the composition that made it.
    val latest by rememberUpdatedState(balance)
    val currentOnChange by rememberUpdatedState(onChange)

    Column(Modifier.padding(horizontal = ROW_INSET, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.equalizer_balance_left),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = String.format(Locale.getDefault(), "%.2f", balance),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.equalizer_balance_right),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(KNOB_RADIUS * 2 + 8.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var last = latest
                        fun report(offset: Offset) {
                            val inset = KNOB_RADIUS.toPx()
                            val usable = (size.width - inset * 2).coerceAtLeast(1f)
                            val fraction = ((offset.x - inset) / usable).coerceIn(0f, 1f)
                            val raw = fraction * 2f - 1f
                            val snapped = if (abs(raw) < BALANCE_DETENT) 0f else raw
                            if (abs(snapped - last) > 0.004f) {
                                if (snapped == 0f && last != 0f) haptics.play(Haptic.Tick)
                                last = snapped
                                currentOnChange(snapped)
                            }
                        }
                        report(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.positionChanged()) {
                                change.consume()
                                report(change.position)
                            }
                        }
                    }
                },
        ) {
            val inset = KNOB_RADIUS.toPx()
            val usable = size.width - inset * 2
            val centreY = size.height / 2
            val centreX = inset + usable / 2
            val trackHeight = TRACK_WIDTH.toPx()
            val knobX = inset + usable * (balance + 1f) / 2f

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(inset, centreY - trackHeight / 2),
                size = Size(usable, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2),
            )
            if (abs(balance) > 0.004f) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(minOf(centreX, knobX), centreY - trackHeight / 2),
                    size = Size(abs(centreX - knobX), trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2),
                )
            }
            drawCircle(
                color = Color.Black.copy(alpha = 0.18f),
                radius = inset + 1.5f,
                center = Offset(knobX, centreY + 1.5f),
            )
            drawCircle(color = knobColor, radius = inset, center = Offset(knobX, centreY))
        }
    }
}

// ---- Presets ---------------------------------------------------------------

/**
 * The preset curves as a page of their own, opened from the Preset row. A pick
 * applies at once and [onChosen] then leaves the page.
 */
@Composable
fun EqualizerPresetPage(
    onChosen: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val selected by AppSettings.equalizerPreset.collectAsStateWithLifecycle()
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.equalizer_preset))
        ChoiceGroup(
            // Custom is a state the bands can be in, not a curve anyone can
            // choose, so it is only ever listed when it is the one in force.
            options = EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM || selected == EqualizerPreset.CUSTOM },
            selected = selected,
            label = { it.localizedLabel() },
            footer = null,
            enabled = { it != EqualizerPreset.CUSTOM },
        ) { preset ->
            AppSettings.setEqualizerPreset(preset)
            onChosen()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun EqualizerMode.localizedLabel(): String = stringResource(
    when (this) {
        EqualizerMode.DYNAMIC -> R.string.equalizer_dynamic
        EqualizerMode.MANUAL -> R.string.equalizer_manual
        EqualizerMode.PARAMETRIC -> R.string.equalizer_parametric
    },
)

@Composable
private fun EqualizerPreset.localizedLabel(): String = stringResource(
    when (this) {
        EqualizerPreset.FLAT -> R.string.eq_preset_flat
        EqualizerPreset.ACOUSTIC -> R.string.eq_preset_acoustic
        EqualizerPreset.BASS_BOOST -> R.string.eq_preset_bass_boost
        EqualizerPreset.BASS_CUT -> R.string.eq_preset_bass_cut
        EqualizerPreset.VOCAL -> R.string.eq_preset_vocal
        EqualizerPreset.TREBLE_BOOST -> R.string.eq_preset_treble_boost
        EqualizerPreset.TREBLE_CUT -> R.string.eq_preset_treble_cut
        EqualizerPreset.LOUDNESS -> R.string.eq_preset_loudness
        EqualizerPreset.SPOKEN_WORD -> R.string.eq_preset_spoken_word
        EqualizerPreset.ELECTRONIC -> R.string.eq_preset_electronic
        EqualizerPreset.ROCK -> R.string.eq_preset_rock
        EqualizerPreset.HIP_HOP -> R.string.eq_preset_hip_hop
        EqualizerPreset.JAZZ -> R.string.eq_preset_jazz
        EqualizerPreset.CLASSICAL -> R.string.eq_preset_classical
        EqualizerPreset.SMALL_SPEAKERS -> R.string.eq_preset_small_speakers
        EqualizerPreset.LATE_NIGHT -> R.string.eq_preset_late_night
        EqualizerPreset.CUSTOM -> R.string.eq_preset_custom
    },
)

private val PAD_HEIGHT = 230.dp
private val PUCK_RADIUS = 19.dp
private val DOT_RADIUS = 2.5.dp
private val FADER_HEIGHT = 150.dp
private val FADER_WIDTH = 34.dp
private val TRACK_WIDTH = 5.dp
private val KNOB_RADIUS = 10.dp

/** How close to flat a band has to be dragged before it snaps there. */
private const val ZERO_DETENT_DB = 0.6f

/** Same, for the balance trim's own centre. */
private const val BALANCE_DETENT = 0.04f
