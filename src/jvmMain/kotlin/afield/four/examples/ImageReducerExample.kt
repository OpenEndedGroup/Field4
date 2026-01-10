package afield.four.examples

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.texture.LoadedTexture
import afield.four.util.ImageReducer
import afield.four.util.Window
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10

/**
 * Example demonstrating the usage of ImageReducer utility class.
 * 
 * This example loads a texture and uses the ImageReducer to compute
 * min, average, and max values across the image using compute shaders.
 */
class ImageReducerExample {
    val window = Window("ImageReducer Example")
    val renderer = Renderer(window)
    
    fun run() {
        // Load a test texture
        // Note: Replace with actual path to test image
        val texture = LoadedTexture.stubTexture(renderer)
        texture.transferNow()
        
        // Create the image reducer
        val reducer = ImageReducer(renderer, texture)
        
        // Create a command buffer
        val cmd = CommandBuffer(renderer.commandPool, true, true)
        cmd.beginRecording()
        
        // Execute the reduction
        reducer.reduce(cmd)
        
        cmd.endRecording()
        
        // Submit the command buffer and wait for completion
        val fence = Fence(renderer.device, true)
        fence.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(
                stack.pointers(cmd.vkCommandBuffer),
                null,
                null,
                null,
                fence
            )
        }
        fence.fenceWait()
        
        // Read back the results
        val results = reducer.readResults()
        
        println("Image Reduction Results:")
        println("  Min: [${results.min.joinToString(", ")}]")
        println("  Avg: [${results.avg.joinToString(", ")}]")
        println("  Max: [${results.max.joinToString(", ")}]")
        
        // Cleanup
        fence.cleanup()
        cmd.cleanup()
        reducer.cleanup()
        texture.cleanup()
    }
    
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val example = ImageReducerExample()
            try {
                example.run()
            } catch (e: Exception) {
                println("Error running ImageReducer example: ${e.message}")
                e.printStackTrace()
            } finally {
                example.renderer.cleanup()
                example.window.cleanup()
            }
        }
    }
}
