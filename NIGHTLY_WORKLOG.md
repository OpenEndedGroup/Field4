# Nightly 2026-08-06 — agent transcript index: make it true, keep it true

Branch `nightly/2026-08-06/3-agent-transcripts-index-truth`, based on `50d838851`.

## Plan

### What is actually wrong (re-measured in this worktree, not taken on trust)

- `dev_docs/agents/transcripts/*.yaml` — **52** transcripts.
- `public/docs/agent_transcripts.md` — **28** entries (lines matching `^\*\*name\*\* — `).
  24 transcripts have no entry: the 22 the orchestrator listed, plus `graphics_renderer_launch`
  and `afield_four_traditional_triangle`, which appear only inside a parenthetical.
- The page's opening claim is *"every one of them is exercised end-to-end in CI"*.
  `DemoTranscriptExecSmokeTest` classifies the corpus into three sets, and they partition it exactly:
  **35 EXECUTE** (replayed end to end through the real compile+eval path, asserted to emit zero
  error-kind output lines), **10 COMPILE_ONLY** (graph built for real, each box's Kotlin compiled
  through the real K2 pipeline with the terminal eval skipped, because the box opens a GLFW window),
  **7 KOTLINJS** (the box body compiled Kotlin→JavaScript through the real head profile). 35 + 10 + 7
  = 52. So the blanket claim is true of 35 and false of 17 — and the page's *own section prose*
  already says something more careful in two of the three sections, which is the tell that the
  headline sentence is the stale part.
- `public/docs/assets/agent_transcripts/` holds **48** bundles; the 4 absent ones are exactly
  `AgentTranscriptMaterializer.EXCLUDE` (`graphics_renderer_launch`,
  `afield_four_traditional_triangle`, `remotear_scene_motion`, `tone_synth_sequence`). So "openable
  as a starting point" is a real per-transcript distinction the page must not paper over with a
  dead `Open ↗` link.

### The structural decision: hand-written entries + a machine guard, not generation

The page could be generated from the corpus, the way `public/docs/reference/` is generated from KDoc.
I am not doing that, and the reason is visible in the diff of what already exists: the good entries on
this page are 150-word paragraphs that say *which number a box prints and why that number is the
interesting one* (`0.985 forwards, 0.246 backwards on a parabola`; `473 diagonal breaks against 0`;
`the alpha-event system's area-scaled increment tops out at 0.3 %` of the ceiling). Nothing in the
transcript yaml carries that: the yaml carries op verbs, box ids, rects and Kotlin source. A generator
over it can emit a name, a box count and a first line of code — which would replace the best writing
on the page with a table, and would make the page *complete and useless*. The editorial content is
the product here.

So: **the prose stays hand-written, and a guard makes incompleteness a build failure.** That is the
same trade `checkKDocAttachment` makes (a guard that forces a human to move a comment) rather than the
trade `checkCodebaseReference` makes (byte-exact regeneration), and it is the right one whenever the
artifact's value is the writing rather than the enumeration.

### Steps

