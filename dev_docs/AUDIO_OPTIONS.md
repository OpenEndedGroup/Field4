# Audio Backend Decision Record: Why OpenAL from LWJGL

> **Historical ADR.** This is the pre-implementation decision record that compared audio
> backends and argued for OpenAL from LWJGL. That choice shipped. For the current,
> living API and how-to, see the "Audio Support" section of the video module's
> `src/jvmMain/kotlin/afield/four/texture/video/README.md`, and
> `PlaybackClock.kt` for the PlaybackClock/AudioClock sync design.
> The comparison below is retained for the rationale ("why OpenAL"); it is not a to-do list.

## Overview

The requirement was a solution for synchronized audio playback alongside the bytedeco
FFmpeg video decoder. The key requirements were:
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

## Outcome

The recommendation was implemented as `AudioDecoder` (FFmpeg audio decoding) and
`AudioPlayer` (OpenAL playback) in `src/jvmMain/kotlin/afield/four/texture/video/`,
with `lwjgl-openal` added to `build.gradle.kts`. A/V synchronization did **not** end up
using a swappable `AudioProvider` interface as sketched in earlier drafts of this doc;
instead it shipped as the `PlaybackClock` interface with `SystemClock` (internal
wall-clock) and `AudioClock` (audio-hardware timing) implementations, passed to
`VideoTexture` via a `clock: PlaybackClock? = null` constructor parameter. See
`PlaybackClock.kt` and the video module `README.md` "Audio Support" section for the
shipped design.

## References

- LWJGL OpenAL: https://www.lwjgl.org/guide#openal
- OpenAL Specification: https://www.openal.org/documentation/
- FFmpeg Audio API: https://ffmpeg.org/doxygen/trunk/group__lavc__core.html
- bytedeco FFmpeg: https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg
