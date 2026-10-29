import afield.animation.mocap.*
import kotlin.time.Duration.Companion.seconds

frames {
    CLoSD.openAsync().awaitMotion().use { dancer ->
        dancer.prompt("A person walks forward.")
        fun draw(snapshot: MotionSnapshot) {
            showMotion(snapshot.frame, dancer.skeleton, follow=true,
                overlays=dancer.telemetry.tethers().flatMap { it.overlays() })
            val foot = dancer.telemetry.tether("left-foot")
            markdown("status", when {
                foot?.active == true -> "**TETHERED** · ${"%.0f".format(foot.forceN)} N"
                foot != null -> "**RELEASED**"
                else -> "**WALKING**"
            })
            displayOpen("motion"); displayOpen("status")
        }
        playMotion(dancer, 2.seconds, ::draw)
        val foot = dancer.body("L_Ankle").tether("left-foot") {
            stiffness = 8000.0
            damping = 120.0
            maxForce = 2500.0
        }
        playMotion(dancer, 2.seconds, ::draw)
        foot.release()
        playMotion(dancer, 3.seconds, ::draw)
    }
}
