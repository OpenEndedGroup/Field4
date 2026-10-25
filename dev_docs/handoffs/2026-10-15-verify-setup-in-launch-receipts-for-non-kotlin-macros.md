# Verify setup in launch receipts for non-Kotlin macros

- **Handoff:** `7902edcc-7658-4345-8a4d-e1907ad48b12` (project)
- **Filed from:** https://openendedgroup.com/field4/aiFeatures/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 7902edcc "what happened"`

Rule 25 sharp edge on `public/docs/aiFeatures.md` ("Source edits, captures and launch receipts"):

> Macros in other languages run as before, and their receipts say `setupVerified: false` with a message that completion was not checked.

Fix in code: verify setup completion for non-Kotlin engines in AI4 macro launch receipts (the backdoor already gets receipts from every engine, per the next sentence), so receipts are uniform. Delete this sentence in the same change.
