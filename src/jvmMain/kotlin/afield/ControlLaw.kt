package afield

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * How a control's *travel* maps onto its value.
 *
 * A slider has two numbers in it: where the handle is (a fraction of the track, `0.0..1.0`) and what
 * the parameter reads. A law is the function between them. Field's sliders were linear-only, which is
 * fine for a mix level and unusable for anything a human hears or sees logarithmically — on a linear
 * 20 Hz–20 kHz control the entire bottom two octaves live in the first 1% of the track, so the first
 * pixel of travel is a 200 Hz jump and there is no resolution to add that fixes it. Faust's
 * `[scale:log]` metadata made the gap visible; the gap is Field's.
 *
 * ```kotlin
 * controlGroup("filter") {
 *     slider("cutoff", min = 20.0, max = 20000.0, initialValue = 1000.0, law = ControlLaw.LOGARITHMIC) {
 *         cutoff = it
 *     }
 * }
 * ```
 *
 * The same law drives the initial HTML ([ControlLawSpec.valueAt] is used from `buildSliderHtml`), the
 * drag in `src/commonMain/resources/controlLaws.js`, and the tablet surface in `controllers.js` — one
 * rule, three renderers.
 *
 * **Two of those three are held together by a test; the third is not.** `ControlSurfaceProbeTest`
 * stages `controlLaws.js` and `sliders.js` verbatim off the classpath and drives them under node
 * against markup built by the real [ControlGroupBuilder], so the box output area's arithmetic is
 * pinned to the Kotlin's. The tablet surface is **not covered**: nothing executes `controllers.js`,
 * and the probe reads its law spec from the `data-law-*` attributes only, never from the JSON path
 * that the controller websocket actually serves. Adding `controllers.js` to that probe is the cheap
 * next step — the harness exists, it just loads the other two files.
 */
enum class ControlLaw(
    /** The spelling that travels to the browser in [ControllerControl.law]. */
    val wireName: String
) {
    /** Travel is proportional to value. The historical behaviour, and still the default. */
    LINEAR("linear"),

    /**
     * Equal travel is equal *ratio*: an octave, a decade, a doubling. Frequencies, times, and
     * anything measured in "times more" belong here.
     *
     * Requires a strictly positive minimum — see [ControlLawSpec.validate] for why that is an error
     * rather than a clamp.
     */
    LOGARITHMIC("log"),

    /**
     * A power taper: `min + (max - min) * t^exponent`. Spends more of the track near the minimum as
     * the exponent grows. Unlike [LOGARITHMIC] it is happy with a zero or negative minimum, which
     * makes it the answer for a `0.0..1.0` amplitude or a `0..10 s` delay time where the interesting
     * part is the short end.
     */
    EXPONENTIAL("exp"),

    /**
     * Two tapers back to back about a centre, so the centre sits *exactly* at mid-travel and fine
     * control is available on both sides of it. Pan, detune, bipolar offsets, anything where "zero"
     * is a place you need to be able to land on.
     */
    BIPOLAR("bipolar");

    companion object {
        /** Reads a [wireName] back, tolerantly; anything unrecognised is [LINEAR]. */
        fun fromWireName(name: String?): ControlLaw =
            entries.firstOrNull { it.wireName == name?.trim()?.lowercase() } ?: LINEAR
    }
}

/**
 * A law together with the range it applies to — everything needed to map travel to value and back.
 *
 * @param law the mapping in use
 * @param min value at zero travel
 * @param max value at full travel
 * @param exponent taper strength for [ControlLaw.EXPONENTIAL] and [ControlLaw.BIPOLAR]; must be positive
 * @param center the value at mid-travel for [ControlLaw.BIPOLAR]; ignored by the other laws
 */
