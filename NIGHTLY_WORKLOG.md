# Nightly worklog — 2026-07-13 / kind B — overload-aware infix completion

Branch: `nightly/2026-07-13/2-infix-completion-overloads` (base `614e2adf`, confirmed green).

## Plan

**Problem.** `InfixCompletion.expectedOperandType` (src/jvmMain/kotlin/afield/execution.kt ~723)
resolves the operand type of an infix operator by asking the engine to complete the operator
token and reading the *first* matching `operator(ParamType)` metadata entry
(`result.metadata.firstOrNull { … }`), returning a single `String?`. Several Functions infix
operators are overloaded on operand type (`Fn.over(Domain)` / `Fn.over(ClosedRange<*>)`,
`Fn.stretch(...)`, `Fn.overlapping(Number)` / `Fn.overlapping(Fn)`), so only ONE overload's type
gets the ranking boost; valid operands of the other overload are not surfaced.

**Fix — widen single type → set of types, contained to `InfixCompletion` + its one call site.**
1. Cache value type `String?` → `Set<String>` (empty set = resolved-but-none / not an operand
   site). `containsKey` still distinguishes cached-empty from not-cached. Store an immutable set.
2. `expectedOperandType(...): String?` → `expectedOperandTypes(...): Set<String>`. Return
   `emptySet()` when not an operand site. Collect EVERY matching overload entry (drop
   `firstOrNull`, iterate `metadata`): for each `method` entry whose display text is
   `operator(...)`, take the single param type, skip multi-param entries (comma **outside**
   generics — new `hasTopLevelComma` helper so `Map<String,Int>`-style single params survive),
   `shortenType`, collect non-blank into a set.
3. `matchesOperandType(candidate, expectedType: String)` → `matchesOperandType(candidate,
   expectedTypes: Set<String>)`: true when candidate's shortened return type ∈ set.
4. Call site (execution.kt ~2884): `infixExpectedType: String?` → `infixExpectedTypes: Set<String>`;
   `completionFor(c, infixExpectedTypes: Set<String> = emptySet())`; boost guard becomes
   `if (infixExpectedTypes.isNotEmpty() && matchesOperandType(it, infixExpectedTypes))`; trace line
   updated.

Preserve `parseInfixSite`, stop-words, cache key/eviction, `OPERAND_MATCH_BOOST`, single-param
guard semantics.

**Verification plan.** Extend InfixCompletionTest.kt: fake engine returning TWO overload entries
for one operator (`over(Domain)` + `over(ClosedRange<*>)`); assert resolver returns BOTH shortened
types and that a candidate of either type matches while an unrelated type does not. Adapt existing
tests to the set signature. Build compileTestKotlinJvm + run `*InfixCompletion*`.

## Execution

Implemented as planned, contained to `InfixCompletion` and its single call site.

Files changed:
- `src/jvmMain/kotlin/afield/execution.kt` — `paramTypeCache` value type `String?` → `Set<String>`;
  `expectedOperandType` → `expectedOperandTypes(...): Set<String>` (collects EVERY matching
  single-param overload via `metadata.asSequence().filter{…}.mapNotNull{…}.toSet()`, immutable set
  cached); new `hasTopLevelComma` helper replaces the naive `',' in paramType` guard so single
  generic params like `Map<String, Int>` survive while true multi-param entries are dropped;
  `matchesOperandType(candidate, Set<String>)` matches ANY type; call site (`~2884`),
  `completionFor` default (`emptySet()`), boost guard (`~2809`), and trace line updated. KDoc on
  both functions + the object header rewritten to describe overload-awareness.
- `src/jvmTest/kotlin/afield/InfixCompletionTest.kt` — `operatorEngine` now takes vararg param
  types; existing 5 tests adapted to the set signature; added `resolvesEveryOverloadOperandType`
  (two `over` overloads → `{Domain, ClosedRange<*>}`), `matchesAnyOverloadReturnType` (both boosted,
  unrelated not), `skipsMultiParameterEntriesButKeepsGenericSingleParam`.
- `dev_docs/COMPLETION_ENGINE.md` — added a routing bullet noting the server-side overload-aware
  infix operand-type boost.

## Verification (observed, honest)

- `./gradlew compileKotlinJvm compileTestKotlinJvm` → BUILD SUCCESSFUL (only pre-existing unrelated
  warnings in FunctionGraphBrowser/smile_*).
- `./gradlew jvmTest --tests '*InfixCompletion*'` → BUILD SUCCESSFUL. JUnit XML
  (`build/test-results/jvmTest/TEST-afield.InfixCompletionTest.xml`): `tests="8" failures="0"
  errors="0"`. All 8 (5 adapted + 3 new) pass.
- This is pure resolver logic (no rendering / no loadable Field doc), so tests are the proof; no
  metadata.edn needed.

**How a human feels it:** open a Functions box, type `pulseBurst(10) stretch ` (or `over `) and
both `Domain`-typed and range-typed operand builders float to the top, not just the first overload's.

## Risks / unknowns

- Low. Change is localised; the only behavioural shift is that MORE (correct) candidates get the
  boost. The single-param guard is now stricter-correct (`hasTopLevelComma` vs raw `,`), which could
  in theory admit a rare single generic param that was previously rejected — that is the intended
  fix, and the multi-param case is still dropped.
- The live end-to-end path was not driven (no JVM scripting runtime spun up here); it shares the
  exact code the unit tests exercise, and the call-site wiring compiles, but I did not observe the
  editor boost in a running Field.

## Recommendation

**Merge.** Small, contained, well-tested bug/ergonomics fix; builds and the full InfixCompletion
test class pass green (observed). Addresses a real sharp edge in an actively-used feature.
