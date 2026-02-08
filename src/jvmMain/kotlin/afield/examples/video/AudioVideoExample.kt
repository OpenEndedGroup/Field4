package afield.examples.video

import afield.four.texture.video.AudioClock
import afield.four.texture.video.AudioDecoder
import afield.four.texture.video.AudioPlayer

/**
 * AudioVideoExample demonstrates synchronized audio/video playback with clock integration.
 */

// Example: Basic synchronized audio/video playback with clock
fun basicAudioVideoPlaybackWithClock() {
    val videoPath = "path/to/video.mp4"
    
    // Setup audio
    val audioDecoder = AudioDecoder(videoPath)
    audioDecoder.open(44100, 2)
    
    val audioPlayer = AudioPlayer(44100, 2)
    audioPlayer.initialize()
    
    // Create AudioClock as master timing source
    val audioClock = AudioClock(audioPlayer)
    
    // Create VideoTexture with audio clock for sync
    // val videoTexture = VideoTexture(renderer, clock = audioClock)
    // videoTexture.load(videoPath)
    
    // Pre-fill and start
    repeat(3) { audioDecoder.decodeFrame()?.let { audioPlayer.queueAudioFrame(it) } }
    audioPlayer.play()
    // videoTexture.play()
    
    // Render loop: video auto-syncs to audio clock
    // audioPlayer.processBuffers()
    // while (audioPlayer.getFreeBufferCount() > 0) {
    //     audioDecoder.decodeFrame()?.let { audioPlayer.queueAudioFrame(it) }
    // }
    // videoTexture.update(commandBuffer)  // Syncs to audioClock internally
    
    audioPlayer.cleanup()
    audioDecoder.close()
}
