# Video Texture Support

This package provides video texture support for Field4 using bytedeco's FFmpeg bindings.

## Features

- **P010 Format Support**: Decodes video frames in P010 format with semi-planar output (separate luma and chroma planes)
- **Configurable Texture Slots**: Choose how many texture slots to rotate through (default double buffering)
- **Resource Reuse**: Intelligently reuses Vulkan image resources when loading videos with matching dimensions
- **Full Playback Control**: Load, play, pause, scrub, and unload videos
- **Cross-Platform**: Supports macOS, Linux, and Windows with platform-specific FFmpeg binaries

## Architecture

### VideoDecoder
Wraps FFmpeg functionality to:
- Open video files
- Decode frames to P010 format
- Seek to specific timestamps
- Extract luma and chroma planes

### VideoTexture
Manages Vulkan textures and playback state:
- Maintains luma + chroma texture arrays with configurable slot count and slot tracking (default 2)
- Handles upload to GPU via staging buffers
- Provides playback control (play/pause/seek)
- Reuses Vulkan resources when dimensions match

### VideoFrame
Data structure representing a decoded frame with:
- Separate luma and chroma buffers
- Stride information
- Timestamp

## Usage

```kotlin
import afield.four.texture.video.VideoTexture
import afield.four.Renderer

// Create video texture
val videoTexture = VideoTexture(renderer)
// Or customize slot count (e.g., triple buffering)
// val videoTexture = VideoTexture(renderer, textureSlots = 3)

// Load a video file
if (videoTexture.load("path/to/video.mp4")) {
    println("Video: ${videoTexture.width}x${videoTexture.height}")
    println("Duration: ${videoTexture.duration}s")
    
    // Start playback
    videoTexture.play()
    
    // In your render loop:
    // videoTexture.update(commandBuffer)
    
    // Access textures for shader binding
    val lumaTexture = videoTexture.getLumaTexture()
    val chromaTexture = videoTexture.getChromaTexture()
    
    // Or get both as array: [luma, chroma]
    val textures = videoTexture.imageViewArray
}

// Pause and seek
videoTexture.pause()
videoTexture.seek(5.0)  // Seek to 5 seconds

// Resume playback
videoTexture.play()

// Unload (keeps Vulkan resources for reuse)
videoTexture.unload()

// Or cleanup completely (frees all resources)
videoTexture.cleanup()
```

## Resource Management

### Resource Reuse
When loading a new video with the same dimensions as the previous one, VideoTexture reuses the existing Vulkan image resources (Image, ImageView, staging buffers). This is efficient for scenarios like:
- Playlist playback with same-resolution videos
- Switching between multiple camera feeds
- Dynamic video content with consistent dimensions

### Configurable Buffering
VideoTexture maintains multiple texture slots (default 2) for each plane (luma and chroma). The current slot index tracks which set contains the most recent frame. Increase the slot count if you need extra buffering headroom for upload timing; reduce to 1 if memory is tight and you can tolerate overwriting in place.

## Platform-Specific Binaries

The build.gradle.kts is configured to download only the FFmpeg binaries for the current platform:
- **macOS arm64**: `macosx-arm64`
- **macOS x64**: `macosx-x86_64`
- **Windows**: `windows-x86_64`
- **Linux arm64**: `linux-arm64`
- **Linux x64**: `linux-x86_64`

This minimizes download size and build time when building on user machines.

## Dependencies

- **bytedeco JavaCPP**: 1.5.11
- **bytedeco FFmpeg**: 7.1-1.5.11

Dependencies are automatically resolved by Gradle with platform-specific classifiers.

## Examples

See `VideoTextureExample.kt` for complete usage examples including:
- Basic playback
- Multiple video loading with resource reuse
- Render loop integration
- Scrubbing and seeking

## Format Details

### P010 Format
- **Luma Plane**: 16-bit single channel (VK_FORMAT_R16_UNORM)
  - Full resolution (width × height)
  - Contains brightness/luminance information
  
- **Chroma Plane**: 16-bit dual channel interleaved (VK_FORMAT_R16G16_UNORM)
  - Half resolution (width/2 × height/2)
  - Contains UV color difference information
  - Semi-planar: U and V are interleaved in the same plane

