val client = cached { FieldAudioClient() }
val sketch = gestureBoard(18.0, "uneasy nocturnal orchestra, old optical-film texture") {
    prompt(0.0, 5.0, "almost motionless low strings")
    prompt(5.0, 8.0, "woodwind fragments begin to circle")
    prompt(13.0, 5.0, "brass shadow, then an unresolved decay")
    event(5.2, "first disturbance", 0.45)
    event(13.1, "dark accent", 0.92, 1.6)
    curve("breath", GestureCurveKind.ACTIVITY) {
        point(0.0, 0.08)
        point(5.0, 0.16)
        point(10.0, 0.55)
        point(13.1, 0.94)
        point(18.0, 0.12)
    }
    curve("event density", GestureCurveKind.DENSITY, 1.25) {
        point(0.0, 0.05)
        point(7.0, 0.22)
        point(12.5, 0.78)
        point(18.0, 0.08)
    }
}

display("gesture", gestureBoardHtml(sketch))
runAsync(
    onResult = { result: CandidateSet ->
        display("ranked gesture", gestureBoardHtml(sketch, candidates = result))
        display("audition", candidateBoardHtml(result))
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
        val submitted = client.submit(gestureCandidates(region, sketch, seeds = 0L until 8L))
        onCancel { submitted.cancel(false) }
        submitted.thenCompose { accepted ->
            if (future.isDone) throw java.util.concurrent.CancellationException("run ended")
            client.awaitCandidates(accepted).also { polling -> onCancel { polling.cancel(false) } }
        }
    }
}
