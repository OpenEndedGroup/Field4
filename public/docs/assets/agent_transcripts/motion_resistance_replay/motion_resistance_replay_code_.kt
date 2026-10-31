import afield.animation.mocap.*
import kotlin.time.Duration.Companion.seconds

val take = MotionRecording("%docs/assets/motion_recordings/closd-resistance.f4motion".path)
frames {
    var loop = 0
    while (true) {
        MotionSession.replay(take).use { dancer ->
            playMotion(dancer, 9.seconds) { snapshot ->
                val t = dancer.telemetry.tether("tether")
                showMotion(
                    snapshot.frame,
                    dancer.skeleton,
                    follow = true,
                    followHalfLife = .4,
                    followEpoch = "${loop}:${snapshot.frame.episode}",
                    overlays = t?.overlays() ?: emptyList(),
                )
                val action =
                    if (t?.active == true) "TETHERED · ${(t.stretchMetres*100).f1} cm · ${t.forceN.f0} N"
                    else if (snapshot.frame.time >= 4.0) "RELEASED" else "WALKING"
                markdown(
                    "status",
                    "**$action** · ${snapshot.frame.time.f2} s\n\n" +
                        "Recorded CLoSD physics. White: executed · cyan: reference · red: anchor · amber: submitted force (scaled). " +
                        "8000 N/m, 120 N s/m, 2500 N cap. The prompt stays unchanged. Follow camera half-life: 0.4 s.",
                )
                displayOpen("motion")
                displayOpen("status")
            }
        }
        loop++
    }
}
