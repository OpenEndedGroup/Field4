/*
FIELD_TEST
id: synth.boolean-network-music.smoke
runner: jvm-main
entrypoint: afield.examples.BooleanNetworkMusicSmokeTest
tags: [synth, boolean, music, scheduling]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "Boolean network music smoke tests passed"
*/
package afield.examples

import afield.four.synth.BooleanTriggerMode
import afield.four.synth.ScheduledNote
import afield.four.synth.asNoteProcess
import afield.four.synth.asDrivenNoteProcess
import afield.four.synth.note
import afield.four.synth.respondAhead
import afield.four.synth.scheduledDrivenSteps
import afield.four.synth.scheduledNotes
import afield.four.synth.scheduledSteps
import afield.stdlib.toggleNetwork

object BooleanNetworkMusicSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        println("Running Boolean network music smoke tests...")
        testScheduledSteps()
        testDrivenScheduledSteps()
        testScheduledNotes()
        testNoteProcess()
        testDrivenNoteProcess()
        testResponseSpec()
        println("Boolean network music smoke tests passed")
    }

    private fun testScheduledSteps() {
        val net = toggleNetwork()
        val initial = booleanArrayOf(true, false)
        val steps = net.scheduledSteps(initialState = initial, stepDuration = 0.25, steps = 5).collectAll()

        require(steps.size == 5) { "Expected 5 scheduled steps, got ${steps.size}" }
        require(steps[0].start == 0.0) { "First step should start at 0.0, got ${steps[0].start}" }
        require(steps[1].start == 0.25) { "Second step should start at 0.25, got ${steps[1].start}" }
        require(steps[0].lanes(BooleanTriggerMode.RISING_EDGE).isNotEmpty()) {
            "Initial active lanes should count as rising edges on the first step"
        }
        require(steps[1].previousState?.contentEquals(steps[0].state) == true) {
            "Step 1 previous state should match step 0 state"
        }
        println("  ✓ scheduledSteps produced a stable timed state stream")
    }

    private fun testDrivenScheduledSteps() {
        val net = toggleNetwork()
        val initial = booleanArrayOf(false, false)
        val steps = net.scheduledDrivenSteps(initialState = initial, stepDuration = 0.25, steps = 6) { stepIndex, _, _, state ->
            state[0] = stepIndex % 2L == 0L
        }.collectAll()

        require(steps.map { it.state[0] } == listOf(true, false, true, false, true, false)) {
            "Driven lane should follow the external oscillator pattern"
        }
        require(steps[1].previousState?.get(0) == true) {
            "previousState should reflect the externally driven step state"
        }
        println("  ✓ scheduledDrivenSteps injected an external oscillator lane")
    }

    private fun testScheduledNotes() {
        val net = toggleNetwork()
        val initial = booleanArrayOf(true, false)
        val notes = net.scheduledNotes(initialState = initial, stepDuration = 0.5, steps = 8) { step ->
            step.lanes(BooleanTriggerMode.RISING_EDGE).map { lane ->
                note(
                    pitch = 60 + lane * 7,
                    duration = step.duration * 0.8,
                    start = step.start,
                    velocity = 0.75,
                    label = "lane-$lane",
                )
            }
        }.collectAll()

        require(notes.isNotEmpty()) { "Expected scheduledNotes to emit at least one note" }
        require(notes.zipWithNext().all { (a, b) -> a.start <= b.start + 1e-9 }) {
            "scheduledNotes must remain sorted by start time"
        }
        require(notes.all { it.label?.startsWith("lane-") == true }) {
            "Expected generated note labels to survive mapping"
        }
        println("  ✓ scheduledNotes mapped Boolean steps to ${notes.size} note(s)")
    }

    private fun testNoteProcess() {
        val net = toggleNetwork()
        val initial = booleanArrayOf(true, false)
        val process = net.asNoteProcess(initialState = initial, stepDuration = 0.5, steps = 12) { step ->
            step.lanes(BooleanTriggerMode.RISING_EDGE).map { lane ->
                ScheduledNote(
                    start = step.start,
                    duration = step.duration * 0.9,
                    pitch = (48 + lane * 12).toFloat(),
                    velocity = 0.85f,
                    label = "n$lane",
                )
            }
        }

        val snapshot = process.snapshot(pastSeconds = 0.0, futureSeconds = 4.0)
        require(snapshot.notes.isNotEmpty()) { "Expected NoteProcess snapshot to contain notes" }
        require(snapshot.windowEndSeconds >= 4.0) { "Expected snapshot to unroll into the requested future window" }
        println("  ✓ asNoteProcess generated ${snapshot.notes.size} visible note(s)")
    }

    private fun testDrivenNoteProcess() {
        val net = toggleNetwork()
        val process = net.asDrivenNoteProcess(
            initialState = booleanArrayOf(false, false),
            stepDuration = 0.25,
            steps = 8,
            driver = { stepIndex, _, _, state ->
                state[0] = stepIndex % 2L == 0L
            },
        ) { step ->
            step.lanes(BooleanTriggerMode.RISING_EDGE).map { lane ->
                note(
                    pitch = 54 + lane * 9,
                    duration = step.duration * 0.8,
                    start = step.start,
                    velocity = 0.8,
                    label = "driven-$lane",
                )
            }
        }

        val notes = process.notesBetween(0.0, 2.0)
        require(notes.isNotEmpty()) { "Expected driven NoteProcess to emit notes" }
        require(notes.all { it.label?.startsWith("driven-") == true }) {
            "Driven NoteProcess should preserve mapped labels"
        }
        println("  ✓ asDrivenNoteProcess reacted to oscillator forcing")
    }

    private fun testResponseSpec() {
        val net = toggleNetwork()
        val initial = booleanArrayOf(true, false)
        val spec = net.respondAhead(initialState = initial, ahead = 0.02, stepDuration = 0.25, steps = 4, clock = null) { step ->
            step.lanes(BooleanTriggerMode.CHANGE)
        }

        val text = spec.toString()
        require("stepDuration" in text && "ahead" in text) { "BooleanNetworkResponseSpec should describe its schedule" }
        println("  ✓ respondAhead created a launchable Boolean network response spec")
    }

    private fun <T> Iterator<T>.collectAll(): List<T> {
        val out = mutableListOf<T>()
        while (hasNext()) out += next()
        return out
    }
}
