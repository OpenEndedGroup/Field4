package afield.examples.four

import afield.document.graph.Box
import afield.geometry.mesh.bounds
import afield.geometry.mesh.decimate
import afield.geometry.mesh.normalized
import afield.geometry.mesh.repaired
import afield.geometry.mesh.tubeMesh
import afield.geometry.spectral.PhaseClock
import afield.geometry.spectral.fitSurfaceDrawing
import afield.graphics.scene.fabricated.PathTraceEnvironment
import afield.graphics.scene.fabricated.PathTraceMaterial
import afield.graphics.scene.fabricated.PathTraceScene
import afield.graphics.scene.fabricated.PathTracingRenderer
import afield.graphics.scene.fabricated.importTriMesh
import afield.graphics.scene.meshFromAnyFile
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.util.Camera
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Matrix4f
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.media.reconstruction.OidnComputeDenoiser
import afield.media.reconstruction.ReconstructionJitter
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.system.exitProcess
import kotlin.time.measureTime

/** Offline capture for the drawing-machine film in the spectral geometry guide. */
object SpectralBunnyFilm {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "Expected output.mp4, frame count, width, and height" }
        val output = File(args[0]).absoluteFile
        val frameCount = args[1].toInt()
        val width = args[2].toInt()
        val height = args[3].toInt()
        require(frameCount >= 2 && width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        val duration = measureTime {
            Options.enableVR = false
            val window = Window("Spectral Bunny film")
            val renderer = Renderer(window)
            val box = Box("spectral-bunny-film")
            box.setPropertyRaw("renderer", renderer, doResync = false)
            try {
                Box.withBoxContextNow(box) { renderFilm(renderer, window, frameCount, width, height, output) }
            } finally {
                renderer.cleanup()
                window.cleanup()
            }
        }
        println("Bunny drawing film complete. ${frameCount} frames at 32 spp with OIDN, elapsed $duration")
        exitProcess(0)
    }

    private fun renderFilm(renderer: Renderer, window: Window, frameCount: Int, width: Int, height: Int, output: File) {
        val assets = "public/docs/assets"
        val bunny = meshFromAnyFile("$assets/spectral_geometry/bun_zipper.ply")
            .repaired().normalized(2.0).decimate(2400).repaired()
        val machine = bunny.fitSurfaceDrawing(targetCount = 72, seed = 11, maxDegree = 172.0).machine
        val steps = 2304
        val sides = 8
        val drawing = machine.trace(PhaseClock(doubleArrayOf(1.0)), 0.0, 2 * PI / steps, steps)
        val tube = tubeMesh(drawing + drawing.first(), radius = 0.015, sides = sides)
        check(tube.vertexCount == (steps + 1) * sides + 2)
        // Carry the stroke time to the intersection shader in the red vertex channel.
        val timedTube = tube.withColors(FloatArray(3 * tube.vertexCount) { i ->
            val vertex = i / 3
            when {
                i % 3 != 0 -> 1f
                vertex == tube.vertexCount - 2 -> 0f
                vertex == tube.vertexCount - 1 -> 1f
                else -> (vertex / sides).toFloat() / steps
            }
        })

        val scene = PathTraceScene()
        val filament = scene.material(PathTraceMaterial.metal(0.64f, 0.73f, 0.83f, 0.25f))
        scene.importTriMesh(timedTube, filament, transform = Matrix4f().translate(0f, -tube.bounds().min.y, 0f), vertexColors = true)
        val floor = scene.material(PathTraceMaterial.diffuse(0.020f, 0.024f, 0.029f))
        val horizon = 1200f
        scene.quad(
            Vec3(-horizon, -0.06f, -horizon), Vec3(-horizon, -0.06f, horizon),
            Vec3(horizon, -0.06f, horizon), Vec3(horizon, -0.06f, -horizon), floor,
        )
        val camera = Camera(renderer).apply {
            target.set(0f, 0.94f, 0f)
            p.fov = 0.52
        }
        val environment = PathTraceEnvironment.fromFile("$assets/fabricate_demo/venice_sunset_1k.exr")
        PathTracingRenderer(renderer, scene, environment, width, height, camera).use { tracer ->
            tracer.showEnvironment = false
            tracer.background = Vec4(0.008f, 0.009f, 0.011f, 1f)
            tracer.environmentRotation = 2.1f
            tracer.environmentIntensity = 1.65f
            tracer.exposure = 1.12f
            tracer.indirectClamp = 7f
            tracer.maxBounces = 4
            tracer.samplesPerFrame = 8
            installStrokeShader(tracer, filament)

            val frame = tracer.enableFrameOutput()
            val jitters = listOf(
                ReconstructionJitter(0.125f, 0.375f), ReconstructionJitter(-0.375f, 0.125f),
                ReconstructionJitter(0.375f, -0.125f), ReconstructionJitter(-0.125f, -0.375f),
            )
            OidnComputeDenoiser(tracer).use { denoiser ->
                denoiser.denoiseAt = 32
                denoiser.show = false
                // Record both passes in order on one queue. The denoiser sees this frame's sample count.
                val target = "bunny-film"
                renderer.renderList.preTargets[target] = Target { command, image ->
                    tracer.render(command, image)
                    denoiser.render(command, image)
                }
                try {
                    SpectralFilmEncoder(output, width, height).use { film ->
                        repeat(frameCount) { index ->
                            val t = index.toDouble() / (frameCount - 1)
                            val angle = -0.90 + 2.30 * t
                            camera.at.set((4.7 * sin(angle)).toFloat(), (2.25 + 0.30 * t).toFloat(), (4.7 * cos(angle)).toFloat())
                            tracer.reset("film frame")
                            for (jitter in jitters) {
                                frame.jitter = jitter
                                val end = renderer.frameCounter + 1
                                while (renderer.frameCounter < end) {
                                    window.pollEvents()
                                    renderer.render()
                                }
                            }
                            check(tracer.samples == 32 && denoiser.current && denoiser.denoisedSamples == 32) {
                                "Frame $index expected 32 samples. Tracer ${tracer.samples}, denoiser ${denoiser.denoisedSamples}"
                            }
                            film.write(denoiser.read(denoiser.denoised), tracer.exposure)
                            if ((index + 1) % 12 == 0 || index + 1 == frameCount) println("Bunny drawing frame ${index + 1}/$frameCount")
                        }
                    }
                } finally {
                    renderer.renderList.preTargets.remove(target)
                }
            }
        }
    }

    private fun installStrokeShader(tracer: PathTracingRenderer, filament: Int) {
        val source = tracer.integratorSource()
        val material = """
            float penProgress() {
                float azimuth = atan(frame.origin.x, frame.origin.z);
                return mix(0.028, 1.0, smoothstep(-0.90, 1.08, azimuth));
            }

            Material shadeMaterial(Material material, PathTraceSurface surface) {
                if (surface.materialId == $filament) {
                    float distanceToTip = (penProgress() - surface.color.r);
                    float recent = exp(-48.0 * distanceToTip);
                    float tip = exp(-1550.0 * distanceToTip) * (1.0 - smoothstep(0.987, 1.0, penProgress()));
                    material.base.rgb = mix(vec3(0.53, 0.69, 0.79), vec3(0.94, 0.46, 0.20), recent);
                    material.emission.rgb = vec3(4.2, 1.5, 0.45) * tip;
                }
                return material;
            }
        """.trimIndent()
        val cull = "if(m.coverage.w<0.5"
        check(source.contains(cull) && source.contains("// MATERIAL_END"))
        val updated = (source.substringBefore("// MATERIAL_BEGIN") + material + "\n" +
            source.substringAfter("// MATERIAL_END")).replaceFirst(cull,
            "if(int(t.a.w)==$filament && hitColor(t,bary).r > penProgress()) return false;\n    $cull")
        val reload = tracer.reloadIntegrator(updated)
        check(reload.ok) { reload.errors.joinToString() }
    }
}

internal class SpectralFilmEncoder(output: File, width: Int, height: Int) : AutoCloseable {
    private val rgb = ByteArray(width * height * 3)
    private val process = ProcessBuilder(
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", "${width}x$height", "-framerate", "24", "-i", "pipe:0",
        "-an", "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", output.path,
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val stream = process.outputStream.buffered()

    fun write(linear: FloatArray, exposure: Float) {
        check(linear.size * 3 == rgb.size * 4)
        for (i in rgb.indices) {
            val value = (linear[i / 3 * 4 + i % 3] * exposure).coerceAtLeast(0f)
            val mapped = (value / (1f + value)).toDouble()
            val srgb = if (mapped <= 0.0031308) 12.92 * mapped else 1.055 * mapped.pow(1.0 / 2.4) - 0.055
            rgb[i] = (srgb * 255).roundToInt().toByte()
        }
        stream.write(rgb)
    }

    override fun close() {
        stream.close()
        check(process.waitFor() == 0) { "FFmpeg could not finish the Bunny film" }
    }
}
