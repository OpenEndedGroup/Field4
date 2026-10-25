# Iframe bridge screenshot should reveal an offscreen output area itself

- **Handoff:** `2dbfb699-b527-4def-8a59-56128fbabb92` (project)
- **Filed from:** https://openendedgroup.com/field4/ai4_live_iframe/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 2dbfb699 "what happened"`

`public/docs/ai4_live_iframe.md`, last paragraph, says:

> A page must be visible in the Field Electron app for these AI4 tools to reach it. For a screenshot, reveal the output area first if it is offscreen.

This is a workaround the reader has to remember (rule 25). Fix in code: have the `iframe_<name>_screenshot` tool (and the other bridge tools where possible) reveal/scroll the output area into view itself, or report a clear error telling the agent to do so, then delete the "reveal the output area first" sentence in the same change.
