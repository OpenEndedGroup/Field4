import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.util.Camera

prop<PathTracingRenderer>(tracer)
prop<Vector3f>(skin)
prop<Vector3f>(radius)
prop<List<Int>>(remapped)

// Linear-light skin colour: what the surface should look like after all the scattering.
skin = Vector3f(0.75f, 0.45f, 0.33f)
// How far each channel travels under the surface, in world units; red goes furthest.
radius = Vector3f(0.36f, 0.14f, 0.08f)

val scene = PathTraceScene()
val floor = scene.material(PathTraceMaterial.diffuse(0.18f, 0.18f, 0.18f))
scene.quad(Vector3f(-20f, 0f, -20f), Vector3f(-20f, 0f, 20f), Vector3f(20f, 0f, 20f), Vector3f(20f, 0f, -20f), floor)
val target = scene.material(PathTraceMaterial(skin.x, skin.y, skin.z, roughness = 0.3f))
scene.sphere(Vector3f(-4.4f, 0.95f, 0f), 0.95f, target, 64, 32)
remapped =
    AlbedoRemap.entries.mapIndexed { i, remap ->
        val m = scene.material(PathTraceMaterial.subsurface(skin, radius, remap))
        scene.sphere(Vector3f(-2.2f + 2.2f * i, 0.95f, 0f), 0.95f, m, 64, 32)
        m
    }
val camera = Camera(renderer).also {
    it.at.set(0f, 2.4f, 10.5f)
    it.target.set(0f, 0.9f, 0f)
    it.p.fov = 0.6
}
tracer = PathTracingRenderer(
    renderer,
    scene,
    PathTraceEnvironment.fromFile("public/docs/assets/fabricate_demo/venice_sunset_1k.exr"),
    width = 1280,
    height = 400,
    camera = camera,
    name = "subsurface-remaps",
).install()
tracer.maxBounces = 10
tracer.samplesPerFrame = 2
tracer.exposure = 1.4f
// Long red walks occasionally return with a large weight; clamp them as specks, not energy.
tracer.indirectClamp = 1f
tracer.blitToScreen()
println("left to right: diffuse target, ${AlbedoRemap.entries.joinToString()}")
frames {
    while (tracer.samples < 1024) frame()
    println("subsurface study converged: ${tracer.samples} samples")
}
