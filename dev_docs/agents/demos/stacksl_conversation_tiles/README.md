# StackSL conversation — zellige tiles

A recorded conversation that talked a still StackSL program into existence, starting from the blank
`stacksl_conversation` document. Four AI4 turns (gpt-6-astra through OpenAI) and three actions by a
person, recorded 2026-09-26 with the window running, so every tool render came from its GPU. The
public walkthrough is `public/docs/stacksl_conversation.md`.

| Step | What happened | Revision |
| --- | --- | --- |
| 01-zellige | Asked for eight-fold star rosettes in cobalt and white with saffron and thin dark grout, no animation; one `EditStackSLStudy` built from `wedge`, `star` and `octagon` definitions. | r2 |
| 02-symmetry | Asked to see 6, 8, 10 and 12 points side by side; four variants in one row, nothing published. | r2 |
| 02a (person) | Adopted **10 points** in the card. | r3 |
| 03-recolour | Asked for terracotta, cream and deep green with the geometry unchanged, and proof; a patch to five colour literals, then three stage comparisons. The first, at a line ending in `>joint`, compared only the program's input; the other two compared real fields and were identical. | r4 |
| 03a (person) | Widened the grout by hand, `0.003 0.005` to `0.007 0.009`, without checking it in. | r4, dirty |
| 04-grout | Asked the model to keep that edit and check the small diamonds; it read the unchecked edit, looked at the live window, and made no change. | r4, dirty |
| 04a (person) | Pressed Check in on the card. | r5 |

The first comparison in 03-recolour is why a stage that ends in a store now compares the value it
stored, and why `CompareStackSLStudy` reports `stageIsProgramInput` and marks a comparison of the
input alone `vacuous`.

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
