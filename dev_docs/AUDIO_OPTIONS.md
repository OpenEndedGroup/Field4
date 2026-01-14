# Audio Options for Synchronized Video Playback

This document outlines the available audio options for synchronized audio playback with the existing FFmpeg video decoder in Field4.

## Overview

With the bytedeco FFmpeg video decoder now working superbly, we need a solution for synchronized audio playback. The key requirements are:
- **Audio extraction from video files**: Decode audio stream from video files using FFmpeg
- **Low-latency playback**: Play audio with minimal delay to maintain sync with video
- **Synchronization**: Keep audio and video in sync during playback, seeking, and pause/resume
- **Cross-platform support**: Work on macOS, Linux, and Windows

## Option 1: OpenAL from LWJGL (Recommended)

### Overview
OpenAL (Open Audio Library) is a cross-platform 3D audio API similar to OpenGL. LWJGL includes comprehensive OpenAL bindings that integrate seamlessly with the existing project infrastructure.

### Advantages
- ✅ **Already available in LWJGL**: Same version management as existing dependencies
- ✅ **Cross-platform**: Excellent support for macOS, Linux, and Windows
- ✅ **Native binaries included**: LWJGL handles platform-specific binaries automatically
- ✅ **Low-latency**: Designed for real-time audio in games and multimedia
- ✅ **Buffer queuing**: Built-in support for streaming audio from multiple buffers
- ✅ **Familiar API**: Similar patterns to OpenGL/Vulkan (context, buffers, sources)
- ✅ **Well-documented**: Extensive documentation and examples
- ✅ **Active maintenance**: Part of LWJGL, regularly updated

