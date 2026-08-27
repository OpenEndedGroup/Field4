package afield.four.modules

import afield.four.fabricated.GaussianSplatRenderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
FIELD_TEST
id: four.ply-loader.smoke
runner: jvm-main
entrypoint: afield.four.modules.PlyLoaderUnitTestKt
tags: [smoke, four, ply, splat, io]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "All tests PASSED"
*/
/**
 * Unit tests for PLY loader that don't require graphics context
 */
object PlyLoaderUnitTest {
    
    private val testDir = File(System.getProperty("java.io.tmpdir"), "ply_test")
    
    fun createTestFiles() {
        testDir.mkdirs()
        
        // ASCII triangle mesh
        File(testDir, "test_ascii_triangle.ply").writeText("""
ply
format ascii 1.0
comment Test triangle mesh
element vertex 4
property float x
property float y
property float z
property float nx
property float ny
property float nz
property uchar red
property uchar green
property uchar blue
element face 2
property list uchar int vertex_indices
end_header
0.0 0.0 0.0 0.0 0.0 1.0 255 0 0
1.0 0.0 0.0 0.0 0.0 1.0 0 255 0
1.0 1.0 0.0 0.0 0.0 1.0 0 0 255
0.0 1.0 0.0 0.0 0.0 1.0 255 255 0
3 0 1 2
3 0 2 3
        """.trimIndent())
        
        // ASCII point cloud
        File(testDir, "test_ascii_pointcloud.ply").writeText("""
ply
format ascii 1.0
comment Test point cloud
element vertex 8
property float x
property float y
property float z
property float nx
property float ny
property float nz
property uchar red
property uchar green
property uchar blue
end_header
-1.0 -1.0 -1.0 -0.577 -0.577 -0.577 255 0 0
1.0 -1.0 -1.0 0.577 -0.577 -0.577 0 255 0
1.0 1.0 -1.0 0.577 0.577 -0.577 0 0 255
-1.0 1.0 -1.0 -0.577 0.577 -0.577 255 255 0
-1.0 -1.0 1.0 -0.577 -0.577 0.577 255 0 255
1.0 -1.0 1.0 0.577 -0.577 0.577 0 255 255
1.0 1.0 1.0 0.577 0.577 0.577 255 255 255
-1.0 1.0 1.0 -0.577 0.577 0.577 128 128 128
        """.trimIndent())
        
        // Minimal (no colors, no normals)
        File(testDir, "test_minimal.ply").writeText("""
ply
format ascii 1.0
element vertex 3
property float x
property float y
property float z
element face 1
property list uchar int vertex_indices
end_header
0.0 0.0 0.0
1.0 0.0 0.0
0.5 1.0 0.0
3 0 1 2
        """.trimIndent())
        
        // Binary little-endian
        createBinaryPly(testDir, "test_binary_le.ply", ByteOrder.LITTLE_ENDIAN)
        
        // Binary big-endian
        createBinaryPly(testDir, "test_binary_be.ply", ByteOrder.BIG_ENDIAN)
        
        println("Test files created in $testDir")
    }
    
    private fun createBinaryPly(dir: File, filename: String, byteOrder: ByteOrder) {
        val file = File(dir, filename)
        val header = """
ply
format ${if (byteOrder == ByteOrder.LITTLE_ENDIAN) "binary_little_endian" else "binary_big_endian"} 1.0
element vertex 3
property float x
property float y
property float z
property uchar red
property uchar green
property uchar blue
element face 1
property list uchar int vertex_indices
end_header
""".trimIndent() + "\n"
        
        val headerBytes = header.toByteArray()
        
        // Create binary vertex data
        val buffer = ByteBuffer.allocate(3 * (3 * 4 + 3) + 1 + 3 * 4) // 3 verts + 1 face
        buffer.order(byteOrder)
        
        // Vertex 0: (0, 0, 0) red
        buffer.putFloat(0f).putFloat(0f).putFloat(0f)
        buffer.put(255.toByte()).put(0.toByte()).put(0.toByte())
        
        // Vertex 1: (1, 0, 0) green
        buffer.putFloat(1f).putFloat(0f).putFloat(0f)
        buffer.put(0.toByte()).put(255.toByte()).put(0.toByte())
        
        // Vertex 2: (0.5, 1, 0) blue
        buffer.putFloat(0.5f).putFloat(1f).putFloat(0f)
        buffer.put(0.toByte()).put(0.toByte()).put(255.toByte())
        
        // Face: triangle (0, 1, 2)
        buffer.put(3.toByte()) // count
        buffer.putInt(0).putInt(1).putInt(2)
        
        file.writeBytes(headerBytes + buffer.array())
    }
    
