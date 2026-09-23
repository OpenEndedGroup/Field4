import afield.stdlib.Functions
import afield.stdlib.StudyComposition
import afield.stdlib.StudyEvent
import afield.stdlib.StudyChoice
import kotlin.math.PI

with(Functions) {
    val durationSeconds = 3600.0
    val carrierPeriodSeconds = 40.0
    val relativePhasePeriodSeconds = 100.0
    val phaseExcursionRadians = 2.0 * PI / 3.0
    val backgroundCenter = 0.45
    val backgroundAmplitude = 0.20
    val slowAmplitude = 0.04
    val slowPeriodSeconds = 230.0

    val firstStartSeconds = 185.0
    val eventSpacingSeconds = 600.0
    val e3OffsetSeconds = 40.0
    val riseSeconds = 100.0
    val e3RiseSeconds = 200.0
    val holdSeconds = 30.0
    val fallSeconds = 100.0
    val lowPulseHz = 0.06
    val highPulseHz = 0.80
    val pulseFloor = 0.25
    val mergeFraction = 0.20
    val e3YPeakLagSeconds = 6.0
    val e3YLagSpanSeconds = 180.0

    // Starts are independent of rise durations: changing a rise pins its start.
    // Stable IDs survive moves and chronological reordering.
    val events = listOf(
        StudyEvent("e1", firstStartSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e2", firstStartSeconds + eventSpacingSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e3", firstStartSeconds + 2.0 * eventSpacingSeconds + e3OffsetSeconds, e3RiseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e5", firstStartSeconds + 4.0 * eventSpacingSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e6", firstStartSeconds + 5.0 * eventSpacingSeconds, riseSeconds, holdSeconds, fallSeconds)
    )

    fun smooth01(value: Double): Double {
        val u = value.coerceIn(0.0, 1.0)
        return u * u * u * (10.0 + u * (-15.0 + 6.0 * u))
    }

    fun background(seconds: Double, channel: Int): Double {
        val drift = phaseExcursionRadians * kotlin.math.sin(2.0 * PI * seconds / relativePhasePeriodSeconds)
        val phase = 2.0 * PI * seconds / carrierPeriodSeconds + (channel - 1) * drift
        return backgroundCenter + backgroundAmplitude * kotlin.math.sin(phase) +
            slowAmplitude * kotlin.math.sin(2.0 * PI * seconds / slowPeriodSeconds)
    }

    // age runs from the background edge toward the plateau on either side.
    // Integral of f(age) = lowPulseHz + (highPulseHz-lowPulseHz)*age/span.
    // Reversing age on the fall gives a decelerating, shrinking pulse train.
    fun shoulder(base: Double, age: Double, span: Double): Double {
        val u = (age / span).coerceIn(0.0, 1.0)
        val cycles = lowPulseHz * age +
            0.5 * (highPulseHz - lowPulseHz) * age * age / span
        val pulse = 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * cycles)
        val strength = smooth01(u)
        val pulseTarget = pulseFloor + (1.0 - pulseFloor) * pulse
        val pulsed = (1.0 - strength) * base + strength * pulseTarget
        // Fill the troughs during the last 20% rather than abruptly cut pulses.
        val merge = smooth01((u - (1.0 - mergeFraction)) / mergeFraction)
        return (1.0 - merge) * pulsed + merge
    }

    val channels = listOf("x", "y", "z").mapIndexed { index, name ->
        fn(0.0..durationSeconds, name) { seconds ->
            // The background always uses absolute time; events never restart it.
            val base = background(seconds, index)
            val event = events.firstOrNull { seconds >= it.start && seconds <= it.end }
            when {
                event == null -> base
                seconds < event.plateauStart -> {
                    val age = seconds - event.start
                    // Instance-specific shoulder clock only; never warp the background.
                    // 64*u^3*(1-u)^3 has peak 1 and zero first/second endpoint derivatives.
                    val lag = if (event.id == "e3" && name == "y" && age < e3YLagSpanSeconds) {
                        val u = age / e3YLagSpanSeconds
                        val v = 1.0 - u
                        e3YPeakLagSeconds * 64.0 * u * u * u * v * v * v
                    } else 0.0
                    // Clock rate is in [0.885514, 1.114487], so time never reverses.
                    val localAge = age - lag
                    // Bind local function result: avoids Wither's brace-tail callableId pitfall.
                    val result = shoulder(base, localAge, event.rise)
                    result
                }
                seconds <= event.plateauEnd -> 1.0 // exact closed constant hold
                else -> shoulder(base, event.end - seconds, event.fall)
            }
        }
    }

    StudyComposition(
        channels = channels,
        events = events,
        duration = durationSeconds,
        maxFrequencyHz = 2.0,
        choices = listOf(
            StudyChoice("Three time-dependent channels over one hour, with smooth relative phase motion on an approximately 100-second scale", "user requirement"),
            StudyChoice("Six evenly spaced pulse crescendos/decrescendos, each reaching an exact 30-second all-ones hold; background continues and returns, with events away from boundaries", "user requirement"),
            StudyChoice("Integrated instantaneous pulse frequency, smooth joins, and stable event IDs e1 through e6", "user requirement"),
            StudyChoice("Background: 40-second carrier, sinusoidal relative-phase drift with 100-second period and +/-2pi/3 excursion, plus a gentle 230-second common sway", "agent assumption"),
            StudyChoice("Starts at 185+600*n seconds; 100-second rises and falls put plateau centers at 300+600*n seconds and leave 185-second boundary margins", "agent assumption"),
            StudyChoice("Synchronized pulse targets sweep from 0.06 to 0.80 Hz; quintic crossfade grows pulse depth toward a 0.25-to-1 target, then fills troughs in the final 20% to merge into the hold. Falls reverse this gesture", "agent assumption"),
            StudyChoice("All channels stay in [0,1]; quintic envelopes give value, velocity and acceleration continuity at event boundaries and plateaus", "agent assumption"),
            StudyChoice("Rise edits pin the event start, retain the 30-second hold, and move that event's later joins only; other event starts do not ripple", "agent assumption"),
            StudyChoice("Move the third event, resolved and selected as e3, 40 seconds later: start 1425, plateau 1525..1555, end 1655 seconds. Preserve its identity and internal timing, every other event, and the continuing background. This supersedes the original even-spacing requirement and nominal timing assumption for e3 only", "user requirement"),
            StudyChoice("Double selected e3's rise from 100 to 200 seconds, pinning its start at 1425. Its hold remains 30 seconds at 1625..1655, followed by the unchanged 100-second fall ending at 1755. The integrated rise frequency ramp now spans 200 seconds with unchanged 0.06/0.80 Hz endpoints. This supersedes e3's prior rise and later join times only; preserve every other event and the absolute-time background without ripple", "user requirement"),
            StudyChoice("During e3 only, let y lag and catch up smoothly on a strictly monotonic local rise clock; preserve x/z, everything outside e3, and the shared 30-second all-ones hold", "user requirement"),
            StudyChoice("e3 y shoulder age is a minus 6*64*u^3*(1-u)^3 seconds for a in [0,180], u=a/180; identity thereafter. Peak lag 6 seconds at 1515; recovered at 1605, 20 seconds before the unchanged 1625..1655 hold. Clock rate stays positive (approximately 0.8855..1.1145), with unit endpoint slopes and zero endpoint curvature. Warp pulse phase and shoulder envelopes together, retaining the absolute-time background. This overrides pulse synchronization only for this instance and channel", "agent assumption"),
            StudyChoice("Delete the fourth interruption, resolved from the current inventory as e4, formerly 1985..2215 seconds. Reveal the continuing absolute-time background there; preserve e1/e2/e3/e5/e6 without renumbering or ripple, including e3 timing and y lag. This supersedes the original six-event requirement only by removing e4. Retain the pre-deletion revision r6-deb1b1738cb0 in history", "user requirement"),
            StudyChoice("Select background-only interval 1100..1250 seconds; slow its first half and catch up in its second with a strictly monotonic time map pinned in value and slope 1 at both ends. Preserve all other times, current event instances, metadata, and held plateaus", "user requirement"),
            StudyChoice("Within the selected interval evaluate all background channels at T(t)=t-A*64*u^3*(1-u)^3, u=(t-1100)/150, A=6 seconds; identity elsewhere. Peak delay 6 seconds at 1175; derivative 1-(A/150)*192*u^2*(1-u)^2*(1-2u) lies in approximately 0.8626..1.1374. Endpoint second derivatives also vanish. Strength zero restores the unwarped background; the strictly increasing map is invertible. This supersedes absolute-time background only inside the selected event-free passage; event clocks and e3 y lag are untouched", "agent assumption"),
            StudyChoice("Restore the unwarped absolute-time background using historical r7-ebc54198e3ae as reference, in a new checked child of r8 rather than discarding history. Remove the background time-map implementation only; preserve all current event timings, e3 y lag and e4 deletion. This supersedes the preceding 1100..1250 background-warp requirement and map assumption; retain that experiment in revision history", "user requirement")
        )
    )
}
