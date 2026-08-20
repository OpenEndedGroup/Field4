# The `deadTransitions` caller sweep

`PetriNet.deadTransitions(within)` was changed to stop at its own `within` argument instead of
running on to `PetriRun`'s `maxUnroll`. That is a semantic change to a public analysis function that
`report()` calls, so it changes what a user's boxes print. The evidence that it was harmless was six
nets at two step limits — good as far as it went, and six nets.

`PetriDeadTransitionsSweepTest` is the sweep that was owed. Two halves.

## The ledger

Nobody had written down which callers were supposed to be covered, so the six nets could have stopped
being the right six and nothing would have said so. The test declares the inventory and then scans
the tree, comparing **both directions and set equality**:

- a declared caller that has vanished fails;
- an undeclared caller that has appeared fails;
- a listed file whose *call count* moved fails.

That is deliberately the opposite of the ordinary failure, which is a scan that silently matches
nothing, returns an empty list and reports perfect agreement. The scan is asserted non-empty and the
scan root is asserted to exist before either comparison runs.

Kotlin call sites are pinned to an exact count per file; documents are pinned only as a *set* of
files. A call site appearing or disappearing is a fact about the code; a rewritten sentence is not,
but a page that stops mentioning the function (or a new one that starts) is a page somebody needs to
re-read.

`public/docs/reference/` is excluded by hand, because it is generated after a merge rather than
written. A regeneration must not be able to fail this test.

## The replay

The old behaviour — run to `maxUnroll`, then look at every step recorded rather than the first
`within` of them — is replayed beside the new one over thirty-nine nets rather than six:

- every net `PetriGallery` builds, through the new public `PetriGallery.nets()`, which is the honest
  corpus for "every net the documentation actually shows a reader";
- every `#petri` program on `public/docs/petri_nets.md`, scraped out of the Kotlin raw strings it
  lives in;
- the philosophers' table at three sizes, symmetric and asymmetric, across all four
  `PetriConflict` policies — which is where the deterministic analysis is most policy-sensitive and
  therefore where a starved transition is most likely to first fire late.

At each `within` any caller actually passes: 2 048 (the default) and 4 096 (`report()`'s `maxSteps`).

**The claim, stated at its real strength.** `PetriRun` keeps every step and `PetriMarking.add` copies
a place's token list, so replaying the old behaviour costs quadratic memory — which is exactly the
trap the fix removed from `report()`. The replay ceiling is 20 000 steps, not the old default of
200 000. So what is checked is: *no transition in any of these nets first fires between `within` and
20 000 steps.* A transition that first fires at step 100 000 would still be invisible to this test,
and to the six-net pin it replaces. Raising the ceiling is a memory problem, not a code problem.

**And the sweep can see a difference when there is one.** The counterfactual is a net built to be
noticed: one token per step accumulates in a place, and a transition needs eight of them, so it first
fires at step eight. `deadTransitions(4)` names it dead and `deadTransitions(64)` does not — which is
the shape of difference the sweep is looking for, asserted directly, so a green sweep is a statement
about the nets rather than about the comparison.

The original six-net pin in `PetriStateSpaceTest` is kept rather than replaced. It makes a narrower
and differently-scoped claim — those six are the nets the *live workspace document* builds, named as
such — and a wider sweep is not a reason to stop checking the specific thing somebody was worried
about.

## Result

Zero differences over every net and both limits. That is a negative result, and it is the one that
was expected: `report()` passes 4 096 and no net anyone has written for this subsystem has a
transition that waits longer than that to fire once. The value is not the zero — it is that the
inventory is now written down and checked, so the next person to add a caller is told to sweep it.
