import afield.io.syphon.*
import afield.graphics.render.TexturePreviewCache
import org.lwjgl.vulkan.VK10.*
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

check(Syphon.isAvailable) { Syphon.describe() }
val probePath = SyphonLibrary.probePath ?: throw IllegalStateException("field_syphon_probe is missing: ./gradlew buildSyphonNative")
val device = renderer.device
println("syphon-zero-copy driver=${device.physicalDevice.driverName} metalObjects=${device.supportsMetalObjects} externalMemoryHost=${device.supportsExternalMemoryHost}")

// 64x48 for the first half of 14 s, then 96x64
val name = "FieldZeroCopySmoke-${ProcessHandle.current().pid()}"
val proc = ProcessBuilder(probePath.toString(), "serve", name, "14", "64", "48", "96", "64").redirectErrorStream(true).start()
val inp = syphonClient(name, 8000)
val tex = inp.texture()
prop<SyphonTexture>(syphonIn) = tex
val copiesBefore = Syphon.cpuCopies
println("syphon-zero-copy path=${tex.path} size=${tex.width}x${tex.height} fallback='${tex.fallbackReason}'")
require(tex.path != SyphonTexture.Path.CPU_COPY) { "fell back to copying: ${tex.fallbackReason}" }

prop<RenderToTexture>(syphonProbeTarget) = RenderToTexture {
    width = 96
    height = 64
}
renderer.renderList += "syphonProbeTarget" to syphonProbeTarget
val module = moduleHere()
val quad = Mesh()
module += "quad" to quad
syphonProbeTarget += "sample" to module
quad.set(4, 2) {
    pos.add(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0)
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)
    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}

fun check(w: Int, h: Int) {
    val html: String = TexturePreviewCache.capture(
        renderer, syphonProbeTarget.colorImageHandles[0], syphonProbeTarget.colorFormats[0], 96, 64,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 96
    ) ?: throw IllegalStateException("readback failed")
    val png = Regex("base64,([^']+)").find(html)?.groupValues?.get(1) ?: throw IllegalStateException("PNG payload missing")
    val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(png)))
    val points = listOf(1 to 1, w / 4 to h / 4, 3 * w / 4 to h / 4, w / 4 to 3 * h / 4, 3 * w / 4 to 3 * h / 4)
    val want = listOf(listOf(17, 34, 51), listOf(255, 0, 0), listOf(0, 255, 0), listOf(0, 0, 255), listOf(255, 255, 255))
    val got = points.map { (x, y) -> image.getRGB(x, y).let { listOf((it ushr 16) and 255, (it ushr 8) and 255, it and 255) } }
    println("syphon-zero-copy ${w}x$h path=${tex.path} imports=${tex.imports} frames=${tex.frames} pixels=$got")
    require(got == want) { "${w}x$h: expected $want, sampled $got" }
}

var ticks = 0
var phase = 0
loop {
    ticks++
    require(ticks < 1500) { "timed out in phase $phase: $tex" }
    if (phase == 0 && ticks > 60 && tex.width == 64) {
        check(64, 48)
        phase = 1
    } else if (phase == 1 && tex.width == 96) {
        phase = 2
        ticks = 1000
    } else if (phase == 2 && ticks > 1060) {
        check(96, 64)
        val copies = Syphon.cpuCopies - copiesBefore
        println("syphon-zero-copy frames=${tex.frames} imports=${tex.imports} cpuCopies(texture)=${tex.cpuCopies} cpuCopies(jvm)=$copies")
        require(tex.imports == 2L) { "expected one import per surface size, got ${tex.imports}" }
        require(tex.cpuCopies == 0L && copies == 0L) { "frames went through memory: ${tex.cpuCopies} / $copies" }
        require(tex.frames > 100) { "only ${tex.frames} frames arrived" }
        println("syphon-zero-copy PASS")
        end()
    }
    frame()
}.end {
    renderer.renderList.others.remove("syphonProbeTarget")
    tex.close()
    inp.close()
    proc.destroy()
}
