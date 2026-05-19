package afield.four.fabricated

import afield.Box
import afield.four.TexturePreviewCache
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.util.Window
import afield.logger.lprintln
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
        bundle.scene.camera.at.set(0f, 0.2f, -4.1f)
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
        bundle.scene.camera.at.set(0f, 0.9f, -5.3f)
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
            bundle.scene.camera.at.set(-1.8f, 0.8f, -2.2f)
            bundle.scene.camera.target.set(-0.95f, -0.2f, 0.95f)
        }
        val farCamera = captureScene(tempPng("fabricate-depth-camera-far"), bundle, renderer) {
            bundle.scene.camera.at.set(1.8f, 0.9f, -4.8f)
            bundle.scene.camera.target.set(0.95f, -0.15f, -1.35f)
        }

        return try {
            composeHorizontal(nearCamera, farCamera, File(outputPath))
        } finally {
            nearCamera.delete()
            farCamera.delete()
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
        buildScene: (StandardRenderer.Bundle, Renderer) -> Unit
    ): File {
        val targetFile = File(outputPath)
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val bundle = StandardRenderer.build(renderer)
        bundle.attachTo(renderer)
        buildScene(bundle, renderer)

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
        val html = TexturePreviewCache.capture(
            renderer = renderer,
            vkImage = bundle.antiAlias.colorImageHandles[0],
            format = bundle.antiAlias.colorFormats[0],
            srcWidth = bundle.antiAlias.width,
            srcHeight = bundle.antiAlias.height,
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
        bundle.scene.camera.at.set(0f, 0.75f, -4.7f)
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
        val half = tilesPerSide / 2
        for (z in 0 until tilesPerSide) {
            for (x in 0 until tilesPerSide) {
                val mesh = bundle.registerCube(
                    name = "tile-$x-$z",
                    size = 1f,
                    renderer = renderer
                )
                val px = (x - half + 0.5f) * tileSize
                val pz = (z - half + 0.5f) * tileSize
                mesh.setModel(Matrix4f().translate(px, y, pz).scale(tileSize * 0.5f, 0.02f, tileSize * 0.5f))
                val bright = ((x + z) and 1) == 0
                tint(
                    mesh,
                    color = if (bright) Vector4f(0.76f, 0.76f, 0.80f, 1f) else Vector4f(0.18f, 0.18f, 0.22f, 1f),
                    metallic = if (bright) 0.05f else 0.02f,
                    roughness = if (bright) 0.84f else 0.92f
                )
            }
        }
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
        try {
            return fromContext()
        } catch (_: IllegalArgumentException) {
        }

        val start = Box.context.get()
        val root = start.root
        val seen = mutableSetOf<Box>()
        val queue = ArrayDeque<Box>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val box = queue.removeFirst()
            if (!seen.add(box)) continue
            box.properties.values.firstOrNull { it is Renderer }?.let { return it as Renderer }
            box.ki.context.vars.values.firstOrNull { it.value is Renderer }?.let { return it.value as Renderer }
            box.children.forEach { queue.add(it) }
        }
        throw IllegalArgumentException("must pass in a value for context parameter ${Renderer::class.java.name}")
    }

    private fun tempPng(prefix: String): File = Files.createTempFile(prefix, ".png").toFile()
}
