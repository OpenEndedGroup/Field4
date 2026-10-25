// `afield.graphics.scene.fabricated` is not among a box's automatic imports, unlike `afield.graphics.scene`.
import afield.graphics.scene.fabricated.StandardRenderer
import afield.graphics.scene.fabricated.applyScenePlan

prop<StandardRenderer.Bundle>(bundle)

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
