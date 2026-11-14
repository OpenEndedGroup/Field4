@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.media.reconstruction

import afield.graphics.render.*
import afield.graphics.render.Module
import afield.graphics.render.lighting.LightingGpuTimer
import afield.graphics.scene.fabricated.PathTraceFrameOutput
import afield.graphics.scene.fabricated.PathTracingRenderer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.device.Fence
import afield.graphics.vulkan.targets.RenderToTexture
import afield.math.linalg.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferCopy
import org.lwjgl.vulkan.VkDependencyInfo
import org.lwjgl.vulkan.VkMemoryBarrier2
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln
import kotlin.math.pow

/*
 * Intel Open Image Denoise's "RT" network as Vulkan compute. The graph, the .tza weight format, the PU transfer and
 * the input/output conventions follow OIDN (Apache-2.0, https://github.com/RenderKit/oidn) and oidn-webgpu 0.6.0
 * (MIT, https://github.com/pissang/oidn-webgpu); the weights are OIDN's own. The kernels are written for Field's
 * ComputeModule. Design, measurements and what is left: dev_docs/lighting/OIDN_COMPUTE_PORT.md.
 */

/** OIDN weight blob (.tza v2): named fp16/fp32 tensors, nothing executable. */
internal class Tza(bytes: ByteArray) {
    class Tensor(val dims: IntArray, val data: FloatArray)
    val tensors = LinkedHashMap<String, Tensor>()
    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(b.getShort(0).toInt() and 0xffff == 0x41d7 && bytes[2].toInt() == 2) { "Not an OIDN .tza v2 blob" }
        b.position(b.getLong(4).toInt())
        repeat(b.getInt()) {
            val name = ByteArray(b.getShort().toInt() and 0xffff).also { b.get(it) }.decodeToString()
            val nd = b.get().toInt()
            val dims = IntArray(nd) { b.getInt() }
            b.position(b.position() + nd) // layout string: oihw or x
            val type = b.get().toInt().toChar()
            val offset = b.getLong().toInt()
            val n = dims.fold(1) { a, d -> a * d }
            val data = FloatArray(n) { i -> if (type == 'h') java.lang.Float.float16ToFloat(b.getShort(offset + i * 2)) else b.getFloat(offset + i * 4) }
            tensors[name] = Tensor(dims, data)
        }
    }
}

/**
 * Denoises a [PathTracingRenderer]'s accumulated image on the GPU with Open Image Denoise's network.
 *
 * Construction turns on the tracer's frame output (which resets accumulation once) and, from then on, averages
 * the albedo and normal of every traced sample alongside the colour. When the tracer reaches [denoiseAt] samples,
 * or on [request], the network runs once on the accumulated colour and those guides. While that snapshot is
 * current — until the next reset of accumulation — the tracer's own `output` shows it, so `blitToScreen` and any
 * box sampling `output` show the denoised image. Tracing continues underneath.
 *
 * Everything stays on the device: guide accumulation, autoexposure, the PU transfer, 16 convolutions with the
 * upsample, concat and max-pool folded into them, and the inverse transfer. [weights] defaults to the
 * `rt_hdr_alb_nrm` weights in Field's resources. Opaque scenes only, as for the frame output it reads.
 */
class OidnComputeDenoiser(val tracer: PathTracingRenderer, weights: Path? = null, val name: String = "${tracer.name}-denoise") : Target, AutoCloseable {
    val renderer: Renderer = tracer.renderer
    val frame: PathTraceFrameOutput = tracer.enableFrameOutput()
    val width = tracer.width
    val height = tracer.height
    private val pw = (width + 15) / 16 * 16
    private val ph = (height + 15) / 16 * 16
    private val owned = ArrayList<() -> Unit>()
    /** Device bytes held by the fp16 activations. */
    var activationBytes: Long = 0; private set
    private fun buffer(bytes: Long, host: Boolean = false) = VulkanBuffer(renderer, bytes,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        if (host) VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT else VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        needsDeviceAddress = true).also { owned += { it.cleanup() } }

    /** A chunked fp16 activation: [chunks][h][w] of 4 channels packed as two packHalf2x16 words. */
    private inner class Act(val level: Int, val channels: Int, val chunks: Int = (channels + 3) / 4) {
        val w = pw shr level; val h = ph shr level
        val buf = buffer(chunks.toLong() * w * h * 8).also { activationBytes += it.requestedSize }
    }

    val albedo = buffer(width.toLong() * height * 16)
    val normal = buffer(width.toLong() * height * 16)
    val denoised = buffer(width.toLong() * height * 16)
    private val bins = buffer(((width + 15) / 16).toLong() * ((height + 15) / 16) * 8)
    val scale = buffer(16)

    private class Pass(val module: ComputeModulePointer, val mesh: ComputeMesh, val label: String)
    private val passes = ArrayList<Pass>()
    private val auxPass: Pass
    internal val timers = LinkedHashMap<String, LightingGpuTimer>()
    private val total = LightingGpuTimer(renderer).also { owned += { it.close() } }
    val totalMilliseconds get() = total.samples.toList()

    private fun pass(label: String, source: String, gx: Int, gy: Int, gz: Int, push: Map<String, Any>): Pass {
        val errors = ArrayList<String>()
        val module = ComputeModule(Parameters(renderer, name = "oidn-$label", compute = source,
            errorHandler = { m, _ -> errors += m })).pointer()
        check(module.current.valid) { "oidn $label failed to compile:\n${errors.joinToString("\n")}\n$source" }
        val mesh = ComputeMesh(renderer, module).also { it.x = gx; it.y = gy; it.z = gz }
        module += "dispatch" to mesh
        mesh.push.setMembers(module.current.ci.pushConstants.flatMap { it.members })
        for ((k, v) in push) mesh.push[k] = v
        owned += { module.current.cleanup() }
        return Pass(module, mesh, label)
    }

