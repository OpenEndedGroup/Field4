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
    val holdSeconds = 30.0
    val fallSeconds = 100.0
    val lowPulseHz = 0.06
    val highPulseHz = 0.80
    val pulseFloor = 0.25
    val mergeFraction = 0.20

    // Starts are independent of rise durations: changing a rise pins its start.
    // Stable IDs survive moves and chronological reordering.
    val events = listOf(
        StudyEvent("e1", firstStartSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e2", firstStartSeconds + eventSpacingSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e3", firstStartSeconds + 2.0 * eventSpacingSeconds + e3OffsetSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e4", firstStartSeconds + 3.0 * eventSpacingSeconds, riseSeconds, holdSeconds, fallSeconds),
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
                seconds < event.plateauStart -> shoulder(base, seconds - event.start, event.rise)
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
            StudyChoice("Move the third event, resolved and selected as e3, 40 seconds later: start 1425, plateau 1525..1555, end 1655 seconds. Preserve its identity and internal timing, every other event, and the continuing background. This supersedes the original even-spacing requirement and nominal timing assumption for e3 only", "user requirement")
        )
    )
}
