# Make ai_tool_demo a starting point and link it from aiFeatures

- **Handoff:** `6c5d2bad-4b8e-4457-8ab6-f09dc4b5f33c` (project)
- **Filed from:** https://openendedgroup.com/field4/aiFeatures/
- **Filed by:** human, 2026-10-11 12:40
- **Close with:** `python tools/docs_notes.py handoff-close 6c5d2bad "what happened"`

`public/docs/aiFeatures.md` ("A worked example you can run") tells the reader there is a loadable
document at the workspace path `public/docs/assets/ai_tool_demo/metadata.edn`, and points at
`public/docs/assets/ai_tool_demo/session.md` by path. DOCS_WRITING_RULES rule 19 says a runnable
document is a starting point on the welcome screen, linked as `agent_transcripts.md#<name>`.

Register `ai_tool_demo` as a starting point (or confirm it is on the AgentDemoRegistrationTest
backlog and remove it from there), then replace the two paths on the page with a link to the
starting point and a source link to session.md.
