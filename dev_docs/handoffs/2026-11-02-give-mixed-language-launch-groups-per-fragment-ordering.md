# Give mixed-language launch groups per-fragment ordering

- **Handoff:** `8fd4cbe6-d2c8-41dc-a7c6-687120a169d5` (project)
- **Filed from:** https://openendedgroup.com/field4/running_code/
- **Filed by:** human, 2026-11-02 10:32
- **Close with:** `python tools/docs_notes.py handoff-close 8fd4cbe6 "what happened"`

running_code.md, "The `//%-` fragment break", now in an `Agents:` aside:

> A single Kotlin box launched through the transcript/multi-box path is also awaited fragment by fragment; mixed-language launch groups do not promise the same first-frame ordering across boxes.

This is a rule-25 sharp edge: a launch group that mixes languages has weaker ordering than a single Kotlin box. Fix it in code: have the multi-box launch path (OperationTranscript `launch-batch`) wait fragment by fragment for every box in the group, whatever its language, or reject a mixed group that relies on that ordering. Delete the aside in the same change.
