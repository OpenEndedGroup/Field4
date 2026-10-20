import afield.animation.mocap.MotionRecording
import afield.animation.mocap.showMotion
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

val recording = MotionRecording("%docs/assets/motion_recordings/closd-foot-tether.f4motion".path)
val clip = recording.clip()
val events = recording.events().toList()
val play = AtomicBoolean(false)
val follow = AtomicBoolean(true)
val followHalfLife = AtomicReference(.4)
val scrub = AtomicReference(3.4)
val showReference = AtomicBoolean(true)
controlGroup {
    checkbox("Follow body", initialValue=true) { follow.set(it) }
    slider("Follow half-life seconds", .05, 2.0, initialValue=.4) { followHalfLife.set(it) }
    slider("Recorded seconds", clip.startTime, clip.endTime, initialValue=3.4) { scrub.set(it) }
    checkbox("Play", initialValue=false) { play.set(it) }
    checkbox("Reference pose", initialValue=true) { showReference.set(it) }
}
val started = AnimationClock.nowSeconds()

loop {
    val seconds = if (play.get()) clip.startTime + (AnimationClock.nowSeconds() - started) % (clip.endTime - clip.startTime) else scrub.get()
    val snapshot = clip.sample(seconds)
    val tether = events.lastOrNull { it.optString("type") == "tetherPhysics" && it.getDouble("time") <= seconds }
    val overlays = if (tether != null && seconds < 4.0) listOf(fline {
        val a = tether.getJSONArray("anchor")
        val p = tether.getJSONArray("point")
        moveTo(a.getDouble(0), a.getDouble(1), a.getDouble(2))
        lineTo(p.getDouble(0), p.getDouble(1), p.getDouble(2))
        color = vec(1.0, 0.3, 0.4, 1.0)
        thickness = 3.0
    }) else emptyList()
    showMotion(snapshot.frame, clip.skeleton, reference=showReference.get(), overlays=overlays, follow=follow.get(), followHalfLife=followHalfLife.get())
    markdown("status", "**CLoSD · recorded GPU motion**\n\n" +
        "White: executed body. Cyan: planner reference positions. Red: physical foot tether, attached at 2 s and released at 4 s. " +
        "Time ${"%.2f".format(seconds)} s · episode ${snapshot.frame.episode}. Orbit the view with the mouse.")
    displayOpen("motion")
    displayOpen("status")
    delay(16) // Yield without flooding the UI transport.

}
