# A head-tracked WAV in text_one

`SpatialWavPlayer` prepares a mono WAV and a Steam Audio HRTF, reads the current published
OpenXR pose on the audio thread, and emits binaural stereo to a dedicated WiVRn output.
It supports `play`, `pause`, `seek`, `restart`, `place`, `gain` and `looping`.

Install the native prerequisite once:

```bash
./gradlew fetchSteamAudioNatives
```

Prepare the example asset and operation transcript, then apply it through Field:

```bash
uv run python templates/text_one/audio/install.py ~/Documents/AFieldWorkspace/text_one
uv run --group backdoor python tools/field_backdoor.py --json --port 9092 \
  --attach-existing --document text_one \
  --command 'macro write install_spatial_wav --file /tmp/text-one-spatial-audio.json' \
  --command 'macro run install_spatial_wav'
```

Connect the headset with WiVRn audio enabled; launch Reading space and Drawing, then
**18 · Head-tracked WAV**. The audio starts paused. Use its controls or execute individual
statements against `spatialWordSound`. Stop the box to close its dedicated audio stream.
The new core API requires a fresh Field JVM if Field was started before this source was built.

The file is copied from the repository's mono eigendrum demo. Stereo WAVs currently use
only their left channel as a point source. See
[the spatial-audio guide](../guides/08-spatial-audio.md) for coordinates, scope and buffering.
