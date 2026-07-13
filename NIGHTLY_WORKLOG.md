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

## Verification evidence

(filled in below after execution)

## Outcome

(filled in below)
