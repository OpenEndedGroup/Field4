@file:afield.CompletionCategory("Synthesis")
/**
 * `+` for the audio return values, so one box can launch more than one thing.
 *
 * A box launches whatever its last expression evaluates to, and `ReturnValueRegistry` reads a
 * `LaunchBundle`, or any `Iterable`, as a request to launch every item in it. `afield` supplies the
 * `+` that builds a bundle from a `Frames` or from a bundle already in hand; this file supplies it
 * for the three audio types a box is likely to be holding on the left, so
 * `audio { SineOsc(220.0) } + pat("bd sd").respond { … }` is one box playing a drone and a pattern.
 *
 * A chain of `+` produces one flat list rather than a nest: the bundle flattens what it already has
 * each time it grows, and the registry flattens again before it dispatches. Each item then goes
 * through the same handler chain it would have reached on its own, the box reads as running until
 * the last of them stops, and an item no handler recognises is logged on the `warn,execution`
 * channel rather than dropped in silence.
 */

package afield.four.synth

import afield.LaunchBundle
import afield.launches

/** Launch this patch and [other] together. [other] is anything the registry has a handler for. */
operator fun AudioPatchSpec.plus(other: Any?): LaunchBundle = launches(this, other)

/** Launch this stereo source and [other] together; the source is wrapped in a patch on the way. */
operator fun StereoRenderable.plus(other: Any?): LaunchBundle = launches(this, other)

/** Launch this responder and [other] together, both stopping when the box does. */
operator fun PatResponseSpec<*>.plus(other: Any?): LaunchBundle = launches(this, other)
