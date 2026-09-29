package afield.examples

/*
 * Stop releases what a run has *running*, and leaves what it registered under a key or as a control.
 *
 * Each ledger kind carries a release policy (`ReleasePolicy`, RunScope.kt). The box below, launched,
 * holds one of each kind that used to outlive stop: a slider callback (ACCUMULATING — the strand's
 * next run releases it), a Runloop preRun hook (KEYED — only deleting the box releases it, and
 * re-setting the key replaces it), a patSynth feeder thread it never returns (the `val sc =
 * patSynth(...)` shape whose feeder leaked a thread per run), an audio patch in the mix and a
 * `loop {}` job (RUNNING — stop releases them). The census before the stop is the negative control —
 * it fails unless the box really holds each of them; the one after it fails unless everything
 * running is gone (`liveRunning == 0`) while the slider and hook are still there; and the one after
 * `delete-box` fails unless the box holds nothing at all (`liveTotal == 0`).
 *
 * Audio: this counts the patch's *registration* with FieldAudioEngine, which happens whether or not an
 * output device opened, so it holds on a machine with no device; it says nothing about whether the
 * patch was audible. The patch plays at gain 0.
 *
 * The `FIELD_TEST` block is parsed by a YAML subset that splits a value on `": "`, so no command line
 * below may contain a colon followed by a space.
 */

/*
FIELD_TEST
id: lifecycle.census.stop-releases
runner: backdoor-box
tags: [smoke, lifecycle, backdoor, audio]
timeoutSec: 180
execution: launch
boxName: LifecycleCensus
w: 320
h: 120
waitSec: 1.0
beforeStop:
  - "lifecycle-census @last --expect-at-least jobs=1 --expect-at-least control-callback=1 --expect-at-least runloop-preRun=1 --expect-at-least patsynth-feeder=1 --expect-at-least audio-patch=1 --timeout 20"
afterStop:
  - "lifecycle-census @last --expect-stopped --expect-exactly audio-patch=0 --expect-exactly patsynth-feeder=0 --expect-exactly control-callback=1 --expect-exactly runloop-preRun=1 --timeout 20"
  - "delete-box @last"
  - "lifecycle-census @deleted --expect-empty --timeout 20"
*/
private val LIFECYCLE_CENSUS_SNIPPET = """
import afield.four.synth.*

var frames = 0
slider("census", 0.0, 1.0) { }
Runloop.runLoop.preRun["lifecycle-census-hook"] = sequence { while (true) yield(null) }
val unreturnedFeeder = patSynth(pat("bd ~"), cps = 1.0)
launches(loop { frames++ }, audio(sine(220), gain = 0f))
"""

/*
 * A re-launch replaces, and `once` belongs to the box.
 *
 * The same kinds as above, plus two `once` values computed in the parent scope: one holding a slider
 * (ACCUMULATING, so neither the stop nor the re-launch touches it, and the cached value is kept) and
 * one holding a patSynth feeder (RUNNING, so the stop releases it and clears the cached value, and
 * the re-launch computes a fresh one). After the stop: nothing running, both sliders and the hook
 * kept. After the re-launch: still exactly two sliders (the old run's released only after the new
 * run registered its own; the once slider not recomputed — recomputing would make three), exactly
 * one hook (the key replaced it), and two feeders (the body's, and the once value's fresh one — a
 * once value still holding its dead feeder would make it one).
 */

/*
FIELD_TEST
id: lifecycle.census.relaunch-replaces-once-survives
runner: backdoor-box
tags: [smoke, lifecycle, backdoor, audio]
timeoutSec: 240
execution: launch
boxName: LifecycleRelaunch
w: 320
h: 120
waitSec: 1.0
beforeStop:
  - "lifecycle-census @last --expect-at-least jobs=1 --expect-exactly control-callback=2 --expect-exactly runloop-preRun=1 --expect-exactly patsynth-feeder=2 --expect-at-least audio-patch=1 --timeout 20"
afterStop:
  - "lifecycle-census @last --expect-stopped --expect-exactly control-callback=2 --expect-exactly runloop-preRun=1 --expect-exactly patsynth-feeder=0 --expect-exactly audio-patch=0 --timeout 20"
  - "launch @last"
  - "lifecycle-census @last --expect-at-least jobs=1 --expect-exactly control-callback=2 --expect-exactly runloop-preRun=1 --expect-exactly patsynth-feeder=2 --expect-at-least audio-patch=1 --timeout 20"
  - "stop @last"
  - "lifecycle-census @last --expect-stopped --expect-exactly control-callback=2 --timeout 20"
  - "delete-box @last"
  - "lifecycle-census @deleted --expect-empty --timeout 20"
*/
private val LIFECYCLE_RELAUNCH_SNIPPET = """
import afield.four.synth.*

prop<Any>(censusOnceSlider)
prop<Any>(censusOnceFeeder)

var frames = 0
slider("relaunch", 0.0, 1.0) { }
once.censusOnceSlider { slider("relaunch-once", 0.0, 1.0) { }; "made once" }
once.censusOnceFeeder { patSynth(pat("hh ~"), cps = 1.0) }
Runloop.runLoop.preRun["lifecycle-relaunch-hook"] = sequence { while (true) yield(null) }
val unreturnedFeeder = patSynth(pat("bd ~"), cps = 1.0)
launches(loop { frames++ }, audio(sine(220), gain = 0f))
"""
