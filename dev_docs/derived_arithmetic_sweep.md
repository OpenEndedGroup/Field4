# Sweeping the corpus for wrong arithmetic

*2026-09-13.* The night `DerivedArithmeticGuard` landed, the corpus was swept by hand for the defect
the guard exists to catch: **a published number that is arithmetic over two other published
numbers, computed wrongly.** `dev_docs/citation_contracts.md` describes the guard. This page records
the sweep — the predicate, the population, what was found, and what the result means.

A guard with no live instances is a weak guard, so the sweep is half the deliverable rather than a
postscript.

## The predicate, and the two populations

Line-scoped grep over tracked `.md` under `dev_docs/` and `public/docs/`, for a **derivation
connective** near a number:

```
so (about|roughly|around) | that is (about|roughly|around) | works out | which gives
 | amounts to | comes to | per (frame|second|core|call|span|client) | apiece
 | times a second | events a second | about <n> | roughly <n> | around <n>
```

Two populations came out of it, and the difference between them matters:

| population | size | what it is |
|---|---|---|
| corpus | **641** pages | every tracked-shape `.md` under the two trees |
| broad candidates | **585** lines | the connectives above, before narrowing |
| with two numerals | **381** lines | a line that could express a derivation at all |
| code-stripped, deduped | **65** lines on 41 pages | the set actually read, one by one |

The last row is the swept population. It is narrower than the 381 because fenced blocks and inline
code spans are removed first — a number inside `` `Renderer.kt:92` `` is an address, not a claim —
and because table rows and lines already carrying a marker drop out. It is the same predicate
`DerivedArithmeticContractTest.DERIVATION_CONNECTIVE` censuses on every run, so the number in the
build log and the number swept here are the same number, deliberately.

**The honest limit: the predicate is line-scoped and a real derivation often spans a sentence or
two.** One of the two 2026-09-12 instances did exactly that — the operands were three lines above
the claim. So this under-selects, and in the direction this repo keeps recording. A sentence-scoped
or paragraph-scoped predicate is the obvious next version and was not built tonight.

Two survey traps were avoided on purpose, both recorded in this repo before. The pathspecs are
quoted single-level globs (`'dev_docs/*.md'`), which git expands across `/` — verified by checking
that `git ls-files -- 'public/docs/*.md'` returns the same 460 as an any-depth walk, because `**` in
a git pathspec does not match a single-level path and once silently excluded 387 top-level pages.
And nothing was `2>/dev/null`'d, because that discards the message naming the cause.

## What the sweep found

**No arithmetic errors.** All 65 lines were read. Roughly 28 of them state a genuinely derived
quantity; the rest matched the connective around an incidental number — a one-off measurement, a
parameter range, an image's alt text. That split is a **hand judgement made by reading, not by a
rule**, so it is written as "roughly" on purpose and is worth ±3; the 65 is exact and the ten
pinned derivations below are exact. Of the derived ones, every derivation whose operands were
recoverable from the page checked out.

That is a real result and not a null one, but it needs its context stated or it will be
over-read:

> **The corpus had been swept for this exact defect the day before, by a human.** `b84ce6d25`
> corrected both 2026-09-12 instances. Finding zero the following night is what you would expect
> after a competent pass, not evidence that the family is rare.

A zero deserves as much suspicion as a sweep that returns everything, so the sweep was checked
against a known positive rather than trusted. Running the predicate over `b84ce6d25^`'s text of
`public/docs/frame_budget.md` — the version that still carried the defect — selects it:

```text
220:At 60 fps that is roughly 0.6 chances per frame, so **a 2 millisecond hitch has about a one
```

That is the line, at the line number the original report gave. The predicate can see the defect it
was built for, which is the one thing a zero-result sweep has to establish about itself.
`DerivedArithmeticContractTest.theAgreementCheckFailsOnAWrongDerivation` then pins the *guard's*
half of the same property as a test, so neither depends on this page staying true.

## The ten derivations the sweep pinned

The valuable output was not a defect list but ten correct derivations that nothing had been
holding. Each is now a marker, so the next edit that moves an operand goes red:

| page | published | derivation | value |
|---|---|---|---|
| `public/docs/frame_budget.md` | 2.13% busy | `63.9/3002.6*100` | 2.1282 |
| `public/docs/frame_budget.md` | 300 possible firings | `3000/10` | 300 |
| `dev_docs/profiling_stack.md` | 300 possible firings | `3000/10` | 300 |
| `public/docs/onnx.md` | about 608 MB | `254.1+351.7+2.2` | 608.0 |
| `public/docs/visualizers.md` | 25 points per second | `1000/40` | 25 |
| `public/docs/path_tracing.md` | about 28 MiB | `640*360*128/1048576` | 28.125 |
| `public/docs/path_tracing.md` | 253 MiB | `1920*1080*128/1048576` | 253.125 |
| `public/docs/mesh_examples.md` | about 4% of the radius | `0.0326/0.8*100` | 4.075 |
| `public/docs/audio_ai_spectral_embeddings.md` | 6,846 frames | `273.82/0.04` | 6845.5 |
| `public/docs/audio_ai_spectral_embeddings.md` | about 250 points | `10000/40` | 250 |