1. **New guard** `afield.docs.AgentTranscriptIndexGuard`, a pure text-reading harness (no boxes, no
   runloop, no GPU — so it is cheap, unlike `checkAgentTranscripts`), wired into `check` as
   `checkAgentTranscriptIndex` and carrying a `FIELD_TEST` block like its neighbours. It checks:
   - every corpus transcript has exactly one entry; no duplicate entries;
   - every entry names a transcript that exists (catches a rename or a deletion);
   - each entry sits in a section whose declared CI level matches the transcript's set membership in
     `DemoTranscriptExecSmokeTest` — declared *on the page* as an invisible
     `<!-- transcript-index: verified-by = … -->` marker under each heading, so the contract lives
     with the prose and the guard needs no hardcoded heading names;
   - an `Open ↗` link is present exactly when the transcript is materialized (i.e. not in
     `EXCLUDE`), points at `…/agent_transcripts/<name>/metadata.edn`, and that bundle really is
     committed — so the page cannot advertise a starting point that does not exist;
   - the counts quoted in the page's CI paragraph (marked with `<!-- transcript-index: totals -->`)
     match the four numbers computed from the corpus and the three sets.
   To do this the two harnesses' sets stop being `private`: `DemoTranscriptExecSmokeTest.EXECUTE /
   COMPILE_ONLY / KOTLINJS` and `AgentTranscriptMaterializer.EXCLUDE` become `internal`, with the
   smoke test remaining the single source of truth for classification.
2. **Rewrite the page's opening claim** to say what CI actually does, in three named levels, and give
   every unindexed transcript a real entry — read from its yaml, not invented. New sections for the
   two families that have none: browser-head scenes (WebXR/WebAudio) and the graphics building blocks
   currently hidden in a parenthetical.
3. **Prove the guard has teeth**: add a throwaway `dev_docs/agents/transcripts/zzz_guard_probe.yaml`,
   observe `checkAgentTranscriptIndex` fail naming it, delete it, observe green. Record the output.
4. **Close the related hole** at `AgentTranscriptMaterializer.kt:173`: deleting the whole committed
   bundle currently makes `checkAgentTranscripts` *pass* with a "skipping drift check" note. In check
   mode (no explicit output dir) an absent bundle is a failure.
5. **Document the contract** in `dev_docs/` so the next person extends the index correctly.
6. Build the page with the Zensical docs build, serve it on a free port, and *open and read it*.

### Deliberately not doing

- Not running `./gradlew generateCodebaseReference` (agent 1 owns `public/docs/reference/`).
- Not inventing a capture. Several new entries describe boxes whose output is a native Vulkan window
  or a headset; those get no image, and the page says why. Where a real committed capture already
  exists under `public/docs/assets/`, it is linked; nothing is synthesized.

## What changed

Four commits on `nightly/2026-08-06/3-agent-transcripts-index-truth`.

| commit | files |
|---|---|
| `78a3d7fd9` | `src/jvmMain/kotlin/afield/examples/AgentTranscriptMaterializer.kt` |
| `1404fa7ed` | `public/docs/agent_transcripts.md` |
| `5f22ea146` | `src/jvmMain/kotlin/afield/docs/AgentTranscriptIndexGuard.kt` (new), `build.gradle.kts`, `src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt`, `src/jvmMain/kotlin/afield/examples/AgentTranscriptMaterializer.kt`, `dev_docs/agent_transcript_index.md` (new), `dev_docs/agents/transcripts/README.md` |
| this one | `NIGHTLY_WORKLOG.md` |

1. **`public/docs/agent_transcripts.md`** — 28 entries became 52, and the headline CI claim became
   accurate. The opening sentence said "every one of them is exercised end-to-end in CI"; the truth is
   35 replayed end to end, 10 compiled with the terminal eval skipped because the box opens a GLFW
   window, 7 compiled Kotlin→JavaScript through the real head profile. The page now says that, in a
   dedicated section, and says what none of the three levels prove. Two new sections were needed for
   families that had none: **Browser-head scenes** (`remotear_start`, `remotear_scene_motion`,
   `tone_synth_sequence`) and **Graphics building blocks** (`graphics_renderer_launch`,
   `afield_four_traditional_triangle`, previously only mentioned inside a parenthetical). Three honest
   limits are now on the page rather than discoverable only by running the thing: `audio_ai_codec`'s
   metrics are literals in a deterministic fixture and it does not run EnCodec; `ceres_free_variables`
   takes its no-wrapper branch as checked out because the dylib is not committed; and
   `remotear_scene_motion` refers to an `/abs/assets/robot.glb` no checkout contains.
2. **`afield.docs.AgentTranscriptIndexGuard`** + `checkAgentTranscriptIndex`, wired into `check`.
   Five checks; see `dev_docs/agent_transcript_index.md`. Text-only, ~1 s.
3. **`AgentTranscriptMaterializer`** — deleting the whole committed bundle used to make
   `checkAgentTranscripts` *pass* ("no committed bundle — skipping drift check"). In check mode that
   is now a failure. `EXCLUDE` and the three classification sets became `internal` so the index guard
   can read them; classification is still decided only in `DemoTranscriptExecSmokeTest`.
4. **`dev_docs/agent_transcript_index.md`** (new) and **`dev_docs/agents/transcripts/README.md`** —
   the contract, the add-a-transcript checklist, and the argument for a guard over a generator. The
   README also lost two hand-maintained lists of `EXECUTE`/`COMPILE-ONLY` members, both of which were
   stale — the same failure one directory up — and gained the `KOTLINJS` bucket it never documented.

### The design decision, and why

The page could be generated from the corpus like `public/docs/reference/` is. I did not do that. The
entries that make it worth reading quote a number a box prints and say why that number is the
interesting one; the yaml carries op verbs, box ids, rects and Kotlin source, so a generator over it
can emit a name and a box count. Generation would have made the page complete and useless in the same
commit. So: prose hand-written, incompleteness a build failure —
`checkKDocAttachment`'s trade rather than `checkCodebaseReference`'s.

The one piece of machinery I did add to the page is an invisible
`<!-- transcript-index: verified-by = execute -->` marker under each `##` heading. It is what turns
"these build and run headless" from a claim about most entries into a checked claim about every entry
beneath it, and it keeps the contract in the page instead of hardcoding heading names in Kotlin.

