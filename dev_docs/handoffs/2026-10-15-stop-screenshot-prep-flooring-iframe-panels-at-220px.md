# Stop screenshot prep flooring iframe panels at 220px

- **Handoff:** `047fa24e-73de-419f-895a-64f65e0d2522` (project)
- **Filed from:** https://openendedgroup.com/field4/output_panel_sizing/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 047fa24e "what happened"`

Rule 25 sharp edge on public/docs/output_panel_sizing.md:

> Both frames in that picture are about two hundred pixels tall, though their markup says 66. That is the screenshot-prep pass, which floors *any* panel containing an iframe at 220 pixels ... before the picture is taken, so a panel in a docs figure can differ from the one you see in Field.

Docs figures should show what the user sees. Fix `fitOutputDisclosuresForScreenshot` in `src/jsMain/kotlin/afield/app/client/Main.kt` so it does not override heights an iframe declares (floor only frames with no concrete height), recapture `assets/output_panel_sizing/fill-vs-fixed.png` (and update its alt text, which says the frames are far taller than 66px), and delete this paragraph plus the matching sentence in "The screenshot pass is a second sizer" in the same change.
