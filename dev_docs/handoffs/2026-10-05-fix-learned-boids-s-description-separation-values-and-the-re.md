# Fix learned_boids's description: separation values and the re-run claim

- **Handoff:** `5e140c17-1ccb-4008-8057-d0bb8d0233e7` (project)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/
- **Filed by:** human, 2026-10-05 14:53
- **Close with:** `python tools/docs_notes.py handoff-close 5e140c17 "what happened"`

Found by the box-code revision pass.

`dev_docs/agents/transcripts/learned_boids.yaml`, `description:`: its things-to-try list gives separation `0.0015 / 3 / 200`, but `lb_written` and `lb_train` both use `0.02f`. It also says re-running the training box catches the learned flock up; `lb_learned` builds its flock inside `once.learned`, so a retrain probably does not reach it until `once` is reset. Check that in a live Field (it needs a GPU) and either fix the description or make the learned box pick up a retrained rule.