This format is efficient for high dynamic range (HDR) video content and modern video codecs.

## Audio Support

### Overview
Synchronized audio playback is now available using OpenAL from LWJGL and FFmpeg audio decoding. The audio system integrates seamlessly with the video texture system through a PlaybackClock interface.

### Clock-Based Synchronization

**PlaybackClock Interface**: Provides timing for video playback synchronization
- `AudioClock`: Uses audio hardware timing (recommended for A/V sync, prevents drift)
- `SystemClock`: Uses wall-clock time (internal fallback, may drift over long durations)

**Integration**: Pass a PlaybackClock to VideoTexture for synchronized playback

```kotlin
// Audio-synchronized playback (prevents drift)
val audioPlayer = AudioPlayer(44100, 2)
val audioClock = AudioClock(audioPlayer)
val videoTexture = VideoTexture(renderer, clock = audioClock)

// Video-only playback (uses internal SystemClock)
val videoTexture = VideoTexture(renderer)  // No clock = internal timing
```

### Components

- **AudioDecoder**: FFmpeg-based audio stream decoder
  - Extracts audio from video/audio files
  - Decodes to PCM format (16-bit signed)
  - Automatic resampling to target sample rate
  - Synchronized seeking with video

- **AudioPlayer**: OpenAL-based audio playback
  - Low-latency streaming playback
  - Buffer queue management (4 buffers)
  - Precise timing for A/V synchronization
  - Volume control and playback state management

- **PlaybackClock**: Timing interface for synchronization
  - `AudioClock`: Syncs to audio hardware (recommended)
  - `SystemClock`: Internal wall-clock timing
  - Custom implementations supported

### Usage Example

```kotlin
// Load audio from video file
val audioDecoder = AudioDecoder("video.mp4")
audioDecoder.open(outputSampleRate = 44100, outputChannels = 2)

// Initialize OpenAL player
val audioPlayer = AudioPlayer(sampleRate = 44100, channels = 2)
audioPlayer.initialize()

// Create AudioClock for video synchronization
val audioClock = AudioClock(audioPlayer)

// Create VideoTexture with audio clock
val videoTexture = VideoTexture(renderer, clock = audioClock)
videoTexture.load("video.mp4")

// Pre-fill audio buffers
repeat(3) {
    audioDecoder.decodeFrame()?.let { audioPlayer.queueAudioFrame(it) }
}

// Start synchronized playback
audioPlayer.play()
videoTexture.play()

// In render loop:
audioPlayer.processBuffers()
while (audioPlayer.getFreeBufferCount() > 0) {
    audioDecoder.decodeFrame()?.let { audioPlayer.queueAudioFrame(it) }
}

// Video automatically syncs to audio clock - no manual time passing needed!
videoTexture.update(commandBuffer)
```

### Synchronization Strategy

- **Master Clock**: AudioPlayer provides hardware-accurate timing via AudioClock
- **Video Sync**: VideoTexture queries clock and decodes frames to match timestamp
- **Frame Skipping**: Automatically skips frames when falling behind  
- **Precision**: Sample-accurate timing via OpenAL, prevents drift over long durations
- **Buffer Management**: Continuous audio queuing prevents underruns

### Benefits of Clock-Based Sync

1. **No Drift**: Audio hardware provides stable timing reference
2. **Long Duration**: Maintains sync over hours of playback
3. **Automatic**: Video handles frame timing internally
4. **Flexible**: Support custom clocks for other timing sources

### See Also

- `AudioVideoExample.kt`: Complete examples with clock integration
- `PlaybackClock.kt`: Clock interface and implementations  
- `AUDIO_OPTIONS.md`: Comparison of audio backend options
- `AUDIO_IMPLEMENTATION.md`: Detailed implementation guide

### Dependencies

Audio support requires OpenAL from LWJGL (automatically included):
```kotlin
implementation("org.lwjgl:lwjgl-openal:$lwjglVersion")
implementation("org.lwjgl:lwjgl-openal::$lwjglNatives")
```
