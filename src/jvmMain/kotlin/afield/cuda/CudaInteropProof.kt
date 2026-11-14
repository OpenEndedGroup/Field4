@file:afield.runtime.completion.CompletionCategory("Internal")
/**
 * The proof that [CudaDriver] and Field's Vulkan renderer share memory on the same GPU with no
 * copies, and the measurements behind `dev_docs/graphics/CUDA_DRIVER_FFM.md`.
 *
 * Two proofs, each checked by reading the memory back from both sides:
 *
 * 1. **Native OIDN on CUDA, in place.** A 1080p path trace accumulates with its accumulation
 *    buffer allocated exportable, and Field's own OIDN port accumulates its albedo and normal guides
 *    the same way. Native Open Image Denoise opens the CUDA device with the Vulkan device's UUID,
 *    imports those three buffers and an output buffer as fds, and denoises under a shared timeline
 *    semaphore: Vulkan signals, OIDN waits, filters and signals, Vulkan waits. The output is read
 *    back through Vulkan and through OIDN (they must agree bit for bit) and compared with Field's
 *    Vulkan port of the same network on the same inputs.
 * 2. **A CUDA kernel writing a buffer Field displays.** A PTX kernel shipped as text (and, when
 *    [Nvrtc] is found, a CUDA C kernel compiled at runtime) fills an exported Vulkan buffer; Field's
 *    path tracer then resolves its `output` texture from that buffer, and the texture is read back and
 *    compared with the values the kernel must have written.
 *
 * Arguments: `[--oidn <dir holding libOpenImageDenoise.so>] [--out <dir>] [--spp N]`. Without CUDA
 * (macOS, no NVIDIA driver, `FIELD_CUDA_DISABLE=1`) it prints the reason and exits 0; without OIDN
 * it runs proof 2 alone.
 */

package afield.cuda

import afield.document.graph.Box
import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.device.ExportedTimelineSemaphore
import afield.graphics.vulkan.device.Fence
import afield.graphics.vulkan.util.Camera
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Vector3f
import afield.media.reconstruction.Oidn
import afield.media.reconstruction.OidnComputeDenoiser
import afield.media.reconstruction.ReconstructionCapture
import org.json.JSONObject
import org.lwjgl.vulkan.VK13.*
import java.io.File
import java.lang.foreign.MemorySegment
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.system.exitProcess

/*
FIELD_TEST
id: cuda.interop.smoke
runner: jvm-main
entrypoint: afield.cuda.CudaInteropProof
timeoutSec: 600
tags: [graphics, cuda, smoke]
expect:
  exitCode: 0
  stdoutContains:
    - "CUDA INTEROP"
*/
object CudaInteropProof {
    /** `field_fill(float4* out, uint width, uint height, float t)`: out[y*width+x] = (x/width, y/height, t, 1). Hand-written PTX, JIT-compiled by the driver. */
    val FILL_PTX = """
        .version 7.0
        .target sm_52
        .address_size 64

        .visible .entry field_fill(
            .param .u64 field_fill_out,
            .param .u32 field_fill_width,
            .param .u32 field_fill_height,
            .param .f32 field_fill_t
        )
        {
            .reg .pred %p<4>;
            .reg .b32 %r<12>;
            .reg .f32 %f<9>;
            .reg .b64 %rd<5>;

            ld.param.u64 %rd1, [field_fill_out];
            ld.param.u32 %r1, [field_fill_width];
            ld.param.u32 %r2, [field_fill_height];
            ld.param.f32 %f1, [field_fill_t];
            mov.u32 %r3, %ctaid.x;
            mov.u32 %r4, %ntid.x;
            mov.u32 %r5, %tid.x;
            mad.lo.s32 %r6, %r3, %r4, %r5;
            mov.u32 %r7, %ctaid.y;
            mov.u32 %r8, %ntid.y;
            mov.u32 %r9, %tid.y;
            mad.lo.s32 %r10, %r7, %r8, %r9;
            setp.ge.u32 %p1, %r6, %r1;
            setp.ge.u32 %p2, %r10, %r2;
            or.pred %p3, %p1, %p2;
            @%p3 bra ${'$'}L_done;
            cvt.rn.f32.u32 %f2, %r6;
            cvt.rn.f32.u32 %f3, %r1;
            div.rn.f32 %f4, %f2, %f3;
            cvt.rn.f32.u32 %f5, %r10;
            cvt.rn.f32.u32 %f6, %r2;
            div.rn.f32 %f7, %f5, %f6;
            mov.f32 %f8, 0f3F800000;
            mad.lo.s32 %r11, %r10, %r1, %r6;
            cvta.to.global.u64 %rd2, %rd1;
            mul.wide.u32 %rd3, %r11, 16;
            add.s64 %rd4, %rd2, %rd3;
            st.global.v4.f32 [%rd4], {%f4, %f7, %f1, %f8};
        ${'$'}L_done:
            ret;
        }
    """.trimIndent()

