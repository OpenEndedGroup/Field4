# MorphoHDL: what the bridge is checked against

`morpho_examples.md` says that Field's netlists are checked two independent ways and what each way
compares. This file is the corpus, the tables, and which of the numbers a test actually asserts.

Sources: `src/jvmTest/kotlin/afield/morpho/MorphoBridgeTest.kt`,
`src/jvmTest/kotlin/afield/morpho/MorphoLanguageTest.kt`.

## Oracle one: arithmetic

A bridge that produces plausible circuits is worse than no bridge, because you find out late. Each
design is compiled through GraalJS, its netlist extracted into Kotlin, and simulated against integer
arithmetic — exhaustively where that is possible.

| design | check | vectors |
| --- | --- | --- |
| `ripple_adder(8,8,1)` | `a + b + carry` | **131 072 — exhaustive** |
| `brent_kung_adder(32,32,1)` | `a + b + carry` | 4 096 random |
| `wallace_multiplier(16,16)` | `a * b` | 2 048 random |
| `right_shifter(32,5,1)` | `x >>> s` | 2 048 random |

Exhaustive matters more than it looks: a carry chain is precisely the thing an off-by-one in pin
indexing gets right on almost every input and wrong on the rare vector where a carry propagates the
full width.

The multiplier and the shifter cover what the adders cannot. `Xor3` and `Maj3` are symmetric in
their inputs: an adder still adds if you feed a LUT's ports in the wrong order, and a transposed
truth-table index is invisible to it. `Mux2`, the carry-save tree and a positional shift amount are
not symmetric, and they catch it.

## Oracle two: `tiny_morpho.py`

Arithmetic alone would pass a circuit that is correct and twice the size it should be. So gate
counts are pinned against morpho's *other* implementation: the Python reference, which shares no
code at all with the JavaScript. Both were run:

```bash
uv run --project . --with numpy python src/jvmMain/resources/morpho/tiny_morpho.py
```

| design | optimize | `tiny_morpho.py` | Field via GraalJS |
| --- | --- | --- | --- |
| `ripple_adder(8,8,1)` | yes | 16 | **16** |
| `ripple_adder(32,32,1)` | yes | 64 | **64** |
| `brent_kung_adder(32,32,1)` | yes | 190 | **190** |
| `wallace_multiplier(16,16)` | yes | 872 | **872** |
| `wallace_multiplier(16,16)` | no | 1086 | **1086** |
| `right_shifter(32,5,1)` | yes | 160 | **160** |

Two implementations of the same language — one Python and numpy, one JavaScript and a
struct-of-arrays compiler — agreeing gate for gate is a sharper instrument than either agreeing with
itself.

## Which of these has a guard

The gate counts are asserted, not merely printed. `MorphoBridgeTest` carries the
`brent_kung_adder(32,32,1)` = 190, `wallace_multiplier(16,16)` = 872 and unoptimized = 1086 cases as
`assertEquals`, and `MorphoLanguageTest` asserts 190 again through the language path and through the
`summary()` string. A recompile that changes a gate count goes red.

The Python side of the table is a recorded run of `tiny_morpho.py`, not a guard: nothing in the
build executes the Python reference. Re-run the command above if you want the comparison again.

## What is not compared

Netlist *shape*. Python emits a flat op list, JavaScript a hierarchical cell graph, and there is no
structural comparison between them that would not amount to reimplementing one in terms of the
other.
