# Nightly worklog — rewrite-strategies (kind A)

Branch `nightly/2026-07-25/1-rewrite-strategies`, based on `ec4ef430`.

## Mission

Turn the pattern-rewrite layer (`afield.macros.PatternRewrite`) from a flat global-fixpoint
rewriter into a *controllable* term-rewriting engine, with a strategy sublanguage layered on top of
the existing collector/matcher. **Backward-compatible:** with no `strategy {}` block the behaviour is
EXACTLY today's global fixpoint (all 23 existing smoke cases stay green, unchanged).

## Plan (refined against the real code)

The engine today (`PatternRewrite.expand`, PatternRewrite.kt): `collect()` gathers every top-level
`rewrite {}` into a `PatternRule`, blanks the blocks out, then a `while` loop runs a **global
fixpoint** — each pass `findMatches` finds every outermost non-overlapping match for ALL rules
everywhere, `applyEdits` splices them, re-parse, repeat to convergence (`maxIterations=64`).
Everything is stateless PSI (ktfmt `Parser` in tests, the REPL project in prod).

### What I will add

1. **Named rules.** `rule("name") { x: Expr -> from{…}; to{…} }`. Collected exactly like `rewrite {}`
   (share `toPatternRule` off the trailing lambda) but carrying a `name`. Anonymous `rewrite {}`
   keeps working unchanged. A `PatternRule.name: String?` field is added (default null).

2. **A `strategy { … }` control block** — a small term-rewriting sublanguage parsed at PSI level
   into a `Strat` AST and interpreted over the whole-file source text. Combinators (all fail-closed
   on unknown rule names / malformed shape, mirroring the malformed-pattern rejection style):
   - leaf `"r"` / `r` — fire rule r at ALL outermost sites, one pass; fails if no site.
   - `once("r")` — fire r at the single first (outermost) site, at most once.
   - `topdown("r")` — fire r at the single outermost site (the shallowest match).
   - `bottomup("r")` — fire r at the single innermost/deepest site.
   - `attempt("r")` (a.k.a. `try`, which is a Kotlin hard keyword so cannot be a call name) — run
     s once, identity on failure; never fails.
   - `repeat(s)` / `repeat(s, n)` — iterate s to fixpoint / at most n times; never fails.
   - `seq(a, b, …)` / `a then b` — sequential, fail-fast.
   - `choice(a, b, …)` — first alternative that succeeds.

   Success/failure is modelled as `String?` (null = the strategy did not apply). `topdown`/`once`
   coincide on the single-outermost case and are documented as such; `bottomup` genuinely differs on
   nested matches (`f(f(a))` → topdown picks the outer, bottomup the inner).

### Semantics contract

- **No `strategy {}` block** ⇒ the existing global fixpoint over all collected rules (local +
  ambient). Byte-for-byte the old path. This is what keeps the 23 cases green.
- **A `strategy {}` block present** ⇒ the strategy drives execution; only rules it names by name
  fire, in the order/traversal it dictates. Anonymous `rewrite {}` rules cannot be named so do not
  fire under a strategy (documented). `provide {}` / `ai()` side-contributions still accrue whenever
  a rule actually fires.
- Fail-closed: an unknown rule name or a malformed strategy expression emits an `onDiagnostic`
  message and the strategy performs NO rewriting (returns the blanked source untouched) — never a
  surprising partial rewrite.

### Implementation shape

- `Collected` gains `strategyExprs: List<KtElement>` (raw PSI, validated against the named-rule map
  in `expand` after collection). Strategy blocks are blanked like rule blocks.
- `expand()` branches: strategy present → `StrategyRunner`, else the untouched fixpoint loop.
- `StrategyRunner` reuses `findMatches` (restricted to one rule) for outermost sites and a new
  `findDeepestMatch` for `bottomup`. It shares the same `GensymScope`, `evalUnquote`, and
  preamble/ai side-channels the fixpoint path uses.

### Verification plan

- `python3 tools/run_field_tests.py --id kotlin.pattern-rewrite.smoke --port <free>` — all 23 old +
  new strategy cases. New cases assert each combinator produces a DIFFERENT result than the default
  global fixpoint (proof it does something) and that fail-closed diagnostics fire.
