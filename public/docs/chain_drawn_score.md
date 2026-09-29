# A Drawn Score, Heard

Three parts of Field, written without each other in mind. The drawing layer holds a score:
rectangles, a pen stroke, a level line. `notation.space` reads it as time across and pitch up. A
dozen lines of box code decide what each mark means as music and build a pattern from the notes,
and `patRenderStereo` plays the pattern offline into a `.wav`. Then the `.wav` is taken apart again,
onsets and pitches, and put next to what was drawn.

The score here is drawn by code, so the page can be rebuilt without anyone holding a pen. The
reading does not know that. A rectangle you draw by hand in the same frame is read the same way.

## The score, and a playhead through it

`drawnScore()` draws a frame named "score", seven blue rectangles as a piano roll, a red glissando
rising over two beats, a green held note with a hand's wobble in it, and a text label. The box then
declares the frame a score, eight beats across and MIDI 84 down to 48, and cuts it with a vertical
line at beat 4½, in the middle of the glissando:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_score/score.png" capture="window" embed=true open-outputs="false" clip="0,0,1080,470" w="180" h="50" wait="3.0" timeout="90" name="chain-drawn-score-score" alt="A frame labelled 'score' holding seven blue rectangles in a rising and falling piano roll, a red curve climbing steeply through the middle, a green level line to its right and an orange vertical playhead crossing the red curve, beside the box's readout of the cut and the notes read"}
notation { drawnScore() }
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)   // eight beats; MIDI 84 at the top
val playhead = 4.5
notation("playhead") { stroke = "#f08c00"; line(listOf(score.toWorld(playhead, 85.0), score.toWorld(playhead, 47.0))) }

:::fold what the playhead cuts, and every note the score reads as
val cut = score.vertical(playhead).filter { !it.isSpan }
val notes = score.notes()
val from = { kind: MarkType -> notes.count { it.event.mark.kind == kind } }
markdown("reading", (cut.map { "at beat $playhead: ${it.mark.name}  \nMIDI %.2f, rising %.1f/beat".format(it.value, it.slope) } +
    "${notes.size} notes: ${from(MarkType.RECTANGLE)} from  \nrectangles, ${from(MarkType.STROKE)} from strokes").joinToString("  \n"))
:::endfold
```

A cut returns a hit per crossing, with the pitch there and the slope, in semitones per beat. The
playhead crosses the glissando and nothing else: the rectangles either side of it end at beat 4 and
start at 6, and the playhead does not cross itself.

## From a reading to a pattern

`score.events` gives every mark in the frame with where it starts and ends in beats, and the pitch
at its two ends. What those mean as music is not the drawing's business, so it is written out here,
in the box, where you can change it. This reading makes three decisions:

* A **rectangle**, or a **stroke that stays level**, is one note: from its left edge to its right, at
  its pitch. "Level" is its two ends within half a semitone, so the held note's wobble does not make
  it a glissando.
* A **stroke that moves** is cut with `score.vertical(t)` every eighth of a beat, and the pitch at
  each cut sounds until the next. The voice holds one pitch per note, so the drawn glissando plays as
  a run of short notes. Pen pressure at the cut, where the stroke recorded any, is the note's gain.
* **Text, frames and arrows** are labels, not notes. The "gliss." inside the score is an event, and
  it is skipped.

`patTimed` turns the notes into a pattern. The whole x range is one cycle, so `cps` is the tempo:
eight beats at a quarter of a cycle a second is 120 beats a minute.

```diffstatic {data-lang=kotlin}
notation { drawnScore() }
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)   // eight beats; MIDI 84 at the top
val glideStep = 0.125                                                 // how finely a moving stroke is read

