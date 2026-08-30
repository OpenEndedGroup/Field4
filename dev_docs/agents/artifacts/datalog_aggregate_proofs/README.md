# `#why` through an aggregate — a replayable box

`aggregate_proofs.yaml` builds a two-box Datalog document, derives three facts through `sum`,
`count` and `max`, and asks `#why` about each of them. Those are the questions that used to answer
*"derived in round N; no proof reconstructed"* — see the "Not done yet" section of
`dev_docs/DATALOG.md` before 2026-08-30. The last directive is a `#whynot`, which explains an
*absent* fact instead.

Pure compute: no Window, no Renderer, no GPU, no audio. It runs headless.

## Replay it

From the repository root, into a fresh scratch document:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --port 9477 \
  --command 'macro write datalog_aggregate_proofs --file dev_docs/agents/artifacts/datalog_aggregate_proofs/aggregate_proofs.yaml' \
  --command 'macro run datalog_aggregate_proofs'
```

`macro run` returns the box's output in its JSON, which is the proof it ran. Expect
`applied=7 skipped=0 errors=0`. Then open the document, edit the `emp` facts, and re-run the box:
the `execute` op carries no inline code, so it runs the box's own stored program and editing it by
hand does the same thing the transcript did.

## What it prints

Observed on 2026-08-30, verbatim:

```
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
whynot payroll(legal, 0)   not in the model; 1 rule(s) could have produced it
  via payroll(D, S) :- dept(D), S = sum P : { emp(_1, D, P) }
       1  dept(legal)  0 answers   <- nothing survives here
?- payroll(D, S)
D      S
-----  ---
eng    120
sales  300
```

Three things in there are worth looking at twice.

`emp(bob, sales, 200)` appears in the `topEarner` proof **twice**, and both are load-bearing: once
inside the `max` group, where it is the tuple that was the maximum, and once as the rule's own
premise, where it is the row that identifies bob as the person. The other group member is there
because "nothing bigger exists" is half of what a maximum means.

The number is not taken on trust. Every group is put back through `dlAggregateValue` — the same
function the fixpoint used — and checked against the fact in the model. A group that did not add up
would produce the old "no proof reconstructed" note rather than a plausible wrong tree.

The `#whynot` walks the one rule that could have produced `payroll(legal, 0)` and stops at
`dept(legal)`, which has no rows. That is a different answer from "legal has no employees", and
telling the two apart is the point.

## No `metadata.edn` here, deliberately

A `save` op inside a committed transcript writes into the repository every time anybody replays it.
The transcript is the artifact; `--scratch` is the fresh document.
