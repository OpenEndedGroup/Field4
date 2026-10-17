# Rename 'code-use telemetry' in UI strings to 'local code-use history'

- **Handoff:** `cafacbd7-8d01-4a35-9630-40833de0c2c1` (project)
- **Filed from:** https://openendedgroup.com/field4/privacy/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close cafacbd7 "what happened"`

`public/docs/privacy.md` has to explain a second name for one thing:

> **Local code-use history** (called "code-use telemetry" in some status messages) is an on-by-default SQLite history ...

"Telemetry" also misleads: it implies an upload, which the same paragraph then has to deny. The UI strings in `src/commonMain/resources/welcome.html` and `src/commonMain/resources/code-use.html` (and any Kotlin status/log messages naming "code-use telemetry") should say "local code-use history". In the same change, delete the parenthetical "(called "code-use telemetry" in some status messages)" from privacy.md.
