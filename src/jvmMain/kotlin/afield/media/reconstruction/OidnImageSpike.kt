@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.media.reconstruction

import afield.document.graph.Box
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * Study: [OidnComputeDenoiser] on images that are not path traced (sRGB screenshots), through [OidnImageSource],
 * and whether it keeps a wrapping (tileable) image wrapping. Each image is denoised with one of two guide sets:
 *  - `flat`: albedo 1, normal +z — the network has nothing that marks detail as real;
 *  - `self`: the image itself as albedo, normal +z.
 * Tiling: an image treated as a torus should denoise the same whether or not it is rolled first. Each image is
 * denoised as is and rolled by half its size (the rolled result rolled back is the wrap-aware answer at the
 * original edges), both plain and with a [PAD]-pixel wrap-around pad cropped off afterwards.
 * Arguments: `<out dir> <image>... [--save-images]`. Results: dev_docs/lighting/OIDN_ON_PARTICLE_IMAGES.md.
 */
object OidnImageSpike {
    private const val PAD = 64
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6
    private fun srgb(x: Float): Float { val c = x.coerceIn(0f, 1f); return if (c <= .0031308f) c * 12.92f else 1.055f * c.toDouble().pow(1 / 2.4).toFloat() - .055f }

    /** An image source and its denoiser at one size. */
    private class Host(renderer: Renderer, width: Int, height: Int) : AutoCloseable {
        val image = OidnImageSource(renderer, width, height, "oidn-image-$width")
        val den = OidnComputeDenoiser(image)
        fun denoise(color: FloatArray, albedo: FloatArray?): Pair<FloatArray, Double> {
            image.upload(color); den.setGuides(albedo, null)
            return den.denoiseImage() to den.lastDenoiseMs
        }
        override fun close() { den.close(); image.close() }
    }

    /** [src] (w x h, vec4) sampled on a torus into a (w+2p) x (h+2p) image whose (p,p) pixel is src(rx,ry). */
    private fun wrap(src: FloatArray, w: Int, h: Int, p: Int, rx: Int, ry: Int): FloatArray {
        val dw = w + 2 * p; val dh = h + 2 * p; val out = FloatArray(dw * dh * 4)
        for (y in 0 until dh) { val sy = Math.floorMod(y - p + ry, h)
            for (x in 0 until dw) { val sx = Math.floorMod(x - p + rx, w); System.arraycopy(src, (sy * w + sx) * 4, out, (y * dw + x) * 4, 4) } }
        return out
    }

