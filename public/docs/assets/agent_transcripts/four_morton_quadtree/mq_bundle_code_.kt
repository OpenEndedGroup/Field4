// `afield.four.fabricated` is not among a box's automatic imports, and the `prop<>` type
// argument is fully qualified because `prop<>`'s code rewrite does not carry the
// snippet's own imports.
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.stdlib.linalg.Vector3f

prop<afield.four.fabricated.StandardRenderer.Bundle>(bundle)

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