    /** The NVRTC kernel: a radial pattern, with FMA contraction off so the CPU can predict every bit. */
    val RINGS_CU = """
        extern "C" __global__ void field_rings(float4* out, int w, int h, float t) {
            int x = blockIdx.x * blockDim.x + threadIdx.x, y = blockIdx.y * blockDim.y + threadIdx.y;
            if (x >= w || y >= h) return;
            float u = (x + 0.5f) / w - 0.5f, v = (y + 0.5f) / h - 0.5f;
            float r2 = u * u + v * v;
            out[y * w + x] = make_float4(4.0f * r2, u + 0.5f, t, 1.0f);
        }
    """.trimIndent()

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6
    private fun median(v: List<Double>) = v.sorted()[v.size / 2]
    private fun srgb(x: Float): Float { val c = x.coerceIn(0f, 1f); return if (c <= .0031308f) c * 12.92f else 1.055f * c.toDouble().pow(1 / 2.4).toFloat() - .055f }
    private fun psnr(a: FloatArray, b: FloatArray): Double { var sum = 0.0; var n = 0
        for (i in a.indices) if (i % 4 != 3) { val d = (srgb(a[i]) - srgb(b[i])).toDouble(); sum += d * d; n++ }; return 10 * log10(1 / (sum / n)) }
    private fun maxRgbDiff(a: FloatArray, b: FloatArray) = a.indices.filter { it % 4 != 3 }.maxOf { abs(a[it] - b[it]) }

