# Remove the history from frames_lifecycle's description

- **Handoff:** `d4c65269-9f22-4ac9-ba42-b595dab2e714` (project)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/
- **Filed by:** human, 2026-10-05 14:53
- **Close with:** `python tools/docs_notes.py handoff-close d4c65269 "what happened"`

Found by the box-code revision pass, which only edits code blocks.

`dev_docs/agents/transcripts/frames_lifecycle.yaml`, `description:`: it still narrates how the boxes came to be (the history the pass took out of the code comments, e.g. why case 1 once printed only the `finally` line). Rewrite it for a reader using the starting point, per dev_docs/agents/DOCS_WRITING_RULES.md.
