# Drop the stale 'slot goes AFTER the push block' comment in gpu_state's fence

- **Handoff:** `f0b8d65e-db9b-4914-9644-53cd918f7587` (project)
- **Filed from:** https://openendedgroup.com/field4/gpu_state/
- **Filed by:** human, 2026-10-17 03:13
- **Close with:** `python tools/docs_notes.py handoff-close f0b8d65e "what happened"`

`public/docs/gpu_state.md`, in the GLSL fence that declares the push-constant block, ends with:

    // The live slot, and it goes AFTER the push block, not before.
    {{ stack.auto.force }}

Since c870aced8 ("stack.auto: a slot written above the push-constant block is expanded after it"),
placement no longer matters, and the prose under the fence now says so. The comment is stale.

Fix: change the fence comment to something true (e.g. `// The live slot.`), keeping the fence's
capture and any `tools/completion-corpus/rank-carets.tsv` pin in step, and check the matching
transcript `dev_docs/agents/transcripts/gpu_state.yaml` if it carries the same comment.
