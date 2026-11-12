# Audio curves: a sound as a few slow controls

Let a sound steer a camera, a synthesis parameter, or the shape of a drawing. An audio curve gives
you a few numbers to work with: it measures how the sound has changed over the last second or so,
then lays that recent history out as a curve in two or three dimensions. The newest point is your
control, updated about ninety times a second.

The numbers stay *locally consistent*. Moments that sound alike land near each other; a sound that
changes slowly moves the point slowly, and a sudden change moves it a long way. The axes don't flip
or spin from one moment to the next, so a value means the same thing now as it did a tenth of a
second ago. Over longer spans the frame can drift: the map is made from the recent past.

*To run the examples on this page, open the [audio_curve_explorer](agent_transcripts.md#audio_curve_explorer) starting point.*

The recording is 13.5 seconds of *Night of the Living Dead* (1968), a public-domain film: Johnny and
Barbara speaking in the cemetery.

<audio controls preload="metadata" src="assets/subharmonic_sound/notld-barbara-source.wav"></audio>

This box analyses it and plots the newest point of every curve, one hop at a time:

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_curves/signals.png" capture="box" open-outputs="true" wait="3" padding="12" timeout="300" w="1000" h="400" embed=true name="audio-curves-signals" alt="Three control signals, x, y and z, plotted against 13.5 seconds of a film voice. They sit near zero in the pauses and swing in bursts with each phrase, x furthest, with small fast ripples on top of slower movements."}
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val curve = audioCurves(wav, AudioCurveSettings(dimensions = 3)).first()

// The newest point of each curve: three numbers per hop, about 94 times a second.
val count = curve.frameCount.toInt()
val frames = count { curve.frame(it.toLong()) }
val newest = frames.map { it.coordinates.takeLast(3) }
curve.close()

showSignals(
    "x" to newest.map { it[0] },
    "y" to newest.map { it[1] },
    "z" to newest.map { it[2] },
    x = frames.map { it.timeSeconds },
    xTitle = "seconds",
    title = "a voice as three control signals",
    height = 320,
)
```


`audioCurves(wav)` runs the analysis once and caches the result in your system's temporary
files, so later runs can reuse it. It returns one `AudioCurveFile` per channel. Each
holds a curve for every hop, and `frameAt(seconds)` gives you the one current at that moment.

The rest of this page builds that curve one step at a time, on the same recording, from
the analyzer's own numbers: every figure below asks it for the stage it shows.

## 1. Overlapping windows

Sound is a pressure wave, and a spectrum needs a stretch of it. The analyzer takes 2048 samples
(43 ms at 48 kHz), tapers both ends with a Hann window so the cut does not add clicks of its own,
and moves on by 512 samples to take the next. Neighbouring windows share three quarters of their
samples, which is the first reason the result changes smoothly: no moment belongs to only one
window.

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_curves/windows.png" capture="box" open-outputs="true" wait="3" padding="12" timeout="300" w="1000" h="420" embed=true name="audio-curves-windows" alt="A tenth of a second of the voice's waveform above seven overlapping Hann windows, each shifted a quarter of its width from the last; the newest window is drawn in gold."}
import afield.audio.engine.WavFloatArrayLoader

val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val audio = WavFloatArrayLoader.loadWavToFloatArray(wav.toString())
val settings = AudioCurveSettings() // fftSize 2048, hopSize 512
val start = (2.0 * audio.sampleRate).toInt()
val windows = 7
val span = settings.fftSize + (windows - 1) * settings.hopSize
val x = { sample: Int -> 1000.0 * sample / span }

// The excerpt is quiet, so draw it at its own peak.
val peak = (0 until span).maxOf { abs(audio.samples[start + it]) }
val waveform = fline {
    for (i in 0 until span step 2) {
        val y = 80 - 75 * audio.samples[start + i] / peak
        if (i == 0) moveTo(x(i), y) else lineTo(x(i), y)
    }
    color = vec(0.39, 0.78, 0.86, 1.0)
}
// One Hann window per hop: each spectrum sees fftSize samples, and neighbours share three quarters of them.
val hann = windows { k ->
    fline {
        val from = k * settings.hopSize
        for (i in 0..settings.fftSize step 16) {
            val y = 330 - 140 * 0.5 * (1 - cos(2 * PI * i / settings.fftSize))
            if (i == 0) moveTo(x(from + i), y) else lineTo(x(from + i), y)
        }
        color = if (k == windows - 1) vec(1.0, 0.85, 0.4, 1.0) else vec(0.85, 0.9, 1.0, 0.75)
        thickness = if (k == windows - 1) 3.0 else 1.5
    }
}
lines(waveform, *hann.toTypedArray(), channel = "windows", w = 1000.0, h = 360.0, fitToSize = true)
```


## 2. Spectra, on the ear's terms

Each window becomes a spectrum, summed into 64 mel bands. Mel bands are narrow at the bottom and
wide at the top, roughly as the ear divides pitch. Each band's energy is raised to the power 0.3
(`compression`), close to how loudness grows with intensity. Without it, a voice's few lowest bands
hold most of the energy and everything above them barely counts. Last, each spectrum is scaled to
add up to 1. Two moments with the same spectral *shape* are now the same, however loud they are;
loudness is not what this map is about. Silence is all zeros, a spectrum with nothing in it.

The curve at 2 seconds is built from the last 128 of these spectra, about 1.4 seconds of sound.
Every fourth one is drawn here, oldest at the back:

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_curves/spectra.png" capture="box" open-outputs="true" wait="3" padding="12" timeout="300" w="1000" h="480" embed=true name="audio-curves-spectra" alt="A ridgeline of 32 mel spectra from the 1.4 seconds before the 2-second mark, oldest at the back. The back rows are the quiet start of the scene, falling smoothly from the low bands; the front rows are speech, with bumps where the voice's formants sit."}
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val stages = audioCurveStages(wav, 2.0)

// The window's 128 mel spectra, oldest at the back. Each is scaled to total 1: shape, not loudness.
val shown = stages.masses.indices.filter { it % 4 == 3 }
val top = stages.masses.flatten().max()
val ridges = shown.mapIndexed { row, i ->
    val mass = stages.masses[i]
    val age = (row + 1.0) / shown.size
    fline {
        mass.forEachIndexed { band, m ->
            val x = band * 14.0 + row * 5.0
            val y = 120.0 + row * 14.0 - 110 * m / top
            if (band == 0) moveTo(x, y) else lineTo(x, y)
        }
        color = vec(0.39, 0.78, 0.86, 0.15 + 0.85 * age)
    }
}
lines(ridges, channel = "spectra", w = 1000.0, h = 420.0, fitToSize = true)
markdown(
    "about",
    "${stages.masses.size} spectra of ${stages.melBands} mel bands, ${stages.melCentersHz.first().toInt()}–${stages.melCentersHz.last().toInt()} Hz",
)
```


## 3. How far apart two spectra are

Now we need a distance between two spectra. The obvious one, band-by-band difference, treats a
resonance that slides up by one band as completely different from where it was: the energy left one
band and arrived in another, and band-by-band they no longer overlap. The ear hears a small move.

The analyzer uses the *earth mover's distance* (one-dimensional Wasserstein distance) instead. Picture
each spectrum as a pile of sand along the frequency axis: the distance is how much sand has to move,
times how far, to turn one pile into the other. A small slide costs a little and a big jump costs a
lot. On a line this has a closed form, the area between the two cumulative sums. Here are the two
spectra in the window that are furthest apart, and below them their running totals, each rising from 0
to 1 across the bands:

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_curves/transport.png" capture="box" open-outputs="true" wait="3" padding="12" timeout="300" w="1000" h="760" embed=true name="audio-curves-transport" alt="Above, two mel spectra from the same window: one concentrated in the lowest bands, the other spread through the middle with formant bumps. Below, their cumulative sums rise from 0 to 1, with the gold area between them; the caption reads that the area and the analyzer's distance agree."}
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val stages = audioCurveStages(wav, 2.0)
// The two spectra in the window that are furthest apart.
val d = stages.distances
val (i, j) = d.indices.flatMap { i -> d.indices.map { j -> i to j } }.maxBy { (i, j) -> d[i][j] }
val a = stages.masses[i]
val b = stages.masses[j]

showSignals(
    "spectrum a" to a,
    "spectrum b" to b,
    xTitle = "mel band",
    yTitle = "share of the spectrum",
    title = "two spectra from the same window",
    channel = "spectra",
    height = 260,
)

// Earth mover's distance on a line is the area between the two cumulative sums.
fun cdf(m: List<Double>) = m.runningReduce { total, v -> total + v }
val ca = cdf(a)
val cb = cdf(b)
val x = { band: Int -> band * 15.0 }
val y = { c: Double -> 300 - 280 * c }
fun curve(c: List<Double>, color: Vec4) = fline {
    c.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
    this.color = color
    thickness = 2.0
}
val between = fline {
    ca.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
    for (i in cb.indices.reversed()) lineTo(x(i), y(cb[i]))
    close()
    filled = true
    stroked = false
    color = vec(1.0, 0.85, 0.4, 0.35)
}
lines(
    between,
    curve(ca, vec(0.39, 0.78, 0.86, 1.0)),
    curve(cb, vec(0.96, 0.62, 0.30, 1.0)),
    channel = "transport",
    w = 1000.0,
    h = 320.0,
    fitToSize = true,
)

val area = ca.indices.toList().dropLast(1).sumOf { abs(ca[it] - cb[it]) } / (stages.melBands - 1)
markdown("about", "area between the curves %.4f · the analyzer's distance %.4f".format(area, d[i][j]))
```


The caption checks the figure against the analyzer: the area drawn here and the distance it
computed are the same number.

## 4. Every distance in the window

Doing that for every pair of the 128 spectra gives a table of distances. Row *i*, column *j* is how
far spectrum *i* is from spectrum *j*; the diagonal is zero, because every spectrum is its own
nearest neighbour:

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_curves/distances.png" capture="box" open-outputs="true" wait="3" padding="12" timeout="300" w="900" h="960" embed=true name="audio-curves-distances" alt="A 128 by 128 distance matrix in dark-to-gold. A large dark block at the top left is the quiet stretch, every spectrum close to every other; bright bars cross the matrix where speech begins, and the speech itself breaks into smaller dark blocks along the diagonal."}
import afield.media.image.util.PngEncoder

val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val d = audioCurveStages(wav, 2.0).distances
val n = d.size
val top = d.flatten().max()

// Row i, column j: how far spectrum i is from spectrum j. Dark is near; the diagonal is zero.
val size = 900
val px =
    IntArray(size * size) { p ->
        val v = d[p / size * n / size][p % size * n / size] / top
        val r = (12 + 243 * v).toInt()
        val g = (16 + 190 * v.pow(1.4)).toInt()
        val bl = (24 + 60 * v.pow(3)).toInt()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
display("distances", PngEncoder.encode(px, size, size))
```


Dark blocks on the diagonal are sounds that last: a pause, a held vowel. Bright bars are moments
unlike the rest of the window. Everything the curve knows about the sound is in this table.

## 5. From distances to points

Multidimensional scaling (MDS) turns a table of distances back into points. It places 128 points so
that the distances between them match the table as closely as two (or three) dimensions allow. Join
the points in time order and you have the curve:

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_curves/mds.png" capture="box" open-outputs="true" wait="3" padding="12" timeout="300" w="800" h="560" embed=true name="audio-curves-mds" alt="The MDS layout of the 128 spectra as one curve, faint where it is oldest and bright at its newest end. The quiet stretch is a tight knot; the speech pulls the curve out into long excursions."}
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val stages = audioCurveStages(wav, 2.0)
val now = stages.curves.last()

// MDS places the 128 spectra in two dimensions so that distances between points match the
// distances between spectra as well as two dimensions allow. Joined in time order, they make a curve.
val frame = AudioCurveFrame(now.time, 2, now.unaligned.flatten().map { it.toFloat() }.toFloatArray())
lines(frame.flines(scale = 1000.0, bands = 12), channel = "mds", w = 700.0, h = 500.0, fitToSize = true)
```


Classical MDS is one eigendecomposition. It finds the directions along which the distances spread
most, and the coordinates are how far each spectrum lies along them.

## 6. Holding the map still

There is a catch. Distances do not change if you rotate the points, mirror them or slide them, so
MDS has no reason to prefer one orientation over another. In practice the answer from one hop to the
next is usually almost the same, but every so often the solver hands back the mirror image, because
the sign of an eigenvector is arbitrary. To your control signals that is a jump from one side of the
range to the other, though nothing in the sound changed.

The fix uses the overlap. Consecutive curves share 127 of their 128 spectra, so the new curve is
rotated, mirrored, scaled and moved (a similarity Procrustes fit) to lie as closely as possible on
the previous one. Here is one of those flips, three seconds in:

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_curves/alignment.png" capture="box" open-outputs="true" wait="3" padding="12" timeout="300" w="1000" h="520" embed=true name="audio-curves-alignment" alt="Left: the previous curve in orange and the current one in blue as MDS solved them, mirror images of each other. Right: the same two after the similarity fit, lying on top of each other. The caption gives how far apart their shared points are before and after alignment."}
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val stages = audioCurveStages(wav, 3.083)
val (before, now) = stages.curves

fun frame(points: List<List<Double>>) =
    AudioCurveFrame(now.time, 2, points.flatten().map { it.toFloat() }.toFloatArray())
val orange = vec(0.96, 0.62, 0.30, 1.0)

// Left: two consecutive MDS solutions as solved (previous orange, current blue).
// Right: the same two after the similarity fit.
val solved = frame(before.unaligned).flines(1000.0, color = orange) + frame(now.unaligned).flines(1000.0)
val width = now.unaligned.maxOf { it[0] } - now.unaligned.minOf { it[0] }
val aligned = (frame(before.aligned).flines(1000.0, color = orange) + frame(now.aligned).flines(1000.0)).map {
    it + vec(1000.0 * width * 1.3, 0.0, 0.0)
}
lines(solved + aligned, channel = "alignment", w = 1000.0, h = 420.0, fitToSize = true)

// How far the shared points are apart, as a fraction of the curve's size.
fun mismatch(a: List<List<Double>>, b: List<List<Double>>): Double {
    val x = a.drop(1).flatten()
    val y = b.dropLast(1).flatten()
    val mean = x.chunked(2).let { p -> listOf(p.map { it[0] }.average(), p.map { it[1] }.average()) }
    val size = sqrt(x.chunked(2).sumOf { (it[0] - mean[0]).pow(2) + (it[1] - mean[1]).pow(2) })
    return sqrt(x.indices.sumOf { (x[it] - y[it]).pow(2) }) / size
}
markdown(
    "about",
    "as solved: shared points %.0f%% of the curve's size apart · aligned: %.2f%%"
        .format(
            100 * mismatch(before.unaligned, now.unaligned),
            100 * mismatch(before.aligned, now.aligned),
        ),
)
```


Each fit looks one hop back. It keeps values consistent from moment to moment, but small changes
in the frame can add up over many seconds. Distances within one curve stay
meaningful; absolute coordinates far apart in time should not be compared.

## 7. Play it

Here is all of it, in three dimensions, under Field's waveform player. The player keeps the audio
clock. `AudioVisualizerOptions(onTime = ...)` hands this box the time it is playing, or wherever you
are hovering or dragging across the waveform, and the box draws the curve current at that moment as
FLines in the Three.js viewer:

```diffstatic {data-lang=kotlin}
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val curve = audioCurves(wav, AudioCurveSettings(dimensions = 3)).first()
val scale = 100 / curve.extent()

// The player keeps the audio clock and tells this box where it is, or where you are scrubbing.
var t = 0.0
displayAudioBytes(wav.toFile().readBytes(), options = AudioVisualizerOptions(onTime = { t = it }))

// A faint cube around where the curve lives, so the view holds still while you orbit.
val room = fline {
    for (z in [-80, 80]) {
        moveTo(-80, -80, z)
        lineTo(80, -80, z)
        lineTo(80, 80, z)
        lineTo(-80, 80, z)
        lineTo(-80, -80, z)
    }
    for (x in [-80, 80]) for (y in [-80, 80]) {
        moveTo(x, y, -80)
        lineTo(x, y, 80)
    }
    color = vec(1, 1, 1, 0.12)
}

var shown = Double.NaN
loop {
    // Before the first complete window, show the first curve.
    val frame = curve.frameAt(t) ?: curve.frame(0)
    if (frame.timeSeconds != shown) {
        shown = frame.timeSeconds
        threeLines(frame.flines(scale, bands = 16, centered = true) + room, channel = "curve", height = 560)
    }
}
    .end { curve.close() }
```

Launch the box so its `loop` keeps drawing until you stop it. Press play, or drag across the
waveform to scrub:

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" capture-box="audio_curve_finale" video="assets/audio_curves/finale.mp4" fps="12" transcript="../../dev_docs/agents/transcripts/docs_capture/audio_curve_finale.yaml" hide-toasts=true padding="8" wait="0" timeout="300" embed=true hidden=true name="audio-curves-finale" alt="Field's waveform player above a Three.js view of the voice's 3D curve inside a faint cube. Play is pressed; the gold playhead runs across the waveform and the curve writhes and stretches with each phrase, folding back into a small knot in the pauses."}
// The setup transcript creates and launches the box and waits for it; press play.
fun playButton(): Pair<Double, Double> {
    val c =
        ui.eval(
                """
        function find(doc, ox, oy) {
            const a = doc.querySelector('audio');
            if (a) { const r = a.getBoundingClientRect(); return [ox + r.left + 24, oy + r.top + r.height / 2]; }
            for (const f of doc.querySelectorAll('iframe')) {
                try { const r = f.getBoundingClientRect(); const hit = find(f.contentDocument, ox + r.left, oy + r.top); if (hit) return hit; } catch (e) {}
            }
            return null;
        }
        const hit = find(document, 0, 0);
        return hit ? hit.join(',') : 'missing';
    """,
            )
            .toString()
            .trim('"')
    check(c != "missing") { "no audio player in the outputs" }
    val (x, y) = c.split(',').map { it.toDouble() }
    return x to y
}
ui.pause(1500)
val (x, y) = playButton()
ui.moveMouse(x, y, 8)
ui.pause(200)
ui.mouseDown()
ui.pause(60)
ui.mouseUp()
ui.pause(9000)
```

Each frame draws the newest curve: faint at its oldest end, bright at its newest, sixteen pieces in
between. `centered = true` draws it around its own middle, because the slow slide
of the frame described above would otherwise carry it out of the cube over a long recording; the
turning and the shape are untouched. The three coordinates are the three strongest directions of the window's distances, so the
newest point is the three-number control from the top of the page, with the rest of the curve as
its recent past.

## A longer memory: averages instead of a window

The rolling curve forgets anything older than its window, and its frame drifts because each fit
only looks one hop back. A different choice of points fixes both. Instead of the last 128 spectra,
keep a set of *running averages* of the spectrum (exponential moving averages), each over a
different timescale: one over a single hop, the next a little longer, and so on up to twenty
seconds, spaced evenly on a log scale. Each hop, MDS lays out those averages.

Now the points mean something different. The bright end of the curve is the sound right now; the
faint end is the sound averaged over the last twenty seconds; everything in between is the same
recording remembered over a longer and longer span. Every point keeps its identity from one hop to
the next, so alignment can fit all of them, and it weights them by their timescale: the slow
averages hardly move, so they hold the frame still. With that, nothing needs rescaling, and
coordinates stay in the units of the distance itself.

Set `emaLongestSeconds` to use it; `history` becomes the number of timescales. The present still
comes last, so the code that reads the newest point does not change:

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_curves/ema-signals.png" capture="box" open-outputs="true" wait="3" padding="12" timeout="300" w="1000" h="400" embed=true name="audio-curves-ema-signals" alt="The voice's three control signals from the averaged curve, with the x coordinate of its 20-second average in yellow. The average climbs slowly and barely moves; the present's x, y and z swing with each phrase around it."}
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
// 48 running averages, from one hop (10.7 ms) up to 20 seconds.
val settings = AudioCurveSettings(dimensions = 3, history = 48, emaLongestSeconds = 20.0)
val curve = audioCurves(wav, settings).first()

val count = curve.frameCount.toInt()
val frames = count { curve.frame(it.toLong()) }
val present = frames.map { it.coordinates.takeLast(3) } // the shortest timescale
val memory = frames.map { it.coordinates.take(3) } // the 20-second average
curve.close()

showSignals(
    "x" to present.map { it[0] },
    "y" to present.map { it[1] },
    "z" to present.map { it[2] },
    "20 s memory, x" to memory.map { it[0] },
    x = frames.map { it.timeSeconds },
    xTitle = "seconds",
    title = "the same voice, anchored by its averages",
    height = 320,
)
```

Compare the yellow line with the signals at the top of the page: the long view sits nearly still,
so the frame the present moves in is the same at the end of the recording as near its start. The
long averages begin from the first spectrum, so for the first few seconds the far end of the curve
describes how the file began.

Here is the curve itself, nine seconds in:

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_curves/ema-curve.png" capture="box" open-outputs="true" wait="3" padding="12" timeout="300" w="900" h="560" embed=true name="audio-curves-ema-curve" alt="The averaged curve in the Three.js viewer: a smooth line, faint at its 20-second end, bending through the middle timescales to a bright, short hook at the present."}
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val curve = audioCurves(wav, AudioCurveSettings(dimensions = 3, history = 48, emaLongestSeconds = 20.0)).first()

// Nine seconds in: the faint end is the 20-second average, the bright end the last hop.
val frame = curve.frameAt(9.0)!!
threeLines(frame.flines(100 / curve.extent(), bands = 16), channel = "curve", height = 480)
curve.close()
```

Because neighbouring timescales average almost the same sound, the curve is smooth along its
length, where the rolling curve is as ragged as the sound. The
[audio_curve_explorer](agent_transcripts.md#audio_curve_explorer) starting point has this curve
live under the waveform player, beside the rolling one.

## A self-organizing map of what was heard

The averaged curve has a weakness that only shows once you measure it. Every running average heads
toward the same place, the recording's overall spectrum, so the averages over one second, two and
five end up describing nearly the same sound, and a distinct sound from six seconds ago is averaged
away rather than kept. The stack remembers *when* in ever blurrier terms; it does not remember
*what*.

A self-organizing map remembers what. Keep 48 prototype spectra on a chain. Each hop, find the
prototype nearest the new spectrum (its best match) and move it, and less so its neighbours along
the chain, a little toward that spectrum. Over a recording the prototypes spread out over the kinds
of sound it makes: a vowel heard once keeps a prototype until something else needs it. Neighbouring
prototypes stay similar, so the chain is itself a curve through the space of spectra, and MDS lays
it out each hop with the present spectrum as one more point. Mixing two spectra mixes their
cumulative spectra the same way, so every prototype stays a valid spectrum, measured with the same
earth mover's distance as everything above.

Two changes from a textbook map suit a stream. The learning rate never decays, so the map keeps
adapting. And a prototype that wins often is handicapped, so that new sounds recruit idle
prototypes instead of piling onto one; that handicap may only hand a sound to a prototype nearly as
close as the best match, or one long steady sound would spread over the whole chain and overwrite
the rest.

Set `somLearnSeconds` to use it: how fast the best match moves toward a new sound. `history` is the
number of prototypes, and each frame holds them plus the present, with `bestMatch` saying which
prototype the present is nearest. That index is a control signal of its own. Here it climbs along
the chain as the voice brings new sounds, and drops back to the low end of the chain, around 10 or
below, in each pause between phrases:

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_curves/map-match.png" capture="box" open-outputs="true" wait="3" padding="12" timeout="300" w="1000" h="400" embed=true name="audio-curves-map-match" alt="The voice's best-match prototype index over time: a stepped line that climbs from about 6 toward 30 and above over the recording, dropping back to 10 or below in the pauses near 1, 4, 7, 8.5, 9.7 and 12.5 seconds."}
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
// 48 prototype spectra; the best match moves toward each new sound with a 0.5 s time constant.
val settings = AudioCurveSettings(dimensions = 3, history = 48, somLearnSeconds = 0.5)
val curve = audioCurves(wav, settings).first()

val count = curve.frameCount.toInt()
val frames = count { curve.frame(it.toLong()) }
curve.close()

showSignals(
    "best match" to frames.map { it.bestMatch!!.toDouble() },
    x = frames.map { it.timeSeconds },
    xTitle = "seconds",
    title = "which remembered sound the voice is nearest",
    height = 320,
)
```

To compare the three kinds of curve, score each on the same recording with 48 points. *Memory* is
how close every spectrum from the last ten seconds comes to some point the curve still holds;
*distinct* counts the points that differ from all the others by more than a quarter of a typical
difference; *moves* is how far the points travel in half a second. Distances are in units of the
typical difference between two moments of the recording, over the second half of each file:

| Recording | Curve | Memory | Distinct points | Moves per 0.5 s |
| --- | --- | --- | --- | --- |
| Film voice, 13.5 s | rolling window | 0.33 | 19 | (replaced every hop) |
| | running averages | 0.43 | 7 | 0.26 |
| | map | 0.23 | 14 | 0.01 |
| Music, 8.2 s | rolling window | 0.21 | 12 | (replaced every hop) |
| | running averages | 0.35 | 6 | 0.24 |
| | map | 0.18 | 16 | 0.01 |

The map holds the last ten seconds more closely than the rolling window, which only ever holds the
last half second, with points twice as distinct as the averages and a twentieth of their movement.
`python -m fieldaudio_curve.compare recording.wav` prints the same table for any recording.

### Sheets and lattices

Nothing about the map needs the prototypes on a chain. Put them on a sheet, 12 by 12, and each one
has neighbours in four directions; on a lattice, 6 by 6 by 6, in six. The best match still pulls its
neighbours along, now across the whole sheet or through the whole volume, so similar sounds end up
near each other in every direction and the grid folds itself through the space of spectra. Pass the
grid as `somGrid`. The neighbourhood starts wide and narrows over the first two seconds, so the grid
unfolds before it settles instead of locking in a twist.

Drawn, every grid edge is a line, coloured by where it sits on the grid (one axis drives red, the
next green, the third blue), so the folds show as bands of colour. The grid glows around the best
match, an orange trail follows the best match over the last half second, and the white tether runs to
the present sound. `stretch = true` evens out the three axes: MDS puts most of a voice's variation
on its first axis, so drawn to scale a sheet is nearly a line. Each map turns slowly as the sound
plays, so its shape reads in depth:

```diffstatic {data-lang=kotlin}
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
// A sheet of 12 × 12 prototypes and a lattice of 6 × 6 × 6, each learning with a 0.5 s time constant.
val sheet = audioCurves(wav, AudioCurveSettings(dimensions = 3, somLearnSeconds = 0.5, somGrid = [12, 12])).first()
val lattice = audioCurves(wav, AudioCurveSettings(dimensions = 3, somLearnSeconds = 0.5, somGrid = [6, 6, 6])).first()

var t = 0.0
displayAudioBytes(wav.toFile().readBytes(), options = AudioVisualizerOptions(onTime = { t = it }))

// A faint room around each map, so the view holds still while the maps turn.
fun room(cx: Double) = fline {
    for (z in [-80.0, 80.0]) {
        moveTo(cx - 80, -80.0, z)
        lineTo(cx + 80, -80.0, z)
        lineTo(cx + 80, 80.0, z)
        lineTo(cx - 80, 80.0, z)
        lineTo(cx - 80, -80.0, z)
    }
    for (x in [cx - 80, cx + 80]) for (y in [-80.0, 80.0]) {
        moveTo(x, y, -80.0)
        lineTo(x, y, 80.0)
    }
    color = vec(1, 1, 1, 0.1)
}
val rooms = [room(-100.0), room(100.0)]

// Turn a map slowly about the vertical axis as the sound plays, then move it into its room.
fun place(lines: List<FLine>, turn: Double, cx: Double) = lines.map { line ->
    line.transform { p -> vec(p.x * cos(turn) + p.z * sin(turn) + cx, p.y, -p.x * sin(turn) + p.z * cos(turn)) }
}

var shown = Double.NaN
var first = true
loop {
    val a = sheet.frameAt(t) ?: sheet.frame(0)
    if (a.timeSeconds != shown) {
        shown = a.timeSeconds
        val b = lattice.frameAt(t) ?: lattice.frame(0)
        val turn = 0.3 * t
        val left = place(
            a.flines(55 / a.spread(), centered = true, stretch = true, trail = sheet.recentMatches(t, 0.5)),
            turn,
            -100.0,
        )
        val right = place(
            b.flines(55 / b.spread(), centered = true, stretch = true, trail = lattice.recentMatches(t, 0.5)),
            turn,
            100.0,
        )
        // Frame the view once; after that the rooms hold it still while the maps turn.
        threeLines(left + right + rooms, channel = "maps", height = 560, autoFrame = first)
        first = false
    }
}
    .end {
        sheet.close()
        lattice.close()
    }
```

The tether is usually short. A single hop's spectrum is noisy next to the prototypes, which each
average many hops, yet the present usually lies nearer its best match than to the average of its
own surrounding half second. It reaches out at onsets, when a sound arrives that the map has not
learned yet, and `stretch` lengthens it along the axes it scales up.

Here it is from the first sound to the last. Both maps start as a single point, every prototype at
the first spectrum, and unfold as the voice gives them more to remember:

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" capture-box="audio_curve_maps" video="assets/audio_curves/maps.mp4" fps="12" transcript="../../dev_docs/agents/transcripts/docs_capture/audio_curve_maps.yaml" hide-toasts=true padding="8" wait="0" timeout="300" embed=true hidden=true name="audio-curves-maps" alt="Field's waveform player above a Three.js view of two maps of the voice, each turning slowly in its own faint room: on the left a coloured sheet, on the right a coloured lattice. Play is pressed; both start as a point and unfold as the voice speaks, glowing where the present sound lands, with an orange trail and a white tether to the present."}
// The setup transcript creates and launches the box and waits for it; press play.
fun playButton(): Pair<Double, Double> {
    val c =
        ui.eval(
                """
        function find(doc, ox, oy) {
            const a = doc.querySelector('audio');
            if (a) { const r = a.getBoundingClientRect(); return [ox + r.left + 24, oy + r.top + r.height / 2]; }
            for (const f of doc.querySelectorAll('iframe')) {
                try { const r = f.getBoundingClientRect(); const hit = find(f.contentDocument, ox + r.left, oy + r.top); if (hit) return hit; } catch (e) {}
            }
            return null;
        }
        const hit = find(document, 0, 0);
        return hit ? hit.join(',') : 'missing';
    """,
            )
            .toString()
            .trim('"')
    check(c != "missing") { "no audio player in the outputs" }
    val (x, y) = c.split(',').map { it.toDouble() }
    return x to y
}
ui.pause(1500)
val (x, y) = playButton()
ui.moveMouse(x, y, 8)
ui.pause(200)
ui.mouseDown()
ui.pause(60)
ui.mouseUp()
ui.pause(13500)
```

## Your own recordings

`audioCurves` takes any uncompressed PCM WAV and returns one file per channel. For your own
recording, use `"~/Downloads/recording.wav".path`, or `"recording.wav".path` for the document's
`files/` folder (see [Files and paths](files_and_paths.md)). [`AudioCurveSettings`](reference/audio.md#audiocurves-kt-audiocurvesettings) changes the
analysis; each combination of settings is analysed once and kept:

| Setting | Default | What it changes |
| --- | --- | --- |
| `fftSize` | 2048 | Samples per spectrum. Larger resolves pitch more finely and blurs timing. |
| `hopSize` | 512 | Samples between curves. Smaller gives more control updates per second. |
| `melBands` | 64 | How finely the spectrum is divided. |
| `history` | 128 | Spectra per curve: how much past the map is made from (`history × hopSize` samples). |
| `dimensions` | 2 | 2 or 3 coordinates per point. |
| `compression` | 0.3 | Exponent on each band's energy; 1.0 is raw power. |
| `emaLongestSeconds` | null | Running averages up to this many seconds instead of a rolling window. |
| `somLearnSeconds` | null | A map of `history` prototypes instead, learning with this time constant. |
| `somGrid` | null | With `somLearnSeconds`, a sheet (`[12, 12]`) or lattice (`[6, 6, 6]`) of prototypes instead of a chain. |

A longer `history` makes a steadier map that reacts more slowly to new kinds of sound; a shorter one
follows the sound closely and forgets it quickly. The [audio_curve_explorer](agent_transcripts.md#audio_curve_explorer)
starting point has the 3D curve above, a stereo recording drawn as one curve per channel, the
voice with half the hop and three times the history, the averaged curve, and the sheet and lattice maps.

A stereo file gives two curves, one per channel, and each has its own axes. To compare the
channels in one frame, fit one to the other with
`python -m fieldaudio_curve.stereo` (see the
[package README](https://github.com/OpenEndedGroup/Field4/blob/main/python/fieldaudio_curve/README.md)).

The analysis itself is the `fieldaudio_curve` Python package that ships with Field, run through
`uv` with NumPy and SciPy (`uv sync --inexact --group audio-curve`). It writes a memory-mappable
`.f4curve` file whose layout is in the README; `AudioCurveFile` reads one directly. Every figure on
this page comes from `audioCurveStages(wav, seconds)`, which runs the same analyzer up to one moment
and returns its spectra, distance table and curves before and after alignment.

## See also

- [Short-time spectral spaces](audio_ai_spectral_embeddings.md): the same mel transport distance,
  laid out over a whole recording with t-SNE rather than rolling through it.
- [Visualizers](visualizers.md#recorded-audio-waveform-player-playhead-and-navigation): the waveform
  player, and `onTime` for drawing in step with it.
- [Loris](loris.md): a recording as partial tracks you can edit and resynthesize.

## API reference

Implementation and parameter details for the helpers used above:

- [`AudioCurveFrame`](reference/audio.md#audiocurvefile-kt-audiocurveframe)
