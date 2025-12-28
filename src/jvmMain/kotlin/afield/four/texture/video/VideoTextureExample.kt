package afield.four.texture.video

import afield.four.Renderer
import afield.four.device.CommandBuffer

/**
 * Example usage of VideoTexture.
 * 
 * This demonstrates how to:
 * - Load a video file
 * - Play/pause playback
 * - Scrub to a specific time
 * - Access luma and chroma textures for rendering
 * - Unload and cleanup
 */
object VideoTextureExample {
    
    /**
     * Basic video playback example.
     */
    fun basicPlayback(renderer: Renderer, videoPath: String) {
        // Create video texture
        val videoTexture = VideoTexture(renderer)
        
        // Load video
        if (!videoTexture.load(videoPath)) {
            println("Failed to load video: $videoPath")
            return
        }
        
        println("Video loaded: ${videoTexture.width}x${videoTexture.height}")
        println("Duration: ${videoTexture.duration}s, FPS: ${videoTexture.fps}")
        
        // Start playback
        videoTexture.play()
        
        // In your render loop, call update:
        // val commandBuffer = ... // your command buffer
        // videoTexture.update(commandBuffer)
        
        // Get textures for shader binding
        val lumaTexture = videoTexture.getLumaTexture()
        val chromaTexture = videoTexture.getChromaTexture()
        
        // Or get both as array
        val textures = videoTexture.imageViewArray
        // textures[0] = luma, textures[1] = chroma
        
        // Later: pause playback
        videoTexture.pause()
        
        // Seek to specific time (e.g., 5 seconds)
        videoTexture.seek(5.0)
        
        // Resume playback
        videoTexture.play()
        
        // When done, unload (keeps Vulkan resources for reuse)
        videoTexture.unload()
        
        // Or cleanup completely (frees all resources)
        videoTexture.cleanup()
    }
    
    /**
     * Example of loading multiple videos with resource reuse.
     */
    fun multipleVideos(renderer: Renderer, videoPath1: String, videoPath2: String) {
        val videoTexture = VideoTexture(renderer)
        
        // Load first video
        println("Loading video 1...")
        if (videoTexture.load(videoPath1)) {
            println("Video 1: ${videoTexture.width}x${videoTexture.height}")
            videoTexture.play()
            
            // ... render frames ...
            
            videoTexture.pause()
        }
        
        // Load second video - if same dimensions, Vulkan resources are reused
        println("Loading video 2...")
        if (videoTexture.load(videoPath2)) {
            println("Video 2: ${videoTexture.width}x${videoTexture.height}")
            println("(Vulkan resources reused: ${videoPath1.substringAfterLast('/').substringBefore('.')} -> ${videoPath2.substringAfterLast('/').substringBefore('.')})")
            videoTexture.play()
            
            // ... render frames ...
        }
        
        // Cleanup when completely done
        videoTexture.cleanup()
    }
    
    /**
     * Example of frame-by-frame rendering integration.
     */
    fun renderLoopIntegration(
        renderer: Renderer,
        videoPath: String,
        onFrame: (CommandBuffer, VideoTexture) -> Unit
    ) {
        val videoTexture = VideoTexture(renderer)
        
        if (!videoTexture.load(videoPath)) {
            return
        }
        
        videoTexture.play()
        
        // Simulate render loop
        var frameCount = 0
        while (videoTexture.playing && frameCount < 300) {  // 300 frames ~10 seconds at 30fps
            // Get command buffer for current frame
            val commandBuffer = CommandBuffer(renderer.commandPool, true, true)
            commandBuffer.beginRecording()
            
            // Update video texture - decodes and uploads new frame if needed
            val newFrame = videoTexture.update(commandBuffer)
            
            if (newFrame) {
                println("New frame at ${videoTexture.timestamp}s")
            }
            
            // Custom rendering with video textures
            onFrame(commandBuffer, videoTexture)
            
            commandBuffer.endRecording()
            
            // Submit and execute command buffer
            // ... (submit to queue, wait, etc.) ...
            
            commandBuffer.cleanup()
            frameCount++
            
            // Simulate frame timing
            Thread.sleep(33)  // ~30 FPS
        }
        
        videoTexture.cleanup()
    }
    
    /**
     * Example of seeking and frame scrubbing.
     */
    fun scrubExample(renderer: Renderer, videoPath: String) {
        val videoTexture = VideoTexture(renderer)
        
        if (!videoTexture.load(videoPath)) {
            return
        }
        
        val commandBuffer = CommandBuffer(renderer.commandPool, true, true)
        
        // Scrub through video at specific timestamps
        val timestamps = listOf(0.0, 1.5, 3.0, 5.0, 10.0)
        
        for (time in timestamps) {
            if (videoTexture.seek(time)) {
                println("Seeking to ${time}s")
                
                // Decode frame at this timestamp
                commandBuffer.beginRecording()
                videoTexture.update(commandBuffer)
                commandBuffer.endRecording()
                
                // Now videoTexture.getLumaTexture() and getChromaTexture() 
                // contain the frame at this timestamp
                
                println("Frame at ${videoTexture.timestamp}s ready")
            }
        }
        
        commandBuffer.cleanup()
        videoTexture.cleanup()
    }
}
