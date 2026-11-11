# A world-space WAV through WiVRn

Box **18 · Head-tracked WAV** places resonant percussion at a fixed point in the room.
The bundled mono clip from the Eigendrum example is 2.5 seconds at 44.1 kHz and loops in the same
OpenXR space as the graphics. Steam Audio renders binaural stereo through a dedicated OpenAL
output to WiVRn's PipeWire sink;
your desktop's default device stays as it was. Headphones give better ear isolation than open speakers.

Launch Reading space and Drawing with the headset connected, then launch box 18. Play/Pause,
Restart, Loop, Gain and X/Y/Z controls are in its output. The status panel shows the actual
output-device name, source coordinates, playback cursor and rendered block count.

```kotlin
spatialWordSound.play()
spatialWordSound.pause() // retain cursor, ramp down over roughly 5 ms
spatialWordSound.seek(1.0) // seconds into the WAV
spatialWordSound.restart()
spatialWordSound.place(-.6, 1.5, -1.5)
spatialWordSound.gain = .2f
spatialWordSound.looping = true
```

Run those statements as editor selections/lines. Keep the full box source intact: a backdoor
`run --value` replaces the box code. Temporary automation belongs in an OperationTranscript
`execute`, which evaluates against the existing box runtime without replacing its source.

## Coordinates and lifecycle

Positions use OpenXR world coordinates in metres: +X right, +Y up, -Z forward. They stay fixed
as your head turns. A source at `(-.6, 1.5, -1.5)` is left of the origin, 1.5 metres high and
1.5 metres forward. Its relationship to the wearer depends on the
runtime's current reference-space origin. To attach audio to a moving visual, feed that
visual's transformed world position to `place`; include the reading renderer's placement
transform, not just its diagram-local point.

`renderer.openXRHmdTarget!!.asHeadPoseSource()` supplies the published headset pose.
The audio thread reads it each block and transforms source-minus-listener into head space.
A tracking dropout retains the last valid pose. It never invokes OpenXR pose-location calls
from the audio thread. Optional inverse-distance attenuation is unity within one metre;
set `distanceRolloff = false` to compare direction changes at constant level.

Stopping/relaunching box 18 closes its patch, dedicated output and native Steam Audio objects.
Other Field audio is unaffected. The demo starts paused on a fresh launch. It refuses to open
when no named WiVRn output is available. After a headset/audio-device reconnect, stop and
relaunch box 18 to reopen the route; automatic device recovery is not implemented.

## Precision, buffering and scope

The WAV is loaded once and linearly resampled to 48 kHz. Stereo files currently use the left
channel as the mono emitter, matching `WavFloatArrayLoader`; prepare a mono mix when both
channels matter. This is one point source, not a stereo bed. Steam Audio supplies HRTF direction
filtering; this first player has distance attenuation but no room geometry, occlusion or reverb.

Steam Audio runs only on the audio render path. OpenAL's HRTF is disabled on this dedicated
context because the signal is already binaural. Output uses three queued 256-frame blocks
(16 ms at 48 kHz), before backend, WiVRn, transport and headset buffering. This is not a
measurement of total latency. OpenAL currently receives PCM16 from FieldAudioEngine; WiVRn
also accepts PCM16 stereo. No spatial encoding is sent over the network: WiVRn carries the
finished left and right channels.

The native test checks transport and head rotation with rendered Steam Audio samples. A broadband
probe gives a clearer ear-level comparison than the bass-heavy musical demo. Live verification
checked a 48 kHz stereo Field stream targeting `wivrn.sink`; listening in the headset is still
needed to judge localization and head-turn latency.
