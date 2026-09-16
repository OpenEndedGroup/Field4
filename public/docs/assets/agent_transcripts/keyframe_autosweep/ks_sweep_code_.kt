// The whole of it. Twelve renders here so the box finishes while you watch; set `count`
// to 5000 and `maxDurationMs` to whatever "stop at 7am" is, and walk away.
import afield.stdlib.KeyframeScope
import afield.stdlib.KeyframeSweep
import afield.stdlib.SweepConfig
import afield.stdlib.awaitFrames
import afield.stdlib.captureClip
import afield.stdlib.captureStill
import afield.stdlib.path
import afield.stdlib.rendersPerHour

val r: Renderer = fromContext()
val keys: KeyframeScope = fromContext()
val bundle: afield.four.fabricated.StandardRenderer.Bundle = fromContext()
val pool = "%document/looks".path
val archive = "%document/sweep".path

val sweep = KeyframeSweep(
    keys, pool, archive,
    SweepConfig(
        seed = 20260916,
        count = 12,
        minArity = 2,
        maxArity = 3,
        // Below 1, a blend is mostly one key with the others as perturbation. Well above
        // it, every blend is the average of its participants — a night of mush.
        concentration = 0.7,
        extrapolationProbability = 0.3
    ),
    onRender = { m -> println("${m.id} ${m.status} — " + m.parents.joinToString(", ") { "${it.key} ${"%.2f".format(it.weight)}" }) }
)

// `frames { }` puts the run on the Runloop, so the callback can wait for real frames and
// the stop key cancels it between renders. The blend is already applied when it is called.
// `frames` rather than `start`: `start` has a receiver overload and a receiverless one, and
// a bare `{ … }` satisfies both, so `start { … }` is an overload resolution ambiguity —
// everywhere, not just here. Nothing in the repo calls it that way (every call site passes
// a callable reference, `start(renderer::run)`), which is why the trap is unspent.
frames {
    val report = sweep.run { render ->
        r.awaitFrames(6)
        render.captureStill(r, bundle.postStack.output(), settleFrames = 0)
        render.captureClip(r, bundle.postStack.output(), frames = 8, fps = 12, settleFrames = 0) { _, t ->
            // A small orbit *around* the blended camera, so the clip moves without
            // overwriting the lane the key set.
            val at = render.key["camera.at"] as afield.stdlib.linalg.Vector3f
            val a = (t - 0.5) * 0.5
            bundle.scene.camera.at.set(
                (at.x * kotlin.math.cos(a) - at.z * kotlin.math.sin(a)).toFloat(),
                at.y,
                (at.x * kotlin.math.sin(a) + at.z * kotlin.math.cos(a)).toFloat()
            )
            r.awaitFrames(2)
        }
    }
    // Interpolated, not passed bare: `println(anything)` in a box goes through Field's
    // object inspector, which renders a data class field by field and ignores a hand
    // written `toString`.
    println("$report")
    println("that is ${"%.0f".format(report.rendersPerHour())} renders an hour")
}