    /**
     * Run the convolutions as cooperative-matrix multiplies (16x16x16 fp16 in, fp32 accumulate; tensor cores on
     * NVIDIA) when the device has `VK_KHR_cooperative_matrix`. Otherwise, and with `afield.oidn.portable=true`,
     * the portable kernels run. Both give the same image to within fp16 rounding.
     */
    val cooperativeMatrix: Boolean = renderer.device.supportsCooperativeMatrix && !java.lang.Boolean.getBoolean("afield.oidn.portable")

    /** Channels-last fp16 activation with a one-pixel zero border, channels padded to 16: one 3x3 tap is a strided matrix load. */
    private inner class Nhwc(val level: Int, val real: Int, val c: Int = (real + 15) / 16 * 16) {
        val w = pw shr level; val h = ph shr level
        // Slack after the last row: a 16-pixel tile that runs past the right edge reads (and discards) it.
        val buf = buffer(((h + 2L) * (w + 2) + (w + 2) + 64) * c * 2).also { activationBytes += it.requestedSize; zeroed += it }
    }
    private val zeroed = ArrayList<VulkanBuffer>()

    init {
        val tza = Tza(weights?.let { Files.readAllBytes(it) } ?: requireNotNull(OidnComputeDenoiser::class.java.getResourceAsStream(DEFAULT_WEIGHTS)) {
            "Open Image Denoise weights are missing from Field's resources ($DEFAULT_WEIGHTS)" }.use { it.readBytes() })
        auxPass = pass("aux", AUX, (width + 15) / 16, (height + 15) / 16, 1,
            mapOf("frame" to frame.buffer, "albedo" to albedo, "normal" to normal, "width" to width, "height" to height))
        val nbx = (width + 15) / 16; val nby = (height + 15) / 16
        passes += pass("autoexposure-bins", AE_BINS, (nbx + 15) / 16, (nby + 15) / 16, 1,
            mapOf("color" to tracer.accumulationBuffer, "bins" to bins, "width" to width, "height" to height, "nbx" to nbx, "nby" to nby))
        passes += pass("autoexposure-reduce", AE_REDUCE, 1, 1, 1, mapOf("bins" to bins, "scale" to scale, "count" to nbx * nby))
        if (cooperativeMatrix) buildMatrixNetwork(tza) else buildPortableNetwork(tza)
        if (zeroed.isNotEmpty()) submit { c -> zeroed.forEach { vkCmdFillBuffer(c.vkCommandBuffer, it.buffer, 0L, VK_WHOLE_SIZE, 0) } }
    }

    private fun buildPortableNetwork(tza: Tza) {
        val input = Act(0, 9)
        passes += pass("input", INPUT, pw / 16, ph / 16, 1, mapOf("color" to tracer.accumulationBuffer, "albedo" to albedo,
            "normal" to normal, "scale" to scale, "dst" to input.buf, "width" to width, "height" to height, "pw" to pw, "ph" to ph))

        // A conv reads A (optionally at half resolution: the fused nearest upsample) then B (the concat skip),
        // and optionally writes the 2x2 max of its ReLU output (the fused pool).
        fun conv(name: String, a: Act, b: Act?, up: Boolean, pool: Boolean): Act {
            val w = tza.tensors.getValue("$name.weight"); val bias = tza.tensors.getValue("$name.bias")
            val oc = w.dims[0]; val ic = w.dims[1]
            val aReal = if (b == null) ic else a.channels
            check(aReal + (b?.channels ?: 0) == ic) { "$name: $ic inputs, A=${a.channels} B=${b?.channels}" }
            val ca = a.chunks; val cb = b?.chunks ?: 0; val ocv = 2; val g = 8; val ocb = (oc + g - 1) / g
            val level = if (up) a.level - 1 else a.level
            val out = Act(if (pool) level + 1 else level, oc, ocb * ocv)
            // Packed weights: [ocb][chunk][tap][g oc] of vec4 over 4 input channels, then bias vec4s.
            fun padded(i: Int) = if (i < aReal) i else ca * 4 + (i - aReal)
            val packed = FloatArray(ocb * (ca + cb) * 9 * g * 4 + ocb * g)
            for (o in 0 until oc) for (i in 0 until ic) for (t in 0 until 9) {
                val p = padded(i); val chunk = p / 4; val lane = p % 4
                packed[(((o / g * (ca + cb) + chunk) * 9 + t) * g + o % g) * 4 + lane] = w.data[(o * ic + i) * 9 + t]
            }
            val biasOffset = ocb * (ca + cb) * 9 * g
            for (o in 0 until oc) packed[biasOffset * 4 + o] = bias.data[o]
            val wb = buffer(packed.size * 4L, host = true)
            wb.mapAndWrite_bytes { packed.forEach { putFloat(it) } }
            val lw = pw shr level; val lh = ph shr level
            val push = mutableMapOf<String, Any>("a" to a.buf, "wt" to wb, "dst" to out.buf, "w" to lw, "h" to lh,
                "aw" to a.w, "ah" to a.h, "biasOffset" to biasOffset)
            if (b != null) push["b"] = b.buf
            passes += pass(name, conv(ca, cb, up, pool), (lw + 15) / 16, (lh + 15) / 16, ocb, push)
            return out
        }
        val e0 = conv("enc_conv0", input, null, false, false)
        val p1 = conv("enc_conv1", e0, null, false, true)
        val p2 = conv("enc_conv2", p1, null, false, true)
        val p3 = conv("enc_conv3", p2, null, false, true)
        val p4 = conv("enc_conv4", p3, null, false, true)
        val e5a = conv("enc_conv5a", p4, null, false, false)
        val e5b = conv("enc_conv5b", e5a, null, false, false)
        val d4a = conv("dec_conv4a", e5b, p3, true, false)
        val d4b = conv("dec_conv4b", d4a, null, false, false)
        val d3a = conv("dec_conv3a", d4b, p2, true, false)
        val d3b = conv("dec_conv3b", d3a, null, false, false)
        val d2a = conv("dec_conv2a", d3b, p1, true, false)
        val d2b = conv("dec_conv2b", d2a, null, false, false)
        val d1a = conv("dec_conv1a", d2b, input, true, false)
        val d1b = conv("dec_conv1b", d1a, null, false, false)
        val d0 = conv("dec_conv0", d1b, null, false, false)
        passes += pass("output", OUTPUT, (width + 15) / 16, (height + 15) / 16, 1,
            mapOf("src" to d0.buf, "scale" to scale, "dst" to denoised, "width" to width, "height" to height, "pw" to pw, "ph" to ph))
    }

