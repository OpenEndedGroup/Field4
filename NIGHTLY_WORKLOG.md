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

## Execution log

(filled in below as work lands)
