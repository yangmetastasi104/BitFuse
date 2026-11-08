package com.music.bitchord.playback

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The shape of one filter in [EqLayout].
 *
 * Shelves rather than bells at the two ends of the manual tab: a bell centred
 * at 60 Hz leaves 40 Hz almost untouched, so "turn the bass up" doesn't turn up
 * the part of the bass people mean. A shelf lifts everything below its corner,
 * which is what the bottom slider is asked for, and the same argument upside
 * down applies to 14 kHz.
 */
enum class FilterKind { BELL, LOW_SHELF, HIGH_SHELF }

/** A slot's fixed identity: its shape and where on the spectrum it sits. */
class FilterSlot(val kind: FilterKind, val frequencyHz: Float)

/**
 * One user-placed parametric band: where it sits, how wide it is, and how loud.
 *
 * Everything a graphical equaliser fixes is a number here instead - that is the
 * whole difference between the two. [enabled] exists so a band can be parked
 * without losing the frequency and Q it was tuned to.
 */
class ParametricBand(
    val frequencyHz: Float,
    val q: Float,
    val gainDb: Float,
    val enabled: Boolean = true,
) {
    fun withFrequency(hz: Float): ParametricBand =
        ParametricBand(
            frequencyHz = hz.coerceIn(ParametricLayout.MIN_HZ, ParametricLayout.MAX_HZ),
            q = q,
            gainDb = gainDb,
            enabled = enabled,
        )

    fun withQ(value: Float): ParametricBand =
        ParametricBand(
            frequencyHz = frequencyHz,
            q = value.coerceIn(ParametricLayout.MIN_Q, ParametricLayout.MAX_Q),
            gainDb = gainDb,
            enabled = enabled,
        )

    fun withGain(value: Float): ParametricBand =
        ParametricBand(
            frequencyHz = frequencyHz,
            q = q,
            gainDb = value.coerceIn(-ParametricLayout.RANGE_DB, ParametricLayout.RANGE_DB),
            enabled = enabled,
        )

    fun withEnabled(value: Boolean): ParametricBand =
        ParametricBand(frequencyHz = frequencyHz, q = q, gainDb = gainDb, enabled = value)
}

/**
 * The parametric bank: how many bands it can hold, what the two counts start
 * from, and the limits its controls may be dragged to.
 *
 * The defaults are a place to start, not a voicing: every gain begins at 0 dB,
 * so switching to the parametric tab changes nothing until a band is moved.
 */
object ParametricLayout {

    /** The most bands the bank can hold, and the length of the 19-band set. */
    const val MAX_BANDS = 19

    /** The other count the tab offers. */
    const val SMALL_BANDS = 10

    /** How far a band travels either way. */
    const val RANGE_DB = 12f

    const val MIN_Q = 0.2f
    const val MAX_Q = 12f

    const val MIN_HZ = 20f
    const val MAX_HZ = 20_000f

    /**
     * Third-octave centres, the set every hardware equaliser shares.
     *
     * A Q of 1.41 is exactly one octave wide, and these centres are one octave
     * apart, so neighbouring bands meet at their -3 dB points instead of
     * leaving scallops between them.
     */
    val BANDS_10_HZ = floatArrayOf(
        31.5f, 63f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f,
    )

    const val Q_10 = 1.41f

    /**
     * Nineteen centres, log-spaced from 20 Hz to 19.6 kHz (about two thirds of
     * an octave apart). A Q of 2.0 makes each one a little narrower than its
     * spacing, which is what gives the fine shaping the count is for.
     */
    val BANDS_19_HZ = floatArrayOf(
        20f, 29f, 43f, 63f, 92f, 135f, 198f, 290f, 425f, 620f,
        910f, 1_350f, 1_970f, 2_900f, 4_250f, 6_200f, 9_100f, 13_400f, 19_600f,
    )

    const val Q_19 = 2.0f

    /** The frequencies a fresh [count]-band bank starts from. */
    fun defaultFrequencies(count: Int): FloatArray =
        if (count >= MAX_BANDS) BANDS_19_HZ else BANDS_10_HZ

    /** The Q a fresh [count]-band bank gives every band. */
    fun defaultQ(count: Int): Float =
        if (count >= MAX_BANDS) Q_19 else Q_10

