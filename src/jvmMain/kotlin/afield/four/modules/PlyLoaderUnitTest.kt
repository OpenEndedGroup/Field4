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
tags: [smoke, four, ply, io]
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
        
        println("=".repeat(50))
        println(if (allPassed) "All tests PASSED ✓" else "Some tests FAILED ✗")
        
        return allPassed
    }
}

// Main function for running tests standalone
fun main() {
    val success = PlyLoaderUnitTest.runTests()
    System.exit(if (success) 0 else 1)
}
