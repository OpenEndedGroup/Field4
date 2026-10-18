"""Keep the three live snippets in one box with explicit published results."""
from pathlib import Path
import re,json,subprocess
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;p='public/docs/audio_ai_corpus_microscope.md';text=subprocess.check_output(['git','show','5e02bad8c:'+p],cwd=R,text=True);before=text
index='''import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

val client = cached { FieldAudioClient() }
prop<AudioAsset>(nancy)
prop<CorpusPreview>(preview)
prop<AssetRepresentation>(canonical)
display("all jobs", fieldAudioJobMonitor(heightPixels = 330))

fun tracked(run: RunScope?, label: String, request: ModelOperation): CompletableFuture<AnalysisResult> =
    RunScope.withCurrent(run) {
        client.trackAnalysis(
            request,
            onAccepted = { display("$label job", fieldAudioJobStatusHtml(it)) },
            onProgress = { job, event -> display("$label job", fieldAudioJobStatusHtml(job, event)) },
            onCompleted = { display("$label job", fieldAudioJobStatusHtml(it)) },
            onError = {}, // The enclosing operation reports failure once.
        )
    }

runAsync<AudioAsset>(
    onResult = { source ->
        nancy = source
        markdown("index", "Index ready. Run the query fragment below.")
    },
    onError = { failure -> markdown("index error", "Index failed: ${(failure.message ?: failure.toString()).md}") },
) {
    // Each HTTP continuation must re-enter the run that owns its tracked job.
    val run = RunScope.current()
    val lookups = ["*Nancy*", "*Candlestick Park*"].map { client.assetByFilename(it) }
    onCancel { lookups.forEach { it.cancel(true) } }
    CompletableFuture.allOf(*lookups.toTypedArray()).thenCompose {
        if (future.isDone) throw CancellationException("run ended")
        val assets = lookups.map { it.join() }
        val jobs = assets.mapIndexed { index, asset ->
            val whole = AssetRegion(asset.id, 0.0, requireNotNull(asset.durationSeconds))
            tracked(run, "source ${index + 1} segments", segmentCorpus(whole, requestedBy = "field:corpus/index"))
                .also { pending -> onCancel { pending.cancel(true) } }
                .thenCompose {
                    if (future.isDone) throw CancellationException("run ended")
                    tracked(run, "source ${index + 1} descriptors", embedCorpus(whole, requestedBy = "field:corpus/index"))
                        .also { pending -> onCancel { pending.cancel(true) } }
                }
        }
        CompletableFuture.allOf(*jobs.toTypedArray()).thenApply { assets.first() }
    }
}'''
query='''val query = AssetRegion(nancy.id, startSeconds = 18.0, durationSeconds = 6.0)

runAsync<CorpusPreview>(
    onResult = { result ->
        preview = result
        canonical = nancy.representations.first { it.role == "canonical_project" }
        display("microscope", corpusMicroscopeHtml(preview, canonical))
    },
    onError = { failure -> markdown("query error", "Query failed: ${(failure.message ?: failure.toString()).md}") },
) {
    val run = RunScope.current()
    val neighbours = tracked(run, "Nancy neighbours", findCorpusNeighbours(
        query,
        limit = 12,
        includeSameAsset = false,
        weights = CorpusDistanceWeights(
            harmony = 0.8,
            pitch = 0.55,
            texture = 1.35,
            rhythm = 0.7,
            dynamics = 0.35,
        ),
        requestedBy = "field:corpus/query",
    ))
    onCancel { neighbours.cancel(true) }
    neighbours.thenCompose { result ->
        if (future.isDone) throw CancellationException("run ended")
        client.corpusPreview(result.id).also { pending -> onCancel { pending.cancel(true) } }
    }
}'''
fences=list(re.finditer(r'```diffstatic \{data-lang=kotlin\}\n([\s\S]*?)\n```',text));assert len(fences)==3
for f,code in reversed(list(zip(fences[:2],[index,query]))):text=text[:f.start(1)]+code+text[f.end(1):]
text=text.replace('This box indexes Nancy and Final Out at Candlestick Park:', 'This box indexes Nancy and Final Out at Candlestick Park. Keep the index, query and placement\nfragments below in the same box. Run the index first and wait for **Index ready** before running the\nquery. The box properties publish only successful results for the later fragments:')
text=text.replace('**place** button. Pressing', '**place** button. Run this fragment after the query has produced its microscope. Pressing')
(R/p).write_text(text);rows=json.loads((H/'changes.json').read_text());rows.append(dict(path=p,before=before,after=text));(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
