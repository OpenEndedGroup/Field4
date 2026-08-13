# Audio backend decision

Status: implemented.

Scope note, added when the render paths were mapped: this record covers **one** of Field's audio
paths — the video package's decode-and-play route, `video-audio` in the map. It is not the audio
backend decision for Field as a whole. There are three independent OpenAL contexts in the process
(this one, `FieldAudioEngine` and `SimpleSpatialAudioEngine`) and five ways for samples to leave it.
`public/docs/sound_paths.md` maps all of them, and `SoundPathMap` holds the count against the code:
a fourth `alcOpenDevice(` anywhere under `src/jvmMain/kotlin` fails the build until it is declared.

Field's video package uses FFmpeg for decoding and LWJGL's OpenAL bindings for queued PCM
playback. This kept audio inside the native-library stack already used by the renderer and exposed
the OpenAL sample offset needed by the shipped playback clock. The alternatives considered before
implementation were JavaSound, direct platform APIs, FFmpeg device output, and SDL2; each would
have added either weaker queue/timing control or another integration surface.

The implementation, not this record, owns the behavioral contract:

- `src/jvmMain/kotlin/afield/four/texture/video/AudioDecoder.kt` — FFmpeg decode and resample to
  interleaved signed 16-bit PCM.
- `src/jvmMain/kotlin/afield/four/texture/video/AudioPlayer.kt` — one OpenAL source and a fixed pool
  of four queued buffers.
- `src/jvmMain/kotlin/afield/four/texture/video/PlaybackClock.kt` — wall-clock and OpenAL-backed
  clock implementations.
- `src/jvmMain/kotlin/afield/four/texture/video/VideoTexture.kt` — optional clock consumption.
- `src/jvmMain/kotlin/afield/four/texture/video/README.md` — current usage guide.
- `build.gradle.kts` — OpenAL and platform-native dependencies.

The old proposal described performance, latency, and long-duration drift outcomes that were not
backed by repository benchmarks. Those claims are intentionally not retained here.
