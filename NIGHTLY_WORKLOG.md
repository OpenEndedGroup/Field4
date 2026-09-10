# nightly/2026-09-10/1 — `patCondense`: structural mini-notation synthesis

Base `c08b19003`. Kind A (big swing).

## Step 0 — falsification verdict

**Premise confirmed.** Population: 1311 `.kt` files under `src/` (87 of them in
`afield/four/synth`, main + test). Three predicates, all run over that whole population, none
`2>/dev/null`'d:

- `fun [A-Za-z.<>]*[Nn]otation` — 48 hits. Every one is either `…Annotation…` (docs annotations,
  Kotlin annotations, slide annotations) or a *different* language's notation: `sorting_networks.kt`
  `notation()`/`fromNotation`, `PetriSmokeTest.testTheNotationRoundTrips`,
  `BooleanNetworksSmokeTest.testNotation`. The only mini-notation emitter in the repo is
  `patNotationOf` (`src/jvmMain/kotlin/afield/four/synth/PatTranscription.kt:407`), and it emits a
  **flat** slot list — `slots.joinToString(" ")` over `grid.steps` slots.
- `description length|minimum description|MDL|shortest (expression|string|notation)` — 0 hits in any
  pattern file (the 18 hits are `getCmdListsCount`-style substring noise in
  `ImGuiRenderToTexture.kt` and `Instance.kt`).
- `fun .*(recognis|recogniz|infer)[A-Za-z]*\(` — 20 hits, all type inference (FieldML), docs-claim
  detection, or render-proof assertions. Nothing over patterns.

So: `PatTranscription.kt`'s own header sentence — *"there is no mini-notation printer anywhere in
Field, in either direction"* — is still true of *structure*. One flat printer now exists; nothing
reads a rhythm and answers `bd(3,8)`.

## Plan

New `src/jvmMain/kotlin/afield/four/synth/PatCondense.kt`.

**Target → canonical form.** A target (step mask / `PatPattern<String>` / `List<PatEvent<String>>` /
`PatTranscription`) is rendered over a stated number of cycles and reduced to, per cycle, a list of
**layers** (greedy interval-partition of overlapping events, which is what `,` means), each layer a
list of `(value|rest, widthInSlots)` items on the finest common grid (lcm of the whole-arc
denominators, reduced by the gcd of the widths).

**Search.** A memoised divisor-recursive DP over each layer's slot range, keeping the best `K`
distinct strings per range (beam). Candidate forms, all drawn from `MiniPat.Node` as the parser
actually defines it (`TidalPatterns.kt:1127`), not from a wish list:

- a token (`bd`, `bd:2`) or `~`; `_` for a held slot, rewritten to `@n` where that is shorter;
- an `m`-way **equal split** for each divisor `m` of the range length, with adjacent identical
  items collapsed to `x!k` (`expandReplicatedItems`, `TidalPatterns.kt:1634`);
- `X*k` where the range is `k` copies of its first `L/k` block (`Fast`);
- `T(p,L)` / `T(p,L,r)` when the range is a `bjorklund` mask of one token (`Euclid`);
- `T(kL)` / `T(gL)` and their offsets when it is a `kolakoskiMask` (`Kolakoski`);
- `<A B …>` at the top level when cycles differ (`Choice` → `slowcat`);
- `A, B` between layers (`Stk`).

**Oracle.** Every emitted candidate goes back through `pat(...)` — the production parser a user's box
calls — and its rendered events are compared to the target's for identity. No second evaluator. The
first candidate in length order that reproduces exactly wins; the flat form is the always-valid
floor, so `patCondense` never fails and never invents.

**Bounds, reported:** beam width per range, memo-entry budget, max cycles. `PatCondensation` carries
them so a caller can see when the search was cut off.

## Verification intended

1. Round trip over the mini-notation corpus already in `PatContractTest.kt` and
   `SonicPiPatternsSmokeTest.kt`, enumerated by scanning those two files for `pat("…")` literals —
   population stated, `N of M` reported, floor asserted.
2. Named discoveries asserted: `bd(3,8)`'s mask → a euclid form; `bd(k16)`'s → a kolakoski form;
   and structureless masks at a stated seed → **themselves**, no operator invented.
3. Compression distribution `len(condensed)/len(flat)` with median and worst case, floor asserted.
4. A piano roll (target lane vs condensed lane), opened and looked at.
5. A loadable demo.

*(filled in below as the night goes)*
