# Move gpu_state backdoor recipe into an Agents aside

- **Handoff:** `48d0f545-565a-403a-b010-a2bef42f2bc1` (project)
- **Filed from:** https://openendedgroup.com/field4/gpu_state/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 48d0f545 "what happened"`

Rule 26 on `public/docs/gpu_state.md`, "Open it": the backdoor replay recipe

    python3 tools/field_backdoor.py --port 9483 --graphics-context --command "transcript write gpustate ..." --command "transcript run gpustate"

is addressed to agents, not readers, and uses bare `python3` where AGENTS.md says `uv run --project . --group backdoor python`. Moving it into a `??? aside "Agents: replaying the transcript"` would indent the byte-pinned fence, so: move the sentence and fence into that aside, update the command to the uv form, and update `tools/completion-corpus/rank-carets.tsv` pins if they cover this fence.
