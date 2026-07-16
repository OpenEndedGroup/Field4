package afield.examples.four

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.modules.Mesh
import afield.four.modules.ModulePointer
import afield.four.modules.fromContext
import afield.four.texture.VirtualTextureSystem
import afield.four.util.Camera
import afield.four.util.Window
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.times
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random

class VirtualTexturingDemo(
    val window: Window = fromContext(),
    val renderer: Renderer = fromContext(),
    val camera: Camera = fromContext()
) {
//    private val window = Window("Field")
//    private val renderer = Renderer(window)

    val module: ModulePointer
    val mesh: Mesh

    val vt: VirtualTextureSystem
    val quads: MutableList<VirtualTextureSystem.VirtualQuadInstance>


    init {
        val renderTarget = renderer.renderList

        val images = collectImages(Paths.get("/Users/marc/Downloads/Blade Runner (1982) [2160p] [4K] [BluRay] [5.1] [YTS.MX]/blade/")).sorted()
        afield.logger.lprintln("info,vt", "VirtualTexturingDemo: found ${images.size} images")

        val source = object : VirtualTextureSystem.VirtualTextureSource {
            override fun resolvePath(virtualTexId: Int): Path? {
                if (images.isEmpty()) return null
                return images[virtualTexId % images.size]
            }
        }

        val config = VirtualTextureSystem.VirtualTextureConfig(
            virtualTexCount = maxOf(150*150, 1),
            maxInstances = 150*150,
            decodeThreads = 4,
            logEveryNFrames = 60,
            enableLogging = true,
            debugCheckDecoded = false,
            debugLogDecodedAnomalies = false,
            thumbnailCacheEnabled = true,
            thumbnailCacheFromBandIndex = 1,
            maxDecodeQueueSize = 4096/4,
            centerBoostEnabled = true,
            centerBoostRegionFraction = 0.5f,
            centerBoostPixelScale = 1.15f,
            centerBoostPriorityScale = 1.25f,
            fastCullingEnabled = true,
            fastCullingThreshold = 5000,
        )

        vt = VirtualTextureSystem(renderer, config, source)
        vt.setDebugFlags(showLodTint = true, showPlaceholder = false, showUv = false)

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

        renderTarget.preTargets["__virtualtext__"] = object : afield.four.Target {
            var f = 0
            override fun render(command: CommandBuffer, index: Int) {
                update(f++)
            }
        }
    }

    private fun buildQuadGrid(texCount: Int): MutableList<VirtualTextureSystem.VirtualQuadInstance> {
        val list = mutableListOf<VirtualTextureSystem.VirtualQuadInstance>()
        val gridX = 200
        val gridY = 200
        val spacing = 1.1f
        val rnd = Random(1)
        var id = 0
        for (y in 0 until gridY) {
            for (x in 0 until gridX) {
                val tx = (x - gridX / 2) * spacing
                val ty = (y - gridY / 2) * spacing
                val tz = 0f//rnd.nextFloat() * 2f - 1f
                val model = Matrix4f().translate(tx, ty, tz).scale(1.0f)
                list.add(VirtualTextureSystem.VirtualQuadInstance((x+y*gridX) % texCount, model))
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
        mesh.push["viewProj"] = camera.p.projectionMatrix * camera.modelView()
        vt.update(quads, camera.p.projectionMatrix * camera.modelView(), window.width, window.height)
        mesh.instanceCount = vt.lastInstanceCount
    }
}
