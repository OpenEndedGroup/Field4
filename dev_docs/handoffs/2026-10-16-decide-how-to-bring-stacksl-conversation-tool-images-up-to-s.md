# Decide how to bring StackSL conversation tool images up to stored-value stages

- **Handoff:** `87b578d4-4d77-414b-92c4-debbbe9471dc` (project)
- **Filed from:** https://openendedgroup.com/field4/stacksl_conversation/
- **Filed by:** human, 2026-10-16 09:35
- **Close with:** `python tools/docs_notes.py handoff-close 87b578d4 "what happened"`

Follow-up to handoff 934c7d1a (re-record StackSL conversation turns that predate stored-value stages), refiled by the 2026-10-16 handoff session as a decision rather than done.

The brief offers two routes, and each one is a choice the session could not make alone:

1. **Re-record** the tiles `03-recolour` turn and the aurora first turn with `tools/conversation_demo.py record`. That is a fresh paid model turn from a restored study revision, and it is not deterministic. The tiles turns `03a-edit`, `04-grout` and `04a-checkin` were recorded on top of 03's result, and `public/docs/stacksl_conversation.md` quotes 03's specifics: the stage-20 comparison, the recolour listing between the `stacksl-study-source:tiles-recolour` markers, and the "petal and hub field" caption. A re-recorded 03 that answers differently means re-recording the rest of the session and rewriting that section too.
2. **Regenerate the tool images from the saved revisions** (the `conversations/tool-io/*.jsonl` logs keep each call for this purpose). `conversation_demo.py` has no replay command yet. More importantly, splicing a replayed image into a recorded turn shows a picture the model never saw next to the model's reply to the old one. Under the never-fake-captures rule that needs at least a disclosed aside, and possibly it is not allowed at all.

Decide: (a) re-record the two sessions end to end, accepting new prose; (b) write a `replay` command and show the replayed images *beside* the recorded ones, labelled as a replay; or (c) keep the paragraphs. Paragraphs to delete under (a) or (b): "A stage comparison now handles this case itself…" (the tiles section), "In this recording, lines that end in a store show only the program's input…" (the aurora section), and the "one kind of result has changed since" sentence in the aside "How the sessions were recorded and tested".
