// `afield.graphics.scene.fabricated` is not among a box's automatic imports, unlike `afield.four`.
import afield.graphics.scene.fabricated.StandardRenderer
import afield.graphics.scene.fabricated.applyScenePlan
import afield.math.linalg.Vector3f

prop<StandardRenderer.Bundle>(bundle)

/*
`renderer` is the parent box's `prop<Renderer>`, visible here through the preamble —
there is nothing to fetch with `fromContext()`.

This one *is* a `once`, unlike the chain boxes below, and for a reason rather than out
of habit: `StandardRenderer.build` has no replace-in-place path, so re-running it would
strand the bundle every chain in the document is appended to.
*/
once.bundle {
    val b = StandardRenderer.build(
        renderer,
        StandardRenderer.Config(
            renderWidth = 1024,
            renderHeight = 1024,
            backgroundColor = Vector3f(0.02f, 0.02f, 0.03f),
        ),
    )
    b.applyScenePlan()
    b.present(renderer = renderer)
    b.attachTo(renderer)
    b
}
println("bundle ready: ${bundle.postStack.names()}")
