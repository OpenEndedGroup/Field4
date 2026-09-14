# Stubborn-set reduction: the differential corpus

`PetriReduction.STUBBORN` is about eighty lines deciding what *not* to explore, so it is checked
against the full search rather than against the theorem. `petri_nets.md` says that it is checked
and what the check compares; this file is the corpus and the numbers, and which of them a test
actually asserts.

Source: `src/jvmTest/kotlin/afield/petri/PetriReductionFuzzTest.kt`.

## The corpus

Seeds `1..1_500`, a fixed range rather than a clock, so a failure names a net somebody can
rebuild. Six families, two random and four structured; the random families cap every place so
almost every seed produces a net the full search can finish, which is what a differential test
needs. Half the families are plain place/transition nets as a control and half are saturated with
read arcs, inhibitor arcs, capacities and arc weights — this model's additions to the classical
one, and precisely where the classical stubborn-set argument stops being free.

## The asserted floors

These are the numbers with a guard. They are set just under the measured values, so a reduction
that degrades has to go red rather than quietly agreeing with the full search and proving nothing.

| assertion | floor |
| --- | --- |
| usable nets out of 1 500 seeds | `>= 1_450` |
| full markings across the corpus | `>= 125_000` |
| reduced markings as a fraction of full | `<= 0.24` |
| nets strictly pruned | `>= 8/15` of the corpus |
| nets pruned to a quarter or less (from >= 32 markings) | `>= 260` |
| disagreements between the two searches | none, on every net |

What is compared per net is the **set** of reachable terminal markings, the counterexample length,
containment of the reduced state set in the full one, and every accessor gated on `exact`
refusing. Existence of a deadlock is deliberately not the oracle: a net with three deadlocks
agrees on existence while the reduction loses two of them.

## An observed run

Printed by the test, not asserted. These were on `petri_nets.md` as a table until the
2026-09-14 voice pass moved them here; they are one run's values and nothing pins them.

| | |
| --- | ---: |
| nets compared | 1 497 |
| reachable markings across the corpus | 137 001 |
| markings the reduced search visited | 27 727 — 20.2 % |
| nets strictly pruned | 840 |
| nets that can deadlock / cannot | 758 / 739 |
| nets with two or more distinct deadlocks | 247 |
| widest single net | 3 465 markings |
| disagreements | 0 |

The test also prints the corpus's width distribution in five buckets, because a fuzz whose nets
are all four markings wide would satisfy any ratio floor and prove nothing.