    /** A flat bank of [count] bands on the default centres. */
    fun defaults(count: Int): List<ParametricBand> {
        val q = defaultQ(count)
        return defaultFrequencies(count).map { ParametricBand(it, q, 0f) }
    }

    /** The only two counts a bank may have: anything below [MAX_BANDS] is [SMALL_BANDS]. */
    fun sanitizeCount(count: Int): Int = if (count >= MAX_BANDS) MAX_BANDS else SMALL_BANDS
}

/**
 * Every filter either tab can ask for, in one fixed list.
 *
 * Both tabs drive the *same* ten slots, and a slot the current tab doesn't use
 * is held at 0 dB rather than removed. That costs nothing - a section at 0 dB
 * is skipped outright by [EqualizerProcessor] - and it buys the thing that
 * matters: the set of running filters never changes shape, so switching tab or
 * preset is a gain change like any other and glides silently. Rebuilding the
 * cascade instead would drop filter state on the floor mid-signal, which is a
 * click every time.
 *
 * The parametric bank sits after them in the same list ([ALL_SLOTS]): same
 * reasoning, one more reason. A band's *centre* is a number the user owns, so
 * those slots take their frequency from the curve rather than from here - but
 * they still exist all the time, flat when the bank is short, so a band dragged
 * across the spectrum never rebuilds the cascade either.
 */
object EqLayout {

    /** The seven centres the manual tab puts a slider under. */
    val MANUAL_BANDS_HZ = floatArrayOf(60f, 150f, 400f, 1_000f, 2_500f, 6_000f, 14_000f)

    /**
     * Wide enough to overlap its neighbours - the centres are about 1.3 octaves
     * apart, so a Q near 1 means pushing every slider up lifts the whole band
     * evenly instead of leaving scallops between them.
     */
    const val MANUAL_Q = 1.0f

    /** Slots 0..6: the manual tab. */
    const val MANUAL_FIRST = 0
    const val MANUAL_COUNT = 7

    /** Slots 7..9: the tone pad's tilt pair and its mid contour. */
    const val TONE_LOW = 7
    const val TONE_MID = 8
    const val TONE_HIGH = 9

    const val SLOTS = 10

    /** The ten fixed slots, then the parametric bank. */
    const val ALL_SLOTS = SLOTS + ParametricLayout.MAX_BANDS

    val slots: List<FilterSlot> = buildList {
        MANUAL_BANDS_HZ.forEachIndexed { index, hz ->
            val kind = when (index) {
                0 -> FilterKind.LOW_SHELF
                MANUAL_BANDS_HZ.lastIndex -> FilterKind.HIGH_SHELF
                else -> FilterKind.BELL
            }
            add(FilterSlot(kind, hz))
        }
        add(FilterSlot(FilterKind.LOW_SHELF, 250f))
        add(FilterSlot(FilterKind.BELL, 1_000f))
        add(FilterSlot(FilterKind.HIGH_SHELF, 4_000f))
    }

    /** How far a manual slider travels either way. */
    const val MANUAL_RANGE_DB = 12f

    /** How far the tone pad travels on each axis, in whole steps. */
    const val TONE_STEPS = 5

    /** Decibels per step of the pad, so a corner is ±6 dB. */
    const val TONE_DB_PER_STEP = 1.2f

    /** Frequency of the tone pad's tilt pair, for the UI to describe. */
    val TONE_TILT_HZ = floatArrayOf(250f, 4_000f)

    /** Whether [slot] is one of the ten fixed ones rather than a parametric band. */
    fun isFixed(slot: Int): Boolean = slot < SLOTS

    /** The shape of [slot]: fixed slots as laid out here, parametric ones always bells. */
    fun kindOf(slot: Int): FilterKind =
        if (isFixed(slot)) slots[slot].kind else FilterKind.BELL
}

/**
 * A tuning, rendered: what every slot in [EqLayout] is set to right now.
 *
 * Gains, Qs and (for the parametric bank) centres, because all three are the
 * user's to move. The constructor pads whatever it is handed to
 * [EqLayout.ALL_SLOTS] and then folds [parametric] into the slots that follow
 * the fixed ten, so a caller can keep thinking in terms of either the old
 * ten-slot curve or the band list and the processor reads one array either way.
 */
