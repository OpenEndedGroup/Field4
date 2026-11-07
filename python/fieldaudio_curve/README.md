# Rolling audio curves

A PCM WAV or a stream of mono samples becomes a time-ordered curve through recent spectral states. Each Hann-windowed FFT is converted to 64 area-normalized mel-band powers and normalized to unit mass. Pairwise Wasserstein-1 distances on uniformly spaced mel positions feed classical metric MDS. A similarity Procrustes fit (translation, rotation, reflection and scale) aligns the shared points with the preceding curve. The default history is 128 frames; startup curves contain fewer points.

```sh
# From the repository root:
PYTHONPATH=python uv run --group audio-curve -- python -m fieldaudio_curve input.wav output.f4curve
```

The library needs NumPy and SciPy (`uv sync --inexact --group audio-curve`). `CurveAnalyzer(CurveConfig(sample_rate)).push(mono_samples)` returns `(center_time_seconds, coordinates)` pairs and accepts arbitrarily sized chunks. It emits only complete FFT windows; there is no end padding. The CLI accepts uncompressed 8-, 16-, 24- and 32-bit integer PCM WAV and analyzes each channel separately. A mono input writes the requested path; a multichannel input writes `output.ch1.f4curve`, `output.ch2.f4curve`, and so on. Channels share a clock but their independently aligned MDS axes are not comparable. `read_wav_channels` preserves the channels in a sample-major array; `read_wav` explicitly downmixes to mono. `--fft-size`, `--hop-size`, `--mel-bands`, `--history`, and `--dimensions` tune it. The current implementation solves an eigenproblem for a dense matrix at every hop: this is an offline/batch implementation, not a guaranteed real-time audio callback.

For a denser trace of the 96 kHz `near_drowning.wav`, the following keeps the 4096-sample analysis window and uses 512 points with a 256-sample hop (2.67 ms):

```sh
PYTHONPATH=python OPENBLAS_NUM_THREADS=1 uv run --group audio-curve -- python -m fieldaudio_curve \
  ~/Downloads/near_drowning.wav ~/Downloads/near_drowning_fine.f4curve \
  --fft-size 4096 --hop-size 256 --history 512
```

Its full history spans about 1.36 seconds. An eight-times-smaller hop with only four times as many points halves the earlier 2.73-second history. A 1024-point history would preserve that duration. Denser sampling does not guarantee a smooth path: sharp spectral changes and noise remain in the measurements.

Mass normalization makes spectral *shape* independent of overall level. Silence is represented by zero mass, making silence-to-silence distance zero; distance from silence to sound is a deliberate zero-reference convention, not balanced optimal transport. An eigenspace can be underdetermined for repeated spectra; alignment then has no unique orientation. Scale fitting may change apparent size over time; absolute MDS distances should not be recovered from aligned coordinates. Each curve's points are oldest to newest, so drawing them in array order traces time rather than frequency.

## Memory-mapped file, version 1

A completed file is little-endian, with a 64-byte header and fixed-size records. Write to a temporary path and rename after completion when readers may observe it concurrently; this is not a concurrent publication protocol. Header:

| Offset | Type | Meaning |
| --- | --- | --- |
| 0 | 8 bytes | `F4CURVE\0` |
| 8 | uint32 | version (1) |
| 12 | uint32 | sample rate |
| 16 | uint32 | FFT size |
| 20 | uint32 | hop size |
| 24 | uint32 | history capacity |
| 28 | uint32 | dimensions (2 or 3) |
| 32 | uint64 | record count |
| 40 | 24 bytes | reserved, zero |

Record `i` begins at `64 + i * (16 + 4 * history * dimensions)`: float64 center time of the newest FFT window, uint32 valid point count, uint32 reserved, followed by `history * dimensions` float32 values, point-major. Only the first `count` points are valid; padding is zero. Point `j` has center time `recordTime - (count - 1 - j) * hopSize / sampleRate`.

For example, after validating the magic, version, sizes and file length in Kotlin:

```kotlin
val bytes = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
    channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN)
}
val history = bytes.getInt(24)
val dims = bytes.getInt(28)
val frame = 10
val record = 64 + frame * (16 + 4 * history * dims)
val time = bytes.getDouble(record)
val count = bytes.getInt(record + 8)
val x = bytes.getFloat(record + 16) // oldest valid point
```

The format stores the whole moving curve at each hop, including revisions of older positions as the window changes. Mapping is read-only and intended for completed files; segment mappings for files larger than the JVM buffer limit.

## Play and draw in Field

First create the curve file from the same WAV you will play. For the included example recording:

```sh
PYTHONPATH=python OPENBLAS_NUM_THREADS=1 uv run --group audio-curve -- python -m fieldaudio_curve \
  dev_docs/assets/pat_sound/pat-voice-leading.wav /tmp/voice-leading.f4curve
```

Paste this into a Kotlin box and launch it from the repository checkout (rather than executing it once). The Java Sound clip supplies the playback clock; stopping the box closes both the clip and the mapped reader. Change `scale` to suit the recording. This stereo example draws one curve per channel, using the first two dimensions of a 3D file too.

```kotlin
import afield.audio.analysis.AudioCurveFile
import java.nio.file.Path
import javax.sound.sampled.AudioSystem

val wavPath = Path.of("dev_docs/assets/pat_sound/pat-voice-leading.wav")
val curves = listOf(
    AudioCurveFile(Path.of("/tmp/voice-leading.ch1.f4curve")),
    AudioCurveFile(Path.of("/tmp/voice-leading.ch2.f4curve")),
)
val clip = AudioSystem.getClip()
try {
    AudioSystem.getAudioInputStream(wavPath.toFile()).use { clip.open(it) }
} catch (t: Throwable) {
    clip.close()
    curves.forEach { it.close() }
    throw t
}
val scale = 1800.0
clip.start()
frames {
    try {
        while (frame()) {
            val time = clip.microsecondPosition / 1_000_000.0
            val paths = curves.mapIndexedNotNull { channel, reader ->
                val curve = reader.frameAt(time) ?: return@mapIndexedNotNull null
                if (curve.pointCount < 2) return@mapIndexedNotNull null
                fline {
                    for (i in 0 until curve.pointCount) {
                        val x = 200.0 + 400.0 * channel + scale * curve.coordinates[i * curve.dimensions]
                        val y = 250.0 - scale * curve.coordinates[i * curve.dimensions + 1]
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    color = vec(1.0, 1.0, 1.0, 1.0)
                    thickness = 2.0
                }
            }
            if (paths.isNotEmpty()) lines(*paths.toTypedArray(), channel = "audio curves", w = 800.0, h = 500.0)
            if (clip.microsecondPosition >= clip.microsecondLength) break
        }
    } finally {
        clip.close()
        curves.forEach { it.close() }
    }
}
```

The clip and analysis must use the same source and timeline. The first curve appears at the center of the first complete FFT window. Java Sound chooses the system output device; its playback clock and device latency are not a sample-accurate visual synchronization guarantee.
