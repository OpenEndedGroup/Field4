# Publish the MQTT and OSCQuery demos as starting points and link them

- **Handoff:** `51779f85-8411-4854-8935-65711b461c10` (project)
- **Filed from:** https://openendedgroup.com/field4/mqtt/
- **Filed by:** human, 2026-10-08 09:10
- **Close with:** `python tools/docs_notes.py handoff-close 51779f85 "what happened"`

`mqtt.md` and `oscquery.md` each send the reader to a workspace path (`public/docs/assets/mqtt_demo/`, `public/docs/assets/oscquery_demo/`) for their demo documents. Rule 19: a runnable document is a starting point on the welcome screen, linked from the page. Publish both demos as starting points and replace the path sentences (near the top of each page) with links. Both pages also have only static fences on the OSCQuery side; consider whether the oscquery demo can supply a runner capture of the published parameter list.
