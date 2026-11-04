# Gesture board: drawing toward a sound

A `gestureBoard` describes a sound you want over time: text prompts for stretches of the piece,
marked events, and curves for how busy, bright or high it should be. A generator then works toward
it from a few seconds of an existing recording, producing one candidate per seed.

*To run the examples on this page, open the [audio_ai_gesture_board](agent_transcripts.md#audio_ai_gesture_board) starting point.*

Different marks guide the generator in different ways:

- The base prompt is sent directly as a description of the whole piece.
- Prompt regions are combined into a description that includes their times.
- Activity, density, register and brightness curves are targets used to score the result.
- Events reward an onset near the marked time.

After generating each candidate, Field measures its waveform against the board. The candidate
browser shows the overall score and the scores for each lane alongside the controls for listening,
rating and selecting.

The example below sketches eighteen seconds of an uneasy orchestral piece: still low strings,
woodwinds entering around five seconds, a dark accent at 13.1 s, and four curves.

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_ai_gesture_board/gesture_board.png" wait="3" capture="box" open-outputs="true" padding="20"}
:::static
val sketch = gestureBoard(18.0, "uneasy nocturnal orchestra, old optical-film texture") {
    prompt(0.0, 5.0, "almost motionless low strings")
    prompt(5.0, 8.0, "woodwind fragments begin to circle")
    prompt(13.0, 5.0, "brass shadow, then an unresolved decay")

    event(5.2, "first disturbance", strength = 0.45)
    event(13.1, "dark accent", strength = 0.92, weight = 1.6)

    curve("breath", GestureCurveKind.ACTIVITY) {
        point(0.0, 0.08)
        point(5.0, 0.16)
        point(10.0, 0.55)
        point(13.1, 0.94)
        point(18.0, 0.12)
    }
    curve("event density", GestureCurveKind.DENSITY, weight = 1.25) {
        point(0.0, 0.05)
        point(7.0, 0.22)
        point(12.5, 0.78)
        point(18.0, 0.08)
    }
    curve("register drift", GestureCurveKind.REGISTER, weight = 0.7) {
        point(0.0, 0.18)
        point(9.0, 0.48)
        point(14.0, 0.72)
        point(18.0, 0.31)
    }
    curve("surface light", GestureCurveKind.BRIGHTNESS, weight = 0.6) {
        point(0.0, 0.13)
        point(11.0, 0.27)
        point(13.1, 0.68)
        point(18.0, 0.16)
    }
}

display("main", gestureBoardHtml(sketch, width = 1100))
```

![](assets/audio_ai_gesture_board/gesture_board.png)

The shared vertical cursor reports time across every lane. Hover event needles and curve points to
inspect their values. The delivery pills above the timeline show whether a mark is direct,
compiled, evaluative or transformative.

## Generate from the sketch

Use the same typed board in a normal Field request. The box submits eight seeds and shows the board again, now with each candidate's measured lanes, next to the audition board:

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_ai_gesture_board/gesture_candidates.png" capture="box" capture-profile="linux-nvidia" open-outputs="true" wait="4" wait-output="true" wait-output-channel="audition" timeout="900" w="1120" h="40" padding="16" embed=true name="gesture-candidates" alt="The gesture board with eight generated candidates scored against its lanes, above an audition board of the eight candidates"}
:::fold the sketch from above
val sketch = gestureBoard(18.0, "uneasy nocturnal orchestra, old optical-film texture") {
    prompt(0.0, 5.0, "almost motionless low strings")
    prompt(5.0, 8.0, "woodwind fragments begin to circle")
    prompt(13.0, 5.0, "brass shadow, then an unresolved decay")

    event(5.2, "first disturbance", strength = 0.45)
    event(13.1, "dark accent", strength = 0.92, weight = 1.6)

    curve("breath", GestureCurveKind.ACTIVITY) {
        point(0.0, 0.08)
        point(5.0, 0.16)
        point(10.0, 0.55)
        point(13.1, 0.94)
        point(18.0, 0.12)
    }
    curve("event density", GestureCurveKind.DENSITY, weight = 1.25) {
        point(0.0, 0.05)
        point(7.0, 0.22)
        point(12.5, 0.78)
        point(18.0, 0.08)
    }
    curve("register drift", GestureCurveKind.REGISTER, weight = 0.7) {
        point(0.0, 0.18)
        point(9.0, 0.48)
        point(14.0, 0.72)
        point(18.0, 0.31)
    }
    curve("surface light", GestureCurveKind.BRIGHTNESS, weight = 0.6) {
        point(0.0, 0.13)
        point(11.0, 0.27)
        point(13.1, 0.68)
        point(18.0, 0.16)
    }
}
:::endfold
val client = cached { FieldAudioClient() }
runAsync(
    onResult = { candidates: CandidateSet ->
        display("gesture", gestureBoardHtml(sketch, candidates = candidates))
        display("audition", candidateBoardHtml(candidates))
    },
    onError = { failure ->
        markdown("generation error", "Generation failed: ${(failure.message ?: failure.toString()).md}")
    },
) {
    val lookup = client.assetByFilename("*Nancy*")
    onCancel { lookup.cancel(false) }
    lookup.thenCompose { source ->
        if (future.isDone) throw java.util.concurrent.CancellationException("run ended")
        val region = AssetRegion(source.id, startSeconds = 18.0, durationSeconds = 4.5)

        val operation = gestureCandidates(
            source = region,
            board = sketch,
            seeds = 0L until 8L,
            backend = "audiocraft.musicgen_style",
            controls = MusicGenStyleControls(temperature = 1.08, topK = 180, cfg = 3.1),
        )

        val submitted = client.submit(operation)
        onCancel { submitted.cancel(false) }
        submitted.thenCompose { accepted ->
            if (future.isDone) throw java.util.concurrent.CancellationException("run ended")
            client.awaitCandidates(accepted).also { polling -> onCancel { polling.cancel(false) } }
        }
    }
}
```

`client.compileGesture(sketch)` asks the server for its authoritative compilation before spending
GPU time. Re-evaluating an unchanged operation reuses the same content-addressed candidate family
and its stored gesture measurements.

See also the [EnCodec round trip](audio_ai_codec.md) for token-level material, and
[beat and pitch analysis](audio_ai_analysis.md).
