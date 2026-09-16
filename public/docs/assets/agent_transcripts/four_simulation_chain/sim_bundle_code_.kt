// `afield.four.fabricated` is not among a box's automatic imports, unlike `afield.four`
// itself. And the `prop<>` type argument is written out in full on purpose: `prop<>`
// rewrites the box's code and the rewrite does not carry the snippet's own imports, so
// `prop<StandardRenderer.Bundle>(bundle)` under an
// `import afield.four.fabricated.StandardRenderer` fails with
// `Unresolved reference 'StandardRenderer'` reported against the *import* line, while
// every other reference in the box resolves through it normally.
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.stdlib.linalg.Vector3f

prop<afield.four.fabricated.StandardRenderer.Bundle>(bundle)

val r: Renderer = fromContext()

once.bundle {
    val b = StandardRenderer.build(
        r,
        StandardRenderer.Config(
            renderWidth = 1024,
            renderHeight = 1024,
            backgroundColor = Vector3f(0.02f, 0.02f, 0.03f)
        )
    )
    b.applyScenePlan()
    b.present(renderer = r)
    b.attachTo(r)
    b
}
println("bundle ready: ${bundle.postStack.names()}")
