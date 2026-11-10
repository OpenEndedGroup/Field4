import afield.graphics.scene.fabricated.*
import afield.graphics.sky.SkyEnvironment

val sky = SkyEnvironment(renderer)
val bundle = StandardRenderer.build(
    renderer,
    StandardRenderer.Config(environmentTexture = sky.texture, environmentStrength = 1f),
)
bundle.showEnvironmentBackground(true)
bundle.scene.camera.at.set(0f, 1.2f, -6.5f)
bundle.scene.camera.target.set(0f, 0f, 0f)

fun torusNode(name: String, x: Float, color: Vector4f, metallic: Float, roughness: Float) {
    val material = FabricateMaterial(baseColor = color, metallic = metallic, roughness = roughness)
    val node = bundle.sceneGraph.mesh(name = name, material = material) {
        transform.set(Matrix4f().translate(x, 0f, 0f).rotateX(0.9f))
    }
    val mesh = bundle.registerTestTorus(name = name, slices = 48, stacks = 48, renderer = renderer)
    mesh.bindNode(node)
    mesh.applyFabricateMaterial(material)
}
torusNode("chrome", -2.3f, Vector4f(0.92f, 0.92f, 0.94f, 1f), 1f, 0.05f)
torusNode("white", 0f, Vector4f(0.85f, 0.85f, 0.85f, 1f), 0f, 0.6f)
torusNode("red", 2.3f, Vector4f(0.75f, 0.12f, 0.08f, 1f), 0f, 0.35f)

sky.setSun(elevation = 20.0, azimuth = 140.0, wait = true)
sky.drive(bundle)
bundle.applyScenePlan()
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

slider("Sun elevation (degrees)", min = -10.0, max = 90.0, initialValue = sky.sunElevation) { sky.setSun(it) }
slider("Sun azimuth (degrees)", min = -180.0, max = 180.0, initialValue = sky.sunAzimuth) {
    sky.setSun(sky.sunElevation, it)
}
println("sky environment ready")