    @JvmStatic fun main(args: Array<String>) {
        fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val folder = File(arg("--out") ?: "build/cuda-interop").also { it.mkdirs() }
        val spp = arg("--spp")?.toInt() ?: 32
        val oidnDir = (arg("--oidn") ?: System.getenv("FIELD_OIDN_LIBRARY_DIR") ?: "${System.getProperty("user.home")}/oidn-spike/oidn-2.5.1.x86_64.linux/lib").let { Path.of(it) }
        val report = JSONObject()
        val driver = CudaDriver.loaded.getOrElse {
            println("CUDA INTEROP SKIPPED: ${it.message}"); report.put("skipped", it.message)
            File(folder, "measurements.json").writeText(report.toString(2)); exitProcess(0)
        }
        report.put("cudaDriverVersion", driver.driverVersion)

        Options.enableVR = false; Options.enableHDR = false; Options.enableRayTracing = false; Options.validate = false; Options.doVulkanSDK()
        val window = Window("Field CUDA interop"); val renderer = Renderer(window)
        val box = Box("cuda-interop"); box.setPropertyRaw("renderer", renderer, doResync = false)
        val failures = ArrayList<String>()
        fun expect(ok: Boolean, what: String) { println((if (ok) "PASS " else "FAIL ") + what); if (!ok) failures += what }
        try { Box.withBoxContextNow(box) {
            if (!renderer.device.supportsExternalMemoryFd || !renderer.device.supportsExternalSemaphoreFd) {
                println("CUDA INTEROP SKIPPED: the Vulkan device does not enable external memory/semaphore fds"); report.put("skipped", "no Vulkan external fds"); return@withBoxContextNow
            }
            fun pump(n: Int = 1) { val end = renderer.frameCounter + n; while (renderer.frameCounter < end) { window.pollEvents(); renderer.render() } }
            val vkUuid = ExportedTimelineSemaphore.deviceUuid(renderer)
            val device = driver.deviceByUuid(vkUuid) ?: error("No CUDA device has the Vulkan device's UUID ${vkUuid.joinToString("") { "%02x".format(it) }}; CUDA sees ${driver.devices}")
            report.put("vulkanDevice", renderer.device.physicalDevice.vkPhysicalDeviceProperties.deviceNameString()).put("cudaDevice", device.toString())
            println("Vulkan device = $device")
            val ctx = device.primaryContext(); val stream = ctx.stream()
            val width = 1920; val height = 1080; val pixels = width * height
            val sem = ExportedTimelineSemaphore(renderer)
            var timeline = 0L

            fun record(body: (CommandBuffer) -> Unit): CommandBuffer =
                CommandBuffer(renderer.commandPool, true, true).also { it.beginRecording(); body(it); it.endRecording() }
            /** Vulkan releases [shared], [work] runs on the other side between wait(v+1) and signal(v+2), Vulkan acquires and waits. Wall ms. */
            fun roundTrip(shared: List<VulkanBuffer>, work: (waitFor: Long, thenSignal: Long) -> Unit): Double {
                val release = record { ExportedTimelineSemaphore.releaseToExternal(it, shared) }
                val acquire = record { ExportedTimelineSemaphore.acquireFromExternal(it, shared) }
                val fence = Fence(renderer.device, false)
                try {
                    val t0 = System.nanoTime()
                    sem.submit(release, null, timeline + 1)
                    work(timeline + 1, timeline + 2)
                    sem.submit(acquire, timeline + 2, timeline + 3, fence)
                    fence.fenceWait(); timeline += 3
                    return ms(t0)
                } finally { fence.cleanup(); release.cleanup(); acquire.cleanup() }
            }
            fun exportable(bytes: Long) = VulkanBuffer(renderer, bytes,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, needsDeviceAddress = true, exportOpaqueFd = true)

            val scene = PathTraceScene().apply {
                val floor = material(PathTraceMaterial.diffuse(.45f, .5f, .55f)); val wall = material(PathTraceMaterial.diffuse(.7f, .68f, .62f))
                val red = material(PathTraceMaterial.plastic(.65f, .055f, .025f, .22f)); val gold = material(PathTraceMaterial.metal(.95f, .7f, .3f, .12f))
                val blue = material(PathTraceMaterial.metal(.3f, .5f, .8f, .5f)); val light = material(PathTraceMaterial.emissive(40f, 28f, 18f))
                quad(Vector3f(-15f, 0f, -15f), Vector3f(-15f, 0f, 15f), Vector3f(15f, 0f, 15f), Vector3f(15f, 0f, -15f), floor)
                quad(Vector3f(-15f, 0f, -2.5f), Vector3f(15f, 0f, -2.5f), Vector3f(15f, 8f, -2.5f), Vector3f(-15f, 8f, -2.5f), wall)
                sphere(Vector3f(-1.7f, .8f, 0f), .8f, red, 48, 24); sphere(Vector3f(0f, .8f, 0f), .8f, gold, 48, 24); sphere(Vector3f(1.7f, .8f, 0f), .8f, blue, 48, 24)
                quad(Vector3f(-2.6f, 3.2f, -.4f), Vector3f(-1.8f, 3.2f, -.4f), Vector3f(-1.8f, 3.2f, .4f), Vector3f(-2.6f, 3.2f, .4f), light)
            }
            val camera = Camera(renderer).also { it.at.set(3.5f, 2.4f, 7.5f); it.target.set(0f, .65f, 0f); it.p.fov = .62 }
            val tracer = PathTracingRenderer(renderer, scene, PathTraceEnvironment.constant(.02f, .025f, .04f), width, height, camera,
                PathTracingRenderer.Backend.COMPUTE_BVH, name = "cuda-path", exportAccumulation = true).install()
            val den = OidnComputeDenoiser(tracer, exportGuides = true).install().also { it.denoiseAt = 0; it.show = false }
            while (tracer.samples < spp) pump(1)
            report.put("spp", tracer.samples)

            // ---- Proof 1: native OIDN on CUDA, reading the tracer's accumulation and the port's guides in place.
            val nativeOut = exportable(pixels * 16L)
            val proof1 = JSONObject(); report.put("oidnNative", proof1)
            val oidnLib = oidnDir.resolve("libOpenImageDenoise.so")
            if (!oidnLib.isRegularFile()) { println("PROOF 1 SKIPPED: no $oidnLib (pass --oidn <dir>)"); proof1.put("skipped", "no $oidnLib") }
            else {
                val oidn = Oidn(oidnDir)
                val dev = oidn.Device(3, uuid = vkUuid)
                expect(dev.deviceType == 3, "OIDN opened a CUDA device from the Vulkan UUID (type ${dev.deviceType})")
                val shared = listOf(tracer.accumulationBuffer, den.albedo, den.normal, nativeOut)
                val buffers = shared.map { dev.sharedBuffer(it.exportOpaqueFd(), it.exportedAllocationSize) }
                val osem = dev.sharedTimeline(sem.exportFd())
                proof1.put("sharedAllocationBytes", shared.map { it.exportedAllocationSize })
                val filters = listOf(6, 5).associateWith { q ->
                    dev.filter(width, height, mapOf("color" to buffers[0], "albedo" to buffers[1], "normal" to buffers[2], "output" to buffers[3]), hdr = true, quality = q)
                }
                fun denoiseShared(q: Int) = roundTrip(shared) { w, s -> dev.waitAsync(osem, w); dev.runAsync(filters.getValue(q)); dev.signalAsync(osem, s) }
                val first = denoiseShared(6)
                val rounds = (1..15).map { denoiseShared(6) }
                val roundsBalanced = (1..15).map { denoiseShared(5) }
                // OIDN alone (blocking execute on the same shared buffers): comparable with the 6.5 / 4.9 ms copy-route figures.
                fun blocking(q: Int) = (1..15).map { val t0 = System.nanoTime(); dev.run(filters.getValue(q)); ms(t0) }
                val execHigh = blocking(6); val execBalanced = blocking(5)
                denoiseShared(6) // leave the high-quality result in nativeOut, handed back to Vulkan
                val viaVulkan = den.read(nativeOut)
                val viaOidn = dev.read(buffers[3], pixels * 4)
                val noisy = tracer.readLinear()
                // Field's Vulkan port on the very same inputs.
                val portMs = median((1..7).map { den.denoiseNow().first().second })
                val port = den.read(den.denoised)
                val aliasDiff = viaVulkan.indices.maxOf { abs(viaVulkan[it] - viaOidn[it]) }
                expect(aliasDiff == 0f, "native OIDN output read through Vulkan equals it read through OIDN (max diff $aliasDiff)")
                expect(viaVulkan.all { it.isFinite() } && viaVulkan.indices.filter { it % 4 != 3 }.sumOf { viaVulkan[it].toDouble() } > 0, "native output is finite and not black")
                val pNativePort = psnr(viaVulkan, port); val pNoisyPort = psnr(noisy, port)
                expect(pNativePort > 35 && pNativePort > pNoisyPort + 6,
                    "native CUDA OIDN agrees with Field's Vulkan port: ${"%.1f".format(pNativePort)} dB (noisy input vs port: ${"%.1f".format(pNoisyPort)} dB)")
                proof1.put("firstRoundTripMs", first).put("roundTripMsMedian", median(rounds)).put("roundTripMsMin", rounds.min())
                    .put("roundTripBalancedMsMedian", median(roundsBalanced))
                    .put("blockingExecuteMsMedian", median(execHigh)).put("blockingExecuteBalancedMsMedian", median(execBalanced))
                    .put("fieldVulkanPortMsMedian", portMs).put("cooperativeMatrix", den.cooperativeMatrix)
                    .put("vulkanVsOidnReadbackMaxDiff", aliasDiff).put("psnrNativeVsPort", pNativePort).put("psnrNoisyVsPort", pNoisyPort)
                ReconstructionCapture.save(File(folder, "noisy-${tracer.samples}spp.png"), noisy, width, height)
                ReconstructionCapture.save(File(folder, "oidn-cuda-native.png"), viaVulkan, width, height)
                ReconstructionCapture.save(File(folder, "oidn-vulkan-port.png"), port, width, height)
                filters.values.forEach { dev.release(it) }; buffers.forEach { dev.releaseBuf(it) }; dev.releaseSem(osem); dev.close()
            }

            // ---- Proof 2: a CUDA kernel fills an exported buffer; the tracer's output texture shows it.
            val proof2 = JSONObject(); report.put("kernel", proof2)
            val target = exportable(pixels * 16L)
            val mem = ctx.importMemory(target.exportOpaqueFd(), target.exportedAllocationSize, dedicated = true)
            val csem = ctx.importSemaphore(sem.exportFd(), timeline = true)
            val start = ctx.event(); val end = ctx.event()
            fun verify(kernel: String, expected: (Int, Int) -> FloatArray, launch: () -> Unit, json: JSONObject) {
                val kernelMs = ArrayList<Double>()
                val trips = (1..10).map {
                    roundTrip(listOf(target)) { w, s -> csem.wait(stream, w); start.record(stream); launch(); end.record(stream); csem.signal(stream, s) }
                        .also { end.synchronize(); kernelMs += end.elapsedMs(start).toDouble() }
                }
                val want = FloatArray(pixels * 4).also { e -> for (y in 0 until height) for (x in 0 until width) expected(x, y).copyInto(e, (y * width + x) * 4) }
                val cudaSide = ctx.copyToHost(mem.pointer, pixels * 16L).toArray(java.lang.foreign.ValueLayout.JAVA_FLOAT)
                val vulkanSide = den.read(target)
                tracer.resolveFrom(target); pump(3)
                val shown = ReconstructionCapture.read(renderer, tracer.output)
                tracer.resolveFrom(null)
                val dCuda = want.indices.maxOf { abs(want[it] - cudaSide[it]) }; val dVulkan = want.indices.maxOf { abs(want[it] - vulkanSide[it]) }
                val dShown = maxRgbDiff(want, shown)
                expect(dCuda == 0f, "$kernel: CUDA readback matches the expected values exactly (max diff $dCuda)")
                expect(dVulkan == 0f, "$kernel: Vulkan readback of the same buffer matches exactly (max diff $dVulkan)")
                expect(dShown < 1e-3f, "$kernel: Field's displayed output texture shows it (max RGB diff $dShown)")
                json.put("kernelMsMedian", median(kernelMs)).put("roundTripMsMedian", median(trips))
                    .put("cudaReadbackMaxDiff", dCuda).put("vulkanReadbackMaxDiff", dVulkan).put("displayedMaxDiff", dShown)
                ReconstructionCapture.save(File(folder, "displayed-$kernel.png"), shown, width, height)
            }
            val grid = Triple((width + 15) / 16, (height + 15) / 16, 1); val block = Triple(16, 16, 1)
            val fill = ctx.module(FILL_PTX)
            verify("ptx-fill", { x, y -> floatArrayOf(x.toFloat() / width, y.toFloat() / height, .25f, 1f) },
                { fill.function("field_fill").launch(grid, block, stream, mem.pointer, width, height, .25f) }, JSONObject().also { proof2.put("ptx", it) })
            val nvrtc = Nvrtc.loaded
            val rings = nvrtc.mapCatching { it.compile(RINGS_CU, device.computeCapability, options = listOf("--fmad=false")) }
            rings.onSuccess { ptx ->
                proof2.put("nvrtc", JSONObject().put("library", nvrtc.getOrThrow().library).put("version", nvrtc.getOrThrow().version).also { j ->
                    val m = ctx.module(ptx)
                    verify("nvrtc-rings", { x, y -> val u = (x + .5f) / width - .5f; val v = (y + .5f) / height - .5f
                        floatArrayOf(4f * (u * u + v * v), u + .5f, .75f, 1f) },
                        { m.function("field_rings").launch(grid, block, stream, mem.pointer, width, height, .75f) }, j)
                    m.close()
                })
            }.onFailure { println("NVRTC VARIANT SKIPPED: ${it.message}"); proof2.put("nvrtcSkipped", it.message) }

            // ---- Proof 3: the box-facing session. run{} once, then everyFrame between frames.
            val proof3 = JSONObject(); report.put("cudaVulkan", proof3)
            val session = CudaVulkan.forRenderer(renderer)
            expect(session.device.uuid.contentEquals(vkUuid) && CudaVulkan.forRenderer(renderer) === session, "CudaVulkan.forRenderer: one session per renderer, on the Vulkan GPU")
            val cells = 4096
            val strip = session.sharedBuffer("strip", cells * 16L)
            expect(session.sharedBuffer("strip", cells * 16L) === strip, "sharedBuffer returns the same buffer for the same name and size")
            val stepper = rings.map { session.compile(RINGS_CU, listOf("--fmad=false")).function("field_rings") }.getOrNull()
            val filler = session.module(FILL_PTX).function("field_fill")
            proof3.put("runMs", session.run { s -> filler.launch(Triple((cells + 255) / 256, 1, 1), Triple(256, 1, 1), s, strip.pointer, cells, 1, -1f) })
            val afterRun = den.read(strip.buffer)
            expect((0 until cells).all { afterRun[it * 4 + 2] == -1f }, "CudaVulkan.run: the kernel's writes are visible to Vulkan afterwards")
            var frameT = 0f
            val before = session.framesWithCuda
            session.everyFrame("strip") { s -> frameT += 1f; filler.launch(Triple((cells + 255) / 256, 1, 1), Triple(256, 1, 1), s, strip.pointer, cells, 1, frameT) }
            pump(30)
            session.stopEveryFrame("strip"); pump(2); renderer.graphQueue.waitIdle(); session.stream.synchronize()
            val afterFrames = den.read(strip.buffer)
            val ran = session.framesWithCuda - before
            expect(ran >= 30 && afterFrames[2] == frameT && (0 until cells).all { afterFrames[it * 4 + 2] == frameT },
                "CudaVulkan.everyFrame: ran on $ran frames, buffer holds the last frame's value ${afterFrames[2]} (expected $frameT)")
            proof3.put("framesWithCuda", ran).put("nvrtcModule", stepper != null)
            session.close()

            stream.synchronize(); renderer.graphQueue.waitIdle()
            fill.close(); start.close(); end.close(); csem.close(); mem.close(); stream.close(); ctx.close()
            den.close(); tracer.close(); pump(4)
            renderer.graphQueue.waitIdle(); sem.close(); target.cleanup(); nativeOut.cleanup()
        } } catch (e: Throwable) { e.printStackTrace(); failures += "exception: $e" }
        finally { renderer.cleanup(); window.cleanup() }
        report.put("failures", failures)
        File(folder, "measurements.json").writeText(report.toString(2)); println(report.toString(2))
        println(if (failures.isEmpty()) "CUDA INTEROP PASSED" else "CUDA INTEROP FAILED: ${failures.size}")
        exitProcess(if (failures.isEmpty()) 0 else 1)
    }
}
