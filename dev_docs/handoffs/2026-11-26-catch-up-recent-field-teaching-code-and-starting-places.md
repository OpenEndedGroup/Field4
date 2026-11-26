# Catch up recent Field teaching code and starting places

- **Handoff:** `4ef4d21c-951c-4d04-a631-1423a5588e0b` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/
- **Filed by:** human, 2026-11-26 17:16
- **Close with:** `python tools/docs_notes.py handoff-close 4ef4d21c "what happened"`

**Recommended session model:** `gpt-6.2-sol`. Take this as a bounded maintenance pass in the Field docs handoff queue. Refile a successor after this pass lands if the team wants another round.

Review teaching code shipped since the last landed catch-up. Start with the Nov 26 pass on `codex/docs-code-catchup-nov26` and its [scope, harness and receipts](../agents/box_code_catchup_20261126/README.md). Find the actual landing on local `main` and use the frozen source head in its `scope.json` to identify new work, subtracting code already reviewed or changed by the landing itself. Include changes brought in during the final merge if they were outside that frozen scope. If the predecessor is still queued, use its checkpoint and avoid overlapping it.

1. Read the [house style](../agents/BOX_CODE_HOUSE_STYLE.md), [revision guide](../agents/BOX_CODE_REVISION_PASS.md) and [helper decisions](../agents/BOX_CODE_HELPER_IDEAS.md). Seed an isolated worktree from local `main` using the repo guide. Freeze the changed authored bodies in docs, transcripts, demos and templates, deduplicate copies, and record the baseline and head. Leave generated preambles, fragment markers and recorded study versions untouched. Classify code by its actual box language, including non-Kotlin code stored in `.kt` sidecars.
2. Make the examples easier to change live. Put the creative inputs and mapping where the reader can find them. Use named inherited properties, collection literals where inference works, Markdown readouts and FLines for small drawings. Keep comments about intent, units and ownership. Avoid redundant declaration/reset pairs. Keep real library algorithms and link important delegated examples to the class and methods in the generated reference or its source mirror. File separate proposals for new shared APIs.
3. Use Field's import formatter, ktfmt and GLSL formatter on the selected source. Keep imports the actual box needs. Run meaningful defaults and edited values through Field, exercise shader reload when shader behavior changes, and inspect real output. Batch by renderer or environment. Recapture changed presentation through the docs runner and record any hardware-specific checks that remain. Sync explainer mirrors and the scraped completion corpus when affected, regenerate starting places and refresh only stale publications.
4. Record coverage, checked scenarios, capture paths, remaining work and the next baseline in a durable checkpoint. Small batches and the existing receipts make a restart cheap after an interruption. Run the built-site link gate and landing precheck. Enqueue via `tools/land.py` and check the result. Repair bounces through `tools/merge_regen.py` and the lander. Close this handoff with the local-main landing SHA, any deferred work, and five short before/after highlights.

One pass is done when every frozen body has a disposition, changed examples have real checks or an explicit handoff, and the branch has landed. This task leaves the cadence and the next run's trigger to the Field task queue operator.