    fun runTests(): Boolean {
        var allPassed = true
        
        println("Running PLY Loader Unit Tests")
        println("=".repeat(50))
        
        createTestFiles()
        
        // Test 1: ASCII Triangle Mesh
        print("Test 1: ASCII Triangle Mesh... ")
        try {
            val model = PlySupport.loadPly(File(testDir, "test_ascii_triangle.ply").absolutePath)
            assert(model.buffers.vertexCount == 4) { "Expected 4 vertices" }
            assert(model.buffers.indexCount == 6) { "Expected 6 indices" }
            assert(model.buffers.hasColors) { "Expected colors" }
            assert(model.buffers.hasNormals) { "Expected normals" }
            assert(!model.buffers.isPointCloud) { "Should not be point cloud" }
            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }
        
        // Test 2: ASCII Point Cloud
        print("Test 2: ASCII Point Cloud... ")
        try {
            val model = PlySupport.loadPly(File(testDir, "test_ascii_pointcloud.ply").absolutePath)
            assert(model.buffers.vertexCount == 8) { "Expected 8 vertices" }
            assert(model.buffers.isPointCloud) { "Should be point cloud" }
            assert(model.buffers.hasColors) { "Expected colors" }
            assert(model.buffers.hasNormals) { "Expected normals" }
            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }
        
        // Test 3: Minimal PLY
        print("Test 3: Minimal PLY (no colors/normals)... ")
        try {
            val model = PlySupport.loadPly(File(testDir, "test_minimal.ply").absolutePath)
            assert(model.buffers.vertexCount == 3) { "Expected 3 vertices" }
            assert(model.buffers.indexCount == 3) { "Expected 3 indices" }
            assert(!model.buffers.hasColors) { "Should not have colors" }
            assert(!model.buffers.hasNormals) { "Should not have normals" }
            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }
        
        // Test 4: Binary Little-Endian
        print("Test 4: Binary Little-Endian... ")
        try {
            val model = PlySupport.loadPly(File(testDir, "test_binary_le.ply").absolutePath)
            assert(model.buffers.vertexCount == 3) { "Expected 3 vertices" }
            assert(model.buffers.indexCount == 3) { "Expected 3 indices" }
            assert(model.buffers.hasColors) { "Expected colors" }
            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }
        
        // Test 5: Binary Big-Endian
        print("Test 5: Binary Big-Endian... ")
        try {
            val model = PlySupport.loadPly(File(testDir, "test_binary_be.ply").absolutePath)
            assert(model.buffers.vertexCount == 3) { "Expected 3 vertices" }
            assert(model.buffers.indexCount == 3) { "Expected 3 indices" }
            assert(model.buffers.hasColors) { "Expected colors" }
            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }
        
        // Test 6: Vertex data validation
        print("Test 6: Vertex Data Validation... ")
        try {
            val model = PlySupport.loadPly(File(testDir, "test_minimal.ply").absolutePath)
            val vd = model.buffers.vertexData
            vd.rewind()
            
            // First vertex should be at origin (0, 0, 0)
            val x = vd.float
            val y = vd.float
            val z = vd.float
            assert(x == 0f && y == 0f && z == 0f) { "First vertex position incorrect: ($x, $y, $z)" }
            
            // Skip normal (3 floats) and color (4 floats)
            vd.position(vd.position() + 7 * 4)
            
            // Second vertex should be at (1, 0, 0)
            val x2 = vd.float
            val y2 = vd.float
            val z2 = vd.float
            assert(x2 == 1f && y2 == 0f && z2 == 0f) { "Second vertex position incorrect: ($x2, $y2, $z2)" }
            
            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }

        // Test 7: Float table + Gaussian splat CPU packing
        print("Test 7: Float Table + Gaussian Splat Packing... ")
        try {
            val table = readPlyAsFloatTable(File(testDir, "test_minimal.ply").absolutePath)
            check(table.rowCount == 3) { "Expected 3 rows" }
            check(table.stride == 3) { "Expected x/y/z stride" }
            check(table.floatAt(1, "x") == 1f) { "Expected second x to be 1" }

            val packed = GaussianSplatRenderer.pack(
                table,
                GaussianSplatRenderer.Config(scaleIsLog = false, fallbackScale = 0.05f)
            )
            check(packed.count == 3) { "Expected 3 packed splats" }
            check(packed.floats.size == 3 * GaussianSplatRenderer.SPLAT_FLOAT_STRIDE) { "Unexpected packed stride" }
            check(packed.floats[3] == 1f) { "Default opacity should be 1" }
            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }

        // Test 8: Gaussian splat shaders compile and reflect
        print("Test 8: Gaussian Splat Shader Introspection... ")
        try {
            val compute = Introspection(
                GaussianSplatRenderer.depthKeyComputeShader,
                ShaderKind.COMPUTE_SHADER,
                sourceName = "gaussian_splat_depth_keys.comp"
            )
            val vertex = Introspection(
                GaussianSplatRenderer.splatVertexShader,
                ShaderKind.VERTEX_SHADER,
                sourceName = "gaussian_splat.vert"
            )
            val fragment = Introspection(
                GaussianSplatRenderer.splatFragmentShader,
                ShaderKind.FRAGMENT_SHADER,
                sourceName = "gaussian_splat.frag"
            )
            check(compute.valid) { "Depth-key compute shader did not compile" }
            check(vertex.valid) { "Splat vertex shader did not compile" }
            check(fragment.valid) { "Splat fragment shader did not compile" }
            val computePush = compute.pushConstants.flatMap { it.members }
            val vertexPush = vertex.pushConstants.flatMap { it.members }
            check(computePush.any { it.name == "splatData" && it.physicalPointer }) {
                "Depth-key compute shader did not expose splatData buffer reference"
            }
            check(computePush.any { it.name == "depthKeys" && it.physicalPointer }) {
                "Depth-key compute shader did not expose depthKeys buffer reference"
            }
            check(vertexPush.any { it.name == "splatData" && it.physicalPointer }) {
                "Vertex shader did not expose splatData buffer reference"
            }
            check(vertexPush.any { it.name == "sortedIndices" && it.physicalPointer }) {
                "Vertex shader did not expose sortedIndices buffer reference"
            }
            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }
        
        // Test 9: Compressed splat PLY, hand-authored packed words
        print("Test 9: Compressed PLY Bit Layout... ")
        try {
            val file = File(testDir, "test_handmade.compressed.ply")
            writeHandmadeCompressedPly(file)

            val table = readPlyAsFloatTable(file.absolutePath)
            check(table.rowCount == 2) { "Expected 2 splats, got ${table.rowCount}" }
            check(table.columns == CompressedSplatPly.decompressedColumns) {
                "Unexpected columns: ${table.columns}"
            }

            val sqrtPi = 0.5f / CompressedSplatPly.SH_C0
            // Splat 0: position unorms (1, 0, 1) inside x[0,1] y[0,2] z[0,4]; scale word all zero;
            // colour (255, 0, 255, 255); rotation with the real part dropped.
            expect(table, 0, "x", 1f)
            expect(table, 0, "y", 0f)
            expect(table, 0, "z", 4f)
            expect(table, 0, "scale_0", -3f)
            expect(table, 0, "scale_1", -3f)
            expect(table, 0, "scale_2", -3f)
            expect(table, 0, "f_dc_0", sqrtPi)
            expect(table, 0, "f_dc_1", -sqrtPi)
            expect(table, 0, "f_dc_2", sqrtPi)
            expect(table, 0, "opacity", CompressedSplatPly.OPACITY_LOGIT_LIMIT)
            expect(table, 0, "rot_0", 0.00069127f)
            expect(table, 0, "rot_1", 0.00069127f)
            expect(table, 0, "rot_2", 0.00069127f)
            expect(table, 0, "rot_3", 0.99999928f)

            // Splat 1 stores the largest component at index 1, and asymmetric a/b/c, so this pins
            // both the 2,10,10,10 field order and where the reconstructed component goes.
            expect(table, 1, "x", 0f)
            expect(table, 1, "y", 0f)
            expect(table, 1, "z", 0f)
            expect(table, 1, "scale_0", -1f)
            expect(table, 1, "scale_1", -3f)
            expect(table, 1, "scale_2", -1f)
            expect(table, 1, "f_dc_0", -sqrtPi)
            expect(table, 1, "f_dc_1", sqrtPi)
            expect(table, 1, "f_dc_2", -sqrtPi)
            expect(table, 1, "opacity", 0.0078431766f)
            expect(table, 1, "rot_0", -0.70710678f)
            expect(table, 1, "rot_1", 0f)
            expect(table, 1, "rot_2", 0.70710678f)
            expect(table, 1, "rot_3", 0.00069127f)

            // Spherical harmonics stay out of the table unless asked for.
            check(!table.hasColumn("f_rest_0")) { "f_rest_* should be opt-in" }
            val withSh = readPlyAsFloatTable(file.absolutePath, includeSphericalHarmonics = true)
            check(withSh.stride == 14 + 9) { "Expected 23 columns with SH, got ${withSh.stride}" }
            expect(withSh, 0, "f_rest_0", -4f)
            expect(withSh, 0, "f_rest_1", 4f)
            expect(withSh, 0, "f_rest_2", 0.015625f)
            expect(withSh, 0, "f_rest_8", -4f)
            expect(withSh, 1, "f_rest_0", 0.015625f)

            // The raw quantization tables are still reachable by element name.
            val chunks = readPlyAsFloatTable(file.absolutePath, elementName = "chunk")
            check(chunks.rowCount == 1) { "Expected 1 chunk, got ${chunks.rowCount}" }
            check(chunks.floatAt(0, "max_z") == 4f) { "Chunk range not readable" }

            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }

        // Test 10: Compressed splat PLY round-trip against the uncompressed file
        print("Test 10: Compressed PLY Round-Trip... ")
        try {
            val splats = syntheticSplats(600, shCount = 9)
            val compressed = File(testDir, "test_roundtrip.compressed.ply")
            val plain = File(testDir, "test_roundtrip.ply")
            CompressedSplatPlyFixture.write(compressed, splats, withChunkColors = true, withSh = true)
            CompressedSplatPlyFixture.writeUncompressed(plain, splats)

            val got = readPlyAsFloatTable(compressed.absolutePath, includeSphericalHarmonics = true)
            val want = readPlyAsFloatTable(plain.absolutePath)
            check(got.rowCount == splats.size) { "Expected ${splats.size} splats, got ${got.rowCount}" }
            check(got.columns == want.columns) {
                "Column mismatch:\n  compressed ${got.columns}\n  plain      ${want.columns}"
            }
            // 600 splats is three chunks, the last one partial, which is where an off-by-one in the
            // chunk index or the tail padding would show up.
            check(got.rowCount / CompressedSplatPly.CHUNK_SIZE == 2) { "Expected a partial third chunk" }

            for (row in 0 until got.rowCount) {
                near(got, want, row, listOf("x", "y", "z"), 0.0025f)
                near(got, want, row, listOf("scale_0", "scale_1", "scale_2"), 0.004f)
                near(got, want, row, listOf("f_dc_0", "f_dc_1", "f_dc_2"), 0.015f)
                near(got, want, row, (0 until 9).map { "f_rest_$it" }, 0.02f)
                // Opacity is quantized after the sigmoid, so compare where the quantization happened.
                val gotAlpha = sigmoidOf(got.floatAt(row, "opacity"))
                val wantAlpha = sigmoidOf(want.floatAt(row, "opacity"))
                check(kotlin.math.abs(gotAlpha - wantAlpha) <= 0.005f) {
                    "row $row opacity: $gotAlpha vs $wantAlpha"
                }
                nearRotation(got, want, row, 0.005f)
            }

            // And the whole point: the packer downstream is happy with it.
            val packed = GaussianSplatRenderer.pack(got)
            check(packed.count == splats.size) { "Packer dropped splats" }
            check(packed.floats.all { it.isFinite() }) { "Packer produced a non-finite splat" }
            for (row in 0 until packed.count) {
                val base = row * GaussianSplatRenderer.SPLAT_FLOAT_STRIDE
                check(packed.floats[base + 3] in 0f..1f) { "row $row alpha out of range" }
                check(packed.floats[base + 8] > 0f && packed.floats[base + 11] > 0f) {
                    "row $row covariance diagonal is not positive"
                }
            }
            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }

        // Test 11: Compressed splat PLY without the per-chunk colour range (older writers)
        print("Test 11: Compressed PLY, 12-Property Chunks... ")
        try {
            val splats = syntheticSplats(300, shCount = 0)
            val file = File(testDir, "test_nochunkcolor.compressed.ply")
            CompressedSplatPlyFixture.write(file, splats, withChunkColors = false)

            val table = readPlyAsFloatTable(file.absolutePath)
            check(table.rowCount == splats.size) { "Expected ${splats.size} splats" }
            check(table.columns == CompressedSplatPly.decompressedColumns) { "Unexpected columns" }
            // Colour is a plain unorm here, so it is only 8 bits across the whole 0..1 range.
            for (row in 0 until table.rowCount) {
                near(table, splats, row, 0.05f)
            }
            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }

        // Test 12: detection is by schema, and every wrong turn is loud
        print("Test 12: Compressed PLY Detection And Errors... ")
        try {
            val splats = syntheticSplats(10, shCount = 0)

            // The name carries no weight: a compressed file called .ply still decompresses...
            val misnamed = File(testDir, "test_misnamed.ply")
            CompressedSplatPlyFixture.write(misnamed, splats)
            check(CompressedSplatPly.isCompressedSplatPly(misnamed.absolutePath)) { "Should detect" }
            check(readPlyAsFloatTable(misnamed.absolutePath).hasColumn("x")) { "Should decompress" }

            // ...and a plain file called .compressed.ply still reads as plain.
            val liar = File(testDir, "test_liar.compressed.ply")
            CompressedSplatPlyFixture.writeUncompressed(liar, splats)
            check(!CompressedSplatPly.isCompressedSplatPly(liar.absolutePath)) { "Should not detect" }
            check(readPlyAsFloatTable(liar.absolutePath).rowCount == splats.size) { "Should read plain" }
            check(
                runCatching { readCompressedSplatPly(liar.absolutePath) }
                    .exceptionOrNull()?.message?.contains("not a compressed splat PLY") == true
            ) { "Reading a plain file as compressed should say so" }

            // A file that carries the packed words but lies about its chunk count must not fall
            // through to the float reader, which would hand back four floats of nonsense per splat.
            val broken = File(testDir, "test_broken.compressed.ply")
            corruptChunkCount(misnamed, broken)
            val brokenError = runCatching { readPlyAsFloatTable(broken.absolutePath) }.exceptionOrNull()
            check(brokenError != null) { "A malformed compressed PLY should not read silently" }
            check(brokenError.message?.contains("chunks of 256") == true) {
                "Expected the chunk-count reason, got: ${brokenError.message}"
            }

            // And the mesh loader should send you somewhere useful rather than to the origin.
            val meshError = runCatching { PlySupport.loadPly(misnamed.absolutePath) }.exceptionOrNull()
            check(meshError?.message?.contains("gaussianSplats") == true) {
                "Expected the mesh loader to point at gaussianSplats, got: ${meshError?.message}"
            }
            println("✓ PASS")
        } catch (e: AssertionError) {
            println("✗ FAIL: ${e.message}")
            allPassed = false
        } catch (e: Exception) {
            println("✗ ERROR: ${e.message}")
            e.printStackTrace()
            allPassed = false
        }

        println("=".repeat(50))
        println(if (allPassed) "All tests PASSED ✓" else "Some tests FAILED ✗")
        
        return allPassed
    }

