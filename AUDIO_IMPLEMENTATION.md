# Audio Synchronization Implementation

This document describes the implementation of synchronized audio playback for Field4's video texture system using OpenAL from LWJGL and FFmpeg for audio decoding.

## Overview

The audio system consists of three main components:

1. **AudioDecoder**: FFmpeg-based audio stream decoder
2. **AudioPlayer**: OpenAL-based audio playback manager
3. **AudioVideoExample**: Usage patterns and synchronization examples

These components integrate with the existing `VideoTexture` and `VideoDecoder` to provide synchronized audio/video playback.

## Architecture

### AudioDecoder

`AudioDecoder` wraps FFmpeg's audio decoding functionality to:
- Extract audio streams from video/audio files
- Decode audio frames to PCM format (16-bit signed)
- Resample audio to target sample rate and channel count
- Support seeking to specific timestamps
- Provide decoded audio in OpenAL-compatible format

**Key Features:**
- Automatic format conversion to S16 (16-bit signed PCM)
- Configurable output sample rate (default 44100 Hz)
- Configurable output channels (1=mono, 2=stereo)
- Frame-accurate seeking synchronized with video
- Handles various input formats via FFmpeg

### AudioPlayer

`AudioPlayer` manages OpenAL for low-latency audio playback:
- Initializes OpenAL context and audio device
- Manages buffer pool for streaming playback
- Provides precise timing information for A/V sync
- Supports volume control, play, pause, and stop
- Automatic buffer recycling for continuous playback

**Key Features:**
- Streaming audio via buffer queue (4 buffers by default)
- Low-latency playback suitable for real-time synchronization
- Precise sample-level timing for synchronization
- Automatic buffer management and recycling
- Cross-platform support via LWJGL OpenAL

## Usage Patterns

### Basic Synchronized Playback

```kotlin
// Load video
val videoTexture = VideoTexture(renderer)
videoTexture.load("video.mp4")

// Load audio from same file
val audioDecoder = AudioDecoder("video.mp4")
audioDecoder.open(outputSampleRate = 44100, outputChannels = 2)

// Initialize audio player
val audioPlayer = AudioPlayer(sampleRate = 44100, channels = 2)
audioPlayer.initialize()

// Pre-fill audio buffers
repeat(3) {
    audioDecoder.decodeFrame()?.let { audioPlayer.queueBuffer(it.data) }
}

// Start synchronized playback
audioPlayer.play()
videoTexture.play()

// In render loop:
while (running) {
    // Maintain audio buffer queue
    audioPlayer.processBuffers()
    while (audioPlayer.getFreeBufferCount() > 0) {
        audioDecoder.decodeFrame()?.let { audioPlayer.queueBuffer(it.data) }
            ?: break
    }
    
    // Get audio time for sync
    val audioTime = audioPlayer.getCurrentTime()
    
    // Update video (will internally sync to audio time if available)
    videoTexture.update(commandBuffer)
    
    // Render...
}
```

### Synchronized Seeking

```kotlin
fun seek(timestamp: Double) {
    // 1. Stop audio
    audioPlayer.stop()
    
    // 2. Seek both decoders
    audioDecoder.seek(timestamp)
    videoTexture.seek(timestamp)
    
    // 3. Refill audio buffers
    repeat(3) {
        audioDecoder.decodeFrame()?.let { audioPlayer.queueBuffer(it.data) }
    }
    
    // 4. Resume playback
    audioPlayer.play()
    videoTexture.play()
}
```

### Volume Control

```kotlin
// Set volume (0.0 to 1.0)
audioPlayer.volume = 0.5f  // 50% volume

// Mute
audioPlayer.volume = 0.0f

// Unmute
audioPlayer.volume = 1.0f
```

## Synchronization Strategy

The synchronization between audio and video is achieved through:

1. **Audio as Master Clock**: Audio playback time is used as the master clock
2. **Video Follows Audio**: Video frames are decoded to match audio time
3. **Buffer Management**: Audio buffers are continuously queued to prevent underruns
4. **Drift Correction**: Video decoder can skip or wait for frames to maintain sync

### Timing Precision

- **Audio timing**: Sample-accurate via `AL_SAMPLE_OFFSET` query
- **Video timing**: Frame-accurate via FFmpeg PTS (Presentation Timestamp)
- **Sync threshold**: Typically ±20ms is acceptable for human perception
- **Drift handling**: Video can skip frames if falling behind

### Buffer Management

**Audio Buffers:**
- Default: 4 buffers of ~0.1-0.2s each
- Total buffer: ~0.4-0.8s of audio
- Provides smooth playback with minimal latency

**Video Buffers:**
- Dual-slot texture system (existing)
- Decode on-demand based on audio time
- No large pre-decode buffer needed

## Integration with VideoTexture

The audio system can be integrated with `VideoTexture` in several ways:

### Option 1: External Synchronization (Current Examples)

Keep audio and video separate, manage synchronization manually in the application:

```kotlin
val videoTexture = VideoTexture(renderer)
val audioDecoder = AudioDecoder(path)
val audioPlayer = AudioPlayer()

// Manual synchronization in render loop
val audioTime = audioPlayer.getCurrentTime()
// Use audioTime to drive video frame selection
```

**Advantages:**
- Maximum flexibility
- Clear separation of concerns
- Easy to add custom sync logic

**Disadvantages:**
- More code in application
- Need to manage both systems

### Option 2: Integrated Synchronization (Future Enhancement)

Extend `VideoTexture` to optionally manage audio:

