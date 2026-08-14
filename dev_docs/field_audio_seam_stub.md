# Testing the Field Audio seam without Field Audio

`afield.stdlib.noir.audioai` talks to a *separate process*: the Python service in
`services/field-audio-ai`, a git submodule with its own worker virtual environments, reached over
HTTP and one WebSocket at `http://127.0.0.1:8765` by default. On a fresh checkout that service is
not installed — no `.venv` at the submodule root, none under `worker-environments/{analysis,audiocraft}` —
and installing it means downloading models. So for most of the life of this codebase, on most
machines, **every code path past the WAV renderers has been unrunnable and untested.**

`src/jvmTest/kotlin/afield/stdlib/noir/audioai/FieldAudioServiceStub.kt` is the answer:
a Ktor/Netty server on an ephemeral loopback port that speaks enough of the protocol to drive the
real client. `RenderedAudioSeamTest` uses it to exercise the real `FieldAudioClient` and the real
`analyzeRenderedAudio`, unmodified, over a real socket.

```
./gradlew jvmTest --tests 'afield.stdlib.noir.audioai.*'
```

## Why a server and not a fake client

Field has paid for this distinction once already. The security suite called the `internal fun`
guards directly, so deleting the guard *call* at the head of a handler left every test green — the
guards were tested, their installation was not. The fix was `ktor-server-test-host` on `jvmTest`
(`build.gradle.kts`), and the same dependency is what makes this stub cheap.

Concretely, running against a socket is what keeps in scope: the client's own
`java.net.http.HttpClient` and its connect timeout; `HttpRequest.BodyPublishers.ofFile` streaming
the WAV; `Json { ignoreUnknownKeys = true; explicitNulls = false }` against real bytes;
`CompletableFuture` composition and cancellation across a real thread boundary; and the lifetime of
the events WebSocket, which is where two of the four defects below lived.

## It is modelled on the service, not on the client

A stub written from the Kotlin cannot disagree with the Kotlin. Every behaviour was read out of the
service instead — `api.py`, `assets.py`, `jobs.py`, `protocol/models.py` — and the schema at
`schemas/protocol.json`, which covers only some of the types (`AssetRegion`, `OperationRequest`,
`JobState`, `AnalysisResult`, `AnalysisPreview` and the gesture types; **not** `AudioAsset`,
`AssetRepresentation`, `JobAccepted`, `JobRecord`, `ProgressEvent` or `StructuredError`). The
Pydantic models are the full contract.

Eight facts the stub honours, each one a place a convenient stub would have lied:

1. `POST /v1/assets/import` defaults `role="original"` and creates **no** `canonical_project`
   representation. `analyzeRenderedAudio` prefers `canonical_project`, so for a freshly imported
   render that preference always falls through to the plain-WAV branch. Asserted.
2. `POST /v1/operations` answers **202**, not 200.
3. Representation duration comes from `soundfile`. When `soundfile` raises, `inspect_wav` returns
   `{}` and duration is **null** — which is exactly the `requireNotNull(source.durationSeconds)`
   path. `omitImportedDuration` models it.
4. Region validation is `start + duration > asset_duration + 1e-9`, answered as **422** with the
   service's own `{"error": {…}}` envelope. Note that the Kotlin guard's slack is `1e-6`, three
   orders wider; see "the 1e-6 slack" below.
5. Every not-found path is a FastAPI `HTTPException`, so it carries `{"detail": …}` and **no**
   envelope — which is why those arrive in Kotlin as `FieldAudioException` with `error == null`.
6. Requests are decoded **strictly** (`ignoreUnknownKeys = false`), because every service model
   extends a Pydantic `StrictModel` with `extra="forbid"`. A field Field invents is a 422 there and
   a 422 here, recorded in `protocolViolations`. This is also why `explicitNulls = false` on the
   client is load-bearing rather than cosmetic.
7. A cache hit is a **new** job, born `COMPLETE`, whose terminal progress event was published
   *before* `submit` returned.
8. The events socket replays history, then streams, then closes 1000 on the first terminal event.

`AssetRegion.asset_id` is checked against `^sha256:[0-9a-f]{64}$` and `duration_seconds` against
`> 0`, as the service does.