    // ------------------------------------------------------------------------
    // Compressed-splat-PLY test support
    // ------------------------------------------------------------------------

    /**
     * A one-chunk, two-splat compressed PLY whose packed words are written out as literal bit
     * patterns rather than produced by an encoder, so that Test 9 checks the reader against the
     * format rather than against a second implementation of it.
     */
    private fun writeHandmadeCompressedPly(file: File) {
        val header = """
ply
format binary_little_endian 1.0
comment hand-authored packed words
element chunk 1
${CompressedSplatPly.requiredChunkProperties.joinToString("\n") { "property float $it" }}
${CompressedSplatPly.colorChunkProperties.joinToString("\n") { "property float $it" }}
element vertex 2
${CompressedSplatPly.vertexProperties.joinToString("\n") { "property uint $it" }}
element sh 2
${(0 until 9).joinToString("\n") { "property uchar f_rest_$it" }}
end_header
""".trimStart()

        val body = ByteBuffer.allocate(18 * 4 + 2 * 16 + 2 * 9).order(ByteOrder.LITTLE_ENDIAN)
        // x[0,1] y[0,2] z[0,4], log-scale [-3,-1] on every axis, colour [0,1] on every channel.
        floatArrayOf(
            0f, 0f, 0f, 1f, 2f, 4f,
            -3f, -3f, -3f, -1f, -1f, -1f,
            0f, 0f, 0f, 1f, 1f, 1f
        ).forEach { body.putFloat(it) }

        // packed_position, packed_rotation, packed_scale, packed_color
        // Splat 0: unorms (1, 0, 1); largest rotation component at index 3; scale all-min; (r,b,a) full.
        body.putInt(0xFFE007FF.toInt()).putInt(0xE0080200.toInt())
            .putInt(0x00000000).putInt(0xFF00FFFF.toInt())
        // Splat 1: unorms (0, 0, 0); largest rotation component at index 1; scale (max, min, max);
        // green full and opacity 128/255.
        body.putInt(0x00000000).putInt(0x400FFE00)
            .putInt(0xFFE007FF.toInt()).putInt(0x00FF0080)

        // sh: the two endpoint bytes and the midpoint, which the format handles as special cases.
        byteArrayOf(0, -1, -128, 0, 0, 0, 0, 0, 0).forEach { body.put(it) }
        repeat(9) { body.put((-128).toByte()) }

        file.outputStream().buffered().use { stream ->
            stream.write(header.toByteArray())
            stream.write(body.array())
        }
    }