class EqCurve(
    initialGains: FloatArray,
    initialQs: FloatArray,
    /**
     * Make-up attenuation, always at or below zero: half of however far the
     * curve's loudest point was pushed up.
     *
     * Half rather than all of it. Taking the whole boost back assumes the
     * music has full-scale content sitting exactly on the boosted frequency,
     * which almost nothing does, and the price of that assumption was every
     * boost reading as "the track got quieter" — a +6 dB bass lift left the
     * bass where it was and turned everything else down 6 dB. The overs the
     * remaining half lets through are rare and brief, and [PeakLimiter] takes
     * them instead.
     */
    val preampDb: Float,
    /** The parametric bank this curve was built from, in band order. */
    val parametric: List<ParametricBand> = emptyList(),
) {
    /** One gain per slot, padded to [EqLayout.ALL_SLOTS]. */
    val gainsDb = FloatArray(EqLayout.ALL_SLOTS) { initialGains.getOrElse(it) { 0f } }

    val qs = FloatArray(EqLayout.ALL_SLOTS) { initialQs.getOrElse(it) { 0.707f } }

    /**
     * Where each slot sits. The ten fixed slots copy [EqLayout]'s centres; the
     * bank after them takes each band's own, and stays at 1 kHz for bands a
     * short bank doesn't have.
     */
    val freqsHz = FloatArray(EqLayout.ALL_SLOTS) { slot ->
        if (EqLayout.isFixed(slot)) EqLayout.slots[slot].frequencyHz else 1_000f
    }

    init {
        parametric.forEachIndexed { index, band ->
            val slot = EqLayout.SLOTS + index
            if (slot >= EqLayout.ALL_SLOTS) return@forEachIndexed
            freqsHz[slot] = band.frequencyHz.coerceIn(ParametricLayout.MIN_HZ, ParametricLayout.MAX_HZ)
            qs[slot] = band.q.coerceIn(ParametricLayout.MIN_Q, ParametricLayout.MAX_Q)
            gainsDb[slot] = if (band.enabled) {
                band.gainDb.coerceIn(-ParametricLayout.RANGE_DB, ParametricLayout.RANGE_DB)
            } else {
                0f
            }
        }
    }

    companion object {
        val FLAT = of(FloatArray(EqLayout.SLOTS), FloatArray(EqLayout.SLOTS) { 0.707f })

        /** Builds a curve and works out the attenuation it needs. */
        fun of(
            gainsDb: FloatArray,
            qs: FloatArray,
            parametric: List<ParametricBand> = emptyList(),
        ): EqCurve {
            val flat = EqCurve(gainsDb, qs, 0f, parametric)
            return EqCurve(flat.gainsDb, flat.qs, preampFor(flat), parametric)
        }
    }
}

/** The seven sliders, as a curve. Slots the tone pad owns stay flat. */
fun manualCurve(bandsDb: List<Float>): EqCurve {
    val gains = FloatArray(EqLayout.SLOTS)
    val qs = FloatArray(EqLayout.SLOTS) { 0.707f }
    for (band in 0 until EqLayout.MANUAL_COUNT) {
        gains[EqLayout.MANUAL_FIRST + band] =
            bandsDb.getOrElse(band) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        qs[EqLayout.MANUAL_FIRST + band] = EqLayout.MANUAL_Q
    }
    return EqCurve.of(gains, qs)
}

/**
 * The parametric bank, as a curve. The ten fixed slots stay flat: this tab is
 * its own way of describing the sound, like the other two.
 */
fun parametricCurve(bands: List<ParametricBand>): EqCurve =
    EqCurve.of(
        FloatArray(EqLayout.SLOTS),
        FloatArray(EqLayout.SLOTS) { 0.707f },
        bands.take(ParametricLayout.MAX_BANDS),
    )

/**
 * The tone pad, as a curve. Slots the manual tab owns stay flat.
 *
 * [x] tilts: left is warm (low shelf up, high shelf down), right is bright, and
 * the two move by the same amount in opposite directions so the middle of the
 * spectrum - where most of a mix lives - keeps its level whichever way the puck
 * goes. [y] is the contour: down scoops the mids into a V, up pushes them
 * forward.
 *
 * [focused] narrows all three. Apple describes the two settings as wider ranges
 * with a richer sound against specific ranges with less distraction, which is a
 * description of bandwidth and nothing else, so that is the only thing it
 * changes.
 */
