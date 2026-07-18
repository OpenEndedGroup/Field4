package afield.four.fabricated

import afield.Box
import afield.documents
import afield.four.TexturePreviewCache
import afield.four.Renderer
import afield.four.modules.GltfSupport
import afield.four.modules.fromContext
import afield.four.texture.LoadedTexture
import afield.four.util.Window
import afield.logger.lprintln
import afield.stdlib.Vec3
import afield.thisSessionInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * Small native-render harness for docs and smoke tests. It renders deterministic
 * `StandardRenderer` scenes to PNGs and can turn those PNGs into HTML for display(...)
 * so MkDocs can capture them through the normal backdoor screenshot path.
 */
object StandardRendererDocsHarness {

    private const val WAIT_FRAMES = 90
    private const val CAPTURE_MAX_DIM = 2048
    private val DATA_IMAGE_RE = Regex("base64,([^']+)")

    suspend fun renderDefaultScene(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File = renderScene(outputPath, renderer, window) { bundle, activeRenderer ->
        bundle.scene.camera.at.set(0f, 0.25f, -3.35f)
        bundle.scene.camera.target.set(0f, -0.05f, 0f)

        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "left",
            model = Matrix4f().translate(-0.95f, 0f, 0f),
            material = material(color = Vector4f(0.17f, 0.98f, 0.24f, 1f)),
            slices = 48,
            stacks = 48
        )
        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "middle",
            model = Matrix4f(),
            material = material(color = Vector4f(0.98f, 0.92f, 0.22f, 1f)),
            slices = 48,
            stacks = 48
        )
        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "right",
            model = Matrix4f().translate(0.95f, 0f, 0f),
            material = material(color = Vector4f(0.98f, 0.30f, 0.32f, 1f)),
            slices = 48,
            stacks = 48
        )

        bundle.present(renderer = activeRenderer)
    }

    /**
     * Renders the whole StackSL gallery (see [StackSLGalleryHarness]) inside a
     * single graphics context, writing every `stacksl-*.png` into
     * `public/docs/assets/stacksl/`, then copies the hero image to [outputPath]
     * so the docs fence can embed one representative capture. This is the
     * "one Field document, many renders" path: the entire gallery is produced
     * without restarting the JVM per screenshot.
     */
    suspend fun renderStackSLGallery(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        val assetsDir = File(System.getProperty("user.dir"), "public/docs/assets/stacksl")
        lprintln("info,doc", "stacksl gallery output dir: ${assetsDir.absolutePath}")
        val hero = StackSLGalleryHarness.renderGallery(assetsDir, renderer)
        val target = File(outputPath)
        target.parentFile?.mkdirs()
        target.delete()
        target.writeBytes(hero.readBytes())
        require(target.length() > 0L) { "stacksl gallery hero copy wrote an empty PNG" }
        return target
    }

    /**
     * Renders an mp4 per animated StackSL example (see
     * [StackSLGalleryHarness.renderAnimations]) into `public/docs/assets/stacksl/`,
     * then writes a still hero to [outputPath] for the docs fence to validate.
     * Same single-graphics-context path as [renderStackSLGallery].
     */
    suspend fun renderStackSLAnimations(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        val assetsDir = File(System.getProperty("user.dir"), "public/docs/assets/stacksl")
        val mp4s = StackSLGalleryHarness.renderAnimations(assetsDir, renderer)
        lprintln("info,doc", "stacksl animations wrote ${mp4s.size} mp4(s) to ${assetsDir.absolutePath}")
        val target = File(outputPath)
        target.parentFile?.mkdirs()
        target.delete()
        // The fence only needs a valid still for visual validation; reuse a gallery frame.
        val hero = File(assetsDir, "stacksl-${StackSLGalleryHarness.loadExamples().first().name}.png")
            .takeIf { it.exists() }
            ?: assetsDir.listFiles { f -> f.name.startsWith("stacksl-") && f.name.endsWith(".png") }?.firstOrNull()
            ?: error("stacksl animations: no gallery still available for the fence hero")
        target.writeBytes(hero.readBytes())
        require(target.length() > 0L) { "stacksl animations hero copy wrote an empty PNG" }
        return target
    }

    /**
     * Renders the whole RectSL gallery (see [RectSLGalleryHarness]) inside a single
     * graphics context, writing a representative still `rectsl-*.png` per program
     * into `public/docs/assets/rectsl/`, then copies the hero image to [outputPath].
     * The RectSL twin of [renderStackSLGallery].
     */
    suspend fun renderRectSLGallery(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        val assetsDir = File(System.getProperty("user.dir"), "public/docs/assets/rectsl")
        lprintln("info,doc", "rectsl gallery output dir: ${assetsDir.absolutePath}")
        val hero = RectSLGalleryHarness.renderGallery(assetsDir, renderer)
        val target = File(outputPath)
        target.parentFile?.mkdirs()
        target.delete()
        target.writeBytes(hero.readBytes())
        require(target.length() > 0L) { "rectsl gallery hero copy wrote an empty PNG" }
        return target
    }

    /**
     * Renders an mp4 per animated RectSL example (see
     * [RectSLGalleryHarness.renderAnimations]) into `public/docs/assets/rectsl/`,
     * then writes a still hero to [outputPath] for the docs fence to validate.
     * Same single-graphics-context path as [renderRectSLGallery].
     */
    suspend fun renderRectSLAnimations(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        val assetsDir = File(System.getProperty("user.dir"), "public/docs/assets/rectsl")
        val mp4s = RectSLGalleryHarness.renderAnimations(assetsDir, renderer)
        lprintln("info,doc", "rectsl animations wrote ${mp4s.size} mp4(s) to ${assetsDir.absolutePath}")
        val target = File(outputPath)
        target.parentFile?.mkdirs()
        target.delete()
        // The fence only needs a valid still for visual validation; reuse a gallery frame.
        val hero = File(assetsDir, "rectsl-${RectSLGalleryHarness.loadExamples().first().name}.png")
            .takeIf { it.exists() }
            ?: assetsDir.listFiles { f -> f.name.startsWith("rectsl-") && f.name.endsWith(".png") }?.firstOrNull()
            ?: error("rectsl animations: no gallery still available for the fence hero")
        target.writeBytes(hero.readBytes())
        require(target.length() > 0L) { "rectsl animations hero copy wrote an empty PNG" }
        return target
    }

    /**
     * Renders the "evolving a distribution" (#5) still — the host fits a curve to a
     * target and RectSL lays out the array from it (see
     * [RectSLGalleryHarness.renderFitToTarget]) — to `rectsl-fit-to-target.png`, then
     * copies it to [outputPath] for the docs fence to validate.
     */
    suspend fun renderRectSLFit(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument()
    ): File {
        val assetsDir = File(System.getProperty("user.dir"), "public/docs/assets/rectsl")
        val hero = RectSLGalleryHarness.renderFitToTarget(assetsDir, renderer)
        val target = File(outputPath)
        target.parentFile?.mkdirs()
        target.delete()
        target.writeBytes(hero.readBytes())
        require(target.length() > 0L) { "rectsl fit hero copy wrote an empty PNG" }
        return target
    }

    suspend fun renderMetalnessComparison(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File = renderScene(outputPath, renderer, window) { bundle, activeRenderer ->
        bundle.scene.camera.at.set(0f, 0.2f, -3.25f)
        bundle.scene.camera.target.set(0f, 0f, 0f)

        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "dielectric",
            model = Matrix4f().translate(-1.05f, 0f, 0f),
            material = material(
                color = Vector4f(0.78f, 0.80f, 0.84f, 1f),
                metallic = 0.0f,
                roughness = 0.16f
            ),
            slices = 56,
            stacks = 56
        )

        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "metal",
            model = Matrix4f().translate(1.05f, 0f, 0f),
            material = material(
                color = Vector4f(0.78f, 0.80f, 0.84f, 1f),
                metallic = 1.0f,
                roughness = 0.16f
            ),
            slices = 56,
            stacks = 56
        )

        bundle.present(renderer = activeRenderer)
    }

    suspend fun renderShadowComparison(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File {
        val bundle = StandardRenderer.build(renderer)
        configureGroundedTorusScene(bundle, renderer)
        bundle.scene.aoStrength = 0f
        bundle.scene.ambientColor.set(0.015f, 0.015f, 0.018f)
        bundle.scene.lightIntensity = 1.35f
        bundle.present(renderer = renderer)
        bundle.applyScenePlan()
        bundle.attachTo(renderer)

        val shadowOff = captureScene(tempPng("fabricate-shadow-off"), bundle, renderer) {
            bundle.scene.shadowStrength = 0f
        }
        val shadowOn = captureScene(tempPng("fabricate-shadow-on"), bundle, renderer) {
            bundle.scene.shadowStrength = 1f
        }
        return try {
            composeHorizontal(shadowOff, shadowOn, File(outputPath))
        } finally {
            shadowOff.delete()
            shadowOn.delete()
        }
    }

    suspend fun renderAoComparison(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File {
        val bundle = StandardRenderer.build(renderer)
        configureAoContactScene(bundle, renderer)
        bundle.present(renderer = renderer)
        bundle.applyScenePlan()
        bundle.attachTo(renderer)
        val originalAoStrength = bundle.scene.aoStrength

        val aoOff = captureScene(tempPng("fabricate-ao-off"), bundle, renderer) {
            bundle.scene.aoStrength = 0f
        }
        val aoOn = captureScene(tempPng("fabricate-ao-on"), bundle, renderer) {
            bundle.scene.aoStrength = originalAoStrength
        }
        return try {
            composeHorizontal(aoOff, aoOn, File(outputPath))
        } finally {
            aoOff.delete()
            aoOn.delete()
        }
    }

    suspend fun renderShinyToriCheckerboard(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File = renderScene(outputPath, renderer, window) { bundle, activeRenderer ->
        bundle.scene.camera.at.set(0f, 0.78f, -3.75f)
        bundle.scene.camera.target.set(0f, -0.25f, 0f)
        bundle.scene.lightDirection.set(Vector3f(-0.55f, -1.0f, -0.20f).normalize())
        bundle.scene.lightIntensity = 1.55f
        bundle.scene.shadowStrength = 1f
        bundle.scene.aoStrength = 1f
        bundle.scene.shadowExtent = 8.5f
        bundle.scene.shadowBias = 0.0022f

        addCheckerboardFloor(
            bundle = bundle,
            renderer = activeRenderer,
            tilesPerSide = 10,
            tileSize = 0.52f,
            y = -1.0f
        )

        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "torus-a",
            model = Matrix4f().translate(-1.25f, -0.25f, 0.6f).rotateY(0.35f),
            material = material(Vector4f(0.92f, 0.35f, 0.33f, 1f), metallic = 1.0f, roughness = 0.08f),
            slices = 72,
            stacks = 72
        )
        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "torus-b",
            model = Matrix4f().translate(0f, -0.16f, 0f).rotateX(0.24f).rotateY(-0.25f),
            material = material(Vector4f(0.28f, 0.86f, 0.96f, 1f), metallic = 1.0f, roughness = 0.07f),
            slices = 72,
            stacks = 72
        )
        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "torus-c",
            model = Matrix4f().translate(1.25f, -0.25f, -0.65f).rotateY(-0.45f),
            material = material(Vector4f(0.95f, 0.78f, 0.29f, 1f), metallic = 1.0f, roughness = 0.09f),
            slices = 72,
            stacks = 72
        )

        bundle.present(renderer = activeRenderer)
    }

    suspend fun renderShaderGeneratedCheckerboard(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File = renderScene(outputPath, renderer, window) { bundle, activeRenderer ->
        bundle.scene.camera.at.set(0f, 0.78f, -3.65f)
        bundle.scene.camera.target.set(0f, -0.2f, 0f)
        bundle.scene.lightDirection.set(Vector3f(-0.52f, -1.0f, -0.22f).normalize())
        bundle.scene.lightIntensity = 1.5f
        bundle.scene.shadowStrength = 1f
        bundle.scene.aoStrength = 1f
        bundle.scene.shadowExtent = 8.0f
        bundle.scene.shadowBias = 0.0022f

        val floor = registerCubeNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "shader-floor",
            model = Matrix4f()
                .translate(0f, -1.0f, 0f)
                .scale(3.2f, 0.02f, 3.2f),
            material = material(
                color = Vector4f(0.58f, 0.58f, 0.62f, 1f),
                metallic = 0.03f,
                roughness = 0.9f
            )
        )
        checker(
            floor,
            scale = 3.0f,
            color = Vector4f(0.20f, 0.20f, 0.22f, 1f),
            mix = 1f
        )

        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "shader-torus-left",
            model = Matrix4f().translate(-0.95f, -0.22f, 0.48f).rotateY(0.32f),
            material = material(Vector4f(0.90f, 0.39f, 0.33f, 1f), metallic = 1f, roughness = 0.1f),
            slices = 72,
            stacks = 72
        )
        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "shader-torus-right",
            model = Matrix4f().translate(0.95f, -0.22f, -0.52f).rotateY(-0.35f),
            material = material(Vector4f(0.30f, 0.84f, 0.95f, 1f), metallic = 1f, roughness = 0.08f),
            slices = 72,
            stacks = 72
        )

        bundle.present(renderer = activeRenderer)
    }

    suspend fun renderCheckerboardCameraDepthStudy(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File {
        val bundle = StandardRenderer.build(renderer)
        bundle.scene.lightDirection.set(Vector3f(-0.48f, -1.0f, -0.26f).normalize())
        bundle.scene.lightIntensity = 1.45f
        bundle.scene.shadowStrength = 1f
        bundle.scene.aoStrength = 1f
        bundle.scene.shadowExtent = 9.0f
        bundle.scene.shadowBias = 0.0024f
        addCheckerboardFloor(bundle, renderer, tilesPerSide = 12, tileSize = 0.48f, y = -1.05f)

        registerTorusNode(
            bundle = bundle,
            renderer = renderer,
            name = "near-torus",
            model = Matrix4f().translate(-1.0f, -0.15f, 1.0f).rotateY(0.35f),
            material = material(Vector4f(0.89f, 0.44f, 0.30f, 1f), metallic = 1f, roughness = 0.12f),
            slices = 72,
            stacks = 72
        )
        registerTorusNode(
            bundle = bundle,
            renderer = renderer,
            name = "mid-torus",
            model = Matrix4f().translate(0f, -0.12f, -0.1f).rotateY(-0.20f),
            material = material(Vector4f(0.34f, 0.82f, 0.95f, 1f), metallic = 1f, roughness = 0.10f),
            slices = 72,
            stacks = 72
        )
        registerTorusNode(
            bundle = bundle,
            renderer = renderer,
            name = "far-torus",
            model = Matrix4f().translate(1.0f, -0.08f, -1.35f).rotateY(0.45f),
            material = material(Vector4f(0.96f, 0.85f, 0.34f, 1f), metallic = 1f, roughness = 0.10f),
            slices = 72,
            stacks = 72
        )

        bundle.present(renderer = renderer)
        bundle.applyScenePlan()
        bundle.attachTo(renderer)

        val nearCamera = captureScene(tempPng("fabricate-depth-camera-near"), bundle, renderer) {
            bundle.scene.camera.at.set(-1.55f, 0.72f, -1.75f)
            bundle.scene.camera.target.set(-0.95f, -0.2f, 0.95f)
        }
        val farCamera = captureScene(tempPng("fabricate-depth-camera-far"), bundle, renderer) {
            bundle.scene.camera.at.set(1.45f, 0.78f, -3.35f)
            bundle.scene.camera.target.set(0.95f, -0.15f, -1.35f)
        }

        return try {
            composeHorizontal(nearCamera, farCamera, File(outputPath))
        } finally {
            nearCamera.delete()
            farCamera.delete()
        }
    }

    suspend fun renderBlendedTransparency(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File = renderScene(outputPath, renderer, window) { bundle, activeRenderer ->
        bundle.scene.camera.at.set(0f, 0.72f, -3.25f)
        bundle.scene.camera.target.set(0f, -0.18f, 0f)
        bundle.scene.lightDirection.set(Vector3f(-0.45f, -1.0f, -0.22f).normalize())
        bundle.scene.lightIntensity = 1.45f
        bundle.scene.shadowStrength = 1f
        bundle.scene.aoStrength = 1f
        bundle.scene.shadowExtent = 7.0f

        addCheckerboardFloor(bundle, activeRenderer, tilesPerSide = 8, tileSize = 0.55f, y = -1.02f)

        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "behind-glass",
            model = Matrix4f().translate(-0.75f, -0.18f, 0.35f).rotateY(0.30f),
            material = material(Vector4f(0.95f, 0.40f, 0.28f, 1f), metallic = 1f, roughness = 0.10f),
            slices = 72,
            stacks = 72
        )
        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "front-metal",
            model = Matrix4f().translate(0.78f, -0.12f, -0.40f).rotateY(-0.35f),
            material = material(Vector4f(0.28f, 0.85f, 0.95f, 1f), metallic = 1f, roughness = 0.08f),
            slices = 72,
            stacks = 72
        )

        transparentSphere(
            bundle = bundle,
            renderer = activeRenderer,
            name = "far-glass",
            model = Matrix4f().translate(-0.52f, -0.32f, 0.85f).scale(0.72f),
            color = Vector4f(0.45f, 0.70f, 1.0f, 0.30f)
        )
        transparentSphere(
            bundle = bundle,
            renderer = activeRenderer,
            name = "near-glass",
            model = Matrix4f().translate(0.42f, -0.28f, -0.38f).scale(0.66f),
            color = Vector4f(1.0f, 0.78f, 0.38f, 0.38f)
        )
        bundle.sortTransparentMeshes()
        bundle.present(renderer = activeRenderer)
    }

    suspend fun renderPostStackVignette(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File = renderScene(outputPath, renderer, window) { bundle, activeRenderer ->
        bundle.scene.camera.at.set(0.15f, 0.62f, -3.05f)
        bundle.scene.camera.target.set(0f, -0.18f, 0.05f)
        bundle.scene.lightDirection.set(Vector3f(-0.55f, -1.0f, -0.20f).normalize())
        bundle.scene.lightIntensity = 1.55f
        bundle.scene.shadowStrength = 1f
        bundle.scene.aoStrength = 1f
        addCheckerboardFloor(bundle, activeRenderer, tilesPerSide = 9, tileSize = 0.52f, y = -1.0f)

        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "post-left",
            model = Matrix4f().translate(-0.72f, -0.20f, 0.18f).rotateY(0.32f),
            material = material(Vector4f(0.96f, 0.28f, 0.68f, 1f), metallic = 1f, roughness = 0.09f),
            slices = 72,
            stacks = 72
        )
        registerTorusNode(
            bundle = bundle,
            renderer = activeRenderer,
            name = "post-right",
            model = Matrix4f().translate(0.72f, -0.16f, -0.32f).rotateY(-0.35f),
            material = material(Vector4f(0.18f, 0.92f, 0.70f, 1f), metallic = 1f, roughness = 0.08f),
            slices = 72,
            stacks = 72
        )

        bundle.addShaderEffect("vignette-warmth", vignetteWarmthFragment, renderer = activeRenderer)
        bundle.present(renderer = activeRenderer)
    }

    suspend fun renderToyCarGltf(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window,
        gltfPath: String = File("public/docs/assets/fabricate_demo/ToyCar.glb").absolutePath,
        environmentPath: String = File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath
    ): File {
        val environment = if (environmentPath.endsWith(".exr", ignoreCase = true)) {
            LoadedTexture.fromExrFile(
                environmentPath,
                renderer,
                requestedMipLevels = 0,
                prefilterEnvironment = true
            )
        } else {
            LoadedTexture.fromFile(
                environmentPath,
                renderer,
                requestedMipLevels = 0,
                generateMipmaps = true
            )
        }.also { texture ->
            texture.setDefaultSampler()
            texture.transferNow()
        }
        return renderScene(
            outputPath,
            renderer,
            window,
            StandardRenderer.Config(
                environmentTexture = environment,
                environmentStrength = 1.35f,
                environmentRotation = 0.42f,
                backgroundColor = srgbGray(0.095f)
            )
        ) { bundle, activeRenderer ->
            bundle.scene.projection.fov = -50.0 * PI / 180.0
            // Cheat the eye down a touch so we read the car more side-on than plan-view; this keeps
            // the softly-lit roof from dominating and matches the three.js reference framing.
            bundle.scene.camera.at.set(1.225f, 0.78f, -1.625f)
            bundle.scene.camera.target.set(0f, 0.32f, 0f)
            bundle.scene.lightDirection.set(Vector3f(-0.36f, -0.58f, 0.72f).normalize())
            bundle.scene.lightIntensity = 2.45f
            bundle.scene.ambientColor.set(0.055f, 0.055f, 0.06f)
            bundle.scene.shadowStrength = 0.34f
            bundle.scene.aoStrength = 0.18f
            bundle.scene.shadowExtent = 6.2f
            bundle.scene.shadowBias = 0.0028f

            val model = GltfSupport.loadGltfBasic(gltfPath, GltfSupport.GltfLoadConfig(loadTextures = true))
            val loaded = bundle.registerGltf(model, namePrefix = "toy-car", renderer = activeRenderer)
            val bounds = gltfWorldBounds(model)
            val scale = 4.45f / bounds.longestAxis.coerceAtLeast(0.0001f)
            val root = bundle.sceneGraph.node("toy-car-root") {
                transform.set(
                    Matrix4f()
                        .translate(0f, -0.18f, 0f)
                        .rotateY(PI.toFloat())
                        .scale(scale)
                        .translate(-bounds.center.x, -bounds.center.y, -bounds.center.z)
                )
            }
            model.meshes.zip(loaded.nodes).forEachIndexed { index, (instance, node) ->
                root.add(node)
                // Freeze the docs frame to the imported bind pose; the parent node owns screenshot fit.
                node.animationSamples = null
                node.renderable?.boundsRadius = 2.2f
                node.renderable?.material?.let { importedMaterial ->
                    when (instance.name) {
		                        "ToyCar" -> {
		                            importedMaterial.metallic = 1.0f
		                            importedMaterial.roughness = 1.0f
		                            importedMaterial.emissiveFactor.set(0f, 0f, 0f)
		                        }
                        "Fabric" -> {
                            importedMaterial.baseColor.set(0.42f, 0.42f, 0.42f, 1f)
                            importedMaterial.metallic = 0f
                            importedMaterial.roughness = 1.0f
                            importedMaterial.uvTransform.set(0f, 0f, 3f, 3f)
                            importedMaterial.receiveAo = false
                        }
                        "Glass" -> {
                            importedMaterial.baseColor.set(0.58f, 0.91f, 0.58f, 0.34f)
                            importedMaterial.metallic = 0f
                            importedMaterial.roughness = 0.0f
                            importedMaterial.alphaMode = FabricateAlphaMode.BLEND
                            importedMaterial.receiveShadow = false
                            importedMaterial.receiveAo = false
                        }
                    }
                    loaded.meshBindings.getOrNull(index)?.applyMaterial(importedMaterial)
                }
            }
            bundle.sceneGraph.updateWorldTransforms()
            bundle.present(renderer = activeRenderer)
        }
    }

    suspend fun renderMultiLightSkybox(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window,
        environmentPath: String = File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath
    ): File {
        val environment = LoadedTexture.fromExrFile(
            environmentPath,
            renderer,
            requestedMipLevels = 0,
            prefilterEnvironment = true
        ).also { texture ->
            texture.setDefaultSampler()
            texture.transferNow()
        }
        return renderScene(
            outputPath,
            renderer,
            window,
            StandardRenderer.Config(
                environmentTexture = environment,
                environmentStrength = 0.85f,
                environmentRotation = 0.35f,
                backgroundColor = srgbGray(0.06f)
            )
        ) { bundle, activeRenderer ->
            bundle.scene.camera.at.set(0f, 1.05f, -4.1f)
            bundle.scene.camera.target.set(0f, -0.15f, 0f)
            bundle.scene.aoStrength = 0.6f
            bundle.scene.shadowStrength = 0.7f
            bundle.scene.shadowExtent = 6.5f
            bundle.scene.shadowBias = 0.0024f

            // Show the loaded environment as the scene backdrop (Three.js scene.background = env).
            bundle.showEnvironmentBackground(true, intensity = 1.0f)

            addCheckerboardFloor(bundle, activeRenderer, tilesPerSide = 10, tileSize = 0.55f, y = -1.0f)

            registerTorusNode(
                bundle = bundle,
                renderer = activeRenderer,
                name = "ml-left",
                model = Matrix4f().translate(-1.35f, -0.2f, 0.5f).rotateY(0.35f),
                material = material(Vector4f(0.85f, 0.85f, 0.88f, 1f), metallic = 1f, roughness = 0.14f),
                slices = 72,
                stacks = 72
            )
            registerTorusNode(
                bundle = bundle,
                renderer = activeRenderer,
                name = "ml-mid",
                model = Matrix4f().translate(0f, -0.16f, 0f).rotateX(0.22f).rotateY(-0.24f),
                material = material(Vector4f(0.85f, 0.85f, 0.88f, 1f), metallic = 1f, roughness = 0.1f),
                slices = 72,
                stacks = 72
            )
            registerTorusNode(
                bundle = bundle,
                renderer = activeRenderer,
                name = "ml-right",
                model = Matrix4f().translate(1.35f, -0.2f, -0.5f).rotateY(-0.4f),
                material = material(Vector4f(0.85f, 0.85f, 0.88f, 1f), metallic = 1f, roughness = 0.18f),
                slices = 72,
                stacks = 72
            )

            // One shadow-casting key light plus three coloured point lights that pool on the floor.
            bundle.addLight(
                FabricateLight.directional(
                    direction = Vector3f(-0.4f, -1f, -0.3f),
                    color = Vector3f(1f, 0.97f, 0.9f),
                    intensity = 1.7f,
                    castShadow = true
                )
            )
            bundle.addLight(FabricateLight.point(Vector3f(-1.7f, 0.15f, -1.2f), color = Vector3f(1f, 0.22f, 0.25f), intensity = 10f, range = 5.5f))
            bundle.addLight(FabricateLight.point(Vector3f(1.7f, 0.1f, -1.0f), color = Vector3f(0.25f, 0.5f, 1f), intensity = 10f, range = 5.5f))
            bundle.addLight(FabricateLight.point(Vector3f(0f, 0.55f, 1.7f), color = Vector3f(0.32f, 1f, 0.45f), intensity = 9f, range = 5.5f))
            bundle.syncLights()

            bundle.present(renderer = activeRenderer)
        }
    }

    suspend fun renderToneMapBloom(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window,
        environmentPath: String = File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath
    ): File {
        val environment = LoadedTexture.fromExrFile(
            environmentPath,
            renderer,
            requestedMipLevels = 0,
            prefilterEnvironment = true
        ).also { texture ->
            texture.setDefaultSampler()
            texture.transferNow()
        }
        val bundle = StandardRenderer.build(
            renderer,
            StandardRenderer.Config(
                environmentTexture = environment,
                environmentStrength = 1.4f,
                environmentRotation = 0.3f,
                backgroundColor = srgbGray(0.05f)
            )
        )
        bundle.scene.camera.at.set(0f, 0.8f, -3.6f)
        bundle.scene.camera.target.set(0f, -0.15f, 0f)
        bundle.scene.lightIntensity = 6.0f
        bundle.scene.shadowStrength = 0.8f
        bundle.scene.aoStrength = 0.7f
        bundle.scene.shadowExtent = 6.0f
        bundle.scene.shadowBias = 0.0024f

        addCheckerboardFloor(bundle, renderer, tilesPerSide = 9, tileSize = 0.52f, y = -1.0f)
        registerTorusNode(
            bundle = bundle,
            renderer = renderer,
            name = "tb-left",
            model = Matrix4f().translate(-1.05f, -0.18f, 0.35f).rotateY(0.32f),
            material = material(Vector4f(0.95f, 0.35f, 0.30f, 1f), metallic = 1f, roughness = 0.08f),
            slices = 72,
            stacks = 72
        )
        registerTorusNode(
            bundle = bundle,
            renderer = renderer,
            name = "tb-right",
            model = Matrix4f().translate(1.05f, -0.16f, -0.35f).rotateY(-0.35f),
            material = material(Vector4f(0.30f, 0.70f, 0.98f, 1f), metallic = 1f, roughness = 0.07f),
            slices = 72,
            stacks = 72
        )

        bundle.present(renderer = renderer)
        bundle.applyScenePlan()
        bundle.attachTo(renderer)

        // Left: raw HDR (highlights clip to white). Right: bloom + ACES tone mapping.
        val raw = captureScene(tempPng("fabricate-tonemap-off"), bundle, renderer) {}
        bundle.addBloom(threshold = 1.0f, intensity = 0.85f, radius = 3.2f, renderer = renderer)
        bundle.setToneMapping(FabricateToneMap.ACES, exposure = 1.0f, renderer = renderer)
        bundle.attachTo(renderer)
        val graded = captureScene(tempPng("fabricate-tonemap-on"), bundle, renderer) {}

        return try {
            composeHorizontal(raw, graded, File(outputPath))
        } finally {
            raw.delete()
            graded.delete()
        }
    }

    fun htmlForImage(imageFile: File, caption: String? = null): String {
        val encoded = Base64.getEncoder().encodeToString(imageFile.readBytes())
        val captionHtml = caption?.takeIf { it.isNotBlank() }?.let {
            """<div style="margin-top:10px;color:#b8b8b8;font-size:13px;">$it</div>"""
        } ?: ""
        return """
            <div style="padding:16px;background:#111;border:1px solid #2f2f2f;">
                <img
                    src="data:image/png;base64,$encoded"
                    alt="StandardRenderer capture"
                    style="display:block;width:100%;height:auto;background:#111;"
                />
                $captionHtml
            </div>
        """.trimIndent()
    }

    private suspend fun renderScene(
        outputPath: String,
        renderer: Renderer,
        window: Window,
        config: StandardRenderer.Config = StandardRenderer.Config(),
        buildScene: (StandardRenderer.Bundle, Renderer) -> Unit
    ): File {
        val targetFile = File(outputPath)
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val bundle = StandardRenderer.build(renderer, config)
        buildScene(bundle, renderer)
        bundle.applyScenePlan()
        bundle.attachTo(renderer)

        return captureFinalImage(bundle, targetFile, renderer)
    }

    private suspend fun captureScene(
        targetFile: File,
        bundle: StandardRenderer.Bundle,
        renderer: Renderer,
        beforeCapture: () -> Unit
    ): File {
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) {
            targetFile.delete()
        }
        beforeCapture()
        bundle.applyScenePlan()
        return captureFinalImage(bundle, targetFile, renderer)
    }

    private suspend fun captureFinalImage(
        bundle: StandardRenderer.Bundle,
        targetFile: File,
        renderer: Renderer
    ): File {
        waitForRendererFrames(renderer)
        val output = bundle.postStack.output()
        val html = TexturePreviewCache.capture(
            renderer = renderer,
            vkImage = output.colorImageHandles[0],
            format = output.colorFormats[0],
            srcWidth = output.width,
            srcHeight = output.height,
            currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            maxDim = CAPTURE_MAX_DIM
        ) ?: error("could not read back StandardRenderer final image")
        val encoded = DATA_IMAGE_RE.find(html)?.groupValues?.get(1)
            ?: error("could not parse StandardRenderer final image data")
        targetFile.writeBytes(Base64.getDecoder().decode(encoded))
        require(targetFile.length() > 0L) { "StandardRenderer final image readback wrote an empty PNG" }
        return targetFile
    }

    private suspend fun waitForRendererFrames(renderer: Renderer) {
        val startFrame = renderer.frameCounter
        val targetFrame = startFrame + WAIT_FRAMES
        val deadline = System.nanoTime() + 15_000_000_000L
        var lastLoggedFrame = Long.MIN_VALUE
        while (renderer.frameCounter < targetFrame) {
            if (renderer.frameCounter != lastLoggedFrame) {
                lastLoggedFrame = renderer.frameCounter
                lprintln(
                    "info,doc",
                    " -- waiting for renderer texture capture frame ${renderer.frameCounter}/$targetFrame --"
                )
            }
            if (System.nanoTime() > deadline) {
                error("timed out waiting for renderer frames: start=$startFrame current=${renderer.frameCounter} target=$targetFrame")
            }
            delay(16)
            yield()
        }
    }

    private fun configureGroundedTorusScene(bundle: StandardRenderer.Bundle, renderer: Renderer) {
        bundle.scene.camera.at.set(0f, 0.62f, -3.35f)
        bundle.scene.camera.target.set(0f, -0.35f, 0f)
        bundle.scene.lightDirection.set(Vector3f(-0.42f, -1.0f, -0.25f).normalize())
        bundle.scene.shadowExtent = 5.5f
        bundle.scene.shadowBias = 0.0025f

        val floor = bundle.registerPullingMesh(name = "floor", maxVertices = 4096, renderer = renderer)
        val floorWrite = StandardShapes.disk(
            positions = floor.gBuffer.position,
            normals = floor.gBuffer.normal,
            radius = 3.2f,
            slices = 96,
            stacks = 1,
            config = StandardShapes.WriteConfig(append = false)
        )
        floor.setVertexCount(floorWrite.writtenVertices)
        bindFabricateMesh(
            bundle = bundle,
            name = "floor",
            mesh = floor,
            model = Matrix4f().translate(0f, -0.85f, 0f).rotateX((-PI * 0.5).toFloat()),
            material = material(
                color = Vector4f(0.74f, 0.74f, 0.78f, 1f),
                metallic = 0.0f,
                roughness = 0.92f
            ),
            boundsRadius = 3.2f
        )

        registerTorusNode(
            bundle = bundle,
            renderer = renderer,
            name = "torus",
            model = Matrix4f().translate(0f, -0.18f, 0f),
            material = material(
                color = Vector4f(0.83f, 0.58f, 0.28f, 1f),
                metallic = 0.18f,
                roughness = 0.36f
            ),
            slices = 64,
            stacks = 64
        )
    }

    /**
     * A tight cluster of bright matte spheres resting on the floor. AO in this renderer
     * only modulates the small ambient floor (direct light is never occluded), so it is
     * visible only where a surface is starved of direct light — a deep, shadowed crevice.
     * Touching spheres create exactly that: the contact valleys are self-shadowed and
     * ambient-dominated, so toggling AO swings them from a soft grey to a firm dark seam.
     * Bright, non-metallic, rough material keeps the ambient term (and thus the AO
     * difference) as large as the shader allows.
     */
    private fun configureAoContactScene(bundle: StandardRenderer.Bundle, renderer: Renderer) {
        bundle.scene.camera.at.set(0f, 1.45f, -3.05f)
        bundle.scene.camera.target.set(0f, -0.18f, 0.15f)
        // A steep, near-overhead key. AO here only modulates the small ambient term, so it
        // is visible only where direct light is scarce; a top-down key keeps the sphere
        // tops bright while leaving the contact seams direct-light-starved and thus
        // ambient- (hence AO-) dominated.
        bundle.scene.lightDirection.set(Vector3f(-0.16f, -1.0f, -0.12f).normalize())
        // Knock the key well down from the default 4.0 so the ambient floor is a large
        // share of the shading — otherwise the ~0.09 ambient (the only thing AO touches)
        // is swamped and AO on/off is indistinguishable.
        bundle.scene.lightIntensity = 1.4f
        // Max AO and a wider gather so the touching-sphere valleys occlude deeply.
        bundle.scene.aoStrength = 1.0f
        bundle.scene.aoRadius = 0.55f
        bundle.scene.shadowExtent = 5.5f
        bundle.scene.shadowBias = 0.0025f

        val floor = bundle.registerPullingMesh(name = "floor", maxVertices = 4096, renderer = renderer)
        val floorWrite = StandardShapes.disk(
            positions = floor.gBuffer.position,
            normals = floor.gBuffer.normal,
            radius = 3.4f,
            slices = 96,
            stacks = 1,
            config = StandardShapes.WriteConfig(append = false)
        )
        floor.setVertexCount(floorWrite.writtenVertices)
        bindFabricateMesh(
            bundle = bundle,
            name = "floor",
            mesh = floor,
            model = Matrix4f().translate(0f, -0.85f, 0f).rotateX((-PI * 0.5).toFloat()),
            material = material(color = Vector4f(0.78f, 0.78f, 0.82f, 1f), metallic = 0.0f, roughness = 0.94f),
            boundsRadius = 3.4f
        )

        // Bright matte pebbles. radius R, centers 2R apart so neighbours just touch and
        // leave a cusp-deep valley between them. Front row of three along X, back row of
        // two hex-nestled into the front gaps to close the valleys off from the sky.
        val r = 0.62f
        val cy = -0.85f + r // rest on the floor
        val ballMaterial = material(color = Vector4f(0.86f, 0.86f, 0.89f, 1f), metallic = 0.0f, roughness = 0.95f)
        val backZ = -0.35f + 2f * r * 0.866f
        val centers = listOf(
            Triple(-2f * r, cy, -0.35f),
            Triple(0f, cy, -0.35f),
            Triple(2f * r, cy, -0.35f),
            Triple(-r, cy, backZ),
            Triple(r, cy, backZ)
        )
        centers.forEachIndexed { index, (x, y, z) ->
            registerSphereNode(
                bundle = bundle,
                renderer = renderer,
                name = "ao-ball-$index",
                center = Vector3f(x, y, z),
                radius = r,
                material = ballMaterial
            )
        }
    }

    private fun registerSphereNode(
        bundle: StandardRenderer.Bundle,
        renderer: Renderer,
        name: String,
        center: Vector3f,
        radius: Float,
        material: FabricateMaterial,
        slices: Int = 44,
        stacks: Int = 44
    ): StandardRenderer.Bundle.PullingMeshPair {
        val mesh = bundle.registerPullingMesh(name = name, maxVertices = slices * stacks * 6 + 256, renderer = renderer)
        val write = StandardShapes.sphere(
            positions = mesh.gBuffer.position,
            normals = mesh.gBuffer.normal,
            radius = radius,
            slices = slices,
            stacks = stacks,
            config = StandardShapes.WriteConfig(append = false)
        )
        mesh.setVertexCount(write.writtenVertices)
        return bindFabricateMesh(
            bundle = bundle,
            name = name,
            mesh = mesh,
            model = Matrix4f().translate(center.x, center.y, center.z),
            material = material,
            boundsRadius = radius * 1.05f
        )
    }

    private fun addCheckerboardFloor(
        bundle: StandardRenderer.Bundle,
        renderer: Renderer,
        tilesPerSide: Int,
        tileSize: Float,
        y: Float
    ) {
        val verticesPerCube = 36
        val tileGridStep = 2f // pre-scale spacing; model scale (tileSize * 0.5f) maps this to tileSize world spacing
        val tileCount = tilesPerSide * tilesPerSide
        val gridHalfSpan = (tilesPerSide - 1).toFloat()
        val model = Matrix4f()
            .translate(0f, y, 0f)
            .scale(tileSize * 0.5f, 0.02f, tileSize * 0.5f)

        fun createParityMesh(
            name: String,
            parity: Int,
            material: FabricateMaterial
        ): StandardRenderer.Bundle.PullingMeshPair {
            val tilesInParity = (0 until tileCount).count { index ->
                val row = index / tilesPerSide
                val col = index % tilesPerSide
                (row + col) % 2 == parity
            }
            val mesh = bundle.registerPullingMesh(
                name = name,
                maxVertices = tilesInParity * verticesPerCube,
                renderer = renderer
            )
            var append = false
            var vertexCount = 0
            for (row in 0 until tilesPerSide) {
                for (col in 0 until tilesPerSide) {
                    if ((row + col) % 2 != parity) continue
                    val write = StandardShapes.cube(
                        positions = mesh.gBuffer.position,
                        normals = mesh.gBuffer.normal,
                        size = 1f,
                        center = Vec3(
                            -gridHalfSpan + col * tileGridStep,
                            0f,
                            -gridHalfSpan + row * tileGridStep
                        ),
                        config = StandardShapes.WriteConfig(append = append)
                    )
                    append = true
                    vertexCount += write.writtenVertices
                }
            }
            mesh.setVertexCount(vertexCount)
            return bindFabricateMesh(
                bundle = bundle,
                name = name,
                mesh = mesh,
                model = Matrix4f(model),
                material = material,
                boundsRadius = tileSize * tilesPerSide * 0.75f
            )
        }

        createParityMesh(
            name = "checkerboard-light",
            parity = 0,
            material = material(
                color = Vector4f(0.57f, 0.57f, 0.61f, 1f),
                metallic = 0.035f,
                roughness = 0.88f
            )
        )
        createParityMesh(
            name = "checkerboard-dark",
            parity = 1,
            material = material(
                color = Vector4f(0.22f, 0.22f, 0.24f, 1f),
                metallic = 0.035f,
                roughness = 0.92f
            )
        )
    }

    private fun material(
        color: Vector4f,
        metallic: Float = 0.08f,
        roughness: Float = 0.42f
    ): FabricateMaterial =
        FabricateMaterial(baseColor = Vector4f(color), metallic = metallic, roughness = roughness)

    private fun srgbGray(value: Float): Vector3f {
        val linear = if (value <= 0.04045f) {
            value / 12.92f
        } else {
            Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return Vector3f(linear, linear, linear)
    }

    private fun registerTorusNode(
        bundle: StandardRenderer.Bundle,
        renderer: Renderer,
        name: String,
        model: Matrix4f,
        material: FabricateMaterial,
        slices: Int,
        stacks: Int,
        tubeRadius: Float = 0.25f,
        majorRadius: Float = 1f
    ): StandardRenderer.Bundle.PullingMeshPair {
        val mesh = bundle.registerTestTorus(
            name = name,
            slices = slices,
            stacks = stacks,
            tubeRadius = tubeRadius,
            majorRadius = majorRadius,
            renderer = renderer
        )
        return bindFabricateMesh(
            bundle = bundle,
            name = name,
            mesh = mesh,
            model = model,
            material = material,
            boundsRadius = majorRadius + tubeRadius
        )
    }

    private fun registerCubeNode(
        bundle: StandardRenderer.Bundle,
        renderer: Renderer,
        name: String,
        model: Matrix4f,
        material: FabricateMaterial,
        size: Float = 1f
    ): StandardRenderer.Bundle.PullingMeshPair {
        val mesh = bundle.registerCube(name = name, size = size, renderer = renderer)
        return bindFabricateMesh(
            bundle = bundle,
            name = name,
            mesh = mesh,
            model = model,
            material = material,
            boundsRadius = size * 0.9f
        )
    }

    private data class GltfBounds(
        val min: Vector3f,
        val max: Vector3f
    ) {
        val center: Vector3f
            get() = Vector3f(min).add(max).mul(0.5f)
        val longestAxis: Float
            get() = max(max.x - min.x, max(max.y - min.y, max.z - min.z))
    }

    private fun gltfWorldBounds(model: GltfSupport.GltfModel): GltfBounds {
        val minPoint = Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val maxPoint = Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val point = Vector3f()
        model.meshes.forEach { instance ->
            val matrix = instance.bindPose.toMatrix()
            val floats = instance.buffers.vertexData.asFloatBuffer()
            for (i in 0 until instance.buffers.vertexCount) {
                val base = i * 12
                point.set(floats.get(base), floats.get(base + 1), floats.get(base + 2))
                matrix.transformPosition(point)
                minPoint.set(min(minPoint.x, point.x), min(minPoint.y, point.y), min(minPoint.z, point.z))
                maxPoint.set(max(maxPoint.x, point.x), max(maxPoint.y, point.y), max(maxPoint.z, point.z))
            }
        }
        require(minPoint.x.isFinite() && maxPoint.x.isFinite()) { "GLTF model contained no vertices" }
        return GltfBounds(minPoint, maxPoint)
    }

    private fun bindFabricateMesh(
        bundle: StandardRenderer.Bundle,
        name: String,
        mesh: StandardRenderer.Bundle.PullingMeshPair,
        model: Matrix4f,
        material: FabricateMaterial,
        boundsRadius: Float = 1f
    ): StandardRenderer.Bundle.PullingMeshPair {
        val node = bundle.sceneGraph.mesh(name = name, material = material) {
            transform.set(model)
            renderable?.userData = name
            renderable?.boundsRadius = boundsRadius
        }
        mesh.bindNode(node)
        mesh.applyFabricateMaterial(material)
        return mesh
    }

    private fun checker(
        mesh: StandardRenderer.Bundle.PullingMeshPair,
        scale: Float,
        color: Vector4f,
        mix: Float = 1f
    ) {
        mesh.gBuffer.mesh.push["checkerScale"] = scale
        mesh.gBuffer.mesh.push["checkerColor"] = color
        mesh.gBuffer.mesh.push["checkerMix"] = mix
    }

    private fun transparentSphere(
        bundle: StandardRenderer.Bundle,
        renderer: Renderer,
        name: String,
        model: Matrix4f,
        color: Vector4f
    ) {
        val node = bundle.sceneGraph.mesh(
            name = name,
            material = FabricateMaterial(baseColor = Vector4f(color)).transparent(color.w)
        ) {
            transform.set(model)
            renderable?.userData = name
        }
        bundle.sceneGraph.updateWorldTransforms()

        val mesh = bundle.registerTransparentPullingMesh(name = name, maxVertices = 32 * 32 * 6, renderer = renderer)
        val write = StandardShapes.sphere(
            positions = mesh.position,
            normals = mesh.normal,
            radius = 1f,
            slices = 32,
            stacks = 32,
            config = StandardShapes.WriteConfig(append = false)
        )
        mesh.setVertexCount(write.writtenVertices)
        mesh.bindModel { Matrix4f(node.worldMatrix) }
        mesh.applyFabricateMaterial(node.renderable?.material ?: FabricateMaterial(baseColor = Vector4f(color)).transparent(color.w))
    }

    private fun composeHorizontal(left: File, right: File, output: File): File {
        val leftImage = ImageIO.read(left)
        val rightImage = ImageIO.read(right)
        val width = leftImage.width + rightImage.width
        val height = max(leftImage.height, rightImage.height)
        val combined = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = combined.createGraphics()
        try {
            graphics.drawImage(leftImage, 0, 0, null)
            graphics.drawImage(rightImage, leftImage.width, 0, null)
        } finally {
            graphics.dispose()
        }
        output.parentFile?.mkdirs()
        ImageIO.write(combined, "png", output)
        return output
    }

    private fun rendererFromContextOrDocument(): Renderer {
        runCatching { fromContext<Renderer>() }.getOrNull()?.let { return it }

        runCatching { Box.context.get() }.getOrNull()?.let { start ->
            rendererNear(start)?.let { return it }
        }

        documents.values.forEach { session ->
            session.boxes["agent_graphics_context"]?.let { rendererIn(it) }?.let { return it }
            session.boxes.values.firstOrNull { it.name == "renderer" }?.let { rendererIn(it) }?.let { return it }
        }

        runCatching { thisSessionInfo.get() }.getOrNull()?.let { session ->
            session.boxes.values.forEach { box ->
                rendererIn(box)?.let { return it }
            }
            rendererIn(session.root)?.let { return it }
        }

        documents.values.forEach { session ->
            session.boxes.values.forEach { box ->
                rendererIn(box)?.let { return it }
            }
            rendererIn(session.root)?.let { return it }
        }

        throw IllegalArgumentException("must pass in a value for context parameter ${Renderer::class.java.name}")
    }

    private fun rendererNear(start: Box): Renderer? {
        val seen = mutableSetOf<Box>()
        val queue = ArrayDeque<Box>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val box = queue.removeFirst()
            if (!seen.add(box)) continue
            rendererIn(box)?.let { return it }
            box.parents.forEach { queue.add(it) }
            box.children.forEach { queue.add(it) }
        }
        return null
    }

    private fun rendererIn(box: Box): Renderer? {
        (box.getPropertyRaw("renderer") as? Renderer)?.let { return it }
        box.properties.values.firstOrNull { it is Renderer }?.let { return it as Renderer }
        return box.ki.context.vars.values.firstOrNull { it.value is Renderer }?.value as? Renderer
    }

    private fun tempPng(prefix: String): File = Files.createTempFile(prefix, ".png").toFile()

    private val vignetteWarmthFragment = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(location = 0) out vec4 outColor;

        void main() {
            vec4 c = texture(sourceTex, vUv);
            vec2 p = vUv * 2.0 - 1.0;
            float vignette = 1.0 - smoothstep(0.18, 1.05, dot(p, p));
            vec3 warm = c.rgb * vec3(1.18, 1.04, 0.84);
            vec3 lifted = pow(max(warm, vec3(0.0)), vec3(0.88));
            vec3 graded = mix(c.rgb, lifted, 0.72) * mix(0.38, 1.22, vignette);
            outColor = vec4(graded, c.a);
        }
    """.trimIndent()
}