    /** Rewrite a compressed PLY's chunk count so the file is recognisably compressed but wrong. */
    private fun corruptChunkCount(source: File, destination: File) {
        val bytes = source.readBytes()
        val text = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val marker = "element chunk 1\n"
        val at = text.indexOf(marker)
        check(at >= 0) { "fixture no longer declares a single chunk" }
        val patched = text.substring(0, at) + "element chunk 9\n" + text.substring(at + marker.length)
        destination.writeBytes(
            patched.toByteArray(Charsets.ISO_8859_1) + bytes.copyOfRange(minOf(bytes.size, 4096), bytes.size)
        )
    }

    /**
     * Deterministic splats spread over enough of each range that quantization error is visible but
     * bounded. Positions live in [-1, 1], log-scales in [-5, -2], SH coefficients inside the
     * (-4, 4) window the format can represent without saturating.
     */
    private fun syntheticSplats(count: Int, shCount: Int): List<CompressedSplatPlyFixture.Splat> {
        var state = 0x9E3779B9L
        fun next(): Float {
            state = (state * 6364136223846793005L + 1442695040888963407L) and 0x7FFFFFFFFFFFFFFFL
            return ((state ushr 24) and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()
        }
        fun span(low: Float, high: Float) = low + next() * (high - low)

        return (0 until count).map {
            val rot = floatArrayOf(span(-1f, 1f), span(-1f, 1f), span(-1f, 1f), span(-1f, 1f))
            val length = kotlin.math.sqrt(rot.sumOf { (it * it).toDouble() }).toFloat()
            CompressedSplatPlyFixture.Splat(
                x = span(-1f, 1f),
                y = span(-1f, 1f),
                z = span(-1f, 1f),
                fDc = FloatArray(3) { span(-1.5f, 1.5f) },
                opacity = span(-4f, 4f),
                rot = FloatArray(4) { rot[it] / length },
                scale = FloatArray(3) { span(-5f, -2f) },
                shRest = FloatArray(shCount) { span(-3.9f, 3.9f) }
            )
        }
    }

    private fun expect(table: PlyFloatTable, row: Int, field: String, want: Float) {
        val got = table.floatAt(row, field)
        val tolerance = kotlin.math.max(1e-5f, kotlin.math.abs(want) * 1e-5f)
        check(kotlin.math.abs(got - want) <= tolerance) {
            "row $row $field: got $got, expected $want"
        }
    }

    private fun near(got: PlyFloatTable, want: PlyFloatTable, row: Int, fields: List<String>, tolerance: Float) {
        for (field in fields) {
            val a = got.floatAt(row, field)
            val b = want.floatAt(row, field)
            check(kotlin.math.abs(a - b) <= tolerance) {
                "row $row $field: decompressed $a, original $b (tolerance $tolerance)"
            }
        }
    }

    /** Compare against the source splats directly, for the variants with no uncompressed twin. */
    private fun near(
        got: PlyFloatTable,
        splats: List<CompressedSplatPlyFixture.Splat>,
        row: Int,
        colorTolerance: Float
    ) {
        val want = splats[row]
        check(kotlin.math.abs(got.floatAt(row, "x") - want.x) <= 0.0025f) { "row $row x" }
        check(kotlin.math.abs(got.floatAt(row, "y") - want.y) <= 0.0025f) { "row $row y" }
        check(kotlin.math.abs(got.floatAt(row, "z") - want.z) <= 0.0025f) { "row $row z" }
        for (channel in 0..2) {
            val a = got.floatAt(row, "f_dc_$channel")
            check(kotlin.math.abs(a - want.fDc[channel]) <= colorTolerance) {
                "row $row f_dc_$channel: $a vs ${want.fDc[channel]}"
            }
        }
    }

    /**
     * A packed rotation loses its overall sign — the encoder negates the quaternion when the dropped
     * component was negative, which names the same orientation — so canonicalize before comparing.
     */
    private fun nearRotation(got: PlyFloatTable, want: PlyFloatTable, row: Int, tolerance: Float) {
        fun read(table: PlyFloatTable): FloatArray {
            val q = FloatArray(4) { table.floatAt(row, "rot_$it") }
            var largest = 0
            for (i in 1..3) if (kotlin.math.abs(q[i]) > kotlin.math.abs(q[largest])) largest = i
            if (q[largest] < 0f) for (i in 0..3) q[i] = -q[i]
            return q
        }

        val a = read(got)
        val b = read(want)
        for (i in 0..3) {
            check(kotlin.math.abs(a[i] - b[i]) <= tolerance) {
                "row $row rot_$i: decompressed ${a[i]}, original ${b[i]}"
            }
        }
    }

    private fun sigmoidOf(logit: Float): Float =
        (1.0 / (1.0 + kotlin.math.exp(-logit.toDouble()))).toFloat()
}

// Main function for running tests standalone
fun main() {
    val success = PlyLoaderUnitTest.runTests()
    System.exit(if (success) 0 else 1)
}