data class ControlLawSpec(
    val law: ControlLaw = ControlLaw.LINEAR,
    val min: Double = 0.0,
    val max: Double = 1.0,
    val exponent: Double = 2.0,
    val center: Double = (min + max) / 2.0
) {
    /**
     * Rejects a range this law cannot express, naming [label] so the message points at the control.
     *
     * This throws rather than repairing anything, and that is the whole point. A `LOGARITHMIC` law on
     * a range starting at zero has no defensible reading: clamping the minimum up to some epsilon
     * gives a control that cannot reach its own stated minimum, and quietly falling back to linear
     * makes the declared law a lie. Both of those are discovered months later by ear. Meanwhile the
     * *unchecked* arithmetic produces `-Infinity` and `NaN`, which reach the DOM as the string "NaN"
     * and wedge the handle. So the range is checked once, here, at the moment the box declares it.
     *
     * @param label the control's label, used only to make the message actionable
     */
    fun validate(label: String) {
        require(exponent > 0.0 && exponent.isFinite()) {
            "control '$label' has a non-positive taper exponent ($exponent); the exponent must be > 0"
        }
        require(min.isFinite() && max.isFinite()) {
            "control '$label' has a non-finite range ($min..$max)"
        }
        when (law) {
            ControlLaw.LINEAR -> Unit
            ControlLaw.EXPONENTIAL -> require(max > min) {
                "control '$label' has an empty range ($min..$max) for an EXPONENTIAL law"
            }
            ControlLaw.LOGARITHMIC -> {
                require(min > 0.0) {
                    "control '$label' asks for a LOGARITHMIC law over $min..$max, but a logarithmic " +
                        "law needs a strictly positive minimum (log($min) is not a number). Use " +
                        "ControlLaw.EXPONENTIAL for a range that starts at zero, or ControlLaw.BIPOLAR " +
                        "for one that spans it."
                }
                require(max > min) {
                    "control '$label' has an empty range ($min..$max) for a LOGARITHMIC law"
                }
            }
            ControlLaw.BIPOLAR -> require(min < center && center < max) {
                "control '$label' asks for a BIPOLAR law with centre $center outside its range " +
                    "($min..$max); the centre must sit strictly inside the range so that mid-travel " +
                    "has somewhere to land."
            }
        }
    }

    /**
     * The value at travel [t], where `t` is a fraction of the track.
     *
     * The endpoints are returned exactly, by short-circuit, in every law. `20.0 * (20000.0 / 20.0)`
     * raised to the power `1.0` is `19999.999999999996` in binary floating point, and a 20 kHz control
     * whose top of travel reads 19999.99 is wrong in the way people notice.
     */
    fun valueAt(t: Double): Double {
        if (!t.isFinite()) return min
        if (t <= 0.0) return min
        if (t >= 1.0) return max
        return when (law) {
            ControlLaw.LINEAR -> min + t * (max - min)
            ControlLaw.LOGARITHMIC -> min * (max / min).pow(t)
            ControlLaw.EXPONENTIAL -> min + (max - min) * t.pow(exponent)
            ControlLaw.BIPOLAR -> when {
                t == 0.5 -> center
                t > 0.5 -> center + (max - center) * ((t - 0.5) * 2.0).pow(exponent)
                else -> center - (center - min) * ((0.5 - t) * 2.0).pow(exponent)
            }
        }
    }

    /**
     * The travel that shows [value] — the inverse of [valueAt], clamped to `0.0..1.0`.
     *
     * Exact at the endpoints for the same reason [valueAt] is: a control rendered at its maximum must
     * put the handle at the very end of the track, not 0.9999 of the way along it.
     */
    fun normalizedFor(value: Double): Double {
        if (!value.isFinite()) return 0.0
        if (value <= min) return 0.0
        if (value >= max) return 1.0
        return when (law) {
            ControlLaw.LINEAR -> if (max == min) 0.0 else (value - min) / (max - min)
            ControlLaw.LOGARITHMIC -> ln(value / min) / ln(max / min)
            ControlLaw.EXPONENTIAL -> ((value - min) / (max - min)).pow(1.0 / exponent)
            ControlLaw.BIPOLAR -> when {
                value == center -> 0.5
                value > center -> 0.5 + 0.5 * ((value - center) / (max - center)).pow(1.0 / exponent)
                else -> 0.5 - 0.5 * ((center - value) / (center - min)).pow(1.0 / exponent)
            }
        }.coerceIn(0.0, 1.0)
    }
}

/**
 * How many decimals to show for [value], given a significant-digit budget.
 *
 * `calculateDecimalPlaces(step)` answers this for a linear control and cannot answer it for a
 * nonlinear one: the same pixel of a 20 Hz–20 kHz logarithmic slider is worth 0.05 Hz at the bottom
 * and 460 Hz at the top, so one fixed decimal count is either noise at one end or a lie at the other.
 * Significant digits track the magnitude instead — with a budget of 4, the same control reads `20.00`,
 * `1000` and `20000`.
 *
 * A [significantDigits] of `0` means "not my department": [stepDecimals] is returned unchanged, which
 * is how every linear control keeps its historical formatting to the digit.
 *
 * This function is written twice — here and as `decimalsFor` in
 * `src/commonMain/resources/controlLaws.js` — and `ControlSurfaceProbeTest` runs the two against the
 * same matrix of values, because a display that disagrees with itself between the desktop and the
 * tablet is worse than either answer.
 *
 * @param value the number about to be shown
 * @param significantDigits digits of precision wanted, or `0` to defer to [stepDecimals]
 * @param stepDecimals the linear answer, from `calculateDecimalPlaces(step)`
 */
fun controlLawDecimals(value: Double, significantDigits: Int, stepDecimals: Int): Int {
    if (significantDigits <= 0) return stepDecimals
    val magnitude = abs(value)
    val exponent = if (magnitude > 0.0 && magnitude.isFinite()) floor(log10(magnitude)).toInt() else 0
    return (significantDigits - 1 - exponent).coerceIn(0, 8)
}

/** The significant-digit budget a control gets by default: none for a linear law, four otherwise. */
internal fun defaultSignificantDigits(law: ControlLaw): Int = if (law == ControlLaw.LINEAR) 0 else 4