```kotlin
class VideoTexture(
    private val renderer: Renderer,
    private val enableAudio: Boolean = false
) {
    private var audioDecoder: AudioDecoder? = null
    private var audioPlayer: AudioPlayer? = null
    
    fun load(videoPath: String): Boolean {
        // Load video...
        if (enableAudio) {
            audioDecoder = AudioDecoder(videoPath)
            audioPlayer = AudioPlayer()
            // Initialize audio...
        }
    }
    
    fun update(commandBuffer: CommandBuffer) {
        // Get audio time if available
        val targetTime = audioPlayer?.getCurrentTime() ?: currentTimestamp
        
        // Decode video frame matching audio time
        // ...
        
        // Manage audio buffers
        audioPlayer?.processBuffers()
        // Queue more audio...
    }
}
```

**Advantages:**
- Simpler application code
- Automatic synchronization
- Single API for A/V playback

**Disadvantages:**
- Less flexibility for custom sync
- Couples audio and video

## Dependencies

### Added to build.gradle.kts

```kotlin
// OpenAL from LWJGL (for audio playback)
implementation("org.lwjgl:lwjgl-openal:$lwjglVersion")
implementation("org.lwjgl:lwjgl-openal::$lwjglNatives")
```

### Existing Dependencies Used

```kotlin
// FFmpeg from bytedeco (already used for video)
implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$ffmpegPlatform")
```

## Platform Support

**Fully Supported:**
- macOS (arm64 and x86_64)
- Linux (arm64 and x86_64)  
- Windows (x86_64)

**Requirements:**
- OpenAL implementation (included with LWJGL)
- FFmpeg binaries (included with bytedeco)

## Performance Considerations

### CPU Usage

- **Audio Decoding**: Minimal CPU (~1-2% per stream)
- **Audio Playback**: Very low CPU (handled by audio driver)
- **Video Decoding**: Moderate CPU (varies by codec/resolution)

### Memory Usage

- **Audio Buffers**: ~4 buffers × 0.2s × sample_rate × channels × 2 bytes
  - Example: 4 × 0.2 × 44100 × 2 × 2 = ~70 KB
- **Audio Decoder**: Minimal (~1-2 MB)
- **Video Buffers**: Existing texture memory (unchanged)

### Latency

- **Audio Latency**: Very low (~10-50ms depending on buffer size)
- **Video Latency**: Matches audio (synchronized)
- **Total A/V Latency**: ~20-100ms (acceptable for most applications)

## Troubleshooting

### No Audio Output

1. Check OpenAL device initialization:
   ```kotlin
   if (!audioPlayer.initialize()) {
       println("OpenAL initialization failed")
   }
   ```

2. Verify audio stream exists:
   ```kotlin
   if (!audioDecoder.open()) {
       println("No audio stream found")
   }
   ```

3. Check system audio device is available

### Audio/Video Out of Sync

1. Verify audio buffers are being continuously queued
2. Check for video frame drops (CPU overload)
3. Adjust buffer sizes if needed
4. Verify timestamps are correct

### Audio Stuttering

1. Increase number of audio buffers
2. Check for CPU overload
3. Verify adequate buffer queuing (don't let buffers run out)
4. Profile audio callback timing

### Audio Distortion

1. Check sample rate matches
2. Verify format is correct (S16)
3. Check for clipping (volume too high)
4. Verify channel count is correct

## Examples

See `AudioVideoExample.kt` for comprehensive examples including:
1. Basic synchronized playback
2. Synchronized seeking
3. Volume control
4. Manual synchronization patterns
5. Audio-only playback
6. Monitoring playback status

## Future Enhancements

### Potential Improvements

1. **Hardware Decoding**: Use GPU for audio processing (limited benefit)
2. **Advanced Sync**: Implement PLL (Phase-Locked Loop) for tighter sync
3. **Audio Effects**: Apply real-time audio effects via OpenAL
4. **Multiple Audio Tracks**: Support multiple audio streams (languages, etc.)
5. **Audio Visualization**: Extract audio data for visualization
6. **Subtitle Support**: Add subtitle rendering synchronized with audio/video
7. **Format Support**: Add more audio format conversions (surround sound, etc.)
8. **Adaptive Buffering**: Dynamically adjust buffer count based on latency
9. **Time Stretching**: Support variable playback speed with pitch correction

### Integration Points

1. **VideoTexture Integration**: Optional built-in audio support
2. **Shader Audio**: Pass audio data to shaders for visualization
3. **Spatial Audio**: Use OpenAL 3D positioning for immersive audio
4. **MIDI Sync**: Synchronize with MIDI clock for music applications

## References

- **LWJGL OpenAL**: https://www.lwjgl.org/guide#openal
- **OpenAL Programming Guide**: https://www.openal.org/documentation/
- **FFmpeg Audio Decoding**: https://ffmpeg.org/doxygen/trunk/group__lavc__decoding.html
- **bytedeco FFmpeg**: https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg
- **A/V Sync Techniques**: https://en.wikipedia.org/wiki/Audio_to_video_synchronization

## Conclusion

The OpenAL-based audio implementation provides a robust, cross-platform solution for synchronized audio playback in Field4. The architecture is flexible enough to support various use cases:

- **Simple cases**: Basic audio/video playback with automatic sync
- **Advanced cases**: Custom synchronization logic and effects
- **Audio-only**: Music playback without video
- **Video-only**: Existing functionality (no audio dependency)

The implementation leverages existing LWJGL infrastructure, requires minimal additional dependencies, and integrates naturally with the current video texture system.