### Disadvantages
- ⚠️ Limited to basic audio formats (PCM, requires FFmpeg for decoding)
- ⚠️ No built-in resampling (but FFmpeg can handle this)
- ⚠️ Additional dependency (though minimal since it's LWJGL)

### Implementation Details
```kotlin
// Dependency (add to build.gradle.kts)
implementation("org.lwjgl:lwjgl-openal:$lwjglVersion")
implementation("org.lwjgl:lwjgl-openal::$lwjglNatives")

// Basic usage pattern
val device = alcOpenDevice(...)
val context = alcCreateContext(device, ...)
val source = alGenSources()
val buffers = alGenBuffers(NUM_BUFFERS)

// Queue decoded audio buffers
alSourceQueueBuffers(source, buffer)
alSourcePlay(source)

// Synchronization via querying playback position
val samplesPlayed = alGetSourcei(source, AL_SAMPLE_OFFSET)
val audioTime = samplesPlayed / sampleRate
```

### Integration with VideoTexture
OpenAL integrates naturally with the existing `VideoTexture` architecture:
- `AudioDecoder` wraps FFmpeg audio stream decoding (similar to `VideoDecoder`)
- `AudioPlayer` manages OpenAL context, sources, and buffer queuing
- `VideoTexture.update()` synchronizes video frames with audio playback time
- Double buffering pattern similar to video texture slots

## Option 2: JavaSound (javax.sound)

### Overview
JavaSound is part of the Java standard library and provides basic audio playback capabilities.

### Advantages
- ✅ **No additional dependencies**: Built into JDK
- ✅ **Simple API**: Easy to use for basic playback
- ✅ **Cross-platform**: Works everywhere Java runs

### Disadvantages
- ❌ **Higher latency**: Not designed for real-time applications
- ❌ **Limited format support**: Requires external decoding (FFmpeg)
- ❌ **Poor synchronization**: Difficult to achieve tight A/V sync
- ❌ **No buffer streaming API**: Less efficient for long videos
- ❌ **No precise timing**: Hard to query exact playback position

### Verdict
**Not recommended** for real-time video synchronization due to latency and timing limitations.

## Option 3: JNAerator + Native Libraries

### Overview
Use JNAerator or similar to bind directly to platform audio APIs (CoreAudio, WASAPI, ALSA).

### Advantages
- ✅ **Native performance**: Direct access to platform audio APIs
- ✅ **Low latency**: Minimal overhead

### Disadvantages
- ❌ **Complex setup**: Requires separate bindings for each platform
- ❌ **High maintenance**: Must maintain platform-specific code
- ❌ **Reinventing the wheel**: OpenAL already provides this abstraction
- ❌ **Time-consuming**: Significant development effort

### Verdict
**Not recommended** when OpenAL provides a cross-platform solution.

## Option 4: FFmpeg Audio Device Output

### Overview
Use FFmpeg's built-in audio output capabilities directly.

### Advantages
- ✅ **Already using FFmpeg**: Leverages existing dependency
- ✅ **Integrated decoding and playback**: One library handles both

### Disadvantages
- ❌ **Limited control**: Less control over buffering and timing
- ❌ **Platform-specific**: FFmpeg audio output varies by platform
- ❌ **Harder to synchronize**: Less precise timing control for A/V sync
- ❌ **Not designed for this use case**: FFmpeg is primarily a decoding library

### Verdict
**Not recommended** for synchronized playback requiring precise control.

## Option 5: SDL2 Audio

### Overview
SDL2 (Simple DirectMedia Layer) provides cross-platform audio capabilities.

### Advantages
- ✅ **Cross-platform**: Works on all major platforms
- ✅ **Low latency**: Designed for games
- ✅ **Good synchronization**: Callback-based audio streaming

### Disadvantages
- ❌ **Additional dependency**: Requires SDL2 native libraries
- ❌ **Overkill**: Includes video, input, and other features we don't need
- ❌ **No LWJGL bindings**: Would need custom JNI/JNA bindings
- ❌ **Less Java ecosystem integration**: Not as common in Java projects

### Verdict
**Not recommended** when OpenAL provides similar capabilities with better Java integration.

## Recommendation: OpenAL from LWJGL

OpenAL from LWJGL is the clear winner for this use case because:

1. **Seamless Integration**: Already using LWJGL for graphics, adding OpenAL is trivial
2. **Battle-Tested**: Used in countless games and multimedia applications
3. **Low Latency**: Designed for real-time audio playback
4. **Good Synchronization**: Easy to query playback position for A/V sync
5. **Cross-Platform**: Single API works across all target platforms
6. **Active Support**: Part of LWJGL ecosystem with regular updates

## Implementation Strategy

### Phase 1: Audio Decoding
- Create `AudioDecoder` class wrapping FFmpeg audio stream decoding
- Extract audio frames in PCM format (16-bit signed, configurable sample rate)
- Implement audio seeking synchronized with video seeking
- Handle audio format conversion (mono/stereo, sample rate)

### Phase 2: Audio Playback
- Create `AudioPlayer` class managing OpenAL context and sources
- Implement buffer queuing pattern for streaming playback
- Add precise timing queries for synchronization
- Handle audio device initialization and cleanup

### Phase 3: Synchronization
- Extend `VideoTexture` to optionally manage audio playback
- Implement master clock for A/V synchronization
- Add drift correction to handle timing discrepancies
- Support audio-only or video-only playback modes

### Phase 4: Integration
- Integrate audio loading in `VideoTexture.load()`
- Synchronize `update()` calls with audio playback time
- Handle seeking, pause, resume with audio state
- Add audio volume control and muting

### Phase 5: Examples and Documentation
- Update `VideoTextureExample.kt` with audio examples
- Document audio API in README
- Add troubleshooting guide for common audio issues
- Document synchronization algorithm and tuning parameters

## Alternative: Modular Audio Support

For projects that don't need audio or want different audio solutions, we can make audio support **optional and modular**:

```kotlin
interface AudioProvider {
    fun load(audioPath: String): Boolean
    fun play()
    fun pause()
    fun seek(timestamp: Double)
    fun getCurrentTime(): Double
    fun cleanup()
}

class OpenALAudioProvider : AudioProvider { ... }
class NoOpAudioProvider : AudioProvider { ... }

// VideoTexture accepts optional audio provider
class VideoTexture(
    private val renderer: Renderer,
    private val audioProvider: AudioProvider? = null
)
```

This allows users to:
- Use OpenAL (recommended)
- Implement custom audio backend
- Skip audio entirely (video-only)
- Swap audio implementations without changing video code

## Next Steps

1. ✅ Document audio options (this document)
2. ⏭️ Add OpenAL dependencies to `build.gradle.kts`
3. ⏭️ Implement `AudioDecoder` for FFmpeg audio stream extraction
4. ⏭️ Implement `AudioPlayer` for OpenAL playback
5. ⏭️ Add synchronization logic to `VideoTexture`
6. ⏭️ Create examples and documentation
7. ⏭️ Test with various video files and formats

## References

- LWJGL OpenAL: https://www.lwjgl.org/guide#openal
- OpenAL Specification: https://www.openal.org/documentation/
- FFmpeg Audio API: https://ffmpeg.org/doxygen/trunk/group__lavc__core.html
- bytedeco FFmpeg: https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg
