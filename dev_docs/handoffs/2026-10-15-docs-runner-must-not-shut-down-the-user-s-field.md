# Docs runner must not shut down the user's Field

- **Handoff:** `1a9e7984-0a41-446e-8f0c-950807a3e550` (project)
- **Filed from:** https://openendedgroup.com/field4/staging/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 1a9e7984 "what happened"`

Rule 25 sharp edge on staging.md:

> Read what it says about ports before you start a build: the runner shuts down whatever Field is answering on its port, including the one you are working in.

Fix: the docs runner should refuse (or pick another port) when the Field on its port is not one it started, instead of shutting it down. Delete the sentence in staging.md (and the matching warning in building_and_testing.md) in the same change.

## Also filed from other pages (merged duplicates)

The same fix removes these paragraphs too; delete each in the same change.

### f316bf81 — Docs runner: refuse instead of shutting down a Field it did not start

Filed from https://openendedgroup.com/field4/building_and_testing/.

Rule 25 sharp edge. The page warns: "The runner owns `FIELD_DOCS_RUNNER_PORT` outright... if something is already answering on that port it posts a shutdown request to it ... Aim a build at the Field you are working in and you lose that session."

Fix in `tools/build_zensical_docs.py` / `public/docs_hooks.py`: when the port is held by a server the runner did not start (identity check), fail with a message naming the PID and port, or pick the next free port, instead of posting a shutdown. Delete the paragraph in the same change.
