package afield.four.examples

import afield.four.Renderer
import afield.four.modules.*
import afield.four.targets.RenderTarget
import afield.four.util.Camera
import afield.four.util.Window
import afield.four.util.forever
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * Example showing how to load and render PLY files with the PLY loader.
 * 
 * This example demonstrates:
 * - Loading ASCII and binary PLY files
 * - Rendering point clouds (vertices without faces)
 * - Rendering triangle meshes (vertices with faces)
 * - Handling vertex positions, normals, and colors
 */
class PlyLoaderExample {
    val window = Window("PLY Loader Example")
    val renderer = Renderer(window)
    var angle = 0.0f

    init {
        // Example 1: Load a triangle mesh PLY file
        // You can use the test files created by PlyLoaderUnitTest.createTestFiles()
        // or any PLY file you have available
        val triangleMeshModule = Module(
            Parameters(
                renderer,
                vertex = PlySupport.plyVertexShader,
                fragment = PlySupport.plyFragmentShader,
                name = "PLY Triangle Mesh"
            )
        )

        // Load the PLY file - handles both ASCII and binary formats automatically
        // To use: replace with your actual PLY file path
        // Or use: File(System.getProperty("java.io.tmpdir"), "ply_test/test_ascii_triangle.ply").absolutePath
        val plyModel = PlySupport.loadPly("path/to/your/model.ply")
        
        val mesh = Mesh(renderer, triangleMeshModule.pointer())
        
        // Simple camera setup
        val camera = Camera().apply {
            position = Vector3f(0f, 0f, 5f)
            lookAt(Vector3f(0f, 0f, 0f))
        }

        val renderTarget = RenderTarget(renderer)

        forever(window, renderer) {
            angle += 0.01f

            renderTarget.renderPass {
                clear()

                // Update camera with rotation
                camera.position = Vector3f(
                    5f * kotlin.math.sin(angle),
                    2f,
                    5f * kotlin.math.cos(angle)
                )
                camera.lookAt(Vector3f(0f, 0f, 0f))

                triangleMeshModule.draw(it) {
                    // Apply the PLY data to the mesh
                    plyModel.applyToMesh(mesh, camera)
                    
                    // Draw the mesh
                    mesh.draw(triangleMeshModule, it, triangleMeshModule.pushConstantBuffer)
                }
            }
        }
    }
}

/**
 * Example usage for point cloud rendering
 */
fun pointCloudExample() {
    val window = Window("Point Cloud Example")
    val renderer = Renderer(window)

    // For point clouds, you might want to use POINT_LIST topology
    val pointCloudModule = Module(
        Parameters(
            renderer,
            vertex = PlySupport.plyVertexShader,
            fragment = PlySupport.plyFragmentShader,
            name = "PLY Point Cloud",
            // primitiveType = VK_PRIMITIVE_TOPOLOGY_POINT_LIST  // Uncomment for point rendering
        )
    )

    val pointCloud = PlySupport.loadPly("path/to/pointcloud.ply")
    // Or use: File(System.getProperty("java.io.tmpdir"), "ply_test/test_ascii_pointcloud.ply").absolutePath
    val mesh = Mesh(renderer, pointCloudModule.pointer())
    val camera = Camera().apply {
        position = Vector3f(0f, 0f, 5f)
        lookAt(Vector3f(0f, 0f, 0f))
    }

    val renderTarget = RenderTarget(renderer)

    forever(window, renderer) {
        renderTarget.renderPass {
            clear()

            pointCloudModule.draw(it) {
                pointCloud.applyToMesh(mesh, camera)
                mesh.draw(pointCloudModule, it, pointCloudModule.pushConstantBuffer)
            }
        }
    }
}

// Entry point for testing
fun main() {
    // Uncomment one of these:
    // PlyLoaderExample()
    // pointCloudExample()
}
