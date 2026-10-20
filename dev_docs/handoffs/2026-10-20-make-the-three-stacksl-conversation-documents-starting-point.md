# Make the three StackSL conversation documents starting points

- **Handoff:** `fa02f501-5de4-4f63-b8f6-228a17055c22` (project)
- **Filed from:** https://openendedgroup.com/field4/stacksl_conversation/
- **Filed by:** human, 2026-10-20 11:10
- **Close with:** `python tools/docs_notes.py handoff-close fa02f501 "what happened"`

`public/docs/stacksl_conversation.md` ("The saved conversations") tells readers:

> The three documents ship with Field, under `dev_docs/agents/demos/stacksl_conversation_ink`,
> `…_tiles` and `…_aurora`. ... Copy one into your workspace rather than opening it in place, because
> continuing the conversation adds to its history:

followed by a shell copy fence. Rule 19: a runnable document is a starting point on the welcome
screen, linked as `agent_transcripts.md#<name>`, never a workspace path; `agent_transcripts.md` has
no entry for any of the three. Make the three documents welcome-screen starting points (opening a
starting point already copies it, which retires the copy step), shrink the
`AgentDemoRegistrationTest` backlog if they are on it, and in the same change replace the paragraph
and the copy fence with links to the three `agent_transcripts.md` anchors.
