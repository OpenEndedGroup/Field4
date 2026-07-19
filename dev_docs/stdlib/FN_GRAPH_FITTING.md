# Fitting `Functions.Fn` graphs

The authoritative optimizer contracts are the KDoc in `FunctionFit.kt` and
`FunctionEvolve.kt`. This note is a choice map and verification pointer.

| Need | Entry point | What changes |
| --- | --- | --- |
| Fixed graph, tune numeric leaves | `fitGradient(...)` | numbers only |
| Fixed graph, try deterministic restarts | `fitBest(...)` | numbers only |
| Search shape, then tune numbers | `fitHybrid(...)` | topology then numbers |
| Genetic structure search | `evolve(...)` | operators and topology |

Numeric leaves are `const`/`param` nodes carrying `numbers["value"]`.
`extractParameters()` and `applyParameters(...)` use the same stable post-order
`mapGraphNumbers(preserveSharing = false)` traversal, so each vector slot maps
back to the leaf that produced it. Extra replacement values are ignored; missing
values leave the remaining leaves unchanged.

`fitGradient` minimizes RMSE with central finite differences and Adam while the
seed's graph structure remains fixed. The default sampling domain is the bounded
unit `Functions.Domain.DEFAULT`, not an oscillator's potentially unbounded
reported domain. It returns `FnFitRun`, including parameters, loss trajectory,
evaluation count, and configuration.

`fitBest` runs one unjittered fit plus deterministic jittered restarts and keeps
the smallest final RMSE. When its `domain` argument is omitted, it samples the
target's domain if finite and otherwise falls back to `Domain.DEFAULT` (`0..1`).
An explicit domain always wins. `fitBest` helps with local numeric minima but
cannot add a missing operator. `fitHybrid` uses `evolve` for structure, then one
`fitGradient` pass for its numeric leaves.

Verification lives in `src/jvmTest/kotlin/afield/stdlib/FunctionFitTest.kt`.
The live transcript example is
`dev_docs/agents/transcripts/fn_differentiable_fit_demo.yaml`.

```bash
./gradlew jvmTest --tests afield.stdlib.FunctionFitTest
```
