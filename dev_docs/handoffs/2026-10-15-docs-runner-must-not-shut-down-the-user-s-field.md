# Docs runner must not shut down the user's Field

- **Handoff:** `1a9e7984-0a41-446e-8f0c-950807a3e550` (project)
- **Filed from:** https://openendedgroup.com/field4/staging/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 1a9e7984 "what happened"`

Rule 25 sharp edge on staging.md:

> Read what it says about ports before you start a build: the runner shuts down whatever Field is answering on its port, including the one you are working in.

Fix: the docs runner should refuse (or pick another port) when the Field on its port is not one it started, instead of shutting it down. Delete the sentence in staging.md (and the matching warning in building_and_testing.md) in the same change.
