# Certified envelopes — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`)
and run the four boxes, in order. Pure CPU: no window, no audio device.

The signal in boxes 1–3 is one hour of a slow 40-second swing plus a glitch 10 ms wide at
1234.57 s, placed exactly halfway between two of the signal viewer's 0.02 s samples. It is built
from `Functions` ops (`sin(...) + gaussian(...)`), not from a `fn { }` lambda — that is what lets
box 2 bound it.

| box | what it does | what it returned when this document was made (2026-09-23) |
| --- | --- | --- |
| **1 — an hour, and a glitch the plot never sees** | `SignalViewer` with a plain `SignalChannel`: the min/max-per-bin envelope the function workbench draws | glitch peak `0.7994`; sampled max in its bin (342 of 1000) `0.3038`; sampled max over the hour `0.65`. **Missed by `0.4956`**, and nothing in the picture says so |
| **2 — the certified band catches it** | the same viewer with `certifiedSignalChannel`: an amber band behind the white envelope, bounding every value in each bin | certified max in bin 342 `0.7995` (the true peak is `0.7994`); 1344 enclosures for 1000 bins. The amber spike is visible at 1234 s in the overview |
| **3 — certifiedRange, with witnesses** | global min and max by branch-and-bound, plus three yes/no claims | max `0.7994` at x = `1234.57`, proven bound within 1e-9, 91 enclosures; min `0.25` at x = 1350, 5 enclosures. `f <= 0.7` REFUTED at x = 1234.5697; `f <= 0.8` PROVEN; `f >= 0.75 somewhere` PROVEN at 1234.5697 |
| **4 — what refuses, and an exact hold** | the workbench's channel shape (`fn(0.0..3600.0, "x") { … }`), and one event rebuilt from formula ops | the lambda: `certified = false`, refused by name (`'lambda' has no formula in FnFormulas …`). The event: `f == 1.0` on `[285, 315]` **PROVEN exactly**; on `[284, 315]` REFUTED at x = 284; `f <= 1 (+1e-12)` over the hour PROVEN |

Box 4's first row is the honest limit. Every channel in the workbench's worked example
(`public/docs/assets/function_conversation/final.kt`) is a `fn { seconds -> … }` lambda, so none of
them can be certified as written; the second half of the box shows that the workbench's own claim
("each event reaches exactly (1,1,1) for 30 seconds") *is* provable — exactly, not to a tolerance —
once the event is written with `smoothStep`, `c` and arithmetic.

Box 4 also writes the event as `1 - (1 - background)(1 - envelope)` rather than the equivalent
crossfade `background(1 - envelope) + envelope`. Both hold exactly 1; only the first isolates the
bound, so `<= 1` can be proven up to a 1e-12 slack. The crossfade form overshoots on every piece
touching the plateau edge (interval arithmetic treats its two `envelope`s as independent) — see
`FnIntervalTest.aCrossfadeThatTouchesItsBoundCannotBeProvenAtTheBoundButCanJustAboveIt`.

`build.yaml` beside this file is the `OperationTranscript` that built the document (the same text
is `dev_docs/agents/transcripts/certified_envelope.yaml`). Replay it with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 8471 \
  --workspace /tmp/ce_ws --document certified_envelope \
  --command 'macro write certified_envelope --file dev_docs/agents/demos/certified_envelope/build.yaml' \
  --command 'macro run certified_envelope'
```

That replay is how this document was made; it reported `applied=12 skipped=0 errors=0` with all
four boxes `ok`. `DemoTranscriptExecSmokeTest certified_envelope` executes it headless
(`[execute] PASS certified_envelope (applied=12, output lines=4)`).

The design note, with the containment contract and the measured numbers, is
[`dev_docs/FN_INTERVAL.md`](../../../FN_INTERVAL.md).
