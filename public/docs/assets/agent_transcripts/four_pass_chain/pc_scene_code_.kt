// `afield.four.fabricated` is not among a box's automatic imports, unlike `afield.four`
// itself, so these are needed. The `prop<>` type argument is written out in full on
// purpose: `prop<>` rewrites the box's code and the rewrite does not carry the snippet's
// own imports, so `prop<StandardRenderer.Bundle>(bundle)` under an
// `import afield.four.fabricated.StandardRenderer` fails with
// `Unresolved reference 'StandardRenderer'` reported against the *import* line. Every
// other reference in the box resolves through the import normally.
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f

prop<afield.four.fabricated.StandardRenderer.Bundle>(bundle)

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