## Verification

**Observed.**

- `./gradlew --no-daemon checkAgentTranscriptIndex` — green:
  `52 transcript(s), 52 entry/entries, "Pure-compute scenes" = execute; "visionOS device scenes" =
  kotlinjs+execute; "Browser-head scenes" = kotlinjs; "Graphics scenes" = compile; "Graphics building
  blocks" = compile`.
- **The guard has teeth — four failure modes, each watched go red and then green again:**
  1. added `dev_docs/agents/transcripts/zzz_guard_probe.yaml` → 2 problems: the missing entry, named
     with the file path, *and* "the totals paragraph does not mention 53 — the corpus size". Deleted
     the probe → green. (The probe was never committed; `git status` confirmed clean.)
  2. renamed two entries to names with no yaml → 4 problems: two orphans reported at their line
     numbers, plus the two transcripts they had stopped covering.
  3. added an `Open ↗` link to `remotear_scene_motion` (in `EXCLUDE`) → "links a starting point, but
     the transcript is in AgentTranscriptMaterializer.EXCLUDE, so no document is generated for it and
     the link is dead."
  4. mislabelled the building-blocks section `verified-by = execute` → both of its entries reported as
     compile-verified, with line numbers and the fix.
- **Docs build**: `uv run --group docs python tools/build_zensical_docs.py --page agent_transcripts.md`
  → `regenerated=0 … reused_existing=428 missing=0 failures=0`, `OK — every referenced asset is
  committed`, `No issues found`, `synced public/field4`. No capture was regenerated, which is correct:
  the page has no `diffstatic_runner` fence.
- **Served and read**: `python3 -m http.server 9611` from
  `public/field4`. `http://127.0.0.1:9611/agent_transcripts/` → **200**, and all fourteen `<img>`
  targets → 200 individually. The six `<!-- transcript-index: … -->` markers are HTML comments in the
  output, i.e. invisible. All 48 `Open ↗` hrefs and every cross-link render as expected relative URLs.
- **Opened and read, not just diffed**: rendered the served page in headless Chrome to a 32-page PDF
  and read all 32 pages. That found a real defect nothing else would have: the `motion.png` figure's
  alt text described "a quiver of tracked flow vectors", and the actual capture has four panels
  (frame A, frame B rotated, |A−B| in inferno, B warped back) with no quiver at all. Corrected. I then
  re-read the two figures I was least sure of (`motion.png`, `spectrogram.png`) as images to check the
  descriptions against the pixels.
