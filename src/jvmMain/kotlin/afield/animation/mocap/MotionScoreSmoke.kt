@file:afield.runtime.completion.CompletionCategory("Animation")

package afield.animation.mocap

import afield.math.linalg.Vec3
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/*
FIELD_TEST
id: motion.score.gpu
runner: jvm-main
entrypoint: afield.animation.mocap.MotionScoreSmoke
tags: [smoke, optional, animation, motion, gpu, closd]
timeoutSec: 240
expect:
  exitCode: 0
  stdoutContains:
    - "Motion score smoke"
*/
/** Real physical-body authoring, moving anchor, observation, reset and reused-handle ordering. */
object MotionScoreSmoke {
    @JvmStatic fun main(args: Array<String>) {
        if (System.getenv("FIELD_MOTION_HOME").isNullOrBlank()) {
            println("Motion score smoke SKIPPED: set FIELD_MOTION_HOME for actual CLoSD physics")
            return
        }
        CLoSD.openAsync().get(180,TimeUnit.SECONDS).use { dancer ->
            dancer.prompt("A person walks forward.")
            dancer.advance(.1).get(120,TimeUnit.SECONDS)
            val foot=dancer.body("L_Ankle").tether("left") {
                stiffness=8000.0; damping=120.0; maxForce=2500.0; ramp=200.milliseconds
            }
            foot.accepted.get(5,TimeUnit.SECONDS)
            val held=dancer.advance(2.0).get(120,TimeUnit.SECONDS)
            check(held.events.any { it.optString("type")=="tetherPhysics" })
            check(foot.observation?.active==true && foot.observation!!.forceN>0)
            check(foot.observation!!.forceN<=2500.00001)
            foot.tune { velocity=Vec3(.05f,0f,0f) }.get(5,TimeUnit.SECONDS)
            dancer.advance(.1).get(120,TimeUnit.SECONDS)
            val a=foot.observation!!.anchor
            foot.moveTo(Vec3(a[0].toFloat()+.1f,a[1].toFloat(),a[2].toFloat())).get(5,TimeUnit.SECONDS)
            dancer.advance(.1).get(120,TimeUnit.SECONDS)
            foot.release().get(5,TimeUnit.SECONDS)
            dancer.advance(.1).get(120,TimeUnit.SECONDS)
            check(foot.observation?.reason=="released")
            check(runCatching { foot.tune { stiffness=1.0 } }.isFailure)
            dancer.reset(dancer.editableTime).get(5,TimeUnit.SECONDS)
            dancer.advance(.1).get(120,TimeUnit.SECONDS)
            check(foot.observation?.reason=="reset")
            check(foot.release().get(5,TimeUnit.SECONDS)==null)
            // Reusing an id must not inherit the previous reset state and swallow this release.
            val again=dancer.body("L_Ankle").tether("left")
            again.release().get(5,TimeUnit.SECONDS)
            dancer.advance(.1).get(120,TimeUnit.SECONDS)
            check(again.observation?.reason=="released")
            dancer.body("Torso").impulse(Vec3(10f,0f,0f)).get(5,TimeUnit.SECONDS)
            val impulse=dancer.advance(.1).get(120,TimeUnit.SECONDS)
            check(impulse.events.count { it.optString("type")=="impulsePhysics" }==1)
            check(dancer.failure==null)
        }
        println("Motion score smoke passed: actual GPU tether/tune/move/release/reset, reused id ordering and one-shot impulse")
    }
}