val notes = mutableListOf<TimedNote>()
for (e in score.events) {
    val kind = e.mark.kind
    if (kind == MarkType.TEXT || kind == MarkType.FRAME || kind == MarkType.ARROW) continue   // labels, not notes
    if (e.end <= e.start) continue                                                           // no width, no time
    val moving = (kind == MarkType.STROKE || kind == MarkType.LINE) && kotlin.math.abs(e.endValue - e.value) > 0.5
    if (!moving) {                                                  // a rectangle, or a level line: one note
        notes += TimedNote(e.start, e.end, e.value)
        continue
    }
    var t = e.start                                                 // a glissando: cut it every glideStep
    while (t < e.end - 1e-9) {
        val hit = score.vertical(t).firstOrNull { it.mark.id == e.mark.id && !it.isSpan }
        if (hit != null) notes += TimedNote(t, minOf(t + glideStep, e.end), hit.value, hit.pressure ?: 1.0)
        t += glideStep
    }
}
val tune = patTimed(notes, beats = 8.0)
showPatRollOf("read" to tune, title = "the score, read", subtitle = "${notes.size} notes", cycles = 1, pitched = true)
```

Played through `patVoice`, the stand-in synth every pattern page uses (one triangle oscillator, an
envelope and a lowpass):

```diffstatic {data-lang=kotlin}
val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
val audio = patRenderStereo(tune, cycles = 2, cps = 0.25, voice = voice)
```

The voice is detached on purpose. Each note holds for 40% of its length and its tail is gone 16 ms
later, so the glissando's steps are separate strikes you can count. Two cycles:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_drawn_score/drawn-score.wav" audio-spec="drawn-score" audio-renderer="afield.four.synth.DrawnScoreDocs" embed=true hidden=true name="chain-drawn-score-audio"}
// Offline-rendered by DrawnScoreDocs.renderWav("drawn-score").
```

The renderer opens a document of its own, draws the same score into it and runs the loop above:
`DrawnScoreDocs.interpret` is that loop, word for word, and `DrawnScorePageTest` fails if the two
drift apart. The same reading also ships as `score.toPattern()`, for when the defaults are what you
want.

## The same drawing, read another way

Nothing in the drawing says the y axis is continuous MIDI. Snap every note to C minor pentatonic
and the glissando stops being a chromatic smear and becomes a run through the scale. A step that
lands on the pitch the last one did is held rather than struck again:

```diffstatic {data-lang=kotlin}
notation { drawnScore() }
val notes = DrawnScoreDocs.interpret(notation.space("score", x = 0.0..8.0, y = 84.0..48.0))   // the loop above

val minorPentatonic = setOf(0, 3, 5, 7, 10)                                // C Eb F G Bb
val inScale = (24..108).filter { it % 12 in minorPentatonic }
val snap = { midi: Double -> inScale.minBy { kotlin.math.abs(it - midi) }.toDouble() }
val tuned = mutableListOf<TimedNote>()
for (n in notes.map { it.copy(note = snap(it.note)) }) {
    val last = tuned.lastOrNull()
    if (last != null && last.note == n.note && last.end == n.start) tuned[tuned.size - 1] = last.copy(end = n.end)   // same pitch: hold it
    else tuned += n
}
showPatRollOf("read" to patTimed(notes, beats = 8.0), "C minor pentatonic" to patTimed(tuned, beats = 8.0),
    title = "one drawing, two readings", subtitle = "${notes.size} notes, then ${tuned.size}", cycles = 1, pitched = true)
```

```diffstatic_runner {data-lang=kotlin audio="assets/chain_drawn_score/drawn-score-pentatonic.wav" audio-spec="drawn-score-pentatonic" audio-renderer="afield.four.synth.DrawnScoreDocs" embed=true hidden=true name="chain-drawn-score-pentatonic-audio"}
// Offline-rendered by DrawnScoreDocs.renderWav("drawn-score-pentatonic").
```

??? aside "Two figures owed"

    The reading loop and the pentatonic loop were first published as figures, a piano roll of each.
    Those captures are owed: the machine was on battery at 50% when this revision was made, and
    the docs runner refuses to capture below 80% off AC. They are code only for now. Neither loop
    goes unrun: `DrawnScorePageTest` holds both equal, line for line, to `DrawnScoreDocs.interpret`
    and `pentatonic`, which render both published clips and which `synth.drawn.score` measures.
    The `play` and `pentatonic` boxes in the starting point run the same loops. Restore
    `screenshot="assets/chain_drawn_score/read.png"` and `pentatonic.png` on the two fences to
    capture them.

Other readings are as short. Rectangle height could be loudness, or the size of a chord; the slope
at a cut could be articulation, a steep line struck hard; a mark's `stroke` colour could pick the
voice. Each is a change to the loop, not to the drawing.

## Listening back

The box below plays the first reading again from its own drawing, then takes the audio apart.
`PatSoundDocs.onsetPeaks` finds the strikes without being told where they are. YIN finds the pitch
of every note over the part of it that is held. The plot is YIN's pitch against the pitch of the
note drawn at that instant, frame by frame. The steps of the glissando are the staircase in the
middle, and the table is the measurement:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_score/heard.png" wait="8" capture="box" open-outputs="true" w="1180" h="300" padding="18" timeout="120" name="chain-drawn-score-heard" embed=true alt="Two traces of MIDI pitch against seconds over two cycles, the drawn pitch and the heard pitch lying on top of each other through the piano roll, the sixteen-step glissando staircase and the held note, above a table of onset and pitch errors"}
notation { drawnScore() }
val notes = DrawnScoreDocs.interpret(notation.space("score", x = 0.0..8.0, y = 84.0..48.0))   // the loop above
val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
val audio = patRenderStereo(patTimed(notes, beats = 8.0), cycles = 2, cps = 0.25, voice = voice)
val heard = DrawnScoreDocs.listen(audio, notes)
notation.clear()   // read and played: take the marks down so they do not lie over the plot