The stub also reads the uploaded WAV's header **by hand** (`wavHeader`, a small RIFF walk at the
bottom of the file) rather than reusing `afield.four.audio` or the production `wavHeaderFacts`. That
is deliberate: a test that asserts the reported duration equals the renderer's own claim is then
cross-validation between two independent parsers, not a tautology.

## Scripting and observation

```kotlin
FieldAudioServiceStub().use { stub ->
    stub.script("analysis.pitch_poly", FieldAudioServiceStub.ScriptedJob(
        outcome = JobState.FAILED,
        failure = StructuredError("ANALYSIS_FAILED", "worker died"),
    ))
    stub.script("analysis.beats", FieldAudioServiceStub.ScriptedJob(neverFinishes = true))
    val client = FieldAudioClient(stub.baseUrl)
    …
}
```

`ScriptedJob` covers: the list of non-terminal progress states, the terminal state, a
`StructuredError` recorded on the job, `neverFinishes` (a worker that wedged — emits progress and
then nothing, forever), `withoutResult` (reaches `COMPLETE` with no `analysis_result_id`), and the
delay between events. `defaultScript` covers anything unscripted. `omitImportedDuration`,
`omitImportedWavRepresentation`, `importResponseOverride` and `analysisResponseOverride` cover the
malformed and incomplete replies.

The observables are the point, because most of what went wrong is invisible from the client:

| property | what a bug looks like |
| --- | --- |
| `openEventSockets` | a number that never falls back to zero |
| `eventSocketsOpened` | more or fewer sockets than analysis legs |
| `jobPolls(jobId)` | a count that keeps growing after nobody is listening |
| `submittedOperations` | the requests, decoded from the wire |
| `protocolViolations` | anything the real service's `extra="forbid"` would have rejected |
| `importCount` | uploads, which content addressing does not deduplicate |

## What it does not model

Say this out loud, because the stub being green is not the service being green.

- **No models and no real analysis output.** No `.npz` arrays, no beat grids, no pYIN curves, no
  Basic Pitch note events. `AnalysisResult.data` is an `AssetRepresentation` pointing at nothing.
- **No `/v1/representations/{id}/content`,** so `download` is unexercised, and no
  `/v1/representations/{id}/waveform` or `/v1/analysis-results/{id}/preview`, so
  `WaveformSummary`, `AnalysisPreview` and everything in `AnalysisVisualization.kt` that consumes
  them are covered only by their own unit tests.
- **No generation, codec, candidate sets, judgements, gesture compilation or `/v1/models`.** Only
  the `analysis.*` operations the rendered-audio seam submits.
- **No durability.** The real service is SQLite-backed and jobs survive a restart; this is
  `ConcurrentHashMap`s that die with the stub.
- **No `canonical_project` derivation.** The real service builds a 48 kHz project copy of an
  imported asset somewhere outside `AssetStore.put`; the stub never does, so the
  `canonical_project`-preferred branch of `analyzeRenderedAudio` is *never taken* in these tests.
  Only the fall-through is covered.
- **The request hash is the same idea, not the same string.** SHA-256 over the request JSON minus
  `force_regenerate`, but without the backend id and revision that `JobSupervisor.request_hash`
  folds in. A hash from the stub will never match a hash from the service. Cache *behaviour* is
  modelled; cache *identity* is not.
- **An unknown-job event subscription is refused as an HTTP 404 handshake failure,** not the
  service's accept-then-`close(4404)`. Ktor accepts before the handler runs, so the stub refuses the
  upgrade in an `ApplicationCallPipeline.Plugins` interceptor instead. The client sees a failed
  `buildAsync` either way, but the close code differs.
- **Socket reaping may be *more* prompt than the real thing.** The stub's events handler drains
  `incoming` in a child coroutine, which is what makes a client-side `abort()` visible as
  `openEventSockets` falling. The real handler is parked on `await queue.get()` and reads nothing,
  so it plausibly does *not* notice a departed client until its next `send`, and a job that never
  publishes again would leave that task alive. That is a service-side observation from reading
  `api.py`, unverified against a running service, and it is not Field's bug — but do not read a
  green socket-lifetime test here as proof that the service reaps its end.
- **No JSON round trip through Pydantic.** The stub encodes with `FieldAudioClient.json`, so it
  cannot detect a wrong `SerialName` on its own. That gap is closed separately by
  `protocol names match the service's Pydantic models`, which decodes bodies transcribed by hand
  from `protocol/models.py`. Keep that test honest when the contract moves.
