# Hear a screen between you and a sound

Put a wooden screen between a listener and a hi-hat. In the first recording the screen is slid off
to the right, leaving the direct path clear. In the second it crosses that path. The source,
listener and input are the same in both. Listen on headphones for the source ahead and to the
right. In the second take, listen for the loss of level and the softer high end.

## Compare the two takes

### Clear path

```diffstatic_runner {data-lang=kotlin audio="assets/steam_audio_scene/clear.wav" audio-spec="clear" audio-renderer="afield.examples.steamaudio.SteamAudioScreenStudy" capture-profile="macos-electron" requires="steam-audio,dirt-samples" embed=true hidden=true name="steam-audio-screen-clear"}
// The pinned Dirt-Samples hi-hat, rendered with the screen out of the direct path.
```

### Screen across the path

```diffstatic_runner {data-lang=kotlin audio="assets/steam_audio_scene/screen.wav" audio-spec="screen" audio-renderer="afield.examples.steamaudio.SteamAudioScreenStudy" capture-profile="macos-electron" requires="steam-audio,dirt-samples" embed=true hidden=true name="steam-audio-screen-blocked"}
// The same hi-hat and render level, with the direct path through wood.
```

## Open the starting point

Fetch Field's pinned Steam Audio library and Dirt samples once for this checkout:

```diffstatic {data-lang=bash}
./gradlew fetchSteamAudioNatives fetchDocsDirtSamples
```

Open [Steam Audio screen study](agent_transcripts.md#steamaudio_screen_study) from **Welcome →
Starting points** and run **A screen between source and listener**. Open the **clear reference** and **screen take**
panels to play them in turn. The **Screen offset (m)** slider moves the screen sideways and regenerates
only the second take. Play it again after moving the slider.

The plan shows the listener at the bottom, the hi-hat ahead and to the right, and the screen
between them. The ray turns orange where the screen blocks it. Here **occlusion** is Steam Audio's
visibility value. A value of 1 means clear and 0 means fully covered. The sound can still get
through a covered path, with transmission gains for the low, middle and high bands.

```diffstatic_runner {data-lang=kotlin screenshot="assets/steam_audio_scene/screen-study.png" capture="box" open-outputs="false" wait-output="true" wait-output-kind="all" wait-output-contains="Screen take ready" timeout="120" w="680" h="1050" padding="10" clip="850,865,500,437" embed=true hidden=true name="steam-audio-screen-study" requires="steam-audio,dirt-samples" alt="Plan from the screen study showing the wooden screen cutting the orange ray between the listener and sound source" macro-file="../../dev_docs/agents/transcripts/steamaudio_screen_study.yaml" capture-box="steam_screen"}
// Replay the editable screen study and capture Field's own output.
```

Try changing `Material.WOOD` to `Material.CURTAIN` or `Material.ANECHOIC` in the box. Keep the
screen centred and compare what reaches the listener. This study uses one ray, so crossing the
edge makes an abrupt change. Steam Audio also supports volumetric occlusion for a source whose
visibility should change gradually as it comes out from behind an edge.

## How the signal reaches the headphones

The measurement and audio render are separate operations. First add the screen mesh to the
`Scene` and commit it. Give that scene to a `Simulator`, add its source, commit the simulator,
and set the source and listener poses. Positions are in metres, with +X to the right, +Y up and
−Z ahead.

A direct simulation computes distance attenuation, visibility and transmission for the source
and listener. Copy its result into a mono direct effect and choose which parts of the result
the effect should apply:

```diffstatic {data-lang=kotlin}
simulator.runDirect()
source.getOutputs(SimulationFlags.DIRECT, outputs)
outputs.copyDirectInto(directParams)
directParams.flags =
    DirectEffectFlags.APPLY_DISTANCE_ATTENUATION or
        DirectEffectFlags.APPLY_OCCLUSION or
        DirectEffectFlags.APPLY_TRANSMISSION
directParams.transmissionType = TransmissionType.FREQUENCY_DEPENDENT
```

Then process blocks of samples through the direct effect and the binaural effect. The first
shapes the mono signal. The second turns it into stereo using a head-related transfer function
(HRTF) in the listener-relative direction. The native buffers are reused for every block:

```diffstatic {data-lang=kotlin}
dry.writeChannel(0, block)
direct.apply(directParams, dry, shaped)
binaural.apply(binauralParams, shaped, stereo)
stereo.readChannel(0, left, offset)
stereo.readChannel(1, right, offset)
```

The walkthrough renders the ordinary audio blocks offline and plays the resulting WAV in the
box. The two WAVs use one fixed playback gain, so their level difference survives export. In a
real-time patch, run the scene updates and direct simulation outside the audio callback, then
apply the latest completed result in the block render.

## Add arrivals from around the room

- **Place music in space.** [A pattern in space](pat_spatial.md) shows `patSpatial`, the shorter
  path from a pattern to binaural playback with optional head tracking.
- **Hear reflections.** `./gradlew caveDemo` renders `build/demo/cave.wav`. The cave adds a
  reflected field to the direct signal. `ReflectionEffect` takes mono audio to Ambisonics, a
  representation of the sound arriving from different directions. `AmbisonicsDecodeEffect`
  decodes that field to headphones. [Cave demo source](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/examples/steamaudio/CaveDemo.kt)
- **Hear around a corner.** `./gradlew pathingDemo` renders `build/demo/pathing.wav`. It bakes
  a network of probes and renders indirect paths through `PathEffect`.
  [Pathing demo source](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/examples/steamaudio/PathingDemo.kt)

When adding reflections to a live patch, run the blocking reflection simulation on its own worker.
Keep the same Ambisonic order in the simulation, reflection effect, buffers and decoder. The
decoder uses the listener orientation when turning the reflected field into a stereo pair.

## API reference

[Scene and meshes](reference/audio.md#scene-kt), [Simulator and source](reference/audio.md#simulation-kt),
[Direct effect](reference/audio.md#directeffect-kt),
[Audio buffers](reference/audio.md#audiobuffer-kt), and [Ambisonic effects](reference/audio.md#ambisonicseffects-kt).
[Valve’s Steam Audio programmer’s guide](https://valvesoftware.github.io/steam-audio/doc/capi/guide.html)
covers the corresponding native API. The [screen study source](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/examples/steamaudio/SteamAudioScreenStudy.kt)
contains the complete render.