With the nine founding markers that came with the mechanism, that is **19 markers on 9 pages**.

The `onnx.md` row is the shape the guard was built for and the best of them: **608 is a sum over
three rows of a table immediately above it.** Both operands and the result are published, on the
same screen, and until tonight nothing connected them. A model file changing size is a routine
download-manifest edit that would leave the prose stale with nothing red.

The two `path_tracing.md` rows are the second-best shape, because they pin the **unit**. 128 bytes
per pixel at 640×360 is 28.125 MiB or 29.49 MB depending on which mebi/mega you mean, and the prose
says "MiB" but nothing checked that the number matched the word. Now `/1048576` does.

## Pages the sweep read but deliberately did not edit

Two derivations were checked and left alone, because the sweep ran on a nightly branch and those
pages belonged to other work. Recorded rather than silently skipped, so the next pass can pick
them up:

- `public/docs/agent_transcripts.md` — "the philosophers' state space grows … about 2.41× per
  philosopher, so eleven is 16 238 markings … a **73×** speed-up at eleven". **Checked and
  correct**: the reduction is `2n²−2n+2`, which at n = 11 is 222, and 16 238 / 222 is 73.1. The
  2.41 is the silver ratio, 1 + √2 = 2.4142. A good marker candidate for whoever owns that page
  next.
- `dev_docs/ALIFE.md:109` — "the lattice costs about 0.7 % (the line reads 0.993 …)". A derived
  quantity, unverified here; the page was owned by another branch on the night of the sweep.

## The near-misses, which are the interesting part

Three lines that the default tolerance rule *would* fail, and which are not errors. They are
recorded because each one is a boundary of the mechanism rather than a defect in a page.

**1. Truncation, not rounding.** `dev_docs/control_surface_laws.md` publishes a "998 285×
reduction" from 20 964 001 `set` calls becoming 21 messages. That quotient is 998 285.76, which
rounds to 998 286 and truncates to 998 285. The author truncated, which is a perfectly ordinary
thing to do with a ratio of that size, and the default rule — round `HALF_UP` to the published
precision — would call it a disagreement. **Left unmarked.** A marker there would need `~1`, and a
future version of the mechanism might be better off learning a `trunc` spelling than making
everyone write a tolerance. (Incidentally invisible to the marker anyway: the page writes its
thousands space-separated, and the numeral grammar accepts `,` and `_`, not spaces.)

**2. A discrete process with no closed form.** `public/docs/dynamic_graph_systems_examples.md`
reports 40 points growing at ×1.05 per frame from frame 109, "reaching 3603 points … by frame 199".
Closed-form, `40*1.05^90` is 3226, and the published 3603 is 11.7% higher. That is not an error: the
point count is an integer and the growth rule rounds up every frame, so the realised factor exceeds
1.05 compounded. **Left unmarked**, and it is the clearest case for the rule that this marker only
ever pins arithmetic a reader can check by eye. A derivation that needs a simulation to verify
belongs in a Kotlin-side `Derivation` on `ProseNumeralContractTest`, not in a comment.

**3. A measurement wearing a derivation's clothes.** `dev_docs/COMPRESSED_SPLAT_PLY.md` says a
764×430 preview of 20k splats is "about 15.5M fragments". 764×430 is 328 520 pixels, so 15.5M is not
arithmetic over the two numbers in the sentence — it is a measured overdraw of about 47 screens, or
775 pixels per splat. The connective matched and the shape misled; there is nothing here to pin.
**This is the census's dominant category** — more than half of the 65 candidates — and the reason
the census is printed as a measurement and never asserted on.

## One thing the sweep found that is not arithmetic

`dev_docs/IMAGING_VECTOR_KERNELS.md` reads "**four** full-image passes and **four** 8 MB
allocations per call at 1920x1080 — around 33 MB of allocate-and-zero". Four eights are thirty-two,
so the sentence reads as a dropped carry, and it is correct: both numbers come from `1920*1080*4`,
which is 8.29 MB, and four of those is 33.18 MB. The page was right and looked wrong.

That is worth naming as its own category, because it is the *opposite* failure to the one the guard
was built for and the marker fixes both: an error the reader cannot see, and a correctness the reader
cannot see. Both of those are a page failing to show its work. Both markers are now in the sentence.

## What this does not check

The limits carried over from `DerivedArithmeticGuard`, restated here because a page that only lists
findings invites over-reading:

- **A wrong operand is invisible.** `1000/50` published as `20` passes. Only the step is checked.
- **Sixteen markers in a corpus of 641 pages** is sixteen guarded sentences, not a guarded corpus.
  The build log prints the unchecked remainder on every run for exactly this reason.
- **Word numerals cannot be pinned**, so "about two and a half per frame" on
  `public/docs/frame_budget.md` — which is correct, 1500 events over 600 frames — stays unguarded.
- **The sweep is one night's reading of 65 lines.** It is not a proof about 641 pages.