    @JvmStatic fun main(args: Array<String>) {
        val saveImages = "--save-images" in args
        val rest = args.filter { it != "--save-images" }
        val folder = File(rest[0]).also { it.mkdirs() }; val files = rest.drop(1).map { File(it) }
        val first = ImageIO.read(files[0]); val w = first.width; val h = first.height
        Options.enableVR = false; Options.enableHDR = false; Options.enableRayTracing = false; Options.validate = false; Options.doVulkanSDK()
        val window = Window("Field OIDN image spike"); val renderer = Renderer(window)
        val box = Box("oidn-image-spike"); box.setPropertyRaw("renderer", renderer, doResync = false)
        val report = JSONObject().put("width", w).put("height", h).put("pad", PAD)
            .put("gpu", renderer.device.physicalDevice.vkPhysicalDeviceProperties.deviceNameString())
        val results = JSONArray()
        try { Box.withBoxContextNow(box) {
            val c0 = System.nanoTime(); val plain = Host(renderer, w, h); val padded = Host(renderer, w + 2 * PAD, h + 2 * PAD)
            report.put("buildMs", ms(c0)).put("cooperativeMatrix", plain.den.cooperativeMatrix)
                .put("activationBytesPlain", plain.den.activationBytes).put("activationBytesPadded", padded.den.activationBytes)
            val n = w * h * 4
            fun display(a: FloatArray) = FloatArray(a.size) { srgb(a[it]) }
            fun crop(a: FloatArray) = FloatArray(n).also { o -> val pw = w + 2 * PAD; for (y in 0 until h) System.arraycopy(a, ((y + PAD) * pw + PAD) * 4, o, y * w * 4, w * 4) }
            fun unroll(a: FloatArray) = wrap(a, w, h, 0, -w / 2, -h / 2)
            /** Mean display |a-b| (RGB) by distance to the nearest image edge, d = 0 until [maxD]. */
            fun edgeProfile(a: FloatArray, b: FloatArray, maxD: Int = 256): DoubleArray {
                val s = DoubleArray(maxD); val c = IntArray(maxD)
                for (y in 0 until h) for (x in 0 until w) { val d = min(min(x, y), min(w - 1 - x, h - 1 - y)); if (d >= maxD) continue
                    val i = (y * w + x) * 4; s[d] += (abs(a[i] - b[i]) + abs(a[i + 1] - b[i + 1]) + abs(a[i + 2] - b[i + 2])) / 3.0; c[d]++ }
                return DoubleArray(maxD) { s[it] / c[it] }
            }
            fun meanDiff(a: FloatArray, b: FloatArray, lo: Int): Double { var s = 0.0; var k = 0
                for (y in lo until h - lo) for (x in lo until w - lo) { val i = (y * w + x) * 4; for (ch in 0..2) { s += abs(a[i + ch] - b[i + ch]); k++ } }; return s / k }
            fun lapRms(a: FloatArray, lo: Int = 128): Double { var s = 0.0; var k = 0
                fun lum(x: Int, y: Int) = ((y * w + x) * 4).let { .2126 * a[it] + .7152 * a[it + 1] + .0722 * a[it + 2] }
                for (y in lo until h - lo) for (x in lo until w - lo) { val l = 4 * lum(x, y) - lum(x - 1, y) - lum(x + 1, y) - lum(x, y - 1) - lum(x, y + 1); s += l * l; k++ }
                return sqrt(s / k) }
            fun mean(a: FloatArray, lo: Int = 128) = meanDiff(a, FloatArray(n), lo)
            fun save(name: String, a: FloatArray) = ReconstructionCapture.save(File(folder, name), a, w, h)
            fun saveDisplayDiff(name: String, a: FloatArray, b: FloatArray, gain: Float) {
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until h) for (x in 0 until w) { val i = (y * w + x) * 4
                    fun g(ch: Int) = (abs(a[i + ch] - b[i + ch]) * gain * 255).toInt().coerceIn(0, 255)
                    img.setRGB(x, y, (g(0) shl 16) or (g(1) shl 8) or g(2)) }
                ImageIO.write(img, "png", File(folder, name))
            }
            fun summarize(p: DoubleArray) = JSONObject().put("d0", p[0]).put("d8", p[8]).put("d32", p[32]).put("d64", p[64]).put("d128", p[128])
                .put("widthAboveHalfLevel", p.indexOfLast { it > .5 / 255 } + 1).put("profile", JSONArray(p.map { "%.5f".format(it).toDouble() }))
            for (f in files) {
                val im = ImageIO.read(f); check(im.width == w && im.height == h) { "${f.name} is ${im.width}x${im.height}, expected ${w}x$h" }
                val id = f.name.substringBefore('_')
                val src = OidnImageSource.linearPixels(im)
                val sd = display(src)
                val entry = JSONObject().put("image", f.name).put("meanDisplaySrc", mean(sd)).put("laplacianRmsSrc", lapRms(sd))
                for (mode in listOf("flat", "self")) {
                    val rolled = wrap(src, w, h, 0, w / 2, h / 2)
                    val (d, t0) = plain.denoise(src, if (mode == "flat") null else src)
                    val (dr, t1) = plain.denoise(rolled, if (mode == "flat") null else rolled)
                    val sp = wrap(src, w, h, PAD, 0, 0); val spr = wrap(src, w, h, PAD, w / 2, h / 2)
                    val (dp, t2) = padded.denoise(sp, if (mode == "flat") null else sp)
                    val (dpr, t3) = padded.denoise(spr, if (mode == "flat") null else spr)
                    val D = display(d); val R = display(unroll(dr)); val P = display(crop(dp)); val PR = display(unroll(crop(dpr)))
                    entry.put(mode, JSONObject()
                        .put("denoiseMs", JSONArray(listOf(t0, t1, t2, t3)))
                        .put("meanDisplay", mean(D)).put("laplacianRms", lapRms(D)).put("detailRetained", lapRms(D) / lapRms(sd))
                        .put("meanDisplayAbsChange", meanDiff(D, sd, 128))
                        .put("plainVsWrapAware", summarize(edgeProfile(D, R)))
                        .put("paddedVsPaddedRolled", summarize(edgeProfile(P, PR)))
                        .put("paddedVsWrapAware", summarize(edgeProfile(P, R)))
                        .put("interiorPaddedVsPlain", meanDiff(P, D, 256)))
                    // Full-size denoised PNGs are ~10 MB each at 4K; pass --save-images to keep them.
                    if (saveImages) { save("$id-$mode.png", d); if (mode == "flat") save("$id-flat-padded.png", crop(dp)) }
                    if (mode == "flat") { saveDisplayDiff("$id-flat-wrapdiff-x8.png", D, R, 8f); saveDisplayDiff("$id-flat-padded-wrapdiff-x8.png", P, R, 8f) }
                    else saveDisplayDiff("$id-self-wrapdiff-x8.png", D, R, 8f)
                    println("$id $mode: detail ${"%.3f".format(lapRms(D) / lapRms(sd))}, edge d0 plain ${"%.4f".format(edgeProfile(D, R)[0])} padded ${"%.4f".format(edgeProfile(P, R)[0])}, ms ${t0.toInt()}")
                }
                results.put(entry)
                File(folder, "results.json").writeText(report.put("results", results).toString(2))
            }
            plain.close(); padded.close()
        } } finally { renderer.cleanup(); window.cleanup() }
        File(folder, "results.json").writeText(report.put("results", results).toString(2))
        println("OIDN IMAGE SPIKE DONE"); exitProcess(0)
    }
}
