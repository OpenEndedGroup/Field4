# Docs runner: refuse instead of shutting down a Field it did not start

- **Handoff:** `f316bf81-a427-42ad-9696-ade7fae48d70` (project)
- **Filed from:** https://openendedgroup.com/field4/building_and_testing/
- **Filed by:** human, 2026-10-15 13:51
- **Close with:** `python tools/docs_notes.py handoff-close f316bf81 "what happened"`

Rule 25 sharp edge. The page warns: "The runner owns `FIELD_DOCS_RUNNER_PORT` outright... if something is already answering on that port it posts a shutdown request to it ... Aim a build at the Field you are working in and you lose that session."

Fix in `tools/build_zensical_docs.py` / `public/docs_hooks.py`: when the port is held by a server the runner did not start (identity check), fail with a message naming the PID and port, or pick the next free port, instead of posting a shutdown. Delete the paragraph in the same change.
