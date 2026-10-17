# Native window capture: name the stage that timed out

- **Handoff:** `69982d56-c849-4636-81c1-030ad2e36a5e` (project)
- **Filed from:** https://openendedgroup.com/field4/capture_limits/
- **Filed by:** human, 2026-10-15 13:51
- **Close with:** `python tools/docs_notes.py handoff-close 69982d56 "what happened"`

Rule 25 sharp edge. The page: "One budget, 15 seconds by default, covers renderer readiness, settling *and* the copy ... When it expires you get \"Timed out waiting for native renderer\" and no information about which stage ate it." The section "Telling a timed-out capture from a broken subject" is a manual workaround for the same gap.

Fix in `afield/tools/docs/doc.kt` / backdoor native capture: report which stage (renderer readiness, settle frames, swapchain copy) was in progress and how many frames were presented when the deadline hit. Delete the "no information about which stage" sentence in the same change.
