# λ-calculus on interaction nets

What `afield.stdlib.interactionnets` and `afield.stdlib.lambdaterms` do as of 2026-09-18, what is verified and by what, and what is not.

Before this, `interactionnets.kt` had a `Lambda` vocabulary (`APP`, `LAM`, `DUP`, `ERASE`, `VAR`, `ROOT`, `CONST`), a rule book of 13 rules, a scheduler and a layout — enough to watch one β-reduction happen. It had no way to say `λx. x x`, no fan/fan rule, and a substrate bug that made duplicating the identity throw. It now reduces closed λ-terms and reads the answer back, and there is an oracle that says when it does not.

## The four changes

**Wire chasing, in `Reducer.apply`.** A loose end a rule reaches through `ext(...)` or `ctx.auxPeer(...)` can be a port of the pair that is about to vanish. That is what a wire running between two ports of the pair looks like, and `λx. x` compiled to a net has exactly one — from the abstraction's binder straight to its own body. Reattaching to it wires to a deleted node and throws out of `Net.connect`.

`apply` now collects every attachment the rule asked for, reconnections and passthroughs together, and resolves each one before wiring anything. An end pointing back into the pair is followed across the internal wire to whichever *other* attachment named the far side of it, and the two surviving ends are joined to each other — which is what the vanished wire meant. It iterates, so a pair joined by more than one internal wire resolves too, and a chain that runs out was a closed loop and is dropped.

Passthroughs matter as much as reconnections here, and it is easy to see only half of it. `beta-linear` on `(λx. x) N` emits two passthroughs **both** of whose ends are inside the pair; handling only reconnections leaves the root and the argument dangling.

The two documented defensive skips survive, in `Reducer.join`: an outside end that is already connected loses its wire silently, and a port the rule built is passed through unchecked so that a rule which double-books one of its own still throws.

**`Reducer(relaxIterations = …)`.** The force-directed layout is `O(n²)` per iteration and ran 8 times per step. Right for an animation, wrong for a corpus. `LambdaTerms.normalise` defaults it to 0; the figure passes 8.

**Labelled fans, and two new rules.** `Lambda.FAN_LABEL` / `Lambda.fan(n)` put a label in `NodeState.scalars`, which `copyDeep` already carries, so every rule that copies a fan inherits the label for free. `fan-fan` is one `rule(DUP, DUP)` — one and not two, because `RuleBook.findMatch` takes the first matching signature and a second `(DUP, DUP)` could never fire — annihilating on equal labels and commuting on different ones through the same 2×2 crossing `dup-app` and `dup-lam` already use. `erase-erase` closes the other gap that wire chasing makes reachable. An unlabelled `DUP` reads as label 0, so every hand-wired demo in the file keeps annihilating rather than commuting forever.

**`lambdaterms.kt`.** A top-level `Term` (with nested `Var`, `Lam`, `App`) and an object `LambdaTerms` holding: `parse` for `\x. x x` and `λx. x x` with `\x y. e` sugar, `format` which round-trips exactly, `deBruijnKey`/`alphaEquivalent`, `compile`, `readback`, `normalise`, `corpus`, and `referenceNormalOrder` — the oracle.

## The compilation

An application becomes an `APP` whose principal faces its function, `aux(1)` holds its argument and `aux(0)` faces the context, which is the orientation `beta-linear` reads. An abstraction becomes a `LAM` whose principal faces the context, `aux(0)` is the binder and `aux(1)` the body.

The interesting case is a variable, because an interaction net has no notion of a wire that forks:

| uses of a binder | what the compiler builds |
|---|---|
| 0 | one `ERASE` — the binder still has to go somewhere, and a net may not simply drop a wire |
| 1 | a bare wire from `LAM.aux(0)` to the use |
| *n* > 1 | a right-nested tree of *n* − 1 `DUP` fans, **each with its own fresh label** |

Distinct labels per fan rather than one per binder, so that no two fans of the same tree can ever meet and wrongly annihilate.

## The readback is where a day went

The first working version of this got 22 of 25 corpus terms right and read the other three back as `λa. λb. a (a (a ⋈))` — correct as far as they went, then stuck. All three were `mul two three`, `mul three three` and `three two`: nested duplication, which is exactly where bracket-free sharing graphs are supposed to fail. It looked like a limitation of the rules.

It was the readback. A normal form over this vocabulary can still carry fans — `mul three three` reduces in 9 reactions and finishes with 12 agents standing — and **a fan is not a node of the term, it is a bracket on the path through it.** Which copy of a shared subterm a given term position means is not local information.

So `readback` carries a stack of fan choices:

- arriving at a fan from auxiliary *i* means "the thing here is copy *i* of what the principal points at": push `(label, i)` and keep going through the principal;
- arriving at a fan's *principal* is the matching close bracket: pop, and if the label agrees, descend into that branch.

That is what `Lambda.FAN_LABEL` is actually for, and it is why annihilation wants equal labels — a matched pair of brackets. With the stack, all 25 terms agree.

Where the stack has no matching entry the readback prints a mark rather than picking a copy. There are five, and every one of them means "the net was not a term":

| mark | what it means |
|---|---|
| `⊥` | an `ERASE` sat in a term position |
| `·` | a port with no wire at all |
| `⋈` | a fan's principal sat in a term position with no matching choice on the stack |
| `∞` | the walk came back to a port already on its own path, with the same stack |
| `…` | the walk ran past its node or depth budget |

They exist to fail a test, not to be tolerated.

## Verification

