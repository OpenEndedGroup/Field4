package afield.nano

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GLSL compute-shader implementation of the Nano VM.
 *
 * The shader (`nano_compute.glsl`) executes one independent Nano VM instance
 * per GPU invocation.  Instances share four flat GPU buffers, each partitioned
 * into non-overlapping byte-ranges.  A fifth *mapping* buffer (binding 0)
 * tells the shader where each instance's slice begins.
 *
 * ## Buffer bindings
 *
 * | binding | layout           | access | description                          |
 * |---------|------------------|--------|--------------------------------------|
 * | 0       | `NanoMapping[]`  | r-only | dispatch-id → slice offsets          |
 * | 1       | `uint[]` (bytes) | r/w    | RAM: persistent working memory       |
 * | 2       | `uint[]` (bytes) | r-only | code: assembled Nano programs        |
 * | 3       | `uint[]` (bytes) | r/w    | OUT stream: results of OUT rX        |
 *
 * ### Byte-packing
 * All byte buffers pack 4 bytes per `uint`: byte at logical offset B lives in
 * word `B >> 2` at bit-shift `(B & 3) << 3`.  The host must honour this layout
 * when uploading code/RAM and when reading back OUT results.
 *
 * ## Push constants (16 bytes, std430)
 *
 * | field            | type   | description                                  |
 * |------------------|--------|----------------------------------------------|
 * | numInstances     | uint32 | total instances (over-dispatch guard)        |
 * | defaultMaxSteps  | uint32 | step limit when mapping.maxSteps == 0        |
 * | ramSize          | uint32 | RAM bytes per instance; **must be power-of-2**|
 * | codeSize         | uint32 | code bytes per instance (≤ 256)              |
 *
 * ## Mapping buffer layout (NanoMapping struct, 16 bytes each)
 *
 * | field      | type   | description                                       |
 * |------------|--------|---------------------------------------------------|
 * | ramBase    | uint32 | byte offset into RAM buffer for this instance     |
 * | codeBase   | uint32 | byte offset into code buffer for this instance    |
 * | outputBase | uint32 | byte offset into OUT buffer for this instance     |
 * | maxSteps   | uint32 | per-instance step cap (0 → use push-constant)     |
 *
 * ## Minimal dispatch example (pseudocode)
 *
 * ```kotlin
 * val N         = 1024               // number of Nano instances
 * val ramSize   = 32                 // bytes per VM
 * val codeSize  = 64                 // bytes per program
 * val outSize   = 64                 // max OUT bytes per VM
 *
 * val mappings  = NanoComputeShader.buildMappings(N, ramSize, codeSize, outSize)
 * // upload mappings, ramBytes (N*ramSize), codeBytes (N*codeSize), outBytes (N*outSize)
 * // bind as buffers 0..3
 * // dispatch ceil(N / 64) groups of 64
 * ```
 *
 * See [buildMappings] for a helper that generates the mapping buffer, and
 * [packBytes] / [unpackBytes] for the byte-packing convention.
 */
object NanoComputeShader {

    /** Source of the `nano_compute.glsl` compute shader. */
    val SOURCE: String by lazy {
        NanoComputeShader::class.java
            .getResourceAsStream("/nano_compute.glsl")
            ?.bufferedReader()
            ?.readText()
            ?: error("nano_compute.glsl not found on classpath")
    }

    // ── Mapping buffer ────────────────────────────────────────────────────────

    /**
     * Kotlin mirror of the `NanoMapping` GPU struct (16 bytes, std430).
     *
     * @param ramBase    byte offset into the RAM buffer for this instance
     * @param codeBase   byte offset into the code buffer for this instance
     * @param outputBase byte offset into the OUT buffer for this instance
     * @param maxSteps   per-instance step cap; 0 means use the push-constant default
     */
    data class NanoMapping(
        val ramBase:    Int,
        val codeBase:   Int,
        val outputBase: Int,
        val maxSteps:   Int = 0
    )

