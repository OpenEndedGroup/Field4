# Route Patchy's own Save/Save As into the Field document (needs a Patchy-side hook)

- **Handoff:** `959292d9-7da9-4f76-adbd-5f21148390cf` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/patchy/
- **Filed by:** human, 2026-10-16 01:58
- **Close with:** `python tools/docs_notes.py handoff-close 959292d9 "what happened"`

Refiled from db16218d-8196-4767-97ad-00ef2c36f848, 2026-10-16. The "still downloads through the browser" sentence on `patchy.md` stands.

Field's side is ready. `src/commonMain/resources/patchy_field.js` already saves into the document's file through `/api/patchy-document/<binding>`: it drives `execute_script` / `saveAs('/field/painting.psd')` and posts the bytes. What's missing is a way to make Patchy's *own* Save / Save As menu items call that path instead of downloading. That needs a hook in Patchy itself: for example a host-provided save handler Patchy calls when it is embedded, or an event Field can intercept over the existing JSON-RPC bridge. Patchy is a separate project (`~/.field4/patchy-web/source`, its own git repo), and its web build (`~/.field4/patchy-web/site`, or `AFIELD_PATCHY_WEB_ROOT`) isn't installed on this machine, so none of this could be run here.

Next: add the embedder save hook in Patchy, then have `patchy_field.js` register a handler that runs `saveIfChanged`'s write path; delete the sentence.
