/**
 * The procedural [Atmosphere] as a live environment light: a baked equirect that the fabricate
 * renderer uses for image-based lighting and as its backdrop, and a sun key light coloured by the
 * atmosphere's transmittance. Move the sun and both follow. See "A sky that lights the scene" in
 * `public/docs/fabricate_renderer.md`.
 */
package afield.graphics.sky

import afield.document.context.fromContext
import afield.graphics.scene.fabricated.FabricateLight
import afield.graphics.scene.fabricated.FabricateLightType
import afield.graphics.scene.fabricated.StandardRenderer
import afield.graphics.texture.StreamingTexture
import afield.graphics.vulkan.Renderer
import afield.math.linalg.Vector3f
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT
import org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
import org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * An atmosphere baked into a [width] x [height] RGBA32F equirect [texture] for
 * `StandardRenderer.Config(environmentTexture = sky.texture)`.
 *
 * ```kotlin
 * val sky = SkyEnvironment(renderer)
 * val bundle = StandardRenderer.build(renderer, StandardRenderer.Config(environmentTexture = sky.texture, environmentStrength = 1f))
 * bundle.showEnvironmentBackground(true)
 * sky.drive(bundle)
 * sky.setSun(elevation = 8.0)   // degrees; re-bakes off the render thread, then relights
 * ```
 *
 * [setSun] bakes on a worker thread (latest request wins) and uploads the result on the renderer's
 * queue thread after the queue is idle, so the texture is never rewritten while a frame samples it.
 * The texture has one mip: rough surfaces sample it unblurred, which a sky (smooth everywhere except
 * the sun, which the bake leaves out) mostly gets away with.
 */
class SkyEnvironment(
    /** The renderer whose device holds the texture. */
    val renderer: Renderer = fromContext(),
    /** Equirect width in texels. */
    val width: Int = 128,
    /** Equirect height in texels. */
    val height: Int = 64,
    /** The atmosphere; Earth's by default. */
    val params: Atmosphere.Params = Atmosphere.Params(),
    /** Kilometres above the ground the scene sits. */
    var altitude: Double = 0.2,
    /** Multiplies the baked radiance, so a noon sky lands near 1 in the renderer's units. */
    var skyScale: Double = 0.2,
    /** Key light intensity when the sun is overhead; it falls with the transmittance's luminance. */
    var sunIntensity: Float = 3f,
) {
    /** Sun elevation in degrees above the horizon, as last requested. */
    @Volatile var sunElevation: Double = 30.0
        private set

    /** Sun azimuth in degrees about +y, as last requested; 0 puts the sun towards -z. */
    @Volatile var sunAzimuth: Double = 0.0
        private set

    private val source: ByteBuffer = MemoryUtil.memAlloc(width * height * 16)

    /** The environment texture; hand it to `StandardRenderer.Config.environmentTexture`. */
    val texture: StreamingTexture

    /** The last baked texels (linear RGBA, row 0 straight up), for reading back on the CPU. */
    @Volatile var texels: FloatArray = FloatArray(0)
        private set

    private val bundles = mutableListOf<StandardRenderer.Bundle>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sky-environment-bake").apply { isDaemon = true } }
    private val generation = AtomicLong()

    init {
        bakeInto(source)
        texture = StreamingTexture(source, width, height, renderer, VK_FORMAT_R32G32B32A32_SFLOAT, "sky_env_${width}x$height")
        texture.setDefaultSampler(addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT, addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
        texture.uploadNow()
    }

    /** The unit direction towards the sun, y up. */
    fun sunDirection(): Atmosphere.V3 = Atmosphere.direction(Math.toRadians(sunElevation), Math.toRadians(sunAzimuth))

    /** Direct sunlight colour (transmittance, unnormalised) for the current sun. */
    fun sunColor(): Atmosphere.V3 = Atmosphere.sunLight(sunDirection(), params, altitude)

    /** Keep [bundle]'s key light on the sun: its first directional light, added (shadowed) if it has none. */
    fun drive(bundle: StandardRenderer.Bundle): SkyEnvironment {
        synchronized(bundles) { if (bundle !in bundles) bundles += bundle }
        applyKeyLight(bundle)
        return this
    }

    /**
     * Move the sun to [elevation] / [azimuth] degrees. The key light of every [drive]n bundle moves at
     * once; the sky texture follows when the bake finishes (tens of milliseconds at 128 x 64). Pass
     * `wait = true` to return only after the new texture is uploaded.
     */
    fun setSun(elevation: Double, azimuth: Double = sunAzimuth, wait: Boolean = false) {
        sunElevation = elevation
        sunAzimuth = azimuth
        synchronized(bundles) { bundles.forEach { applyKeyLight(it) } }
        val ticket = generation.incrementAndGet()
        val job = worker.submit {
            if (generation.get() != ticket) return@submit
            val staging = MemoryUtil.memAlloc(width * height * 16)
            try {
                bakeInto(staging)
                if (generation.get() != ticket && !wait) return@submit
                renderer.runOnQueueThread {
                    renderer.graphQueue.waitIdle()
                    source.duplicate().put(staging.duplicate())
                    texture.uploadNow()
                }
            } finally {
                MemoryUtil.memFree(staging)
            }
        }
        if (wait) job.get()
    }

    private fun bakeInto(dst: ByteBuffer) {
        val baked = Atmosphere.bakeEquirect(width, height, sunDirection(), params, altitude, scale = skyScale)
        dst.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(baked)
        texels = baked
    }

    private fun applyKeyLight(bundle: StandardRenderer.Bundle) {
        val c = sunColor()
        val lum = 0.2126 * c.x + 0.7152 * c.y + 0.0722 * c.z
        val m = maxOf(c.x, c.y, c.z, 1e-6)
        val color = Vector3f((c.x / m).toFloat(), (c.y / m).toFloat(), (c.z / m).toFloat())
        val s = sunDirection()
        val toward = Vector3f(-s.x.toFloat(), -s.y.toFloat(), -s.z.toFloat())
        val lights = bundle.sceneGraph.lights
        val key = lights.firstOrNull { it.type == FabricateLightType.DIRECTIONAL && it.castShadow }
            ?: lights.firstOrNull { it.type == FabricateLightType.DIRECTIONAL }
            ?: bundle.addLight(FabricateLight.directional(toward, castShadow = true))
        key.direction = toward
        key.color = color
        key.intensity = (sunIntensity * lum).toFloat()
        bundle.syncLights()
    }
}
