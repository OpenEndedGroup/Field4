# `#why` through an aggregate — a replayable box

`aggregate_proofs.yaml` builds two boxes: one derives facts through `sum`, `count` and `max` and
asks `#why` about each of them, the other asks `#whynot` about facts that are absent. Those `#why`
questions are the ones that used to answer *"derived in round N; no proof reconstructed"* — see the
"Not done yet" section of `dev_docs/DATALOG.md` before 2026-08-30.

Pure compute: no Window, no Renderer, no GPU, no audio. It replays headless.

## Replay it

From the repository root, into a fresh scratch document:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --port 9477 \
  --command 'macro write datalog_aggregate_proofs --file dev_docs/agents/artifacts/datalog_aggregate_proofs/aggregate_proofs.yaml' \
  --command 'macro run datalog_aggregate_proofs'
```

Expect `applied=6 skipped=0 errors=0` and **7 output entries**. The count matters: `applied=6` with
zero output entries is the failure this artifact was rewritten to avoid (below). Then open the
document, edit the `emp` facts, and re-run a box — the `execute` ops carry no inline code, so they
run each box's own stored `code`, and editing by hand does exactly what the transcript did.

Verified 2026-08-30 against a **cold** Field on port 9482 — a JVM started for this replay and
nothing else, `cwd` confirmed to be this worktree — on the first run, not a second.

## What it prints

Observed on 2026-08-30, verbatim, from the cold first replay:

```
D      S
-----  ---
eng    120
sales  300

payroll(sales, 300)   [payroll(D, S) :- dept(D), S = sum P : { emp(_1, D, P) }]
    dept(sales)   [fact]
    S = sum P : { emp(_1, sales, P) }   [aggregate: sum over 2 tuples = 300]
        emp(ada, sales, 100)   [fact; contributes P = 100]
        emp(bob, sales, 200)   [fact; contributes P = 200]
headcount(eng, 2)   [headcount(D, N) :- dept(D), N = count : { emp(_2, D, _3) }]
    dept(eng)   [fact]
    N = count : { emp(_2, eng, _3) }   [aggregate: count over 2 tuples = 2]
        emp(cy, eng, 50)   [fact]
        emp(di, eng, 70)   [fact]
topEarner(sales, bob)   [topEarner(D, W) :- peak(D, M), emp(W, D, M)]
    peak(sales, 200)   [peak(D, M) :- dept(D), M = max P : { emp(_4, D, P) }]
        dept(sales)   [fact]
        M = max P : { emp(_4, sales, P) }   [aggregate: max over 2 tuples = 200]
            emp(ada, sales, 100)   [fact; contributes P = 100]
            emp(bob, sales, 200)   [fact; contributes P = 200, the max]
    emp(bob, sales, 200)   [fact]

whynot path(a, e)   not in the model; 2 rule(s) could have produced it
  via path(X, Z) :- path(X, Y), edge(Y, Z)
       1  path(a, Y)  3 answers
       2  edge(Y, e)  0 answers   <- nothing survives here
      a binding that got this far: Y = b
  via path(X, Y) :- edge(X, Y)
       1  edge(a, e)  0 answers   <- nothing survives here

whynot cell(C, V), V > 5, colour(C, red)   no answers
     1  cell(C, V)      3 answers
     2  V > 5           1 answer
     3  colour(C, red)  0 answers   <- nothing survives here
    a binding that got this far: C = a3, V = 10

whynot V > 50, cell(C, V)   no answers
     1  V > 50      not evaluable this early — nothing has bound its variables yet
     2  cell(C, V)  0 answers   <- nothing survives here