- **Spot-checked three prose claims against the yaml directly** rather than trusting my notes: the
  `|+|`/`|+`/`+|` event counts (4, 2, 3), the `2400x1800 — 4.3 megapixels, 52 MB of float RGB`
  comment, and that `close_node_demo` really runs ten operations.
- `./gradlew --no-daemon check` — **red, on `:checkCodebaseReference`, and I am leaving it red.** It
  reports `imaging.md`, `index.md` and `dev_docs/kdoc_worklist.md` out of date. Agent 1 owns the
  codebase reference tonight and I was told not to run `generateCodebaseReference`, so I did not.
  It is also demonstrably **not** my branch's doing, and the proof needs no extra run:
  `StdlibReferenceGenerator.SOURCE_ROOTS` is exactly
  `["src/jvmMain/kotlin/afield/stdlib", "src/jvmMain/kotlin/afield/visualization"]`, and
  `git diff 50d838851..HEAD --name-only` touches neither — the only `src/` files on this branch are
  `afield/docs/AgentTranscriptIndexGuard.kt` and two files under `afield/examples/`. Since the task's
  inputs (those two roots) and its expected output (`public/docs/reference/`) are byte-identical to the
  base commit here, its verdict is the base commit's verdict, not mine. That contradicts the
  orchestrator's 03:20 note that the base was fully green, which is worth someone's attention: either
  that run differed, or the generator is not deterministic across runs. I did not investigate further,
  because doing so means running the generator.
- Everything else in `check` passed, including the two tasks this branch is about:
  `checkAgentTranscripts` → `AgentTranscriptMaterializer ok — materialized 48 document(s)` with
  `checking committed bundle at …/public/docs/assets/agent_transcripts` (so my materializer edit did
  not break the drift check), and `checkAgentTranscriptIndex` → green. `jvmTest` and `allTests` passed.
  Zero Kotlin warnings from `compileKotlinJvm` on a clean compile of the new guard (the first compile
  emitted two "unnecessary non-null assertion" warnings, which I fixed before committing — the baseline
  is zero warnings and this branch keeps it).

**Not verified.**

- I did not *run* any transcript. Every number quoted in a new entry is read from the transcript
  source (a format string, an inline comment, or a literal), not observed output. Where a docs page
  had already published a measured figure I let the walkthrough link carry it rather than repeating
  it here. This is the weakest part of my evidence: an entry could describe a box faithfully and still
  quote a number the box no longer prints, and neither guard would notice.
- The seven figures are pre-existing docs-capture output from the walkthrough pages, produced by
  `diffstatic_runner` fences (verified by grepping each asset back to its fence) — but I did not
  re-run those captures, so I am relying on their pages being current. `imgui-vr-panel.png` is the one
  that is *not* from a runner fence: it is referenced as a plain image on `imgui_vr_panel.md`, so I
  can say it is a real capture of the running demo (it matches the demo exactly) but I cannot point at
  the fence that made it.
- Not run: the `examples.demo-transcript.exec` FIELD_TEST (~3 min of real box compiles) and the docs
  build for the whole site. Neither is affected by anything here except the `internal` visibility
  change, which `check` compiles.

## Corrections to the brief

- **`dev_docs/ALIFE.md:47` is not broken and I did not "fix" it.** The brief called it a pointer to
  `reference/uncategorised.md`, "a page that no longer exists". Read in context it is inline code in a
  sentence about what happens *without* the annotation: "without it a new file lands in a
  freshly-created `reference/uncategorised.md`". It is not a link, and it is accurate —
  `StdlibReferenceGenerator.kt:456` really does fall back to the category `Uncategorised`. Editing it
  would have made the file wrong. Nothing changed.
