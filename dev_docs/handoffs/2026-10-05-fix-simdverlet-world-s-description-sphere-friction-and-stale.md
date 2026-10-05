# Fix simdverlet_world's description: sphere friction and stale numbers

- **Handoff:** `526f4c63-3c3d-47d7-97f3-878ae626140d` (project)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/
- **Filed by:** human, 2026-10-05 14:53
- **Close with:** `python tools/docs_notes.py handoff-close 526f4c63 "what happened"`

Found by the box-code revision pass (dev_docs/agents/BOX_CODE_REVISION_PASS.md).

`dev_docs/agents/transcripts/simdverlet_world.yaml`, `description:`: it gives `friction = 0.9` for Box 1's sphere, but `world_drape` sets the sphere to `0.75` (0.9 is the floor's). Its quoted numbers (centroid 0.789 against -32.2, a 10 x 10 sheet) may be from an earlier version; the box now builds a 22 x 22 sheet. Re-run the box (`DEMO_TRANSCRIPT_ONLY=simdverlet_world`, `AFIELD_DEMO_TRANSCRIPT_VERBOSE=1`), and make the description match what it prints. Check `public/docs/agent_transcripts.md`'s entry quotes the same numbers.