    private fun buildMatrixNetwork(tza: Tza) {
        val input = Nhwc(0, 9)
        passes += pass("input", INPUT_NHWC, pw / 16, ph / 16, 1, mapOf("color" to tracer.accumulationBuffer, "albedo" to albedo,
            "normal" to normal, "scale" to scale, "dst" to input.buf, "width" to width, "height" to height, "pw" to pw, "ph" to ph))
        // [split] is where the concat's second input starts, in real channels; the first input's width is a multiple of 16.
        fun conv(name: String, src: Nhwc, split: Int? = null): Nhwc {
            val w = tza.tensors.getValue("$name.weight"); val bias = tza.tensors.getValue("$name.bias")
            val oc = w.dims[0]; val ic = w.dims[1]
            val ci = src.c; val co = (oc + 15) / 16 * 16; val kb = ci / 16; val nt = co / 16
            val out = Nhwc(src.level, oc, co)
            // B tiles: [tap][k block][n tile] of 16x16 row-major (k rows, n columns), fp16.
            val packed = ShortArray(9 * kb * nt * 256)
            for (o in 0 until oc) for (i in 0 until ic) for (t in 0 until 9) {
                val p = if (split == null || i < split) i else split + (i - split)
                check(p < ci) { "$name: channel $i maps past $ci" }
                packed[((t * kb + p / 16) * nt + o / 16) * 256 + (p % 16) * 16 + o % 16] = java.lang.Float.floatToFloat16(w.data[(o * ic + i) * 9 + t])
            }
            val wb = buffer(packed.size * 2L, host = true).also { b -> b.mapAndWrite_bytes { packed.forEach { putShort(it) } } }
            val bb = buffer(co * 4L, host = true).also { b -> b.mapAndWrite_bytes { for (o in 0 until co) putFloat(if (o < oc) bias.data[o] else 0f) } }
            passes += pass(name, convMatrix(ci, co), (src.w + 63) / 64, src.h, 1,
                mapOf("src" to src.buf, "wt" to wb, "bias" to bb, "dst" to out.buf, "w" to src.w, "h" to src.h))
            return out
        }
        fun pool(name: String, src: Nhwc): Nhwc {
            val out = Nhwc(src.level + 1, src.real, src.c); val g = src.c / 4
            passes += pass(name, POOL_NHWC, (out.w * g + 63) / 64, out.h, 1, mapOf("src" to src.buf, "dst" to out.buf, "w" to out.w, "h" to out.h, "g" to g))
            return out
        }
        fun upcat(name: String, a: Nhwc, b: Nhwc): Nhwc {
            check(a.c == a.real && a.level == b.level + 1)
            val out = Nhwc(b.level, a.c + b.c, a.c + b.c)
            passes += pass(name, UPCAT_NHWC, (out.w * (out.c / 4) + 63) / 64, out.h, 1,
                mapOf("a" to a.buf, "b" to b.buf, "dst" to out.buf, "w" to out.w, "h" to out.h, "ga" to a.c / 4, "gb" to b.c / 4))
            return out
        }
        val e0 = conv("enc_conv0", input)
        val p1 = pool("pool1", conv("enc_conv1", e0))
        val p2 = pool("pool2", conv("enc_conv2", p1))
        val p3 = pool("pool3", conv("enc_conv3", p2))
        val p4 = pool("pool4", conv("enc_conv4", p3))
        val e5b = conv("enc_conv5b", conv("enc_conv5a", p4))
        val d4b = conv("dec_conv4b", conv("dec_conv4a", upcat("up4", e5b, p3), split = e5b.c))
        val d3b = conv("dec_conv3b", conv("dec_conv3a", upcat("up3", d4b, p2), split = d4b.c))
        val d2b = conv("dec_conv2b", conv("dec_conv2a", upcat("up2", d3b, p1), split = d3b.c))
        val d1b = conv("dec_conv1b", conv("dec_conv1a", upcat("up1", d2b, input), split = d2b.c))
        val d0 = conv("dec_conv0", d1b)
        passes += pass("output", OUTPUT_NHWC, (width + 15) / 16, (height + 15) / 16, 1,
            mapOf("src" to d0.buf, "scale" to scale, "dst" to denoised, "width" to width, "height" to height, "pw" to pw, "c" to d0.c))
    }

