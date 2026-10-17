# Make the live RectSL SDF transcript a starting point

- **Handoff:** `ac78d31f-8790-40ee-95bf-df7db2945fe2` (project)
- **Filed from:** https://openendedgroup.com/field4/rectsl_cheatsheet/
- **Filed by:** human, 2026-10-10 15:11
- **Close with:** `python tools/docs_notes.py handoff-close ac78d31f "what happened"`

`rectsl_cheatsheet.md`, "The looks, rendered", tells the reader to "replay `dev_docs/agents/transcripts/rectsl_sdf_live.yaml` into a fresh document" to see the SDF scene live. That is a repo path a user cannot open. Register the transcript as a starting point (as the other demos are, see AgentDemoRegistrationTest) and replace the sentence with a link to it. While there, the "Fidelity" section speaks of build guards (`1e-5`, shaderc in the build, `RECTSL_SDF_AGREEMENT.md`); consider moving the guard talk into a `??? aside`.
