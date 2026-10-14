// `afield.graphics.scene.fabricated` is not among a box's automatic imports, unlike `afield.graphics.scene`.
import afield.graphics.scene.fabricated.StandardRenderer
import afield.graphics.scene.fabricated.applyScenePlan
import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector3f

prop<StandardRenderer.Bundle>(bundle)

val r: Renderer = fromContext()

once.bundle {
    val b = StandardRenderer.build(
        r,
        StandardRenderer.Config(
            renderWidth = 1024,
            renderHeight = 1024,
            backgroundColor = Vector3f(0.09f, 0.10f, 0.13f)
        )
    )
    b.scene.camera.at.set(0f, 1.5f, -4.2f)
    b.scene.camera.target.set(0f, 0f, 0f)
    b.scene.lightDirection.set(Vector3f(-0.5f, -1f, -0.3f).normalize())
    b.scene.lightIntensity = 3.4f
    b.scene.shadowStrength = 1f
    b.scene.aoStrength = 0.8f

    for (ix in -2..2) {
        for (iz in -2..2) {
            b.registerSphere(name = "ball-$ix-$iz", radius = 0.3f, slices = 24, stacks = 24, renderer = r)
                .setModel(Matrix4f().translate(ix * 0.85f, 0f, iz * 0.85f))
        }
    }
    b.applyScenePlan()
    b.present(renderer = r)
    b.attachTo(r)
    b
}
println("scene ready: ${bundle.postStack.names()}")