```

Three things in there are worth looking at twice.

`emp(bob, sales, 200)` appears in the `topEarner` proof **twice**, and both are load-bearing: once
inside the `max` group, where it is the tuple that was the maximum, and once as the rule's own
premise, where it is the row that identifies bob as the person. The other group member is there
because "nothing bigger exists" is half of what a maximum means.

The number is not taken on trust. Every group is put back through `dlAggregateValue` — the same
function the fixpoint used — and checked against the fact in the model. A group that did not add up
would produce the old "no proof reconstructed" note rather than a plausible wrong tree.

The last why-not distinguishes *"not evaluable this early"* from *"0 answers"*. The planner reorders,
so that goal is perfectly fine as a whole; it is the prefix that cannot be asked yet. In an empty
result those two look identical, and telling them apart is most of the value.

## Why Kotlin boxes and not Datalog boxes

The first version of this transcript did it the way the docs page does: a root box with
`mainLanguage: datalog` and a child holding the program. It replays cleanly against a Field that has
already hosted a Datalog box, and **silently does nothing against a cold one** — `applied=9
skipped=0 errors=0`, both boxes created, and zero output from either the macro's collector or the
box's own output channel.

Measured on 2026-08-30, port 9482, each run against a freshly started Field:

| route | cold JVM, first run |
| --- | --- |
| transcript `edit mainLanguage` + `execute` | **no output**, `errors=0` |
| the same transcript, second run on the now-warm JVM | correct output |
| backdoor `new-box` / `set … mainLanguage datalog` / `connect` / `set code` / `run` | correct output |
| Kotlin boxes calling `Datalog()` (what ships here) | correct output |

Not a timing problem: `wait: frames: 600` — ten seconds of pumped runloop, and `frames` really does
pump it while `millis` is a bare `delay()` that does not (`operation_transcript.kt`,
`waitForRunloopFrames` against `waitForMacroMillis`) — did not help, while the backdoor `set` route
worked cold with no wait at all.

The first version of this note blamed the `edit` op, and that was wrong: a transcript `edit` and the
backdoor's `set` are the **same call** (`setPropertyRaw(property, …, doResync = true)` —
`operation_transcript.kt:1314`, `agent_backdoor.kt:657`), and the transcript side additionally runs
`refreshMainLanguageRuntimes`. What differs is the row's *second* column, the execute step. Transcript
`execute` calls `executeFragmentAndWait`, whose default body forwards to fire-and-forget
`executeFragment` (`execution.kt:66-68`) and which **only** `KotlinExecution` overrides
(`execution.kt:2021`) — ten other `BoxExecution` implementations, `DatalogExecution` among them, inherit
the default. The backdoor's `run` instead sends `execute-all` through the server and polls `is-running`
until false. Note the table changes two variables per row, so it localises the problem to the route but
not to the step; the call sites are what settle it.

That is a transcript-layer gap affecting every non-Kotlin box language, recorded here rather than
fixed, because it is not `afield.datalog`'s bug.

The failure shape is why this is written down at length: it works for whoever wrote it and is silent
for whoever reads it, and it reports success either way.

If you want the **box-language** version, this sequence is verified to work on a cold JVM, where
`prog.datalog` holds the same program written as Datalog rather than Kotlin:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --port 9477 \
  --command 'new-box --name datalog-root --alias dlroot --x 60 --y 40 --w 120 --h 80' \
  --command 'set dlroot mainLanguage datalog' \
  --command 'new-box --name aggregate-proofs --alias dlprog --x 60 --y 160 --w 720 --h 320' \
  --command 'connect dlroot dlprog' \
  --command 'set dlprog code --file /path/to/prog.datalog' \
  --command 'run dlprog --timeout 180' \
  --command 'output dlprog'
```

## No `metadata.edn` here, deliberately

Two reasons. A `save` op inside a committed transcript writes into the repository every time anybody
replays it. And a materialized bundle could not have been validated: `open` on the known-good
committed bundle `dev_docs/agents/artifacts/petri_state_graph_bound/state_graph_bound_demo` returns
`ok=true` with `"boxes": []`, and a following `boxes` returns `[]` as well — it resolves the argument
as a workspace-relative document name, finds nothing, and reports success. Run as a control on
2026-08-30 before trusting any replay result. The transcript is the artifact, `--scratch` is the
fresh document, and `boxes` returning 2 after the replay is what distinguishes a real result from
that control's empty one.
