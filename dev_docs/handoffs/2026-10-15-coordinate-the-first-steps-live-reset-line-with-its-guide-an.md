# Coordinate the first-steps live reset line with its guide and recording

- **Handoff:** `d6dc958a-e40a-4067-9f1b-7e2e33c16a2f` (project)
- **Filed from:** https://openendedgroup.com/field4/first_steps/
- **Filed by:** human, 2026-10-15 15:10
- **Close with:** `python tools/docs_notes.py handoff-close d6dc958a "what happened"`

The box-code revision rehearsal proved a useful live-edit gesture for the first moving drawing:

```kotlin
var petals = 5
petals = 5 // Change this 5 to 8, then Alt+Enter this line while the drawing runs.
```

The actual editor Alt-Enter test showed declaration editing left the running loop's captured value at 5, while assignment editing changed that same binding to 8. Phase continued advancing in both cases. Rerunning retained one active job, and stopping released it. See `dev_docs/agents/box_code_revision_pilot/README.md`, the candidate diff and runtime evidence alongside it.

The candidate has not been applied to the shipped template. `public/docs/first_steps.md` explicitly tells the reader to edit `var petals = 5` and relaunch. Its `first-look` capture driver selects that declaration and checks the inline value eight. Adding an assignment of five beneath the declaration would override that edit on a whole-box relaunch.

Revise the example as one group: the template sidecar, its metadata through Field's codec, the quoted code in `dev_docs/agents/transcripts/docs_capture/first_steps.yaml`, the guide instructions and driver, and the real first-look recording. Keep the launch/stop/reopen lesson understandable while introducing the assignment as the live edit point. Do not substitute the rehearsal stills for the guide's recording; recapture the real workflow after updating it.