- **`recordSonicPiWav` is not covered at all.** It needs a running Sonic Pi and a real-time
  capture. Only the header reader it now shares with `RenderedAudioFile.existing` is tested.

## What it found

Four defects, each observed failing before it was fixed. The messages are quoted because a test that
has never been seen to fail proves something about itself and nothing about the code.

1. **The returned future never completed when a leg failed beside a leg that hung.**
   `CompletableFuture.allOf` does not short-circuit: `BiRelay.tryFire` needs *both* of its sources
   done, so `allOf` keeps waiting for every future long after one has failed. A box got no result and
   no error, indefinitely. Found only because the test took **65.5 s** — `assertFails` had swallowed
   the `TimeoutException` from `get(60, SECONDS)`. Each leg now reports its own failure directly.
   The test asserts an elapsed-time floor so a timeout can never read as a pass again.
2. **Nothing ever closed a progress socket or stopped an abandoned poll.**
   `analyzeRenderedAudio` discarded the `CompletableFuture<WebSocket>` that `watch` returns, and
   cancelled nothing on failure. Red as `condition was still false after 5000 ms:
   openEventSockets=2 of 3 opened`.
3. **A cancelled `await` kept polling.** `await` re-armed from inside `whenComplete` without asking
   whether anyone was listening, and the three `await…` helpers are `await(...).thenCompose { … }`,
   so cancelling what a caller holds could not reach the poll. Red as `a cancelled await is still
   polling expected:<10> but was:<17>`.
4. **A missing service produced an empty error message.** Red as, verbatim, `what a box would see:
   java.net.ConnectException: java.net.ConnectException` against `CharSequence <>`.
   `java.net.ConnectException` from `sendAsync` carries no message at all — and, measured against a
   closed loopback port, no cause that carries one either, so there is no "Connection refused" text
   anywhere to quote. Now `FieldAudioTransportException`, which names the address, the path, a
   supplied plain-English reason, and where to start the service. The same type now covers a 2xx body
   that is not the protocol, which used to surface as a bare `SerializationException`.

   What a box actually shows, captured from a live Field:

   ```
   what a box sees :: Field Audio is not answering at http://127.0.0.1:8765/v1/assets/import:
                      ConnectException: nothing accepted the connection. The service is a separate
                      process — start it from services/field-audio-ai — or construct
                      FieldAudioClient with the address of one that is already running.
   how it reads    :: FieldAudioTransportException · baseUri=http://127.0.0.1:8765 ·
                      requestPath=/v1/assets/import · cause=java.net.ConnectException
   ```

   Reproduce it with `dev_docs/agents/transcripts/docs_capture/field_audio_absent_service.yaml`,
   whose own description explains why it lives under `docs_capture/` rather than beside the corpus.

### The 1e-6 slack: verified fine, not a bug

`analyzeRenderedAudio` permits `startSeconds + duration <= total + 1e-6`, while the service rejects
anything over `total + 1e-9`. A window that squeezed past the Kotlin guard would therefore be an
`INVALID_TIME_RANGE` if it were sent verbatim. It is not sent verbatim — the region is built with
`duration.coerceAtMost(total - startSeconds)` — and `a window inside the floating-point slack is
clamped to exactly the render` asserts that at the *stub*, checking the region the service actually
received rather than the one the caller asked for. No change made.

### Also verified rather than quoted

- `analyzeRenderedAudio`'s "Nothing blocks Field's run loop": the returned future is not done when
  the call returns, and no `onProgress` callback lands on the calling thread.
- `JobMonitor.kt`'s "nothing here reaches across origins": true of the **default** `serviceBaseUrl`
  only. The parameter will build a cross-origin iframe from an absolute URL. Both halves asserted so
  the sentence has a stated scope.

## Where the tests live

- `src/jvmTest/kotlin/afield/stdlib/noir/audioai/FieldAudioServiceStub.kt` — the stub.
- `src/jvmTest/kotlin/afield/stdlib/noir/audioai/RenderedAudioSeamTest.kt` — the seam, end to end.
- `src/jvmTest/kotlin/afield/stdlib/noir/audioai/RenderedAudioAnalysisTest.kt` — the renderers and
  the HTML, which predate the stub and need none of it.