    /** Display texture: the denoised buffer copied into an RTT the same way PathTraceFrameOutput fills its images. */
    val output = RenderToTexture(renderer) {
        width = this@OidnComputeDenoiser.width; height = this@OidnComputeDenoiser.height; useDepth = false
        format = VK_FORMAT_R32G32B32A32_SFLOAT; debugName = "oidn-denoised"
    }
    private val present = Module(parameters(renderer) {
        name = "oidn-present"; +output; depthTest = false; depthWrite = false; blend = false
        vertex = PRESENT_VERTEX; fragment = PRESENT_FRAGMENT
    })
    init {
        check(present.valid) { "oidn present shader failed" }
        val p = present.pointer(); val mesh = Mesh(renderer, p); mesh.resize(3, 0, p)
        mesh.push.setMembers(present.fi.pushConstants.single().members)
        mesh.push["pixels"] = denoised; mesh.push["width"] = width; mesh.push["height"] = height
        present.meshes["quad"] = mesh; output.modules["present"] = p
        for (p2 in passes) timers[p2.label] = LightingGpuTimer(renderer).also { t -> owned += { t.close() } }
    }

    /** Average albedo and normal as samples arrive. Turning it off leaves the guides of the last run in place. */
    var accumulateGuides = true
    private var lastSamples = -1
    /** Denoise once per accumulation run when the tracer reaches this many samples; zero waits for [request]. */
    var denoiseAt = 64
    /** Show the current denoised snapshot in the tracer's `output`. */
    var show = true
        set(value) { field = value; if (!value) showing(false) }
    /** Denoise on each of the next [n] frames. One is a still; more are for timing. */
    fun request(n: Int = 1) { pending = n }
    @Volatile var pending = 0; private set
    internal var profileLayers = false
    /** How many times the network has run. */
    var denoiseCount = 0; private set
    /** The tracer's sample count at the last denoise, or -1. */
    var denoisedSamples = -1; private set
    private var denoisedGeneration = -1L
    private var shown = false
    /** True while the snapshot belongs to the tracer's current accumulation run. */
    val current get() = denoisedGeneration == tracer.resetGeneration
    private fun showing(on: Boolean) { if (on != shown) { tracer.resolveFrom(if (on) denoised else null); shown = on } }
    private var closed = false

    /** Schedule on the render list after the tracer. Reusing [name] retires the previous denoiser. */
    fun install(): OidnComputeDenoiser {
        val previous = renderer.renderList.preTargets[name]
        require(previous == null || previous === this || previous is OidnComputeDenoiser) { "'$name' belongs to another kind of target" }
        if (previous is OidnComputeDenoiser && previous !== this) previous.close()
        renderer.renderList.preTargets[name] = this
        return this
    }

