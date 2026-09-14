# Sonic Pi measurements

The methodology behind the timing numbers on `public/docs/sonic_pi.md`. The page states the results
and the asserted bounds; this note says how the instrument was built so that those results mean
something.

## The two probes

`synth.sonic-pi.patterns` (`SonicPiPatternsSmokeTest.kt`) is a differential oracle over the emitted
*text*. It parses the generated program back and compares it to the pattern's own `PatQ` positions,
with no floating point in the comparison. Comparing the emitter against its own event list would
only prove the emitter can enumerate; everything that can reach a listener lives in the gap between
that list and the text — a third rendered as `0.333333`, a `sleep` delta that does not accumulate
back to the position it came from, a missing pad at the end of the loop so the second bar arrives
early.

`sonicpi.pattern.timing` (`SonicPiPatternProbe.kt`) answers the separate question of whether Sonic
Pi then *does* what the program says, on the real engine. It asserts its bounds rather than printing
them: 25 ms on the line-activity (flash) train and 2 ms on the recorded audio train.

## Why the onset numbers are trustworthy at that resolution

Two properties of the audio probe are what make a sub-millisecond figure meaningful rather than an
artefact of the instrument.

**Onsets are refined to the sample rather than left at the analysis frame.** A flux peak in a
256-sample window can only say which *frame* something happened in. At a frame resolution of 2.7 ms,
a measurement coarser than the thing it measures will read `0.0 ms` whatever the truth is, so the
number would be meaningless without the refinement step.

**Onsets are deduplicated against the pattern's own smallest gap.** `bd_haus` has a secondary energy
rise 129 ms into its decay. A first-past-the-post refractory window would report that rise in place
of the attack that follows it, silently shifting the whole train.

`PatOnsetDetection.kt` carries what the detector does and does not assert.

## Why the test pattern is the one it is

`pat("bd ~ [bd bd bd] bd")` at a quarter cycle per second puts a triplet inside a binary bar, chosen
so that a detector cannot agree with the schedule by accident: a detector that quantised to the
binary grid would disagree on three of the eleven onsets.

The audible run is at a master volume of 0.15 and is recorded through Sonic Pi's own recorder, then
analysed in process. `checkNotSilent` is the assertion that stops the probe quietly measuring
nothing; every measurement below it degrades to meaningless if it does not hold.

## Open

Nothing here has been run on Linux or Windows. Both probes skip on a machine without a Sonic Pi
install, and `FIELD_SONIC_PI_REQUIRED=1` turns that skip — or a boot that never becomes ready — into
a failure, which is how a machine whose job is to exercise the external engine should be configured.