:::fold plot it, and compare it with the published clip's reading
val trace = heard.trace()
showSignals(
    "drawn" to trace.map { it.third }, "heard" to trace.map { it.second },
    x = trace.map { it.first }, title = "pitch, drawn and heard", xTitle = "seconds", yTitle = "MIDI",
    channel = "pitch",
)
// The .wav above is rendered from a document the renderer draws for itself; this is the same reading.
val same = DrawnScoreDocs.sceneNotes("drawn-score") == notes
markdown("measured", """
| | |
|---|---|
| notes scheduled, two cycles | ${heard.scheduled} |
| onsets detected, not told where | ${heard.onsetsDetected} |
| onsets within 30 ms of a note | ${heard.matchedOnsets} |
| worst onset error | ${"%.1f".format(heard.maxOnsetErrorMs)} ms |
| notes with a voiced pitch | ${heard.pitched} |
| worst pitch error | ${"%.3f".format(heard.maxPitchError)} semitones |
| the published clip reads the same notes | $same |
""", width = 1000)
:::endfold
```

Every note is found and every pitch is where the reading put it, including the glissando's steps,
whose pitches fall between semitones. The pentatonic reading measures the same way: 32 notes over
two cycles, all 32 onsets found (worst 10.8 ms) and all 32 pitches within 0.003 semitones. The onset error is the detector's, not the score's: `onsetPeaks`
reports the start of the 12 ms window that first rises, and that window starts before the strike.
The `synth.drawn.score` smoke test runs the same measurement on every build and fails if a strike
goes missing or a pitch moves by a quarter of a semitone, in either reading.

??? aside "Why the voice is detached"

    The first render held each note for 60% of its length with a 12 ms tail, and the detector found
    16 of 48 strikes. At 50% and 8 ms it found 32, missing every glissando step in the first cycle
    and none in the second. `onsetPeaks` re-arms when the level falls below half the level of the
    window that triggered it, and that window is low on the attack, so it can be a few percent of
    the note's peak. The gaps between steps fell to about 5%, and whether that was deep enough
    depended on where the 2 ms hop landed against each attack. Its KDoc had said it re-arms against
    "the last onset's peak", and now says what it does. The voice was then shortened until the
    steps are separated by true silence, rather than changing a detector six other pages count with.

## Drawing your own

Open **drawn_score** from the welcome screen's starting points: `score`, `read`, `play`,
`pentatonic` and `listen back`, run in that order. `play` holds the reading loop from this page, so
that is the box to change. Then press `alt-d` and draw into the frame: `r` for a rectangle (a note),
`p` for a pen stroke (a glissando if it moves, a held note if it does not). Run the boxes after
`score` again, and they read what you drew along with what the code drew.

A frame you make yourself with Excalidraw's frame tool works too, once you rename it "score".

??? aside "Two things the chain found in `notation.space`"

    Both were one-line fixes in `afield/notation`, and `DrawnScoreTest` measured each one failing
    before it was fixed.

    A frame named by hand could not be found. Excalidraw keeps a frame's title in the element's own
    `name` field, and `Mark.label` read a name given from code, a text, and a bound text, but never
    that field. So `space("score")` over a frame someone had drawn and renamed returned an empty
    space, and the claim that "the region can be a frame" held only for frames drawn from code. The
    live check in `dev_docs/NOTATION.md` used a code-drawn frame, which is why it did not show.

    A space defined by a named rectangle counted the rectangle as one of its own events: a note as
    wide as the score, at its middle pitch. Only a frame was excluded. Now any closed mark whose
    bounds are the region's is.

## Where each piece is documented

* [Drawing and Notation](drawing_and_notation.md) for the drawing layer, `notation { }`, and reading
  by intersection.
* [Patterns & Rhythm](pattern_language.md) for the event model the score becomes.
* [A Cellular Automaton You Can Hear](chain_ca_sound.md) is the nearest chain: a different thing
  upstream of the same pattern and the same voice.
* [A Function, Played and Read Back](chain_fn_sound_roundtrip.md) for the pitch detector, and the
  delay in how it reports time.
