package afield.examples

/*
 * Stop releases what a run registered.
 *
 * The box below, launched, holds one of each thing that used to outlive stop: a slider callback, a
 * Runloop preRun hook, a patSynth feeder thread it never returns (the `val sc = patSynth(...)` shape
 * whose feeder leaked a thread per run), an audio patch in the mix, and a `loop {}` job. The census
 * before the stop is the negative control — it fails unless the box really holds each of them — and
 * the census after it fails unless every one is gone (`liveTotal == 0`).
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
  - "lifecycle-census @last --expect-empty --timeout 20"
*/
private val LIFECYCLE_CENSUS_SNIPPET = """
import afield.four.synth.*

var frames = 0
slider("census", 0.0, 1.0) { }
Runloop.runLoop.preRun["lifecycle-census-hook"] = sequence { while (true) yield(null) }
val unreturnedFeeder = patSynth(pat("bd ~"), cps = 1.0)
launches(loop { frames++ }, audio(sine(220), gain = 0f))
"""
