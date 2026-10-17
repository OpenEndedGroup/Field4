# Make the seven FieldML bridge demos starting points

- **Handoff:** `082ceba5-b0ab-42f8-ba33-6c2469829fbf` (project)
- **Filed from:** https://openendedgroup.com/field4/fieldml_host_bridge/
- **Filed by:** human, 2026-10-10 03:11
- **Close with:** `python tools/docs_notes.py handoff-close 082ceba5 "what happened"`

`public/docs/fieldml_host_bridge.md` ("Open it") links seven saved documents as `offline.html?file=...`
plus "or open `public/docs/assets/<name>/metadata.edn` from a Field workspace". Rule 19 says a runnable
document is a starting point on the welcome screen, linked as `agent_transcripts.md#<name>`, never a
workspace path.

The seven (`fieldml_macro_demo`, `fieldml_host_bridge_demo`, `fieldml_afield_four_demo`,
`fieldml_frames_demo`, `fieldml_macro_module_demo`, `fieldml_mesh_demo`, `fieldml_lambda_demo`) are all
in `AgentDemoRegistrationTest.ASSET_DOCUMENTS_NOT_YET_STARTING_POINTS`.

Do: register each as a starting point (the GPU ones with the launch ops they need), remove them from
the backlog set, and replace the workspace-path sentences on the page with starting-point links.
Keep or drop the `offline.html` links as `tools/check_doc_offline_links.py` requires.
