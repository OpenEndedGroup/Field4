// Run this box, then edit the curve: the dashed overlay is a smoothed copy of it, and
// onChange redraws it after every edit.
val wobble = keyframes(name = "wobble", defaults = listOf(
    vec(0.0, 0.2), vec(0.5, 0.9), vec(1.0, 0.1), vec(1.5, 0.8), vec(2.0, 0.3)
))

val smooth = { keys: List<Keyframe> -> with(Functions) { gaussianSmooth(fromKeyframeEditor(keys), sigma = 0.15) } }
wobble.setOverlay(smooth(wobble.getKeyframes()))
wobble.onChange = smooth
