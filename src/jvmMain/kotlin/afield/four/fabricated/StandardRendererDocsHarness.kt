package afield.four.fabricated

import afield.Box
import afield.documents
import afield.four.TexturePreviewCache
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.util.Window
import afield.logger.lprintln
import afield.stdlib.Vec3
import afield.thisSessionInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.max

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

        val left = bundle.registerTestTorus(name = "left", slices = 48, stacks = 48, renderer = activeRenderer)
        left.setModel(Matrix4f().translate(-0.95f, 0f, 0f))
        tint(left, color = Vector4f(0.17f, 0.98f, 0.24f, 1f))

        val middle = bundle.registerTestTorus(name = "middle", slices = 48, stacks = 48, renderer = activeRenderer)
        tint(middle, color = Vector4f(0.98f, 0.92f, 0.22f, 1f))

        val right = bundle.registerTestTorus(name = "right", slices = 48, stacks = 48, renderer = activeRenderer)
        right.setModel(Matrix4f().translate(0.95f, 0f, 0f))
        tint(right, color = Vector4f(0.98f, 0.30f, 0.32f, 1f))

        bundle.present(renderer = activeRenderer)
    }

    suspend fun renderMetalnessComparison(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File = renderScene(outputPath, renderer, window) { bundle, activeRenderer ->
        bundle.scene.camera.at.set(0f, 0.2f, -3.25f)
        bundle.scene.camera.target.set(0f, 0f, 0f)

        val left = bundle.registerTestTorus(name = "dielectric", slices = 56, stacks = 56, renderer = activeRenderer)
        left.setModel(Matrix4f().translate(-1.05f, 0f, 0f))
        tint(
            left,
            color = Vector4f(0.78f, 0.80f, 0.84f, 1f),
            metallic = 0.0f,
            roughness = 0.16f
        )

        val right = bundle.registerTestTorus(name = "metal", slices = 56, stacks = 56, renderer = activeRenderer)
        right.setModel(Matrix4f().translate(1.05f, 0f, 0f))
        tint(
            right,
            color = Vector4f(0.78f, 0.80f, 0.84f, 1f),
            metallic = 1.0f,
            roughness = 0.16f
        )

        bundle.present(renderer = activeRenderer)
    }

    suspend fun renderShadowComparison(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File {
        val bundle = StandardRenderer.build(renderer)
        bundle.attachTo(renderer)
        configureGroundedTorusScene(bundle, renderer)
        bundle.scene.aoStrength = 0f
        bundle.scene.ambientColor.set(0.015f, 0.015f, 0.018f)
        bundle.scene.lightIntensity = 1.35f
        bundle.present(renderer = renderer)

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
        bundle.attachTo(renderer)
        configureGroundedTorusScene(bundle, renderer)
        bundle.present(renderer = renderer)
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

        val torusA = bundle.registerTestTorus(name = "torus-a", slices = 72, stacks = 72, renderer = activeRenderer)
        torusA.setModel(Matrix4f().translate(-1.25f, -0.25f, 0.6f).rotateY(0.35f))
        tint(torusA, color = Vector4f(0.92f, 0.35f, 0.33f, 1f), metallic = 1.0f, roughness = 0.08f)

        val torusB = bundle.registerTestTorus(name = "torus-b", slices = 72, stacks = 72, renderer = activeRenderer)
        torusB.setModel(Matrix4f().translate(0f, -0.16f, 0f).rotateX(0.24f).rotateY(-0.25f))
        tint(torusB, color = Vector4f(0.28f, 0.86f, 0.96f, 1f), metallic = 1.0f, roughness = 0.07f)

        val torusC = bundle.registerTestTorus(name = "torus-c", slices = 72, stacks = 72, renderer = activeRenderer)
        torusC.setModel(Matrix4f().translate(1.25f, -0.25f, -0.65f).rotateY(-0.45f))
        tint(torusC, color = Vector4f(0.95f, 0.78f, 0.29f, 1f), metallic = 1.0f, roughness = 0.09f)

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

        val floor = bundle.registerCube(name = "shader-floor", size = 1f, renderer = activeRenderer)
        floor.setModel(
            Matrix4f()
                .translate(0f, -1.0f, 0f)
                .scale(3.2f, 0.02f, 3.2f)
        )
        tint(
            floor,
            color = Vector4f(0.58f, 0.58f, 0.62f, 1f),
            metallic = 0.03f,
            roughness = 0.9f
        )
        checker(
            floor,
            scale = 3.0f,
            color = Vector4f(0.20f, 0.20f, 0.22f, 1f),
            mix = 1f
        )

        val torusLeft = bundle.registerTestTorus(name = "shader-torus-left", slices = 72, stacks = 72, renderer = activeRenderer)
        torusLeft.setModel(Matrix4f().translate(-0.95f, -0.22f, 0.48f).rotateY(0.32f))
        tint(torusLeft, color = Vector4f(0.90f, 0.39f, 0.33f, 1f), metallic = 1f, roughness = 0.1f)

        val torusRight = bundle.registerTestTorus(name = "shader-torus-right", slices = 72, stacks = 72, renderer = activeRenderer)
        torusRight.setModel(Matrix4f().translate(0.95f, -0.22f, -0.52f).rotateY(-0.35f))
        tint(torusRight, color = Vector4f(0.30f, 0.84f, 0.95f, 1f), metallic = 1f, roughness = 0.08f)

        bundle.present(renderer = activeRenderer)
    }

    suspend fun renderCheckerboardCameraDepthStudy(
        outputPath: String,
        renderer: Renderer = rendererFromContextOrDocument(),
        window: Window = renderer.window
    ): File {
        val bundle = StandardRenderer.build(renderer)
        bundle.attachTo(renderer)
        bundle.scene.lightDirection.set(Vector3f(-0.48f, -1.0f, -0.26f).normalize())
        bundle.scene.lightIntensity = 1.45f
        bundle.scene.shadowStrength = 1f
        bundle.scene.aoStrength = 1f
        bundle.scene.shadowExtent = 9.0f
        bundle.scene.shadowBias = 0.0024f
        addCheckerboardFloor(bundle, renderer, tilesPerSide = 12, tileSize = 0.48f, y = -1.05f)

        val near = bundle.registerTestTorus(name = "near-torus", slices = 72, stacks = 72, renderer = renderer)
        near.setModel(Matrix4f().translate(-1.0f, -0.15f, 1.0f).rotateY(0.35f))
        tint(near, color = Vector4f(0.89f, 0.44f, 0.30f, 1f), metallic = 1f, roughness = 0.12f)

        val middle = bundle.registerTestTorus(name = "mid-torus", slices = 72, stacks = 72, renderer = renderer)
        middle.setModel(Matrix4f().translate(0f, -0.12f, -0.1f).rotateY(-0.20f))
        tint(middle, color = Vector4f(0.34f, 0.82f, 0.95f, 1f), metallic = 1f, roughness = 0.10f)

        val far = bundle.registerTestTorus(name = "far-torus", slices = 72, stacks = 72, renderer = renderer)
        far.setModel(Matrix4f().translate(1.0f, -0.08f, -1.35f).rotateY(0.45f))
        tint(far, color = Vector4f(0.96f, 0.85f, 0.34f, 1f), metallic = 1f, roughness = 0.10f)

        bundle.present(renderer = renderer)

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

        val backTorus = bundle.registerTestTorus(name = "behind-glass", slices = 72, stacks = 72, renderer = activeRenderer)
        backTorus.setModel(Matrix4f().translate(-0.75f, -0.18f, 0.35f).rotateY(0.30f))
        tint(backTorus, color = Vector4f(0.95f, 0.40f, 0.28f, 1f), metallic = 1f, roughness = 0.10f)

        val frontTorus = bundle.registerTestTorus(name = "front-metal", slices = 72, stacks = 72, renderer = activeRenderer)
        frontTorus.setModel(Matrix4f().translate(0.78f, -0.12f, -0.40f).rotateY(-0.35f))
        tint(frontTorus, color = Vector4f(0.28f, 0.85f, 0.95f, 1f), metallic = 1f, roughness = 0.08f)

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

        val left = bundle.registerTestTorus(name = "post-left", slices = 72, stacks = 72, renderer = activeRenderer)
        left.setModel(Matrix4f().translate(-0.72f, -0.20f, 0.18f).rotateY(0.32f))
        tint(left, color = Vector4f(0.96f, 0.28f, 0.68f, 1f), metallic = 1f, roughness = 0.09f)

        val right = bundle.registerTestTorus(name = "post-right", slices = 72, stacks = 72, renderer = activeRenderer)
        right.setModel(Matrix4f().translate(0.72f, -0.16f, -0.32f).rotateY(-0.35f))
        tint(right, color = Vector4f(0.18f, 0.92f, 0.70f, 1f), metallic = 1f, roughness = 0.08f)

        bundle.addShaderEffect("vignette-warmth", vignetteWarmthFragment, renderer = activeRenderer)
        bundle.present(renderer = activeRenderer)
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
        buildScene: (StandardRenderer.Bundle, Renderer) -> Unit
    ): File {
        val targetFile = File(outputPath)
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val bundle = StandardRenderer.build(renderer)
        buildScene(bundle, renderer)
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
        floor.setModel(Matrix4f().translate(0f, -0.85f, 0f).rotateX((-PI * 0.5).toFloat()))
        tint(
            floor,
            color = Vector4f(0.74f, 0.74f, 0.78f, 1f),
            metallic = 0.0f,
            roughness = 0.92f
        )

        val torus = bundle.registerTestTorus(name = "torus", slices = 64, stacks = 64, renderer = renderer)
        torus.setModel(Matrix4f().translate(0f, -0.18f, 0f))
        tint(
            torus,
            color = Vector4f(0.83f, 0.58f, 0.28f, 1f),
            metallic = 0.18f,
            roughness = 0.36f
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

        fun createParityMesh(name: String, parity: Int): StandardRenderer.Bundle.PullingMeshPair {
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
            mesh.setModel(model)
            return mesh
        }

        tint(
            createParityMesh(name = "checkerboard-light", parity = 0),
            color = Vector4f(0.57f, 0.57f, 0.61f, 1f),
            metallic = 0.035f,
            roughness = 0.88f
        )
        tint(
            createParityMesh(name = "checkerboard-dark", parity = 1),
            color = Vector4f(0.22f, 0.22f, 0.24f, 1f),
            metallic = 0.035f,
            roughness = 0.92f
        )
    }

    private fun tint(
        mesh: StandardRenderer.Bundle.PullingMeshPair,
        color: Vector4f,
        metallic: Float? = null,
        roughness: Float? = null
    ) {
        mesh.gBuffer.mesh.push["baseColor"] = Vector4f(color)
        metallic?.let { mesh.gBuffer.mesh.push["metallic"] = it }
        roughness?.let { mesh.gBuffer.mesh.push["roughness"] = it }
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
