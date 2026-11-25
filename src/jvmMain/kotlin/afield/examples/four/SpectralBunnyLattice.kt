package afield.examples.four

import afield.geometry.mesh.boundsAround
import afield.geometry.mesh.decimate
import afield.geometry.mesh.fillHoles
import afield.geometry.mesh.normalized
import afield.geometry.mesh.repaired
import afield.geometry.spectral.PlaneWaveField
import afield.geometry.spectral.PlaneWaveGrid
import afield.graphics.scene.meshFromAnyFile
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.GpuProbe
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.system.exitProcess
import kotlin.time.measureTime

/** Offline capture of the periodic zero set in the spectral geometry guide. */
object SpectralBunnyLattice {
    private const val cells = 64
    private const val period = 2.68f

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "Expected output.mp4, frame count, width, and height" }
        val output = File(args[0]).absoluteFile
        val frameCount = args[1].toInt()
        val width = args[2].toInt()
        val height = args[3].toInt()
        require(frameCount >= 2 && width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        val duration = measureTime {
            val field = samplePeriodicField()
            Options.enableVR = false
            val window = Window("Spectral Bunny lattice")
            val renderer = Renderer(window)
            try {
                renderFilm(renderer, window, field, frameCount, width, height, output)
            } finally {
                renderer.cleanup()
                window.cleanup()
            }
        }
        println("Bunny lattice film complete. ${frameCount} frames, elapsed $duration")
        exitProcess(0)
    }

    private fun samplePeriodicField(): FloatArray {
        val scan = meshFromAnyFile("public/docs/assets/spectral_geometry/bun_zipper.ply")
            .repaired().normalized(2.0).decimate(2400).repaired().fillHoles().mesh
        val bounds = boundsAround(radius = period / 2)
        val fit = PlaneWaveField.fitCartesian(bounds, intArrayOf(32, 32, 32), 850, PlaneWaveField.meshTarget(scan, 0.24))
        val grid = PlaneWaveGrid(bounds, cells)
        val data = FloatArray(cells * cells * cells * 2)
        val wave = 2 * PI / period
        for (z in 0 until cells) for (y in 0 until cells) for (x in 0 until cells) {
            val p = grid.points[(z * (cells + 1) + y) * (cells + 1) + x]
            val u = wave * p.x + 0.48
            val v = wave * p.y - 0.19
            val w = wave * p.z + 0.75
            val i = 2 * index(x, y, z)
            data[i] = fit.evaluate(p).toFloat()
            data[i + 1] = (0.22 * (sin(u) * cos(v) + sin(v) * cos(w) + sin(w) * cos(u)) - 0.025).toFloat()
        }
        println("Fitted ${fit.waves.size} Bunny waves on a periodic $cells³ render grid")
        return data
    }

    private fun index(x: Int, y: Int, z: Int): Int = ((z % cells) * cells + y % cells) * cells + x % cells

    /** Each trilinear partial derivative is a convex combination of the cell's four edge slopes. */
    private fun slopeBound(data: FloatArray, channel: Int): Vec3 {
        val bound = Vec3()
        for (z in 0 until cells) for (y in 0 until cells) for (x in 0 until cells) {
            val value = data[2 * index(x, y, z) + channel]
            bound.x = maxOf(bound.x, abs(data[2 * index(x + 1, y, z) + channel] - value))
            bound.y = maxOf(bound.y, abs(data[2 * index(x, y + 1, z) + channel] - value))
            bound.z = maxOf(bound.z, abs(data[2 * index(x, y, z + 1) + channel] - value))
        }
        return bound.mul(cells / period)
    }

    private fun renderFilm(renderer: Renderer, window: Window, field: FloatArray, frameCount: Int, width: Int, height: Int, output: File) {
        val source = checkNotNull(javaClass.getResource("/spectral/bunny_lattice.comp")).readText()
        val eye = Vec4()
        val forward = Vec4()
        val right = Vec4()
        val up = Vec4()
        val controls = Vec4(0f, period / 2, width.toFloat(), height.toFloat())
        val slope = Vec4()
        val bunnySlope = slopeBound(field, 0)
        val latticeSlope = slopeBound(field, 1)
        val pump = { window.pollEvents(); renderer.render(); Unit }
        GpuProbe(renderer, "bunny-lattice", pump).use { gpu ->
            val samples = gpu.fixture("field", field.size).apply { mapAndWrite { put(field) } }
            val image = gpu.fixture("image", width * height * 4)
            val stage = gpu.stage("march", source.replace("GRID_SIZE", cells.toString()))
            check(stage.valid) { gpu.verdictLines().joinToString("\n") }
            stage.dispatch((width + 7) / 8, (height + 7) / 8) {
                bind("field") { samples }
                bind("image") { image }
                bind("eye") { eye }
                bind("forward") { forward }
                bind("right") { right }
                bind("up") { up }
                bind("controls") { controls }
                bind("slope") { slope }
            }
            SpectralFilmEncoder(output, width, height).use { film ->
                repeat(frameCount) { index ->
                    val t = index.toDouble() / (frameCount - 1)
                    val morph = 0.94f * smooth(0.16, 0.76, t)
                    val pull = smooth(0.26, 0.90, t)
                    val radius = 4.13f + 10.47f * pull
                    val azimuth = -0.71 + 1.45 * t
                    val elevation = 0.09 + 0.33 * pull
                    val origin = Vec3(
                        (radius * cos(elevation) * sin(azimuth)).toFloat(),
                        (radius * sin(elevation)).toFloat(),
                        (radius * cos(elevation) * cos(azimuth)).toFloat(),
                    )
                    val look = Vec3(origin).negate().normalize()
                    val across = Vec3(look).cross(Vec3(0f, 1f, 0f)).normalize()
                    val vertical = Vec3(across).cross(look).normalize()
                    val halfFov = tan(0.28).toFloat()
                    eye.set(origin.x, origin.y, origin.z, 0f)
                    forward.set(look.x, look.y, look.z, 0f)
                    across.mul(halfFov * width / height)
                    vertical.mul(halfFov)
                    right.set(across.x, across.y, across.z, 0f)
                    up.set(vertical.x, vertical.y, vertical.z, 0f)
                    controls.x = morph
                    controls.y = period / 2 + 4.15f * smooth(0.35, 0.87, t)
                    val bound = Vec3(bunnySlope).lerp(latticeSlope, morph)
                    slope.set(bound.x, bound.y, bound.z, period)
                    gpu.arm(image)
                    while (gpu.stillWaiting()) pump()
                    film.write(gpu.read(image), 1.16f)
                    if ((index + 1) % 24 == 0 || index + 1 == frameCount) println("Bunny lattice frame ${index + 1}/$frameCount")
                }
            }
        }
    }

    private fun smooth(start: Double, end: Double, t: Double): Float {
        val p = ((t - start) / (end - start)).coerceIn(0.0, 1.0)
        return (p * p * (3.0 - 2.0 * p)).toFloat()
    }
}
