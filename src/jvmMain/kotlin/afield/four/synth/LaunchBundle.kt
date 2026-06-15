package afield.four.synth

import afield.LaunchBundle
import afield.launches

operator fun AudioPatchSpec.plus(other: Any?): LaunchBundle = launches(this, other)

operator fun StereoRenderable.plus(other: Any?): LaunchBundle = launches(this, other)

operator fun PatResponseSpec<*>.plus(other: Any?): LaunchBundle = launches(this, other)
