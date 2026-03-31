package afield.four.fabricated

import afield.raiseAndScreenshot
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.util.Window
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
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
    private const val CAPTURE_TIMEOUT_MS = 60_000L

    suspend fun renderDefaultScene(
        outputPath: String,
        renderer: Renderer = fromContext(),
        window: Window = fromContext()
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
        renderer: Renderer = fromContext(),
        window: Window = fromContext()
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
        renderer: Renderer = fromContext(),
        window: Window = fromContext()
    ): File {
        val bundle = StandardRenderer.build(renderer)
        bundle.attachTo(renderer)
        configureGroundedTorusScene(bundle, renderer)
        bundle.scene.aoStrength = 0f
        bundle.scene.ambientColor.set(0.015f, 0.015f, 0.018f)
        bundle.scene.lightIntensity = 1.35f
        bundle.present(renderer = renderer)

        val shadowOff = captureScene(tempPng("fabricate-shadow-off"), window) {
            bundle.scene.shadowStrength = 0f
        }
        val shadowOn = captureScene(tempPng("fabricate-shadow-on"), window) {
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
        renderer: Renderer = fromContext(),
        window: Window = fromContext()
    ): File {
        val bundle = StandardRenderer.build(renderer)
        bundle.attachTo(renderer)
        configureGroundedTorusScene(bundle, renderer)
        bundle.present(renderer = renderer)
        val originalAoStrength = bundle.scene.aoStrength

        val aoOff = captureScene(tempPng("fabricate-ao-off"), window) {
            bundle.scene.aoStrength = 0f
        }
        val aoOn = captureScene(tempPng("fabricate-ao-on"), window) {
            bundle.scene.aoStrength = originalAoStrength
        }
        return try {
            composeHorizontal(aoOff, aoOn, File(outputPath))
        } finally {
            aoOff.delete()
            aoOn.delete()
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

        window.raiseAndScreenshot(WAIT_FRAMES, targetFile.absolutePath, captureTimeoutMs = CAPTURE_TIMEOUT_MS)
        return targetFile
    }

    private suspend fun captureScene(targetFile: File, window: Window, beforeCapture: () -> Unit): File {
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) {
            targetFile.delete()
        }
        beforeCapture()
        window.raiseAndScreenshot(WAIT_FRAMES, targetFile.absolutePath, captureTimeoutMs = CAPTURE_TIMEOUT_MS)
        return targetFile
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

    private fun tempPng(prefix: String): File = Files.createTempFile(prefix, ".png").toFile()
}
