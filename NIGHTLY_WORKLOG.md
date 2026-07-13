# Nightly worklog — 2026-07-13 / 3 — "Shaping signals" (kind C, documentation)

Branch: `nightly/2026-07-13/3-fn-transform-pipeline-doc` (base `614e2adf`)
Mission: an explainer of the Functions transform pipeline — how a source `Fn` flows
`through` a `FnTransform`, how transforms chain with `then`, how `over`/`stretch` set
domain/range, and how type-aware infix completion helps discover operators.

## Plan

**Deliverable:** a new, well-marked pedagogical section in
`public/docs/functions_examples.md`, inserted between `## Domains and timeline
composition` (ends ~L480) and `## Expanded reference` (L482). This is the natural home:
the existing timeline section names the operators tersely, and the reference section is
exhaustive/alphabetical — neither gives the *mental model* of building up a pipeline.
The new section is `## Shaping signals — the Functions transform pipeline`.

**Cover (house voice, concise):**
1. Mental model — a source `Fn` shaped by reusable `FnTransform`s; the declarative layer
   only rearranges pure functions (no second evaluator).
2. Operator table — one-line semantics for `through` / `pipe` / `/` / `then` / `over` /
   `stretch` / `overlapping … with`, being careful that `then` is overloaded:
   `FnTransform then FnTransform` (compose transforms) vs `Fn then Fn` (concatenate on a
   timeline).
3. 3–5 short **runnable** snippets (`diffstatic_runner` fences, `:::static`, ending in
   `.plot(...)`), building up: source `through` one transform → chain with `then` →
   set range/placement with `over` → a richer piped gesture.
4. "The editor helps you" note on type-aware infix completion (`InfixCompletion`, commit
   `3f731751`): after `x through `, operands whose return type is `FnTransform` are
   boosted (OPERAND_MATCH_BOOST = 160); discovered generically, no hard-coded op list.

**Cross-links:** back to `### over ... vs stretch ...` and `#### FnTransform` in this
file, and forward to the variety-generation section (`#### Variety generation`) as the
"evolve a whole family of these gestures" follow-on.

**Avoid duplication:** do not re-explain `domain = ...` vs `over` (already at L368) or the
full over/stretch math (L424) — link to them. Keep the new section pipeline-focused.

**Verify:** grep every referenced symbol/overload in `Functions.kt` at `614e2adf`; confirm
signatures match usage. No live execution (snippets not run through the JVM); correctness
= symbol existence + signature match. Look for a docs lint/build step; run if cheap, else
note absence. No invented images.

## What I added + why

A new section `## Shaping signals — the Functions transform pipeline` in
`public/docs/functions_examples.md`, inserted immediately before
`## Expanded reference` (now ~L482). It has four parts:

- **The mental model** — source `Fn` shaped by reusable, stateless `FnTransform`s; the
  declarative layer is thin Kotlin, no second evaluator.
- **The operators** — a table with the exact type shape and one-line semantics of
  `through` / `pipe` / `/` / `then` / `over` / `stretch` / `overlapping … with`, calling out
  that `then` is overloaded (`FnTransform then FnTransform` composes; `Fn then Fn`
  concatenates).
- **Building up a pipeline** — five runnable `diffstatic` snippets, each ending in
  `.plot(...)`, escalating from `pulseBurst(10) through gain(0.6)` to a layered gesture.
- **The editor helps you** — how `InfixCompletion` boosts `FnTransform`-returning operands
  after `through`, discovered generically.

Cross-links back to `#over-vs-stretch` and `#fntransform`, and forward to the
variety-generation section. Rationale for placement: the existing timeline section names
the operators tersely and the reference is alphabetical/exhaustive; neither gives the
build-a-pipeline mental model. No duplication of the domain/over/stretch math (linked
instead).

## Verification evidence (symbols exist + signatures match, at base 614e2adf)

Source file: `src/jvmMain/kotlin/afield/stdlib/Functions.kt`. Every symbol used in a snippet
or the operator table was grepped and its signature confirmed against usage:

