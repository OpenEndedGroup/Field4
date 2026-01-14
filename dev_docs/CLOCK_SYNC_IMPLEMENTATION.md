# Clock-Based Synchronization Implementation

## Summary

Addressed @marcdownie's concern about synchronization over long durations by implementing a PlaybackClock interface that allows VideoTexture to sync to audio hardware timing instead of accumulating wall-clock deltas.

## Problem

The original implementation used `System.nanoTime()` and delta accumulation for video timing:
```kotlin
val deltaTime = (currentTime - lastUpdateTime) / 1_000_000_000.0
currentTimestamp += deltaTime
```

This approach can drift over long durations (hours) due to:
- System timer resolution variations
- CPU scheduling delays
- Accumulated floating point errors

## Solution

### 1. PlaybackClock Interface

Created a timing abstraction that allows different clock sources:

```kotlin
interface PlaybackClock {
    fun getCurrentTime(): Double
    fun isPlaying(): Boolean
}
```

### 2. Clock Implementations

**AudioClock**: Syncs to audio hardware timing
```kotlin
class AudioClock(private val audioPlayer: AudioPlayer) : PlaybackClock {
    override fun getCurrentTime() = audioPlayer.getCurrentTime()
    override fun isPlaying() = audioPlayer.isCurrentlyPlaying()
}
```

**SystemClock**: Wall-clock timing (internal fallback)
```kotlin
class SystemClock : PlaybackClock {
    // Manages internal timing with play/pause/seek
}
```

### 3. VideoTexture Integration

Modified VideoTexture to accept optional clock:

```kotlin
class VideoTexture(
    private val renderer: Renderer,
    private val clock: PlaybackClock? = null  // New parameter
) {
    private val internalClock = SystemClock()
    private val activeClock: PlaybackClock
        get() = clock ?: internalClock
}
```

### 4. Clock-Based Frame Decoding

Updated `update()` method to query clock instead of accumulating time:

```kotlin
fun update(commandBuffer: CommandBuffer): Boolean {
    // Get target timestamp from clock (not delta accumulation)
    val targetTimestamp = activeClock.getCurrentTime()
    
    // Decode frames until reaching target
    while (attempts < maxAttempts) {
        val frame = decoder.decodeNextFrame() ?: break
        
        if (frame.timestamp >= targetTimestamp) {
            break  // Found frame at or past target
        }
        
        // Skip frames when catching up
        if (targetTimestamp - frame.timestamp > frameDuration * 2) {
            continue
        }
        
        break  // Frame is close enough
    }
    
    // Upload and track frame timestamp
    lastFrameTimestamp = frame.timestamp
}
```

## Benefits

1. **No Drift**: Audio hardware provides stable timing reference
2. **Long Duration**: Maintains sync over hours of playback
3. **Automatic Sync**: Video queries clock, no manual time passing
4. **Frame Skipping**: Automatically catches up when falling behind
5. **Backward Compatible**: Works without clock (video-only)
6. **Flexible**: Custom clock implementations supported

## Usage Patterns

### Audio/Video Sync (Recommended)
```kotlin
val audioPlayer = AudioPlayer(44100, 2)
val audioClock = AudioClock(audioPlayer)
val videoTexture = VideoTexture(renderer, clock = audioClock)

audioPlayer.play()
videoTexture.play()

// In render loop:
audioPlayer.processBuffers()
videoTexture.update(commandBuffer)  // Syncs to audio automatically
```

### Video Only
```kotlin
val videoTexture = VideoTexture(renderer)  // Uses internal SystemClock
videoTexture.play()
videoTexture.update(commandBuffer)
```

### Custom Clock
```kotlin
class MyCustomClock : PlaybackClock {
    override fun getCurrentTime() = myTimingSource.time
    override fun isPlaying() = myTimingSource.active
}

val videoTexture = VideoTexture(renderer, clock = MyCustomClock())
```

## Technical Details

### Frame Selection Logic

The video decoder now:
1. Queries clock for target timestamp
2. Decodes frames until reaching/passing target
3. Skips frames when >2 frame durations behind
4. Uses closest frame when within tolerance
5. Prevents infinite loops with max attempts

### Clock Management

- **Load**: Resets internal clock when loading new video
- **Play**: Starts clock (internal only, external clock managed by caller)
- **Pause**: Pauses clock (internal only)
- **Seek**: Seeks clock to timestamp (internal only)

External clocks (like AudioClock) are managed by the audio system, ensuring video follows audio timing.

## Files Changed

1. **PlaybackClock.kt** (new): Clock interface and implementations
2. **VideoTexture.kt**: Accept optional clock, sync to clock time
3. **AudioVideoExample.kt**: Updated examples with clock usage
4. **README.md**: Document clock-based synchronization

## Testing Recommendations

1. Test video-only playback (no clock)
2. Test audio/video sync with AudioClock
3. Verify no drift over 1+ hour playback
4. Test seeking while using clock
5. Test frame skipping when system is slow
6. Monitor sync delta: `audioClock.getCurrentTime() - videoTexture.timestamp`

## Future Enhancements

- Add clock drift monitoring/logging
- Implement adaptive frame skipping based on drift
- Support clock speed adjustments (fast-forward/slow-motion)
- Add clock synchronization metrics

## Conclusion

The PlaybackClock integration solves the long-duration drift problem by using audio hardware as a stable timing reference. Video now queries the clock each frame instead of accumulating deltas, maintaining accurate synchronization over any duration.
