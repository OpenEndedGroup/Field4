# Canonicalise or reject hand-edited demo metadata.edn

- **Handoff:** `f2e4fedb-9c77-4dc0-9d86-c93fd00a63e3` (project)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/
- **Filed by:** human, 2026-10-15 18:35
- **Close with:** `python tools/docs_notes.py handoff-close f2e4fedb "what happened"`

Page `public/docs/agent_transcripts.md`, aside "Agents: editing a saved document":

> **Do not hand-edit a `metadata.edn`.** `AgentDemoDriftGuard` requires it to be byte-for-byte what
> Field's own EDN codec writes, so an edit that looks right comes back modified from the next
> regenerate, in every branch. Change the loose `<box>_code_.kt` sidecar, or change it in Field.

This is a hazard a tool can prevent. Fix: have the drift guard (or a pre-commit report) detect a hand-edited demo/starting-point `metadata.edn` and either canonicalise it through the EDN codec automatically or fail with a message naming the sidecar to edit instead. Delete the aside in the same change.
