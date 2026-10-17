@file:afield.runtime.completion.CompletionCategory("Audio AI")

package afield.audio.ai

import afield.runtime.lifecycle.runAsync
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/**
 * Submit one explicit analysis request, watch progress, await its result and inspect final job state.
 * Publication and resources belong to the calling run through [runAsync]. Stop/rerun cancels local
 * polling and closes the watch, including a handshake that finishes late. Durable service jobs and
 * shared clients are retained: abandoning a subscription does not cancel work another client may use.
 * Progress transport failures fail this operation; final job state comes from [FieldAudioClient.inspect].
 */
fun FieldAudioClient.trackAnalysis(
    request: ModelOperation,
    onAccepted: (JobAccepted) -> Unit = {},
    onProgress: (JobAccepted, ProgressEvent) -> Unit = { _, _ -> },
    onCompleted: (JobRecord) -> Unit = {},
    onResult: (AnalysisResult) -> Unit = {},
    onError: ((Throwable) -> Unit)? = null,
): CompletableFuture<AnalysisResult> = runAsync(onResult, onError) {
    val operation = this
    val leg = own(AnalysisLeg())
    val acceptedCallback = progress(onAccepted)
    val progressCallback = progress<Pair<JobAccepted, ProgressEvent>> { (accepted, event) -> onProgress(accepted, event) }
    val completedCallback = progress(onCompleted)
    val submitted = submit(request)
    onCancel { submitted.cancel(false) }
    submitted.thenCompose { accepted ->
        if (operation.future.isDone) return@thenCompose CompletableFuture.failedFuture(CancellationException())
        acceptedCallback(accepted)
        if (operation.future.isDone) return@thenCompose CompletableFuture.failedFuture(CancellationException())
        val watching = watch(accepted.jobId,
            onError = { operation.fail(it) },
            onEvent = { event -> progressCallback(accepted to event) })
        leg.watching(watching)
        watching.thenCompose {
            if (operation.future.isDone) return@thenCompose CompletableFuture.failedFuture(CancellationException())
            val analysis = awaitAnalysis(accepted)
            leg.polling(analysis)
            analysis.thenCompose { result ->
                if (operation.future.isDone) return@thenCompose CompletableFuture.failedFuture(CancellationException())
                val inspection = inspect(accepted.jobId)
                onCancel { inspection.cancel(false) }
                inspection.thenApply { job -> completedCallback(job); result }
            }
        }
    }
}.future
