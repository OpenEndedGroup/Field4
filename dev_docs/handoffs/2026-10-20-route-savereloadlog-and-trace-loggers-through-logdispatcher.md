# Route SaveReloadLog and trace loggers through LogDispatcher

- **Handoff:** `49051176-861d-4802-a884-739996775426` (project)
- **Filed from:** https://openendedgroup.com/field4/logging/
- **Filed by:** human, 2026-10-20 11:11
- **Close with:** `python tools/docs_notes.py handoff-close 49051176 "what happened"`

Sharp edge (rule 25) on public/docs/logging.md, "Where the lines end up":

> Two loggers sit outside this layer. `SaveReloadLog` writes save and reload timings straight to `~/.afield/logs/save_reload.log` without touching the dispatcher, so it is invisible to every sink above. `TraceLogger` and `AiToolIoLogger` write AI request traces ...

Fix in code: route SaveReloadLog (and the trace loggers' per-request lines) through `LogDispatcher` on a channel (e.g. `info,persist,savereload`) as well as their files, so sinks and the log widget see them. Delete the paragraph in the same change.