**The oracle identity.** `readback(reduce(compile(t)))` is asserted α-equivalent to `referenceNormalOrder(t)` — a plain capture-avoiding normal-order substitution evaluator with no nets and no sharing anywhere in it — for **25 of 25** named closed terms. Asserted as *set equality on the corpus names*, so an entry cannot vanish into a pass, and the per-term table prints before the assertion rather than after it. Population: Church numerals with `succ`/`add`/`mul`/`pred` (10 entries), Church booleans with `if` (4), `S`/`K`/`I` (5), terms whose reduction copies a redex (4), terms that erase an argument (2).

**Confluence.** 25 terms × 4 `ActivationOrder`s agree on the de Bruijn key of the normal form. `RANDOM` is excluded because a failure message has to be reproducible, and `LOWEST_PAIR_ENERGY` for the same run-count reason. The scheduler is a free choice; the answer is not.

**Step counts, two columns and two units.** 228 net reactions against 117 oracle β-steps over the 25-term corpus at `FIRST_FOUND`, measured 2026-09-18. A net reaction is any rule firing — β, duplication, erasure, fan meeting — and an oracle step is a β and nothing else, so the pair is not a ratio. The smoke test asserts the band 205..255 with a **floor** as well as a ceiling, because a count that drops is a reducer that has stopped doing the work, and asserts the oracle total exactly, there being no scheduler in it.

**The word "optimal" is not used.** There are no brackets and no croissants, so nothing here bounds how many times a fan may be copied. Correct sharing, not maximal sharing.

**The figure.** `afield.examples.stdlib.LambdaNetFigure` writes `(λx. x x) (λy. y)` as five panels through `Net.toFLines` and `drawToSVG`, committed at `public/docs/assets/interaction_nets_lambda/lambda_reduction.svg`. The reduction it found:

| panel | agents |
|---|---|
| compiled | 6 — APP ×2, DUP ×1, LAM ×2, ROOT ×1 |
| 1. `beta-linear` | 4 — APP, DUP, LAM, ROOT |
| 2. `dup-lam` | 6 — APP, DUP ×2, LAM ×2, ROOT |
| 3. `beta-linear` | 4 — DUP ×2, LAM, ROOT |
| 4. `fan-fan` | 2 — LAM, ROOT, holding `λa. a` |

`dup-lam` on an abstraction whose binder is wired to its own body, then two fans annihilating: the two things neither of which could happen the day before. The count goes **up** at `dup-lam`, which is the point — duplication is work the net does explicitly rather than by copying a subtree behind your back. Each panel is fitted to its own box, so the panel scales are not comparable; the agent counts are what carry the size.

**Existing tests.** `stdlib.interaction-nets.smoke` — 260 lines, six named assertions pinning the `Lambda.rules()` behaviour these changes touch, including `testBetaRuleHandlesSwappedPairOrientation` and `testEraseRuleCreatesDecayAgents` — **passes**. Adding `fan-fan` and `erase-erase` and chasing wires altered nothing it observes. The four `backdoor-box` tests `docs.interaction-nets.{beta-bridge, duplication, energy-scheduler, erase}` pass both before and after the docs page edit, 4 of 4 each time. `afield/stdlib/alife.kt` mentions `interactionnets.kt` in one KDoc line and consumes no symbol from it, so it is not a second consumer.

**Catalog population.** The test catalog is **260** in a worktree, of which `stdlib.lambda-terms.smoke` is the one addition. The primary checkout reports **277** for the same tree: all 18 of the difference are under `src/personal`, which is untracked and therefore absent from every worktree. A count taken in the primary repo is not the count a worktree can run.

## What to be careful of

- **Closed terms are the contract.** An open term compiles, and each *occurrence* of a free variable becomes its own unshared `Lambda.VAR` agent with its name kept in `CompiledTerm.freeVariables` — but `dup-var` builds fresh agents with fresh ids when it copies one, so a free variable that gets duplicated reads back as an agent id rather than as its name. `corpus()` is closed throughout and asserts it.
- **`Run.steps` is not a measure of the term.** It is a property of this rule book and this schedule.
- **A term with no normal form does not terminate.** `normalise` takes a `maxSteps` and `Run.finished` is false when it ran out, which is the only honest answer.
- **The fan labels are not levels.** Duplicating a fan gives the copies the *same* label, which is the classical bookkeeping problem. The corpus does not exhibit it; that is a measurement over 25 terms, not a proof. A term that does could surface either way: as a `⋈`, or as a wrong normal form with no marker at all. Two paths give the quiet one. `fan-fan` annihilates on label equality alone, and `NodeState.split` copies the label, so every rule that copies a fan copies its label and a wrong annihilation can leave no fan standing; and `readback`'s bracket guard compares labels only, so two distinct same-labelled fans nested on one path match each other. Only the oracle distinguishes the two outcomes, which is why the corpus test is the evidence and not the marker.

## Where things are

| path | what |
|---|---|
| `src/jvmMain/kotlin/afield/stdlib/lambdaterms.kt` | front end, oracle, corpus |
| `src/jvmMain/kotlin/afield/stdlib/interactionnets.kt` | `Reducer.apply` wire chasing, `fan-fan`, `erase-erase`, `Lambda.fan` |
| `src/jvmMain/kotlin/afield/examples/stdlib/LambdaTermsSmokeTest.kt` | `stdlib.lambda-terms.smoke` |
| `src/jvmMain/kotlin/afield/examples/stdlib/LambdaNetFigure.kt` | the figure generator |
| `dev_docs/agents/demos/interaction_nets_lambda/` | the loadable demo, three boxes |
| `public/docs/interaction_nets_examples.md` | the published page |
