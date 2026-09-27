# RectSL conversation — a visual score

A recorded conversation that talked an animated RectSL scene into existence, starting from the blank
`rectsl_conversation` document. Three AI4 turns (gpt-6-astra through OpenAI) and a hand edit by a
person, checked in from the scene's study card, recorded 2026-09-27 with the window running, so every
tool image came from its GPU. The public walkthrough is `public/docs/rectsl_conversation.md`.

| Step | What happened | Revision |
| --- | --- | --- |
| 01-score | Asked for a Fischinger-style score pulsing at 120 bpm in 4/4 with a stronger downbeat; one `EditRectSLStudy`: three rows keyed from one shared bar-long track. | r2 |
| 02-canon | Asked for the lower rows to answer the top a beat later; a patch delaying each lower row's looped track. | r3 |
| 02a (person) | Typed a warm black, `0x0e0a08`, over the pure black background in the editor; the window followed and the study reported the edit as unchecked. | r3 |
| 02b (person) | Pressed Check in on the study card. | r4 |
| 03-accent | Asked which line makes the downbeat flash, and for it to linger; a trace named the shared track, one number moved (0.22 → 0.34 s), and a comparison showed nothing else changed — the person's background included. | r5 |

To look at it: copy this folder into your workspace and open it; the window opens and plays the
scene, and the scene box shows its study card. The recorded chat is in the scene box's AI4 chat.
Continuing the chat adds to this document's history, which is why a copy is better than opening it
in place.

What is here:

- `conversations/` — each turn's prompt, transcript (`.md`) and full record (`.json`), the tool I/O
  AI4 logged for the turn (`tool-io/`), and the person-side actions (`*.human.json`). Transcript
  images are referenced as `images/<hash>`; the bytes are inside the saved chat
  (`rectsl_source_ai4_chat_history_.md`), and `tools/package_rectsl_conversation.py` extracts them.
- `studies/rectsl_source/` — every checked revision (`r<n>-<hash>.rectsl` plus its facts).
- `prompts/` — the requests, as sent.

`RectSLConversationExamplesTest` replays this document's revisions on the CPU reference, and checks
the last edit directly: only the downbeat's release moved.
