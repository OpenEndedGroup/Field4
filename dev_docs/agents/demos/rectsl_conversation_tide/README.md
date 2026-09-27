# RectSL conversation — a tide under a rising moon

A recorded conversation that talked an animated RectSL scene into existence, starting from the blank
`rectsl_conversation` document. Three AI4 turns (gpt-6-astra through OpenAI) and one action by a
person in the scene's study card, recorded 2026-09-27 with the window running, so every tool image
came from its GPU. The public walkthrough is `public/docs/rectsl_conversation.md`.

| Step | What happened | Revision |
| --- | --- | --- |
| 01-tide | Asked for a tide of thin bars rising and falling in a wave travelling left to right; one `EditRectSLStudy`: 80 bars whose heights come from one phase track, offset per bar and passed through `sin`. | r2 |
| 02-moon | Asked for a moon rising on the right with a shimmer on the water; a patch adding a moon of 64 horizontal slices and 30 shimmer strokes that follow the water's height. | r3 |
| 02a (person) | Moved knob 22, the moon's width factor, from 1.3 to 1.8 with its slider in the card and pressed Keep. | r4 |
| 03-faster | Asked for the wave twice as fast with the moon and shimmer untouched, shown; one number changed, and two comparisons found the 64 moon slices and 30 shimmer strokes unchanged at nine times. | r5 |

To look at it: copy this folder into your workspace and open it; the window opens and plays the
scene, and the scene box shows its study card. The recorded chat is in the scene box's AI4 chat.
Continuing the chat adds to this document's history, which is why a copy is better than opening it
in place.

What is here:

- `conversations/` — each turn's prompt, transcript (`.md`) and full record (`.json`), the tool I/O
  AI4 logged for the turn (`tool-io/`), and the person-side action (`*.human.json`). Transcript
  images are referenced as `images/<hash>`; the bytes are inside the saved chat
  (`rectsl_source_ai4_chat_history_.md`), and `tools/package_rectsl_conversation.py` extracts them.
- `studies/rectsl_source/` — every checked revision (`r<n>-<hash>.rectsl` plus its facts).
- `prompts/` — the requests, as sent.

`RectSLConversationExamplesTest` replays this document's revisions on the CPU reference, and checks
the last edit directly: the moon and shimmer at r5 are the moon and shimmer at r4.
