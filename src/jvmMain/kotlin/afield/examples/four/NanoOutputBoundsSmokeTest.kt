package afield.examples.four

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.GpuProbe
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.lang.nano.NanoComputeShader
import afield.lang.nano.NanoVM
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.nano.output-bounds
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.NanoOutputBoundsSmokeTest
tags: [smoke, four, gpu, compute, nano]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "Nano GPU output bounds PASSED"
*/
/** Runs the production shader on adjacent byte slices, including shared packed words. */
object NanoOutputBoundsSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        check(
            runCatching { NanoComputeShader.buildMappings(2, outSize = -1) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        check(
            runCatching { NanoComputeShader.buildMappings(2, maxSteps = -1) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        check(
            runCatching { NanoComputeShader.buildMappings(2, outSize = Int.MAX_VALUE) }.exceptionOrNull()
                is ArithmeticException,
        )
        Options.enableVR = false
        val window = Window("Nano output bounds")
        val renderer = Renderer(window)
        try {
            for (capacity in listOf(0, 1, 3, 4)) {
                GpuProbe(
                        renderer,
                        "nano-out-$capacity",
                        pump = {
                            window.pollEvents()
                            renderer.render()
                        },
                    )
                    .use { probe ->
                        val count = 32
                        val programs =
                            (0 until count).map { i ->
                                // LDI r0,i+1; OUT r0; INC r0; JMP back to OUT.
                                byteArrayOf(0xc0.toByte(), (i + 1).toByte(), 0x18, 0x04, 0xe0.toByte(), 0xfc.toByte())
                            }
                        val mapping =
                            NanoComputeShader.buildMappings(count, codeSize = 8, outSize = capacity, maxSteps = 100)
                        // Keep guard bytes before and after the entire allocation as well as per-VM bounds.
                        for (i in 0 until count) mapping.putInt(i * 20 + 8, 4 + i * capacity)
                        val code = NanoComputeShader.packPrograms(programs, 8)
                        val mappings = probe.fixture("mappings", mapping.remaining() / 4)
                        mappings.mapAndWrite_bytes { put(mapping.duplicate()) }
                        val ram = probe.fixture("ram", count * 32 / 4)
                        ram.mapAndWrite_bytes { while (hasRemaining()) put(0) }
                        val codes = probe.fixture("codes", code.remaining() / 4)
                        codes.mapAndWrite_bytes { put(code.duplicate()) }
                        val outputBytes = 4 + count * capacity + 16
                        val output = probe.fixture("out", outputBytes / 4)
                        output.mapAndWrite_bytes { while (hasRemaining()) put(0x7e.toByte()) }
                        val sizes = mapOf(
                            "MappingBuffer" to mapping.capacity(),
                            "RamBuffer" to count * 32,
                            "CodeBuffer" to code.capacity(),
                            "OutBuffer" to outputBytes,
                        )
                        val buffers = mapOf(
                            "MappingBuffer" to mappings,
                            "RamBuffer" to ram,
                            "CodeBuffer" to codes,
                            "OutBuffer" to output,
                        )
                        val stage =
                            probe
                                .stage("bounded-out", NanoComputeShader.SOURCE) {
                                    ssboResolver = { requested ->
                                        requested.apply {
                                            size = sizes.getValue(name).toLong()
                                            addressable()
                                            streamReads()
                                            result = buffers.getValue(name)
                                        }
                                    }
                                }
                                .dispatch(groups = 1) {
                                    bind("numInstances") { count }
                                    bind("defaultMaxSteps") { 200 }
                                    bind("ramSize") { 32 }
                                    bind("codeSize") { 8 }
                                }
                        check(stage.valid) { probe.verdictLines("Nano compilation").joinToString("\n") }
                        // The untouched guard bytes are the assertion: sentinel-based settle would
                        // deliberately overwrite them and wait for writes that must never happen.
                        probe.pumpFrames(4)
                        probe.idle()
                        val actual = ByteBuffer.allocate(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
                        probe.readInts(output).forEach(actual::putInt)
                        val expected = ByteArray(outputBytes) { 0x7e.toByte() }
                        for (i in programs.indices) {
                            val cpuOutput = mutableListOf<Int>()
                            NanoVM(maxSteps = 100).apply { onOutput = { cpuOutput += it } }.run(programs[i])
                            check(cpuOutput.size > capacity)
                            cpuOutput.take(capacity).forEachIndexed { j, value ->
                                expected[4 + i * capacity + j] = value.toByte()
                            }
                        }
                        check(actual.array().contentEquals(expected)) {
                            "Output escaped or corrupted a $capacity-byte slice"
                        }
                        println("Nano capacity=$capacity: 32 CPU prefixes match; guard bytes preserved")
                    }
            }
            println("Nano GPU output bounds PASSED")
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }
}
