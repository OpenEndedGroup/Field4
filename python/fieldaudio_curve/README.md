# Rolling audio curves

A PCM WAV or a stream of mono samples becomes a time-ordered curve through recent spectral states. Each Hann-windowed FFT is converted to 64 area-normalized mel-band powers, compressed (each band raised to `compression`, 0.3 by default) and normalized to unit mass. Pairwise Wasserstein-1 distances on uniformly spaced mel positions feed classical metric MDS. A similarity Procrustes fit (translation, rotation, reflection and scale) aligns the shared points with the preceding curve. The default history is 128 frames; startup curves contain fewer points.

```sh
# From the repository root:
PYTHONPATH=python uv run --group audio-curve -- python -m fieldaudio_curve input.wav output.f4curve
```

The library needs NumPy and SciPy (`uv sync --inexact --group audio-curve`). `CurveAnalyzer(CurveConfig(sample_rate)).push(mono_samples)` returns `(center_time_seconds, coordinates)` pairs and accepts arbitrarily sized chunks. It emits only complete FFT windows; there is no end padding. The CLI accepts uncompressed 8-, 16-, 24- and 32-bit integer PCM WAV and analyzes each channel separately. A mono input writes the requested path; a multichannel input writes `output.ch1.f4curve`, `output.ch2.f4curve`, and so on. Channels share a clock but their independently aligned MDS axes are not comparable. `read_wav_channels` preserves the channels in a sample-major array; `read_wav` explicitly downmixes to mono. `--fft-size`, `--hop-size`, `--mel-bands`, `--history`, `--dimensions` and `--compression` tune it. `--progress` prints `progress <done> <total>` lines (records, counted across every channel) as it goes, which Field's `audioCurves` turns into a progress bar; `write_curves` takes the same report as a `progress(done, frames)` callback. Without compression (`--compression 1`), a voice's lowest five of 64 bands hold a median 71% of the mass and the distance mostly compares them; 0.3, close to how loudness grows with intensity, lets the whole spectrum count. The current implementation solves an eigenproblem for a dense matrix at every hop: this is an offline/batch implementation, not a guaranteed real-time audio callback.

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
| 40 | uint32 | kind: 0 for a rolling window, 1 for EMA timescales (below) |
| 44 | float64 | EMA files: the longest time constant, seconds; otherwise zero |
| 52 | 12 bytes | reserved, zero |

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

## Aligning stereo channels

The usual analysis aligns each channel to its own previous frame. To compare the two channels in one coordinate system, fit the right curve to the left curve at each timestamp:

```sh
PYTHONPATH=python uv run --group audio-curve -- python -m fieldaudio_curve.stereo \
  left.f4curve right.f4curve right-aligned.f4curve
```

The output has the same memory-mappable format and leaves both inputs unchanged. The files must have matching headers, timestamps and point counts. Each fit uses corresponding points, allowing translation, rotation, uniform scale and reflection; left is the reference. It preserves each right curve's shape up to a similarity transform, but does not force genuinely different stereo content to coincide. Each channel was already aligned through time; since the additional stereo fit is independent at each timestamp, it can introduce extra motion when the fit is ambiguous or the channels differ. The two channels are in the left channel's moving frame, not a fixed global frame.

## Averages instead of a window

`python -m fieldaudio_curve.ema input.wav output.f4curve --points 48 --longest 20` writes the same
format, but each curve's points are exponential moving averages of the unit-mass mel spectrum over
`--points` time constants spaced geometrically from one hop to `--longest` seconds, longest first.
Points keep their identity from hop to hop, so each curve is fitted to the previous one using every
point, weighted by its time constant, as a rigid motion (no scale). The slow end barely moves and
holds the frame still. Header `kind` is 1 and the longest time constant is stored at byte 44; the
point-time formula of the rolling format does not apply.

## A map instead of a window

`python -m fieldaudio_curve.som input.wav output.f4curve --nodes 48 --learn 0.5` writes the same
format from a one-dimensional self-organizing map: `--nodes` prototype spectra on a chain. Each hop
the prototype nearest the new spectrum (by Wasserstein-1) and its chain neighbours (Gaussian,
`--sigma` prototypes wide) move toward it at a constant rate set by `--learn` seconds. A conscience
(`--conscience`) handicaps frequent winners so idle prototypes are recruited, but only among
prototypes within `REACH` (1.5) times the best match's distance, so one long sound cannot overwrite
the chain. Each record holds the prototypes in chain order plus the present spectrum (header
`history` is `--nodes + 1`), aligned to the previous record by a rigid fit of the prototypes; the
record's reserved field is the best match's index. Header `kind` is 2 and `--learn` is stored at
byte 44.

`--grid 12x12` or `--grid 6x6x6` puts the prototypes on a sheet or lattice instead of a chain: the
neighbourhood is Gaussian in grid distance, so neighbours in every direction stay similar. Prototypes
are listed in C order (last axis fastest), and the grid's three sizes are stored as uint32 at byte
52 (1 for an unused axis; all zero in a file from before grids, which is a chain). The neighbourhood
starts at half the grid's longest side and narrows to `--sigma` over the first two seconds, so the
grid unfolds before it settles. `--sigma` defaults to 2 on a chain and 1 on a sheet or lattice: at 2,
a 6-wide lattice's neighbourhood spans a third of each side, and its prototypes contract toward the
middle (on the film voice, a median 0.44 typical differences apart instead of 0.58).

`python -m fieldaudio_curve.compare a.wav [b.wav ...]` scores rolling, EMA and map curves of the same
size on memory (how close recent spectra come to the points held), distinct points and movement.

## Stages, for figures

`python -m fieldaudio_curve.stages input.wav out.json --time 2.0` runs the rolling analyzer up to one
moment and writes the window's unit-mass mel spectra, their distance matrix, and the MDS coordinates
of that curve and the previous one before and after alignment. Field's `audioCurveStages(...)` reads
it; the figures on the public [Audio curves](https://openendedgroup.com/field4/audio_curves/) page
come from it.