fun toneCurve(x: Int, y: Int, focused: Boolean): EqCurve {
    val gains = FloatArray(EqLayout.SLOTS)
    val qs = FloatArray(EqLayout.SLOTS) { 0.707f }
    val steps = EqLayout.TONE_STEPS
    val tilt = x.coerceIn(-steps, steps) * EqLayout.TONE_DB_PER_STEP
    val contour = y.coerceIn(-steps, steps) * EqLayout.TONE_DB_PER_STEP

    gains[EqLayout.TONE_LOW] = -tilt
    gains[EqLayout.TONE_HIGH] = tilt
    gains[EqLayout.TONE_MID] = contour

    val shelfQ = if (focused) 0.9f else 0.5f
    val bellQ = if (focused) 2.2f else 0.7f
    qs[EqLayout.TONE_LOW] = shelfQ
    qs[EqLayout.TONE_HIGH] = shelfQ
    qs[EqLayout.TONE_MID] = bellQ
    return EqCurve.of(gains, qs)
}

/**
 * How far down the curve is pulled: [MAKEUP_FRACTION] of its peak boost.
 *
 * Taking the largest single band gain would under-read it: the bands overlap,
 * so two neighbours at +6 dB are worth more than +6 dB between them. This walks
 * the summed response instead - magnitudes multiply through a cascade, so
 * decibels add - and takes its actual peak. Runs on whoever changed the
 * setting, never on the audio thread.
 */
private fun preampFor(curve: EqCurve): Float {
    var peak = 0f
    for (point in 0 until RESPONSE_POINTS) {
        val hz = responseFrequency(point)
        var sum = 0f
        for (slot in 0 until EqLayout.ALL_SLOTS) {
            sum += sectionGainDb(
                kind = EqLayout.kindOf(slot),
                frequencyHz = curve.freqsHz[slot],
                gainDb = curve.gainsDb[slot],
                q = curve.qs[slot],
                hz = hz,
            )
        }
        if (sum > peak) peak = sum
    }
    return -peak * MAKEUP_FRACTION
}

/** Log-spaced across the audible band: 20 Hz to 20 kHz. */
private fun responseFrequency(point: Int): Float {
    val fraction = point.toDouble() / (RESPONSE_POINTS - 1)
    return (20.0 * (1_000.0).pow(fraction)).toFloat()
}

/**
 * One section's contribution at [hz], in decibels.
 *
 * The analog prototypes these are the magnitudes of are exactly what
 * [EqualizerProcessor]'s state-variable mixes realise - the bilinear transform
 * only bends the frequency axis near Nyquist, which is nowhere near where a
 * peak worth attenuating for lands.
 */
internal fun sectionGainDb(slot: FilterSlot, gainDb: Float, q: Float, hz: Float): Float =
    sectionGainDb(slot.kind, slot.frequencyHz, gainDb, q, hz)

internal fun sectionGainDb(
    kind: FilterKind,
    frequencyHz: Float,
    gainDb: Float,
    q: Float,
    hz: Float,
): Float {
    if (abs(gainDb) < 0.01f) return 0f
    val a = 10.0.pow(gainDb / 40.0)
    val a2 = a * a
    val x = (hz / frequencyHz).toDouble()
    val x2 = x * x
    val qq = (q * q).toDouble()
    val magnitude = when (kind) {
        FilterKind.BELL -> {
            val flat = (1 - x2) * (1 - x2)
            sqrt((flat + x2 * a2 / qq) / (flat + x2 / (a2 * qq)))
        }
        FilterKind.LOW_SHELF -> {
            val common = x2 * a / qq
            a * sqrt(((a - x2) * (a - x2) + common) / ((1 - a * x2) * (1 - a * x2) + common))
        }
        FilterKind.HIGH_SHELF -> {
            val common = x2 * a / qq
            a * sqrt(((1 - a * x2) * (1 - a * x2) + common) / ((a - x2) * (a - x2) + common))
        }
    }
    return (20.0 * log10(magnitude)).toFloat()
}

/** How much of a curve's peak boost [preampFor] takes back; [PeakLimiter] covers the rest. */
private const val MAKEUP_FRACTION = 0.5f

/** Enough to find the peak of a curve this smooth without wasting time on it. */
private const val RESPONSE_POINTS = 96
