# Approved helper implementation

Implements the 2026-10-16 [helper decisions](../box_code_first_pass/HELPER_TRIAGE.md).
ToolPool notes remain with the house-machine agents. This change adds the eleven other approved
facilities; the later corpus edit pass will use them. The eight earlier approvals rediscovered by the sweep are implemented in the follow-up; see
[their API contracts and evidence](OLDER_APPROVALS.md). Other earlier ideas remain in
[BOX_CODE_HELPER_IDEAS.md](../BOX_CODE_HELPER_IDEAS.md).

## Small APIs

- `smoothTo` and `inertialTo` return callable `Interpolant<T>`: `q.startAt(0f); q()`.
  The same private objects still implement uniform/progression interfaces.
- `WallSpec.worldToClip()` is public and retains centered/top-left projection behavior.
- `FaustPatternRender.wav(normalizePeak = null)` encodes existing samples through the stereo
  PCM encoder. Normalization is explicit and neither input channel is mutated.
- `syphonClient(match)` caches open clients by the exact supplied match. Missing/closed clients
  are created under a per-key atomic lookup. Closing an older client cannot evict a replacement.
  The clients persist across box runs, as requested; different match strings use different slots.
- `osc(port).on(address, runOwned = true) { ... }` removes only its own registration on stop,
  supersede or retirement. Queued work from a released registration is discarded, and a suspended
  generator is cancelled on its next service tick so its `finally` runs. Existing persistent
  `on(address) { ... }` behavior and shared server lifetime are preserved.
- `solid.sampleSlices(n, fillRule = FillRule.POSITIVE) { z, section -> ... }` returns callback
  results at layer midpoints. Each native section closes after its callback, including exceptions.
- `someFline.grid(bounds, nx, ny)` appends disconnected segments and returns the same FLine.
  Counts are cells, styling stays on the FLine, and prior geometry is retained.
- `bestTimeMs(repetitions = 5, warmup = 2) { ... }` returns the shortest measured milliseconds.
  Counts must be valid; workload exceptions propagate.
- `stage(title, lines, summary)` captures drawing geometry/colors and summary text;
  `showInteractionStages(...)` fits the snapshots into panels with arrows and reuses the overview
  renderer. Mutating original geometry or colors, or a returned stage drawing, leaves the capture
  intact. The reused overview surface uses black backgrounds and Iosevka without gradients.

## Shared async mechanism: decisions 5 and 6

The start block returns a future promptly; publication callbacks run in the originating box/run
context on the completing thread. Progress/result/error callbacks must remain short and non-blocking.

```kotlin
runAsync(
    onResult = { result: LorisResult -> display("result", result.samples) },
) {
    client.analyzeWavAsync(
        path,
        onProgress = progress { text: String -> println(text) },
    )
}
```

The handle exposes `future` and `close()`. Stop/rerun suppresses stale publication and cancels the
source future. `onCancel { ... }` requests any worker cancellation the API actually supports;
`own(resource)` releases only operation-local resources on every ending. Shared clients and cached
results remain outside operation ownership. Resources registered after abandonment close immediately.
Successful operations detach from the run to avoid accumulating completed registrations.

Loris async entrypoints now use an interruptible worker bridge. Cancellation interrupts blocking work;
the existing process-boundary `finally` kills a still-running native child and removes request temp
files. Completed immutable cache entries are retained.

Field Audio uses this same mechanism:

```kotlin
client.trackAnalysis(
    request,
    onAccepted = { accepted -> display("job", fieldAudioJobStatusHtml(accepted)) },
    onProgress = { accepted, event -> display("job", fieldAudioJobStatusHtml(accepted, event)) },
    onCompleted = { job -> display("job", fieldAudioJobStatusHtml(job)) },
    onResult = { result -> display("result", result) },
)
```

This helper returns a future for existing composition code. It submits, watches, awaits and inspects
final authoritative job state. It reuses the rendered-audio pipeline's `AnalysisLeg` resource cleanup.
Failed handshakes/progress transports fail the operation. Cancelling or ending the run cancels local
polling and aborts the WebSocket, including one whose handshake finishes later. It does not cancel a
durable shared service job or delete its cache. Use the guarded callbacks for user-visible publication;
callbacks added later through arbitrary asynchronous executors are outside this ownership contract.

## Verification

- **80 focused JVM tests passed, zero failures/skips**, covering new helpers and interpolation,
  OSC and rendered-audio regressions. The test-tree guard also passed.
- Built the pinned native Syphon bridge and exercised cross-process pixel round trips, same-name
  reuse, concurrent lookups and recreation after close. These native cases ran, rather than taking
  the optional-library skip path.
- Built the pinned native Loris worker, observed the child created by a real analysis request,
  cancelled it through `runAsync`, and verified process exit and request-temp cleanup.
- Native Manifold slices matched the expected midpoint heights and areas and closed on exceptions.
- A real box compiled/called the callable interpolant, grid, wall projection, timing, opt-in OSC and
  `runAsync` APIs. Five private copies of the real interaction-net documentation programs ran with
  the layout helper. All five final screenshots were inspected: isolated box positions, no error
  markers, readable two/three-stage drawings and preserved before/after summary values.
- That final backdoor rehearsal passed all **18 commands**, without timeouts or capture warnings.
  It used a separate headless browser, not the user's Electron view. Teaching originals are unchanged.

The focused suite exercises the real HTTP/WebSocket client against the repository's service-protocol
test server. This proves transport/resource behavior, not external model inference or service
durability. No full-corpus runtime result is claimed. The repository landing queue performs its full
guard and generated-reference refresh.

[Verification record](verification.json) binds the evidence to source hashes.
[Evidence archive](evidence.tar.gz) retains the six runnable rehearsal files, five genuine screenshots
and focused test XML. The initial private rehearsal accidentally included a docs `:::static` marker
and overlapping scratch-box positions; those fixture errors were corrected before the final run.
A box API check also required an explicit `Rect` inside the FLine receiver to avoid selecting the
append-style `rect` extension. Verification exposed stray commas between existing summary HTML
blocks; the shared renderer now joins those blocks without separators.
