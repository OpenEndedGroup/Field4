# Keep Kratos cancellation, result snapshots and graph restoration safe

Frozen findings: `cf60e95a215d-01`, `cf60e95a215d-02`, `cf60e95a215d-04`, `26e1f530f93d-01`, `a006a9d7cfa3-01`, `1bfc33f3767a-01`.

The Kratos study callbacks explicitly throw CancellationException, avoiding Box.error's display-only behavior. The extra winning-field solve now runs inside the existing background future, with cancellation checks around it; the Runloop only consumes its completed result. Each terminal operation finishes and clears its progress reporter, and Stop signals cancellation before closing the worker. Flow results retain a copied submitted settings list for geometry, radius and Reynolds while preview controls remain independent. Outer worker-error handlers propagate coroutine cancellation rather than presenting Stop as an installation failure.

The nesting diagnostic now uses the typed parents property and restores the original set in finally, removing both raw-property calls. Field's actual codec changes only this code property; its transfer checks preserve every box ID and unselected property. A private copy loaded through Field proves normal lookup and an injected, typed gather failure both restore the real incoming edge.

All three complete Kratos bodies compile and execute their actual unavailable-worker cleanup. Held-opening fixtures exercise real Frame Stop/cancellation/finally in each box. Both extracted authored progress callbacks throw in an actual Box receiver. The authored supplyAsync block executes verbatim with a controlled blocking dependency: retrieval is off the box thread and three main frames advance while it remains blocked. Extracted submission statements, real FlowParameters and SimulationGeometry constructors verify radius and Reynolds remain for A after controls mutate to B. These are control-flow/snapshot checks, not numerical solver substitutes or fabricated captures.

Verification limit: no local optional/kratos environment is installed and the Podman VM is stopped. Full grid/search/CFD/thermal solves and result captures are not claimed. That platform acceptance remains explicit rather than holding up the other groups.

Actual import/ktfmt covered the three transcript bodies; the inline diagnostic was separately formatted and rewritten through the selective codec. Token/container checks preserve raw strings, GLSL, metadata and execution boundaries. Fixture diagnostics (typed failure injection, a custom-type preamble and exact floating comparison) are retained along with corrected evidence. The batch recorder now enqueues its explicit committed branch, allowing the prepared group 5 edits to remain unstaged safely. Full guards remain with the lander.

Checkpoint: 361/405 addressed, 44 open. Mechanical coverage: Three transcript bodies and the inline diagnostic formatted with the actual editor; selective codec preserves unrelated graph properties. Full guards are delegated to the lander.
