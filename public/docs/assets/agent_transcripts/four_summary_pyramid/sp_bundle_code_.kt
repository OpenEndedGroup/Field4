// Import the fabricated scene helpers explicitly; they are not part of a box's automatic imports.
import afield.graphics.scene.fabricated.StandardRenderer
import afield.graphics.scene.fabricated.applyScenePlan

prop<StandardRenderer.Bundle>(bundle)

/*
`renderer` is the parent box's `prop<Renderer>`, visible here through the preamble —
there is nothing to fetch with `fromContext()`.

This one *is* a `once`, unlike the chain box below, and for a reason rather than out of
habit: `StandardRenderer.build` has no replace-in-place path, so re-running it would
strand the bundle the chain is appended to.
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
