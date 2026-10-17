# Reject capture=native-window with video= instead of recording the browser

- **Handoff:** `a1adafe8-1b74-4f04-b293-63bbeabac33d` (project)
- **Filed from:** https://openendedgroup.com/field4/capture_limits/
- **Filed by:** human, 2026-10-15 13:51
- **Close with:** `python tools/docs_notes.py handoff-close a1adafe8 "what happened"`

Rule 25 sharp edge. The page: "A docs fence that asks for `capture=\"native-window\"` together with `video=` is accepted, falls through to the browser recorder, and silently records the wrong window."

Fix in `public/docs_hooks.py`: fail the fence with a clear error (or wire it to `Window.raiseAndRecord` on hosts with Screen-Recording permission). Delete that sentence in the same change.
