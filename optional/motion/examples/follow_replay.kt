:::fold imports and recording
import afield.animation.mocap.*
val clip = MotionRecording("%docs/assets/motion_recordings/closd-resistance.f4motion".path).clip()
val started = AnimationClock.nowSeconds()
:::endfold

loop {
    val elapsed = AnimationClock.nowSeconds() - started
    val duration = clip.endTime - clip.startTime
    val time = elapsed % duration
    val snapshot = clip.sample(clip.startTime + time)
    // First two seconds: fixed camera. Then follow; the grid stays in world coordinates.
    val following = time >= 2.0
    showMotion(snapshot.frame, clip.skeleton, reference=false,
        follow=following, followHalfLife=.4, followEpoch="${(elapsed/duration).toInt()}:${snapshot.frame.episode}")
    markdown("status", if(following) "**Following the body** · 0.4 s half-life · orbit and zoom remain yours"
        else "**Fixed camera** · Follow engages at two seconds")
    displayOpen("motion"); displayOpen("status")
    delay(16)
}
