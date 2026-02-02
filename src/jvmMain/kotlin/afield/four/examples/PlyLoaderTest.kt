package afield.four.examples

import afield.four.modules.PlySupport
import java.io.File

/**
 * Simple test program to verify PLY loader functionality without requiring full graphics setup
 */
fun main() {
    val testDir = File(System.getProperty("java.io.tmpdir"), "ply_test")
    
    println("PLY Loader Test")
    println("================")
    
    // Test 1: ASCII Triangle Mesh
    println("\nTest 1: ASCII Triangle Mesh")
    try {
        val testFile = File(testDir, "test_ascii.ply")
        if (testFile.exists()) {
            val model = PlySupport.loadPly(testFile.absolutePath)
            println("✓ Successfully loaded: ${model.name}")
            println("  - Vertices: ${model.buffers.vertexCount}")
            println("  - Indices: ${model.buffers.indexCount}")
            println("  - Has colors: ${model.buffers.hasColors}")
            println("  - Has normals: ${model.buffers.hasNormals}")
            println("  - Is point cloud: ${model.buffers.isPointCloud}")
            
            if (model.buffers.vertexCount != 4) {
                println("✗ ERROR: Expected 4 vertices, got ${model.buffers.vertexCount}")
            }
            if (model.buffers.indexCount != 6) {
                println("✗ ERROR: Expected 6 indices, got ${model.buffers.indexCount}")
            }
            if (!model.buffers.hasColors) {
                println("✗ ERROR: Expected colors to be present")
            }
            if (!model.buffers.hasNormals) {
                println("✗ ERROR: Expected normals to be present")
            }
            if (model.buffers.isPointCloud) {
                println("✗ ERROR: Should not be detected as point cloud")
            }
        } else {
            println("✗ Test file not found: ${testFile.absolutePath}")
            println("  Creating test file...")
            testFile.parentFile.mkdirs()
            testFile.writeText("""
ply
format ascii 1.0
comment Test PLY file - ASCII format
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
            println("  Test file created. Run again to test.")
        }
    } catch (e: Exception) {
        println("✗ Error: ${e.message}")
        e.printStackTrace()
    }
    
    // Test 2: ASCII Point Cloud
    println("\nTest 2: ASCII Point Cloud")
    try {
        val testFile = File(testDir, "test_pointcloud.ply")
        if (testFile.exists()) {
            val model = PlySupport.loadPly(testFile.absolutePath)
            println("✓ Successfully loaded: ${model.name}")
            println("  - Vertices: ${model.buffers.vertexCount}")
            println("  - Indices: ${model.buffers.indexCount}")
            println("  - Has colors: ${model.buffers.hasColors}")
            println("  - Has normals: ${model.buffers.hasNormals}")
            println("  - Is point cloud: ${model.buffers.isPointCloud}")
            
            if (model.buffers.vertexCount != 8) {
                println("✗ ERROR: Expected 8 vertices, got ${model.buffers.vertexCount}")
            }
            if (!model.buffers.isPointCloud) {
                println("✗ ERROR: Should be detected as point cloud")
            }
            if (!model.buffers.hasColors) {
                println("✗ ERROR: Expected colors to be present")
            }
        } else {
            println("✗ Test file not found: ${testFile.absolutePath}")
            println("  Creating test file...")
            testFile.parentFile.mkdirs()
            testFile.writeText("""
ply
format ascii 1.0
comment Test PLY file - Point cloud
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
            println("  Test file created. Run again to test.")
        }
    } catch (e: Exception) {
        println("✗ Error: ${e.message}")
        e.printStackTrace()
    }
    
    // Test 3: Minimal PLY (no colors, no normals)
    println("\nTest 3: Minimal PLY (positions only)")
    try {
        val testFile = File(testDir, "test_minimal.ply")
        testFile.parentFile.mkdirs()
        testFile.writeText("""
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
        
        val model = PlySupport.loadPly(testFile.absolutePath)
        println("✓ Successfully loaded: ${model.name}")
        println("  - Vertices: ${model.buffers.vertexCount}")
        println("  - Indices: ${model.buffers.indexCount}")
        println("  - Has colors: ${model.buffers.hasColors}")
        println("  - Has normals: ${model.buffers.hasNormals}")
        
        if (model.buffers.hasColors) {
            println("✗ ERROR: Should not have colors")
        }
        if (model.buffers.hasNormals) {
            println("✗ ERROR: Should not have normals")
        }
    } catch (e: Exception) {
        println("✗ Error: ${e.message}")
        e.printStackTrace()
    }
    
    // Test 4: Polygon triangulation (quad)
    println("\nTest 4: Polygon Triangulation (Quad)")
    try {
        val testFile = File(testDir, "test_quad.ply")
        testFile.parentFile.mkdirs()
        testFile.writeText("""
ply
format ascii 1.0
element vertex 4
property float x
property float y
property float z
element face 1
property list uchar int vertex_indices
end_header
0.0 0.0 0.0
1.0 0.0 0.0
1.0 1.0 0.0
0.0 1.0 0.0
4 0 1 2 3
        """.trimIndent())
        
        val model = PlySupport.loadPly(testFile.absolutePath)
        println("✓ Successfully loaded: ${model.name}")
        println("  - Vertices: ${model.buffers.vertexCount}")
        println("  - Indices: ${model.buffers.indexCount}")
        
        // Quad should be triangulated to 6 indices (2 triangles)
        if (model.buffers.indexCount != 6) {
            println("✗ ERROR: Quad should be triangulated to 6 indices, got ${model.buffers.indexCount}")
        } else {
            println("✓ Quad correctly triangulated to 2 triangles")
        }
    } catch (e: Exception) {
        println("✗ Error: ${e.message}")
        e.printStackTrace()
    }
    
    println("\n================")
    println("Tests completed!")
}
