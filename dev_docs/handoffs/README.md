# Handoffs from the documentation passes

Each file here is a task that an agent, working through the notes on one documentation page,
judged too big for a docs edit. It is either a **project** (a code change, a new tool, a bug in
Field, a capture that needs the runtime fixed) or **crosscutting** (merging, splitting, moving or
renaming pages, a navigation or terminology change across pages, a concern that recurs). The
agent wrote the file; `tools/docs_notes.py handoff` added the header (id, scope, whether it
blocks, the page, the notes it came from, and their quotes).

These are meant for a separate pass, with a person in the loop, not for the parallel page agents.

```bash
# the queue: blocking handoffs first, with how many notes each is holding
uv run python tools/docs_notes.py handoffs

# someone is on it (it keeps blocking)
uv run python tools/docs_notes.py handoff-close <id> --state taken "started in branch X"

# finished: releases every note it was holding back into the page queue
uv run python tools/docs_notes.py handoff-close <id> "merged pages in 1234abc; notes can proceed"

# abandoned: releases them too
uv run python tools/docs_notes.py handoff-close <id> --state dropped "not worth it, because ..."
```

A **blocking** handoff holds every open note on its page, including ones written after it was
filed, so the page is not polished while its future is still being decided. Every handoff holds
the notes it came from. When it closes, those notes return to the queue and the next
`docs_notes.py revise` brings the page in line with what was done. The closing sentence appears in
that page's brief.

To start one in an interactive session, open the file and hand it over as it stands, for example
`claude "Read dev_docs/handoffs/<file>.md and let's work on it"`. The document already holds the
evidence the page agent collected and the questions it could not answer alone.

Keep a handoff's file after it closes, because the page's brief lists it along with its closing
sentence. Delete the file once the work it describes has landed and nothing refers to it.
