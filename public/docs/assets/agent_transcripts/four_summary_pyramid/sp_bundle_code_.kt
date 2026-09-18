// `afield.four.fabricated` is not among a box's automatic imports, unlike `afield.four`
// itself. And the `prop<>` type argument is written out in full on purpose: `prop<>`
// rewrites the box's code and the rewrite does not carry the snippet's own imports, so
// `prop<StandardRenderer.Bundle>(bundle)` under an
// `import afield.four.fabricated.StandardRenderer` fails with
// `Unresolved reference 'StandardRenderer'` reported against the *import* line.
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.stdlib.linalg.Vector3f

prop<afield.four.fabricated.StandardRenderer.Bundle>(bundle)

// `renderer` is the parent box's `prop<Renderer>`, visible here through the preamble —
// there is nothing to fetch with `fromContext()`.
//
// This one *is* a `once`, unlike the chain box below, and for a reason rather than out of
// habit: `StandardRenderer.build` has no replace-in-place path, so re-running it would
// strand the bundle the chain is appended to.
once.bundle {
    val b = StandardRenderer.build(
        renderer,
        StandardRenderer.Config(
            renderWidth = 1024,
            renderHeight = 1024,
            backgroundColor = Vector3f(0.02f, 0.02f, 0.03f)
        )
    )
    b.applyScenePlan()
    b.present(renderer = renderer)
    b.attachTo(renderer)
    b
}
println("bundle ready: ${bundle.postStack.names()}")
