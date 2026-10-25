# Network examples: reliable failure, note release and rerun ownership

Baseline `6147d3463`, plus the cumulative stacked-KDoc landing repair `385c2fd15`. Four frozen findings addressed: `0aefe1d7c786-01`, `0aefe1d7c786-02`, `e7aa0b16f15f-01`, `e85075e73aab-01`.

## DMX

The one-shot scene uses nested AutoCloseable scopes, so receiver-constructor failure also closes the sender. A monotonic two-second deadline now requires both universe versions before drawing the received panel, with explicit port/version diagnostics on failure. The displayed source scene was missing despite the page promising it; showImg(frame, channel = "scene") now shows the actual RectSL frame.

Actual sACN loopback verifies both 512-byte universes match the sender byte-for-byte, and both sockets close. Redirecting packets to a private blackhole produces the arrival diagnostic and never reaches the received-panel call. A real occupied UDP port forces input construction to fail; the already-open output is closed. A second normal run passes afterward. The final three-panel figure is genuinely regenerated and inspected through the docs pipeline.

## MIDI

The documentation and saved/transcript demo use Frames, cancellable delay(150), and noteOff in finally. The peer waits for an actual connected peer and clock sample, with a bounded deadline. Actions and instructions say launch. Shared network sessions retain their existing lifetime.

The canonical three sidecars and transcript match exactly. Field's codec refreshes saved fallbacks without changing the graph. A copied saved document loads those sources, changes only the two action labels through actual Field property/save operations, and preserves every non-name property. Generated transcript assets remain the lander's responsibility.

Real private RTP-MIDI sessions complete invitation and clock synchronization. Both teaching sequences send all four expected note-on/off pairs. Actual held durations are 149.94–157.75 ms for the saved demo and 152.33–174.83 ms for the documentation, with the teaching delay remaining 150 ms. Stop and relaunch tests use a private ten-second hold to make the interruption observable; neither teaching source nor published output uses that fixture duration. Tests retain the same box rather than deleting/recreating a box ID between phases. Only the two owned session ports are closed.

The real rerun exposed a runtime defect: a Frames job waiting in delay was tracked for Stop but not owned by its superseded run. Packet evidence records two note-ons followed by the old note-off ten seconds later. Owning the job fixed cancellation but exposed the remaining ordering race: old note-off just after new note-on. The registry now owns the job and defers the body one dispatcher turn, allowing old synchronous finally cleanup before replacement work. All Frames starts gain that initial dispatcher turn; their ongoing frame cadence is unchanged. Actual final packet order for both examples is old-on, old-off, new-on, new-off, with no held notes remaining.

Two regression tests use the real return-value registry: supersession cancels a wait without another frame() call, and replacement acquires shared state after old synchronous cleanup. All 52 cases across FramesSemanticsTest, RunScopeTest and GoStopTest pass. Actual MIDI traffic also passes normal timing, Stop and active rerun for both source surfaces.

## Coverage

Actual import/ktfmt covers 17 Kotlin states/occurrences. Two pitch-list conversions are part of the reviewed edit, with no additional mechanical conversions. Token/container, raw-string, prose and fold/fragment checks pass; explicit collection/idiom audit and scoped transcript compilation pass. No GLSL changes. Existing live fragment/reset semantics remain intact.

Reusable formatting, codec, network and capture harnesses plus evidence.tar.gz retain genuine results and diagnostic failures, including the pre/intermediate/final packet ordering and the initial harness/API mistakes. No capture or model output is fabricated. Runtime coverage is the changed network examples and the stated lifecycle suites; the full corpus sweep remains incomplete.
