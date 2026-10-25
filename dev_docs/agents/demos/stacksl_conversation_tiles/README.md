# StackSL conversation — zellige tiles

A recorded conversation that talked a still StackSL program into existence, starting from the blank
`stacksl_conversation` document. Four AI4 turns (gpt-6.1-sol through OpenAI) and three actions by a
person, recorded 2026-10-16 with the window running, so every tool render came from its GPU. The
public walkthrough is `public/docs/stacksl_conversation.md`.

| Step | What happened | Revision |
| --- | --- | --- |
| 01-zellige | Asked for eight-fold star rosettes in cobalt and white with saffron and thin dark grout, no animation; one `EditStackSLStudy` built from `squaremax` and `starfield` definitions. | r2 |
| 02-symmetry | Asked to see 6, 8, 10 and 12 points side by side; four variants in one row, nothing published. | r2 |
| 02a (person) | Adopted **10 points** in the card. | r3 |
| 03-recolour | Asked for terracotta, cream and deep green with the geometry unchanged, and proof; fifteen colour numbers set with `SetStackSLNumbers`, then five stage comparisons, each at a line ending in a store (`>tile`, `>star`, `>edge`, `>octagon`, `>corner`), all identical. | r4 |
| 03a (person) | Widened the star grout by hand, `0.006 0.009` to `0.012 0.015`, without checking it in. | r4, dirty |
| 04-grout | Asked the model to keep that edit and check the small green centres; it read the unchecked edit, rendered it beside r4, and made no change. | r4, dirty |
| 04a (person) | Pressed Check in on the card. | r5 |

The first recording (2026-09-26, gpt-6-astra) compared a stage ending in `>joint` and saw only the
program's input; that is why a stage that ends in a store now compares the value it stored. This
session was recorded again after that change. Its 04 prompt says "green centres" where the first
said "green diamonds", because this recolour put the green in the centres.

To look at it: copy this folder into your workspace and open it; the window opens and draws the
program, and the program box shows its study card. The recorded chat is in the program box's AI4 chat. Continuing the chat
adds to this document's history, which is why a copy is better than opening it in place.

What is here:

- `conversations/` — each turn's prompt, transcript (`.md`) and full record (`.json`), the tool I/O
  AI4 logged for the turn (`tool-io/`), and the person-side actions (`*.human.json`). Transcript
  images are referenced as `images/<hash>`; the bytes are inside the saved chat
  (`stacksl_source_ai4_chat_history_.md`), and `tools/package_stacksl_conversation.py` extracts them.
- `studies/stacksl_source/` — every checked revision and each variant batch the model offered (`speculations/`).
- `prompts/` — the requests, as sent.

`StackSLConversationExamplesTest` replays this document on the CPU interpreter, including all three
recorded comparisons.
