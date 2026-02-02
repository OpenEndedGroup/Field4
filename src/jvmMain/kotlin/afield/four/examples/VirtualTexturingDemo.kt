package afield.four.examples

import afield.four.Renderer
import afield.four.modules.Mesh
import afield.four.modules.ModulePointer
import afield.four.targets.RenderTarget
import afield.four.texture.VirtualTextureSystem
import afield.four.util.Projection
import afield.four.util.Window
import org.joml.Matrix4f
import org.joml.Vector3f
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class VirtualTexturingDemo {
    private val window = Window("Field")
    private val renderer = Renderer(window)

    private val renderTarget = RenderTarget(renderer)
    private val module: ModulePointer
    private val mesh: Mesh

    private val vt: VirtualTextureSystem
    private val quads: MutableList<VirtualTextureSystem.VirtualQuadInstance>

    private val projection = Projection(window.width, window.height)
    private val view = Matrix4f()
    private val viewProj = Matrix4f()

    init {
        renderer.renderList = renderTarget

        val images = collectImages(Paths.get("/Users/marc/Movies/blade/scenes/allBlade/"))
        afield.logger.lprintln("info,vt", "VirtualTexturingDemo: found ${images.size} images")
        val source = object : VirtualTextureSystem.VirtualTextureSource {
            override fun resolvePath(virtualTexId: Int): Path? {
                if (images.isEmpty()) return null
                return images[virtualTexId % images.size]
            }
        }

        val config = VirtualTextureSystem.VirtualTextureConfig(
            virtualTexCount = maxOf(images.size, 1),
            maxInstances = 100_000,
            decodeThreads = 4,
            logEveryNFrames = 60,
            enableLogging = true,
            debugCheckDecoded = true,
            debugLogDecodedAnomalies = true
        )

        vt = VirtualTextureSystem(renderer, config, source)
        vt.setDebugFlags(showLodTint = true, showPlaceholder = true, showUv = true)

        module = vt.buildModule("virtual-texturing")
        renderTarget += "virtual-textures" to module

        mesh = Mesh(renderer, module)
        mesh.resize(4, 2)
        module += "quads" to mesh

        val positions = floatArrayOf(
            -0.5f, -0.5f, 0f,
            0.5f, -0.5f, 0f,
            0.5f, 0.5f, 0f,
            -0.5f, 0.5f, 0f
        )
        val uvs = floatArrayOf(
            0f, 0f,
            1f, 0f,
            1f, 1f,
            0f, 1f
        )
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)

        val posBuffer = mesh.v[0] ?: mesh.vn["inPos"]
        val uvBuffer = mesh.v[1] ?: mesh.vn["inUV"]
        if (posBuffer == null || uvBuffer == null) {
            throw IllegalStateException("Vertex inputs missing. Locations=${mesh.v.keys} names=${mesh.vn.keys}")
        }

        posBuffer.mapAndWrite {
            for (i in positions.indices) put(positions[i])
            rewind()
        }
        uvBuffer.mapAndWrite {
            for (i in uvs.indices) put(uvs[i])
            rewind()
        }
        mesh.t!!.mapAndWrite_ints {
            put(indices)
            rewind()
        }

        quads = buildQuadGrid(config.virtualTexCount)
    }

    private fun buildQuadGrid(texCount: Int): MutableList<VirtualTextureSystem.VirtualQuadInstance> {
        val list = mutableListOf<VirtualTextureSystem.VirtualQuadInstance>()
        val gridX = 80
        val gridY = 60
        val spacing = 1.1f
        val rnd = Random(1)
        var id = 0
        for (y in 0 until gridY) {
            for (x in 0 until gridX) {
                val tx = (x - gridX / 2) * spacing
                val ty = (y - gridY / 2) * spacing
                val tz = rnd.nextFloat() * 2f - 1f
                val model = Matrix4f().translate(tx, ty, tz).scale(1.0f)
                list.add(VirtualTextureSystem.VirtualQuadInstance(id % texCount, model))
                id++
            }
        }
        return list
    }

    private fun collectImages(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            val out = mutableListOf<Path>()
            stream.filter {
                val n = it.fileName.toString().lowercase()
                n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
            }.forEach { out.add(it) }
            out
        }
    }

    fun update(frame: Int) {
        projection.resize(window.width, window.height)
        val radius = 40f
        val angle = frame * 0.002f
        val cx = cos(angle) * radius
        val cy = sin(angle) * radius
        view.identity().lookAt(
            Vector3f(cx, cy, 30f),
            Vector3f(0f, 0f, 0f),
            Vector3f(0f, 1f, 0f)
        )
        viewProj.set(projection.projectionMatrix).mul(view)

        mesh.push["viewProj"] = viewProj
        vt.update(quads, viewProj, window.width, window.height)
        mesh.instanceCount = vt.lastInstanceCount
    }

    fun run() {
        var frame = 0
        while (!window.shouldClose()) {
            window.pollEvents()
            update(frame)
            renderer.render()
            frame++
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // Configuration.VULKAN_LIBRARY_NAME.set("/path/to/libvulkan")
            val demo = VirtualTexturingDemo()
            demo.run()
        }
    }
}