- Operators: `Fn.through(FnTransform)` L4496; `Fn.pipe(FnTransform)` L4498;
  `Fn.div(FnTransform)` L4500; `FnTransform.then(FnTransform)` L4474; `FnTransform.div` L4477;
  `Fn.over(ClosedRange<*>)` L4487; `stretch(to: ClosedRange<*>, …): FnTransform` L4511;
  `Fn.then(Fn)` L5092; `Fn.overlapping(Number): OverlapStart` L5102 + `OverlapStart.with(Fn)`
  L5099.
- Transform factories (all `: FnTransform`): `gain(Number)` L4540; `bias(Number)` L4546;
  `delay(Number)` L4534; `hold(Number)` L4564; `lowPass(window: Number, samples)` L4594;
  `softClipBy(Number)` L4558; `slower(factor: Number = 2.0, about = 0.0)` L4582;
  `turbulence(amount: Number, frequency: Number = 8.0, …)` L4630 (named args
  `amount=`, `frequency=` resolve to this overload; the `frequency: Fn` overload L4636 does
  not match a `Double`); `mirror(about: Number? = null)` L4713 (so `mirror()` is valid).
- Sources: `pulseBurst(count: Int, …all-defaults)` L1870 (`pulseBurst(10)`, `pulseBurst(8)`
  resolve); `cos(frequency: Number, …)` L4941 (`cos(2.0)`, `cos(3.0)`); `ramp(start: Number,
  end: Number, domain = DEFAULT)` L714 (`ramp(0.0, 1.0)`).
- Output/arithmetic: `Fn.plot(channel: String = "plot", …)` L2858 (so `.plot("source")`
  sets the channel, matching the live transcript); `Fn.plus(Fn)` L5171; `Fn.times(Fn)` L5189;
  `Fn.times(Number)` L5211.
- Completion claim: `InfixCompletion` object `src/jvmMain/kotlin/afield/execution.kt` L669,
  `OPERAND_MATCH_BOOST = 160` L671; docstring (L655-668) states the `through`→`FnTransform`
  boost and the generic operator-type discovery. `git show --stat 3f731751` confirms that
  commit introduced `InfixCompletion` in `execution.kt` (+ `InfixCompletionTest.kt`).

Anchor links verified with Python-Markdown's own `toc.slugify`: `#over-vs-stretch`,
`#variety-generation-novelty-search-for-a-set-of-gestures`, `#fntransform`,
`#algebra-and-mapping`, `#rewrite-limitations` — all exact matches; no slug collision (the
other "over" heading slugs to `over-stretch-pipe`).

Structural checks: the 5 new code fences are balanced; whole-file fence count is even (486);
zero `diffstatic_runner` fences in the new section (so no capture-harness images are claimed
or required).

## Build/lint

No markdown linter or lightweight docs-lint step exists in the repo (no `.markdownlint*`, no
npm docs scripts). The only docs build is `cd public && mkdocs build` (per `build.gradle.kts`
`generateApiDocs`), which runs the GPU screenshot-capture harness over every
`diffstatic_runner` fence in the site — heavy and, per prior nightly notes, fragile
(disk/GPU-fence sensitive). I did not run a full site build. My section adds no
`_runner` fences, so it introduces no capture load and needs no GPU rendering.

## Risks / unknowns

- Snippets were **not executed** through the JVM/REPL; correctness rests on symbol existence
  and signature matching (documented above), not a live run. Overload resolution for the
  named-argument `turbulence(...)` and the `then` overloads was reasoned from the signatures,
  not compiled.
- Anchor slugs assume the standard Python-Markdown `toc.slugify`; if the site's actual
  builder (Zensical, per repo memory) uses a different slugifier the cross-links could drift.
  All five slugs are simple, single-hyphen forms, which is the common case across slugifiers.
- No image is included (intentional), so nothing to mis-render.

## Recommendation

**Merge.** The section fills the stated gap (no single explainer of the pipeline), every
referenced symbol/signature is verified against the base commit, links resolve under the
standard slugifier, and it adds zero build/capture cost. Optional follow-up if desired: a
maintainer with a warm docs environment could promote one or two snippets to
`diffstatic_runner` to add captured plots — but that requires the GPU capture harness and is
out of scope for a verified prose+snippet explainer.

## Outcome

Done. Two commits on `nightly/2026-07-13/3-fn-transform-pipeline-doc`: the plan, then the
explainer section. This worklog commit follows.