    /**
     * Timing only: record the network into its own command buffer, submit, and wait on a fence. KosmicKrisp's
     * timestamps do not separate dispatches inside one encoder, so wall time around a fence is the measurement.
     * [perPass] submits each pass alone (each figure then includes one submit+wait; see [emptySubmitMs]).
     */
    fun denoiseNow(perPass: Boolean = false): List<Pair<String, Double>> =
        if (!perPass) listOf("total" to submit { c -> passes.forEach { it.module.current.render(c, Rectanglef()); barrier(c, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT) } })
        else passes.map { p -> p.label to submit { c -> p.module.current.render(c, Rectanglef()) } }
    fun emptySubmitMs() = submit { }
    private fun submit(body: (CommandBuffer) -> Unit): Double {
        val command = CommandBuffer(renderer.commandPool, true, true); val fence = Fence(renderer.device, false)
        try {
            command.beginRecording(); barrier(command, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT); body(command); command.endRecording()
            val t0 = System.nanoTime()
            MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(command.vkCommandBuffer), null, null, null, fence) }
            fence.fenceWait()
            return (System.nanoTime() - t0) / 1e6
        } finally { fence.cleanup(); command.cleanup() }
    }

    override fun render(command: CommandBuffer, index: Int) {
        if (closed || tracer.isClosed) return
        val n = tracer.samples
        if (accumulateGuides && n != lastSamples && n > 0) {
            barrier(command, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
            auxPass.mesh.push["samples"] = n; auxPass.mesh.push["step"] = tracer.samplesPerFrame
            auxPass.module.current.render(command, Rectanglef())
            lastSamples = n
        }
        if (!current) showing(false)
        if (pending <= 0 && denoiseAt > 0 && n >= denoiseAt && !current) pending = 1
        if (pending <= 0 || n <= 0) return
        barrier(command, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
        total.begin(command, index)
        for (p in passes) {
            if (profileLayers) timers.getValue(p.label).begin(command, index)
            p.module.current.render(command, Rectanglef())
            if (profileLayers) timers.getValue(p.label).end(command, index)
            barrier(command, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
        }
        total.end(command, index)
        graphicsMemoryBarrier(command, denoised)
        output.render(command, index)
        pending--; denoiseCount++; denoisedSamples = n; denoisedGeneration = tracer.resetGeneration
        if (show) showing(true)
    }

    private fun barrier(command: CommandBuffer, src: Long) = MemoryStack.stackPush().use { s ->
        val m = VkMemoryBarrier2.calloc(1, s).`sType$Default`().srcStageMask(src).srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT).dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
        vkCmdPipelineBarrier2(command.vkCommandBuffer, VkDependencyInfo.calloc(s).`sType$Default`().pMemoryBarriers(m))
    }

    /** Blocking diagnostic readback of one of this denoiser's buffers. */
    fun read(source: VulkanBuffer): FloatArray {
        val staging = VulkanBuffer(renderer, source.requestedSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        val command = CommandBuffer(renderer.commandPool, true, true); val fence = Fence(renderer.device, false)
        try {
            command.beginRecording()
            MemoryStack.stackPush().use { s ->
                val m = VkMemoryBarrier2.calloc(1, s).`sType$Default`().srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_TRANSFER_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
                vkCmdPipelineBarrier2(command.vkCommandBuffer, VkDependencyInfo.calloc(s).`sType$Default`().pMemoryBarriers(m))
                vkCmdCopyBuffer(command.vkCommandBuffer, source.buffer, staging.buffer, VkBufferCopy.calloc(1, s).size(source.requestedSize))
            }
            command.endRecording()
            MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(command.vkCommandBuffer), null, null, null, fence) }
            fence.fenceWait()
            return FloatArray((source.requestedSize / 4).toInt()).also { d -> staging.mapAndRead { get(d) } }
        } finally { fence.cleanup(); command.cleanup(); staging.cleanup() }
    }

    override fun close() {
        if (closed) return; closed = true
        showing(false); renderer.renderList.preTargets.remove(name, this)
        renderer.cleanup += Runnable {
            owned.asReversed().forEach { runCatching(it) }; present.cleanup()
            output.imageViewArray.forEach { it.imageView.cleanup() }; output.colorImageRefs.forEach { it?.cleanup() }; output.defaultSampler?.cleanup()
        }
    }

    companion object {
        /** OIDN's `rt_hdr_alb_nrm` weights (Apache-2.0), as vendored with oidn-webgpu 0.6.0. */
        const val DEFAULT_WEIGHTS = "/vendor/oidn-webgpu/0.6.0/rt_hdr_alb_nrm.tza"
        private fun puForward(y: Double) = when {
            y <= 1.5794576e-6 -> 1.41283765e3 * y
            y <= 3.22087631e-2 -> 1.64593172 * y.pow(4.31384981e-1) - 2.94139609e-3
            else -> 1.92653254e-1 * ln(y + 6.26026094e-3) + 9.98620152e-1
        }
        private val PU_NORM = 1.0 / puForward(65504.0)
        private val PU = """
            const float PU_A=1.41283765e3, PU_B=1.64593172, PU_C=4.31384981e-1, PU_D=-2.94139609e-3, PU_E=1.92653254e-1,
              PU_F=6.26026094e-3, PU_G=9.98620152e-1, PU_Y0=1.5794576e-6, PU_Y1=3.22087631e-2, PU_X0=2.23151711e-3, PU_X1=3.70974749e-1;
            const float PU_NORM=$PU_NORM, PU_RCP=${1.0 / PU_NORM};
            float sanitize(float v,float lo,float hi){ return (isnan(v)||isinf(v)) ? lo : clamp(v,lo,hi); }
            float puForward(float y){ return (y<=PU_Y0 ? PU_A*y : y<=PU_Y1 ? PU_B*pow(y,PU_C)+PU_D : PU_E*log(y+PU_F)+PU_G)*PU_NORM; }
            float puInverse(float x){ float y=x*PU_RCP; return y<=PU_X0 ? y/PU_A : y<=PU_X1 ? pow((y-PU_D)/PU_B,1.0/PU_C) : exp((y-PU_G)/PU_E)-PU_F; }
        """
        private const val HEAD = """#version 460
            #extension GL_EXT_buffer_reference : require
            layout(buffer_reference, std430, buffer_reference_align=16) buffer Vec4s { vec4 v[]; };
            layout(buffer_reference, std430, buffer_reference_align=8) buffer Halves { uvec2 v[]; };
            layout(buffer_reference, std430, buffer_reference_align=8) buffer Vec2s { vec2 v[]; };
            layout(buffer_reference, std430, buffer_reference_align=4) buffer Floats { float v[]; };
            uvec2 pack4(vec4 v){ return uvec2(packHalf2x16(v.xy), packHalf2x16(v.zw)); }
            vec4 unpack4(uvec2 u){ return vec4(unpackHalf2x16(u.x), unpackHalf2x16(u.y)); }
        """

        /** Running mean of primary albedo (diffuse+specular, clamped) and normal from the tracer's frame records. */
        private val AUX = HEAD + """
            struct FramePixel {vec4 radiance;vec4 positionDepth;vec4 normalRoughness;vec4 diffuse;vec4 specular;vec4 motion;};
            layout(buffer_reference,std430,buffer_reference_align=16) readonly buffer Pixels {FramePixel items[];};
            layout(local_size_x=16, local_size_y=16) in;
            layout(push_constant) uniform P { Pixels frame; Vec4s albedo; Vec4s normal; int width; int height; int samples; int step; } pc;
            void main(){
              ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(p.x>=pc.width||p.y>=pc.height) return;
              int i=p.y*pc.width+p.x; FramePixel f=pc.frame.items[i];
              vec4 a=vec4(clamp(f.diffuse.rgb+f.specular.rgb,0.0,1.0),1.0); vec4 n=vec4(f.normalRoughness.xyz,0.0);
              if(pc.samples<=pc.step){ pc.albedo.v[i]=a; pc.normal.v[i]=n; }
              else { float t=float(pc.step)/float(pc.samples); pc.albedo.v[i]=mix(pc.albedo.v[i],a,t); pc.normal.v[i]=mix(pc.normal.v[i],n,t); }
            }
        """

        /** OIDN autoexposure: mean luminance per (up to) 16x16 bin, geometric mean of the bins above eps, key 0.18. */
        private val AE_BINS = HEAD + PU + """
            layout(local_size_x=16, local_size_y=16) in;
            layout(push_constant) uniform P { Vec4s color; Vec2s bins; int width; int height; int nbx; int nby; } pc;
            void main(){
              ivec2 b=ivec2(gl_GlobalInvocationID.xy); if(b.x>=pc.nbx||b.y>=pc.nby) return;
              int x0=b.x*pc.width/pc.nbx, x1=(b.x+1)*pc.width/pc.nbx, y0=b.y*pc.height/pc.nby, y1=(b.y+1)*pc.height/pc.nby;
              float sum=0.0;
              for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++){ vec3 c=pc.color.v[y*pc.width+x].rgb;
                c=vec3(sanitize(c.r,0.0,3.4e38),sanitize(c.g,0.0,3.4e38),sanitize(c.b,0.0,3.4e38)); sum+=dot(c,vec3(0.212671,0.715160,0.072169)); }
              float L=sum/float((x1-x0)*(y1-y0));
              pc.bins.v[b.y*pc.nbx+b.x]= L>1e-8 ? vec2(log2(L),1.0) : vec2(0.0);
            }
        """
        private val AE_REDUCE = HEAD + """
            layout(local_size_x=256) in;
            layout(push_constant) uniform P { Vec2s bins; Floats scale; int count; } pc;
            shared vec2 part[256];
            void main(){
              uint t=gl_LocalInvocationID.x; vec2 s=vec2(0.0);
              for(int i=int(t);i<pc.count;i+=256) s+=pc.bins.v[i];
              part[t]=s; barrier();
              for(uint k=128u;k>0u;k>>=1u){ if(t<k) part[t]+=part[t+k]; barrier(); }
              if(t==0u) pc.scale.v[0]= part[0].y>0.0 ? 0.18/exp2(part[0].x/part[0].y) : 1.0;
            }
        """

        /** Network input: PU(color*scale), albedo in [0,1], normal mapped to [0,1]; zero in the padding. 9 channels in 3 chunks. */
        private val INPUT = HEAD + PU + """
            layout(local_size_x=16, local_size_y=16) in;
            layout(push_constant) uniform P { Vec4s color; Vec4s albedo; Vec4s normal; Floats scale; Halves dst; int width; int height; int pw; int ph; } pc;
            void main(){
              ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(p.x>=pc.pw||p.y>=pc.ph) return;
              vec3 c=vec3(0.0), a=vec3(0.0), n=vec3(0.0);
              if(p.x<pc.width&&p.y<pc.height){
                int i=p.y*pc.width+p.x; float s=pc.scale.v[0]; vec3 col=pc.color.v[i].rgb*s;
                c=vec3(puForward(sanitize(col.r,0.0,3.4e38)),puForward(sanitize(col.g,0.0,3.4e38)),puForward(sanitize(col.b,0.0,3.4e38)));
                vec3 al=pc.albedo.v[i].rgb; a=vec3(sanitize(al.r,0.0,1.0),sanitize(al.g,0.0,1.0),sanitize(al.b,0.0,1.0));
                vec3 nr=pc.normal.v[i].xyz; n=vec3(sanitize(nr.x,-1.0,1.0),sanitize(nr.y,-1.0,1.0),sanitize(nr.z,-1.0,1.0))*0.5+0.5;
              }
              int plane=pc.pw*pc.ph, j=p.y*pc.pw+p.x;
              pc.dst.v[j]=pack4(vec4(c,a.r)); pc.dst.v[plane+j]=pack4(vec4(a.gb,n.rg)); pc.dst.v[2*plane+j]=pack4(vec4(n.b,0.0,0.0,0.0));
            }
        """

        /**
         * 3x3 conv + bias + ReLU. A 8x8 workgroup covers 16x16 output pixels (2x2 per invocation) and 8 output
         * channels; one 4-channel input chunk at a time is staged through shared memory with a 1-pixel apron.
         * (Measured on KosmicKrisp: 16 channels per invocation, or two chunks per barrier with array-indexed
         * accumulators, were 1.8x and 1.3x slower than this explicit form.)
         */
        private fun conv(ca: Int, cb: Int, up: Boolean, pool: Boolean) = HEAD + """
            const int CA=$ca, CB=$cb, CT=CA+CB; const bool UP=$up, POOL=$pool;
            layout(local_size_x=8, local_size_y=8) in;
            layout(push_constant) uniform P { Halves a; ${if (cb > 0) "Halves b;" else ""} Vec4s wt; Halves dst; int w; int h; int aw; int ah; int biasOffset; } pc;
            shared vec4 tile[18*18];
            shared vec4 wsh[72];
            vec4 load(int c,int x,int y){
              if(x<0||y<0||x>=pc.w||y>=pc.h) return vec4(0.0);
              if(c<CA){ if(UP){ x>>=1; y>>=1; } int rw=UP?pc.aw:pc.w, rh=UP?pc.ah:pc.h;
                return unpack4(pc.a.v[(c*rh+y)*rw+x]); }
              ${if (cb > 0) "return unpack4(pc.b.v[((c-CA)*pc.h+y)*pc.w+x]);" else "return vec4(0.0);"}
            }
            void main(){
              ivec2 l=ivec2(gl_LocalInvocationID.xy); int li=l.y*8+l.x;
              ivec2 base=ivec2(gl_WorkGroupID.xy)*16; int ob=int(gl_WorkGroupID.z);
              vec4 acc0[4], acc1[4];
              for(int p=0;p<4;p++){ acc0[p]=vec4(0.0); acc1[p]=vec4(0.0); }
              for(int c=0;c<CT;c++){
                for(int i=li;i<324;i+=64){ int ty=i/18, tx=i-ty*18; tile[i]=load(c,base.x+tx-1,base.y+ty-1); }
                for(int i=li;i<72;i+=64) wsh[i]=pc.wt.v[(ob*CT+c)*72+i];
                barrier();
                for(int ky=0;ky<3;ky++) for(int kx=0;kx<3;kx++){
                  int t=(ky*3+kx)*8;
                  vec4 w0=wsh[t],w1=wsh[t+1],w2=wsh[t+2],w3=wsh[t+3],w4=wsh[t+4],w5=wsh[t+5],w6=wsh[t+6],w7=wsh[t+7];
                  for(int p=0;p<4;p++){
                    vec4 v=tile[(l.y*2+(p>>1)+ky)*18+l.x*2+(p&1)+kx];
                    acc0[p]+=vec4(dot(w0,v),dot(w1,v),dot(w2,v),dot(w3,v));
                    acc1[p]+=vec4(dot(w4,v),dot(w5,v),dot(w6,v),dot(w7,v));
                  }
                }
                barrier();
              }
              vec4 b0=pc.wt.v[pc.biasOffset+ob*2], b1=pc.wt.v[pc.biasOffset+ob*2+1];
              for(int p=0;p<4;p++){ acc0[p]=max(acc0[p]+b0,vec4(0.0)); acc1[p]=max(acc1[p]+b1,vec4(0.0)); }
              if(POOL){
                int ow=pc.w>>1, oh=pc.h>>1; ivec2 o=(base>>1)+l; if(o.x>=ow||o.y>=oh) return;
                pc.dst.v[((ob*2)*oh+o.y)*ow+o.x]=pack4(max(max(acc0[0],acc0[1]),max(acc0[2],acc0[3])));
                pc.dst.v[((ob*2+1)*oh+o.y)*ow+o.x]=pack4(max(max(acc1[0],acc1[1]),max(acc1[2],acc1[3])));
              } else {
                for(int p=0;p<4;p++){ ivec2 o=base+l*2+ivec2(p&1,p>>1); if(o.x>=pc.w||o.y>=pc.h) continue;
                  pc.dst.v[((ob*2)*pc.h+o.y)*pc.w+o.x]=pack4(acc0[p]);
                  pc.dst.v[((ob*2+1)*pc.h+o.y)*pc.w+o.x]=pack4(acc1[p]); }
              }
            }
        """

        /** Inverse PU transfer and exposure; the network output may be negative or NaN. */
        private val OUTPUT = HEAD + PU + """
            layout(local_size_x=16, local_size_y=16) in;
            layout(push_constant) uniform P { Halves src; Floats scale; Vec4s dst; int width; int height; int pw; int ph; } pc;
            void main(){
              ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(p.x>=pc.width||p.y>=pc.height) return;
              vec3 d=unpack4(pc.src.v[p.y*pc.pw+p.x]).rgb; float s=pc.scale.v[0];
              d=vec3(puInverse(sanitize(d.r,0.0,3.4e38)),puInverse(sanitize(d.g,0.0,3.4e38)),puInverse(sanitize(d.b,0.0,3.4e38)))/s;
              pc.dst.v[p.y*pc.width+p.x]=vec4(d,1.0);
            }
        """

        private const val HEAD16 = """#version 460
            #extension GL_EXT_buffer_reference : require
            #extension GL_EXT_shader_16bit_storage : require
            #extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
            layout(buffer_reference, std430, buffer_reference_align=16) buffer Vec4s { vec4 v[]; };
            layout(buffer_reference, std430, buffer_reference_align=8) buffer H4s { f16vec4 v[]; };
            layout(buffer_reference, std430, buffer_reference_align=4) buffer Floats { float v[]; };
        """

        /** As [INPUT], into a 16-channel NHWC tensor with a zero border. */
        private val INPUT_NHWC = HEAD16 + PU + """
            layout(local_size_x=16, local_size_y=16) in;
            layout(push_constant) uniform P { Vec4s color; Vec4s albedo; Vec4s normal; Floats scale; H4s dst; int width; int height; int pw; int ph; } pc;
            void main(){
              ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(p.x>=pc.pw||p.y>=pc.ph) return;
              vec3 c=vec3(0.0), a=vec3(0.0), n=vec3(0.0);
              if(p.x<pc.width&&p.y<pc.height){
                int i=p.y*pc.width+p.x; float s=pc.scale.v[0]; vec3 col=pc.color.v[i].rgb*s;
                c=vec3(puForward(sanitize(col.r,0.0,3.4e38)),puForward(sanitize(col.g,0.0,3.4e38)),puForward(sanitize(col.b,0.0,3.4e38)));
                vec3 al=pc.albedo.v[i].rgb; a=vec3(sanitize(al.r,0.0,1.0),sanitize(al.g,0.0,1.0),sanitize(al.b,0.0,1.0));
                vec3 nr=pc.normal.v[i].xyz; n=vec3(sanitize(nr.x,-1.0,1.0),sanitize(nr.y,-1.0,1.0),sanitize(nr.z,-1.0,1.0))*0.5+0.5;
              }
              int j=((p.y+1)*(pc.pw+2)+p.x+1)*4;
              pc.dst.v[j]=f16vec4(vec4(c,a.r)); pc.dst.v[j+1]=f16vec4(vec4(a.gb,n.rg)); pc.dst.v[j+2]=f16vec4(vec4(n.b,0.0,0.0,0.0)); pc.dst.v[j+3]=f16vec4(0.0);
            }
        """

        /**
         * 3x3 conv + bias + ReLU as an implicit GEMM on cooperative matrices. Each subgroup owns 16 consecutive pixels of
         * one row and every output channel; for each tap and 16-channel block it loads one A tile straight from the
         * bordered NHWC input (row stride = channels) and multiplies it into CO/16 fp32 accumulators.
         */
        private fun convMatrix(ci: Int, co: Int) = """#version 460
            #extension GL_KHR_cooperative_matrix : require
            #extension GL_KHR_memory_scope_semantics : require
            #extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
            #extension GL_EXT_shader_16bit_storage : require
            #extension GL_EXT_buffer_reference : require
            #extension GL_KHR_shader_subgroup_basic : require
            const int CI=$ci, CO=$co, KB=CI/16, NT=CO/16;
            layout(local_size_x=128) in;
            layout(buffer_reference, std430, buffer_reference_align=16) buffer F16s { float16_t v[]; };
            layout(buffer_reference, std430, buffer_reference_align=16) buffer F32s { float v[]; };
            layout(push_constant) uniform P { F16s src; F16s wt; F32s bias; F16s dst; int w; int h; } pc;
            shared float sh[4*16*CO];
            void main(){
              int sg=int(gl_SubgroupID), lane=int(gl_SubgroupInvocationID);
              int x0=int(gl_WorkGroupID.x)*64+sg*16, y=int(gl_WorkGroupID.y);
              if(x0>=pc.w) return;
              coopmat<float,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator> acc[NT];
              for(int n=0;n<NT;n++) acc[n]=coopmat<float,gl_ScopeSubgroup,16,16,gl_MatrixUseAccumulator>(0.0);
              int rw=pc.w+2;
              for(int t=0;t<9;t++){
                int ky=t/3, kx=t-ky*3;
                uint abase=uint(((y+ky)*rw+x0+kx)*CI);
                for(int kb=0;kb<KB;kb++){
                  coopmat<float16_t,gl_ScopeSubgroup,16,16,gl_MatrixUseA> a;
                  coopMatLoad(a,pc.src.v,abase+uint(kb*16),uint(CI),gl_CooperativeMatrixLayoutRowMajor);
                  for(int n=0;n<NT;n++){
                    coopmat<float16_t,gl_ScopeSubgroup,16,16,gl_MatrixUseB> b;
                    coopMatLoad(b,pc.wt.v,uint(((t*KB+kb)*NT+n)*256),16u,gl_CooperativeMatrixLayoutRowMajor);
                    acc[n]=coopMatMulAdd(a,b,acc[n]);
                  }
                }
              }
              int sb=sg*16*CO;
              for(int n=0;n<NT;n++) coopMatStore(acc[n],sh,uint(sb+n*16),uint(CO),gl_CooperativeMatrixLayoutRowMajor);
              subgroupMemoryBarrierShared(); subgroupBarrier();
              for(int i=lane;i<16*CO;i+=32){ int m=i/CO, o=i-m*CO; if(x0+m>=pc.w) continue;
                pc.dst.v[((y+1)*rw+x0+m+1)*CO+o]=float16_t(max(sh[sb+i]+pc.bias.v[o],0.0)); }
            }
        """

        private val POOL_NHWC = HEAD16 + """
            layout(local_size_x=64) in;
            layout(push_constant) uniform P { H4s src; H4s dst; int w; int h; int g; } pc;
            void main(){
              int i=int(gl_GlobalInvocationID.x), y=int(gl_GlobalInvocationID.y); if(i>=pc.w*pc.g||y>=pc.h) return;
              int x=i/pc.g, k=i-x*pc.g; int sw=2*pc.w+2, dw=pc.w+2;
              int s=((2*y+1)*sw+2*x+1)*pc.g+k, r=sw*pc.g;
              pc.dst.v[((y+1)*dw+x+1)*pc.g+k]=max(max(pc.src.v[s],pc.src.v[s+pc.g]),max(pc.src.v[s+r],pc.src.v[s+r+pc.g]));
            }
        """

        /** Nearest 2x upsample of A next to B, channels A then B. */
        private val UPCAT_NHWC = HEAD16 + """
            layout(local_size_x=64) in;
            layout(push_constant) uniform P { H4s a; H4s b; H4s dst; int w; int h; int ga; int gb; } pc;
            void main(){
              int g=pc.ga+pc.gb; int i=int(gl_GlobalInvocationID.x), y=int(gl_GlobalInvocationID.y); if(i>=pc.w*g||y>=pc.h) return;
              int x=i/g, k=i-x*g;
              f16vec4 v = k<pc.ga ? pc.a.v[((y/2+1)*(pc.w/2+2)+x/2+1)*pc.ga+k] : pc.b.v[((y+1)*(pc.w+2)+x+1)*pc.gb+k-pc.ga];
              pc.dst.v[((y+1)*(pc.w+2)+x+1)*g+k]=v;
            }
        """

        private val OUTPUT_NHWC = HEAD16 + PU + """
            layout(local_size_x=16, local_size_y=16) in;
            layout(push_constant) uniform P { H4s src; Floats scale; Vec4s dst; int width; int height; int pw; int c; } pc;
            void main(){
              ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(p.x>=pc.width||p.y>=pc.height) return;
              vec3 d=vec3(pc.src.v[((p.y+1)*(pc.pw+2)+p.x+1)*(pc.c/4)].rgb); float s=pc.scale.v[0];
              d=vec3(puInverse(sanitize(d.r,0.0,3.4e38)),puInverse(sanitize(d.g,0.0,3.4e38)),puInverse(sanitize(d.b,0.0,3.4e38)))/s;
              pc.dst.v[p.y*pc.width+p.x]=vec4(d,1.0);
            }
        """

        private const val PRESENT_VERTEX = """#version 460
            layout(location=0) out vec2 uv;
            void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);uv=vec2(p.x,1-p.y);}
        """
        private const val PRESENT_FRAGMENT = """#version 460
            #extension GL_EXT_buffer_reference : require
            layout(buffer_reference,std430,buffer_reference_align=16) readonly buffer Pixels {vec4 items[];};
            layout(push_constant) uniform P {Pixels pixels;int width;int height;} pc;
            layout(location=0) in vec2 uv;
            layout(location=0) out vec4 color;
            void main(){ivec2 p=clamp(ivec2(uv*vec2(pc.width,pc.height)),ivec2(0),ivec2(pc.width-1,pc.height-1));
              color=pc.pixels.items[p.y*pc.width+p.x];}
        """
    }
}