- **The stretch goal as written is not available.** The brief suggested illustrating the missing
  entries from "each transcript's bundle … under `public/docs/assets/agent_transcripts/<name>/`". Those
  bundles contain `metadata.edn`, `document.md`, `AGENTS.md`, `README.md` and one `.kt` per box
  property — **no images at all**. So the figures had to come from the walkthrough pages instead, and
  most entries have none. The page now says so rather than implying a gap that is not there.
- **The "42 % incomplete" count was 24, not 22.** `graphics_renderer_launch` and
  `afield_four_traditional_triangle` appeared only inside a parenthetical, not as entries; both now
  have real entries in their own section.

## Risks and unknowns

- **Friction is the point, and it is real.** Adding a transcript now requires four edits (classify,
  materialize, write an entry, bump the totals) instead of two. If that proves annoying the totals
  check is the part to drop; the completeness check is the part that matters.
- **The entry grammar is a reserved shape.** A line starting `**word** — ` at column zero is read as
  an entry. Bold mid-sentence is unaffected, and the existing page had one line starting
  `**native Vulkan window** — ` which is correctly *not* matched (the name charset excludes spaces),
  but a future author who starts a paragraph with a single bold lowercase word and an em dash will get
  a confusing "names no transcript" failure. It is documented in the guard's KDoc and in
  `dev_docs/agent_transcript_index.md`.
- **The visionOS section declares two levels**, so the per-entry CI check is weaker there than
  elsewhere. Its prose names the two JVM-Kotlin exceptions explicitly, which is the mitigation.
- **My prose is only as good as the yaml comments it is drawn from.** Several transcripts carry a
  self-description that is itself slightly wrong (`imaging_visualizers_demo` says "Nine pure-compute
  boxes" and has ten; `spectral_examples` says "five boxes" and then "all four on screen"). I wrote
  from the box code rather than those headers where they disagreed, and did not fix the yaml — out of
  scope, and it would have churned the committed bundle.
- **The guard reads five markdown conventions**, so it is coupled to the page's shape. Fenced code
  blocks are now skipped, and the `Open ↗` scan is still line-based (a link inside a fence within an
  entry's paragraph would be picked up), which is a small remaining sharp edge.

## Final state

- 5 commits (`ad6da4c95` .. `82c438880`), 8 files, 2 of them new.
- Rebuilt page re-verified after the alt-text fix: **200**, 52 entries, exactly 48 `Open ↗` links
  (52 minus the four in `EXCLUDE`), 14 `<img>` elements and **zero** with a missing or empty `alt`.
- Served at `http://127.0.0.1:9611/agent_transcripts/` from
  `/Users/marc/field4-worktrees/2026-08-06-3-agent-transcripts-index-truth/public/field4` (own
  `python3 -m http.server`, pid recorded and stopped at the end of the run). Also confirmed the
  preview-pane server I briefly started was rooted in the **primary** repo `/Users/marc/field4`, i.e.
  it was serving `main`'s docs and told me nothing about this branch — stopped it and verified port 8123
  free before doing the real verification against 9611.

## Recommendation: **merge**

The page was making a false claim about completeness on the project's public front door for automation,
and it now makes a true one that a build task keeps true. The guard's red→green behaviour was observed
on four distinct failure modes rather than assumed, `check` is green apart from a pre-existing
`checkCodebaseReference` staleness this branch demonstrably cannot have caused, and the page was read
in a browser rather than in a diff — which is how the one real defect in it got found and fixed.

Two things a reviewer should push back on if they disagree. (1) The added friction: a new transcript now
costs four edits instead of two. I think that is correct — the page is published and was 42 % wrong —
but the totals check is the droppable half if it proves irritating. (2) The entries quote numbers read
from transcript source, not from observed runs; a box could be changed to print something else and no
guard would notice. Closing that would mean capturing each transcript's output, which is a much larger
piece of work and belongs behind the docs-capture pipeline, not behind a text guard.
