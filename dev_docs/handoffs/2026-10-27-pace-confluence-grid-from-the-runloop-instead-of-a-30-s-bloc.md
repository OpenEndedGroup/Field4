# Pace confluence_grid from the Runloop instead of a 30 s blocking sleep loop

- **Handoff:** `3b4e7126-6cce-47eb-bd78-526ab83e05f0` (project)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/
- **Filed by:** human, 2026-10-27 23:52
- **Close with:** `python tools/docs_notes.py handoff-close 3b4e7126 "what happened"`

`dev_docs/agents/transcripts/confluence_grid.yaml` drives its animation with a blocking `Thread.sleep` loop on the Runloop for well over 30 seconds. Since the runaway-box watchdog landed (2026-10-27, `BoxInterrupt.Watchdog`, `AFIELD_RUNLOOP_WATCHDOG_INTERRUPT_MS` default 30000), executing the transcript is interrupted mid-loop (`InterruptedException: sleep interrupted`), so any change that makes the lander execute it bounces in `checkBoxCodeCompiles` (seen on batch-20261027-232400-668459).

Fix in code: have the box advance its grid from the Runloop one step per frame (or on a background strand that publishes to the Three.js channel) instead of sleeping on the Runloop, so `execute` returns and the animation runs without holding a frame for 30 s; keep its `GRID3D DONE` check meaningful for `DemoTranscriptExecSmokeTest`. In the same change, drop the description's stale "**Pacing and panel size.** ... opens at 480 x 174 ..." sentences: the panel has honoured its requested size since 2026-09-18 (`adoptDeclaredSizeForFixedPanel`; measured 1460 x 872 for 1460 x 840 on 2026-10-27).