- Ship + **replay** a loadable demo transcript `rewrite_strategies_demo.yaml` (EXECUTE class) through
  `examples.demo-transcript.exec`, observing the boxes expand + execute with the expected printed
  values.
- A short dev_docs note documenting the sublanguage.

## Status: DONE — verified, recommend merge

### Files changed
- `src/jvmMain/kotlin/afield/macros/PatternRewrite.kt` — the engine. Added `PatternRule.name`; a
  `Strat` sealed AST; `collect()` now also gathers `rule("name") {}` and `strategy {}` blocks
  (blanked like `rewrite {}`); a fail-closed strategy parser (`parseStrategy`/`parseStrat`/
  `flattenThen`); a `StrategyRunner` interpreter; and `findDeepestMatch` for `bottomup`. `expand()`
  branches to the runner iff a local `strategy {}` is present, else the untouched fixpoint loop.
- `src/jvmMain/kotlin/afield/macros/RewriteDsl.kt` — erased authoring surface for `rule`, `strategy`,
  `once`/`topdown`/`bottomup`/`attempt`/`repeat`/`seq`/`choice`/`then` (+ a `Strategy` marker type).
- `src/jvmMain/kotlin/afield/PatternRewriteSmokeTest.kt` — 13 new strategy cases wired into the
  existing `kotlin.pattern-rewrite.smoke`; each asserts the strategy output DIFFERS from the default
  global fixpoint (or that a bad strategy is fail-closed). One end-to-end case drives the real engine.
- `dev_docs/agents/transcripts/rewrite_strategies_demo.yaml` — the loadable demo (8 boxes).
- `src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt` — classified the demo `EXECUTE`.
- `dev_docs/pattern_rewrite_strategies.md` — the sublanguage note.

### Combinators landed (all genuinely change behaviour vs the default)
Named rules; `once`, `topdown`, `bottomup`, `attempt`, `repeat`/`repeat(_, n)`, `seq`/`then`,
`choice`; bare-leaf "fire everywhere one pass". Fail-closed on unknown names / malformed programs.

### PSI-level pivots (honest notes)
- `try` is a Kotlin hard keyword and cannot be a call name at PSI level, so the surface spelling is
  `attempt` (documented). This is the one spelling deviation from the brief.
- `once(r)` and `topdown(r)` coincide on the single-outermost case (both fire the shallowest single
  match); they are two intent-spellings of the same primitive. `bottomup` genuinely differs (deepest
  match). This is a deliberate, documented consequence of doing traversal selection on whole-file PSI
  rather than a node-by-node congruence traversal.

### Verification (real, not by inspection)
- `./gradlew compileKotlinJvm` — clean.
- `python3 tools/run_field_tests.py --id kotlin.pattern-rewrite.smoke --port 9401` — **PASS**
  (7.7s). Server freshly started on the dedicated port 9401 ("Started persistent Field on port
  9401"), not a reused cross-worktree server; all 23 original + 13 new cases green (the harness only
  prints "Pattern-rewrite smoke passed" if every case passes).
- `python3 tools/run_field_tests.py --id examples.demo-transcript.exec --port 9402` — **PASS**
  (43s). The demo was **replayed through the real engine**: `[execute] PASS rewrite_strategies_demo
  (applied=17, output lines=8)` — all 8 boxes expanded (rule + strategy blocks blanked, rewrites
  applied), compiled, executed, and each box's embedded `check()` on the exact expected value held
  (a wrong value would surface as an error-kind output line and fail the harness).

### Risks & unknowns
- The strategy interpreter operates at whole-file granularity (the "term" is the source text), not a
  true congruence traversal; `topdown`/`bottomup` select a single site rather than recursing
  structurally. This is coherent and tested, but a user expecting full Stratego `all`/`one` congruence
  semantics would find it shallower. Documented.
- No live-editor (preview) exercise of a `strategy {}` beyond the smoke/exec paths; the preview path
  shares `expand()`, so it is covered transitively, but not screenshotted.

### Recommendation
**MERGE.** Backward-compatible (all pre-existing behaviour unchanged when no `strategy {}` is
present), fully verified through the canonical runner on dedicated ports, ships a replayed
self-verifying demo + a dev doc. Natural follow-ups (separate PRs): a congruence-style `all`/`one`
traversal, and surfacing strategy diagnostics in the box UI.
