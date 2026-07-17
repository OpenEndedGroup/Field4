package afield.examples.four

import afield.four.Renderer
import afield.four.targets.RenderTarget
import afield.four.texture.LoadedTexture
import afield.four.util.ImageReducer
import afield.four.util.Window

/**
 * Example demonstrating the usage of ImageReducer utility class.
 *
 * Shows both the "just do it once" case ([runOnce], via [ImageReducer.reduceNow]) and the
 * "do it every frame" case ([runPerFrame], via [ImageReducer.installInto]).
 *
 * See the KDoc on [afield.four.util.ImageReducer] for the authoritative API, buffer layout, and
 * sampled-texture binding model.
 */
class ImageReducerExample {
    val window = Window("ImageReducer Example")
    val renderer = Renderer(window)
    
    /**
     * Example 1: "Just do it once" - synchronous one-shot execution
     */
    fun runOnce() {
        println("\n=== Example 1: One-shot execution ===")
        
        val texture = LoadedTexture.stubTexture(renderer)
        texture.transferNow()
        
        // Create the reducer and execute once
        val reducer = ImageReducer(renderer, texture)
        val results = reducer.reduceNow()
        
        println("Image Reduction Results:")
        println("  Min: [${results.min.joinToString(", ")}]")
        println("  Avg: [${results.avg.joinToString(", ")}]")
        println("  Max: [${results.max.joinToString(", ")}]")
        
        reducer.cleanup()
        texture.cleanup()
    }
    
    /**
     * Example 2: "Do it every frame" - install into RenderTarget.others
     */
    fun runPerFrame() {
        println("\n=== Example 2: Per-frame execution ===")
        
        val texture = LoadedTexture.stubTexture(renderer)
        texture.transferNow()
        
        val renderTarget = RenderTarget(renderer)
        renderer.renderList = renderTarget
        
        // Create reducer and install into render target
        val reducer = ImageReducer(renderer, texture)
        reducer.installInto(renderTarget, "imageStats")
        
        // Simulate a few frames
        for (frame in 0 until 5) {
            window.pollEvents()
            renderer.render()
            
            // Read results after frame completes
            val results = reducer.readResults()
            println("Frame $frame - Avg R: ${results.avg[0]}, G: ${results.avg[1]}, B: ${results.avg[2]}")
            
            if (window.shouldClose()) break
        }
        
        reducer.cleanup()
        texture.cleanup()
    }
    
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val example = ImageReducerExample()
            try {
                // Run the one-shot example
                example.runOnce()
                
                // Run the per-frame example
                example.runPerFrame()
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
