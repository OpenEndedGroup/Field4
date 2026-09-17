// `afield.four.fabricated` is not among a box's automatic imports, and the `prop<>` type
// argument is written out in full on purpose: `prop<>` rewrites the box's code and the
// rewrite does not carry the snippet's own imports, so `prop<StandardRenderer.Bundle>`
// under an `import ...StandardRenderer` fails against the *import* line.
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f

prop<afield.four.fabricated.StandardRenderer.Bundle>(bundle)

// A `once`, unlike the chain box: `StandardRenderer.build` has no replace-in-place path,
// so re-running it would strand the bundle the chain is appended to.
once.bundle {
    val b = StandardRenderer.build(
        renderer,
        StandardRenderer.Config(
            renderWidth = 1024,
            renderHeight = 1024,
            backgroundColor = Vector3f(0.05f, 0.06f, 0.08f)
        )
    )
    b.scene.camera.at.set(0f, 1.4f, -4.2f)
    b.scene.camera.target.set(0f, 0f, 0f)
    b.scene.lightIntensity = 3.4f
    b.scene.aoStrength = 0.8f
    // The standard stack's own motion blur off: it is a second, different temporal effect
    // and it would sit upstream of everything the chain below is showing you.
    b.scene.motionBlurStrength = 0f
    // Silhouette edges, so there is something for a reprojection to be right or wrong about.
    for (ix in -2..2) for (iz in -2..2) {
        b.registerSphere(name = "ball-" + ix + "-" + iz, radius = 0.3f, slices = 24, stacks = 24)
            .setModel(Matrix4f().translate(ix * 0.85f, 0f, iz * 0.85f))
    }
    b.applyScenePlan()
    b.present(renderer = renderer)
    b.attachTo(renderer)
    b
}
println("bundle ready: " + bundle.postStack.names())