    /**
     * Build a flat [ByteBuffer] containing one [NanoMapping] per instance,
     * with each instance allocated contiguous, non-overlapping slices.
     *
     * Slice layout (zero-based):
     * - RAM  : `i * ramSize` … `i * ramSize + ramSize - 1`
     * - code : `i * codeSize` … `i * codeSize + codeSize - 1`
     * - OUT  : `i * outSize` … `i * outSize + outSize - 1`
     *
     * @param numInstances number of VM instances
     * @param ramSize      RAM bytes per instance (must be power of 2, typically 32)
     * @param codeSize     code bytes per instance (typically 64)
     * @param outSize      OUT-stream bytes allocated per instance
     * @param maxSteps     per-instance step cap (0 → use push-constant default)
     */
    fun buildMappings(
        numInstances: Int,
        ramSize:      Int = NanoVM.RAM_SIZE,
        codeSize:     Int = 64,
        outSize:      Int = 64,
        maxSteps:     Int = 0
    ): ByteBuffer {
        require(numInstances > 0)           { "numInstances must be > 0" }
        require(ramSize  > 0 && (ramSize  and (ramSize  - 1)) == 0) {
            "ramSize must be a power of 2 (got $ramSize)"
        }
        require(codeSize > 0)               { "codeSize must be > 0" }
        require(outSize  >= 0)              { "outSize must be >= 0" }

        // Each NanoMapping = 4 × uint32 = 16 bytes
        val buf = ByteBuffer.allocate(numInstances * 16)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numInstances) {
            buf.putInt(i * ramSize)    // ramBase
            buf.putInt(i * codeSize)   // codeBase
            buf.putInt(i * outSize)    // outputBase
            buf.putInt(maxSteps)       // maxSteps
        }
        buf.flip()
        return buf
    }

    // ── Byte-packing helpers ──────────────────────────────────────────────────

    /**
     * Pack [bytes] into a `uint[]`-compatible [ByteBuffer] using the shader's
     * 4-bytes-per-uint little-endian layout.
     *
     * The returned buffer is ready for upload to a Vulkan SSBO.  Its capacity
     * is `ceil(bytes.size / 4) * 4` bytes.
     */
    fun packBytes(bytes: ByteArray): ByteBuffer {
        val wordCount = (bytes.size + 3) / 4
        val buf = ByteBuffer.allocate(wordCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(bytes)
        // zero-pad to word boundary (ByteBuffer.allocate already zero-fills)
        buf.flip()
        return buf
    }

    /**
     * Unpack [length] bytes from a `uint[]`-packed [ByteBuffer] starting at
     * byte offset [baseOffset].
     *
     * Use this to read back RAM or OUT results after a dispatch.
     */
    fun unpackBytes(buf: ByteBuffer, baseOffset: Int, length: Int): ByteArray {
        require(baseOffset >= 0 && baseOffset + length <= buf.limit()) {
            "range [$baseOffset, ${baseOffset + length}) out of buffer bounds (limit=${buf.limit()})"
        }
        val out = ByteArray(length)
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(baseOffset)
        view.get(out)
        return out
    }

    /**
     * Pack [numInstances] assembled programs (each up to [codeSize] bytes) into
     * a single flat [ByteBuffer] ready for binding as the code SSBO.
     *
     * Programs shorter than [codeSize] are zero-padded (byte 0x00 = NOP).
     */
    fun packPrograms(
        programs: List<ByteArray>,
        codeSize: Int = 64
    ): ByteBuffer {
        val wordCount = (programs.size * codeSize + 3) / 4
        val buf = ByteBuffer.allocate(wordCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (prog in programs) {
            val slice = ByteArray(codeSize)
            prog.copyInto(slice, endIndex = minOf(prog.size, codeSize))
            buf.put(slice)
        }
        buf.flip()
        return buf
    }

    // ── Assemble convenience ──────────────────────────────────────────────────

    /**
     * Assemble [source] with [NanoAssembler] and return the raw bytes, or throw
     * [NanoAssemblyException] on error.  Useful for building code buffers inline.
     *
     * Delegates to the top-level `nanoAssemble()` function in `NanoInterface.kt`
     * (same package).
     */
    fun assemble(source: String): ByteArray = nanoAssemble(source)
}
