# Return tool images over the MCP bridge

- **Handoff:** `d7c21120-568b-4005-a582-fcc5e507f83e` (project)
- **Filed from:** https://openendedgroup.com/field4/aiFeatures/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close d7c21120 "what happened"`

Rule 25 sharp edge on `public/docs/aiFeatures.md` ("Returning something the model can read — and see"):

> The exception is a tool called over the MCP backdoor: that reply is JSON, so it carries the numbers and not the picture. Put anything the model must not miss in the result as well as in the image.

Fix in code: have the MCP bridge return `CarriesToolImages` images as MCP image content blocks alongside the JSON text result. Delete the exception sentences in the same change.
