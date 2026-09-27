# StackSL conversation — ink in amber water

A recorded conversation that talked a StackSL program into existence, starting from the blank
`stacksl_conversation` document. Four AI4 turns (gpt-6-astra through OpenAI) and two actions by a
person in the shader card, recorded 2026-09-26 with the window running, so every tool render came
from its GPU. (The study tools were a box in the document then; they are library code now.) The public walkthrough is `public/docs/stacksl_conversation.md`.

| Step | What happened | Revision |
| --- | --- | --- |
| 01-bloom | Asked for slow indigo ink blooming into amber water; one `EditStackSLStudy`, then a look at the live window. | r2 |
| 02-structure | Asked for ways to give the ink internal structure; three variants (veils, threads, warm channels), nothing published. | r2 |
| 02a (person) | Adopted variant B, fine threads, in the card. | r3 |
| 02b (person) | Moved knob 7, the drift rate, from `0.035t` to `0.06t` and pressed Keep. | r4 |
| 03-water | Asked for light moving through the water with the ink untouched; a patch with an exact ink guard, proved by a stage comparison at 0, 8, 20 and 40 s. | r5 |
| 04-export | Asked for a still and a clip; a 2048² still at 20 s and a 12 s clip from 20 s, 512 px at 24 fps, into `files/`. | r5 |

To look at it: copy this folder into your workspace and open it; the window opens and draws the
program, and the program box shows its study card. The recorded chat is in the program box's AI4 chat, and the card shows
the revision history. Continuing the chat adds to this document's history, which is why a copy is
better than opening it in place.

What is here:

- `conversations/` — each turn's prompt, transcript (`.md`) and full record (`.json`), the tool I/O
  AI4 logged for the turn (`tool-io/`), and the person-side actions (`*.human.json`). Transcript
  images are referenced as `images/<hash>`; the bytes are inside the saved chat
  (`stacksl_source_ai4_chat_history_.md`), and `tools/package_stacksl_conversation.py` extracts them.
- `studies/stacksl_source/` — every checked revision (`r<n>-<hash>.stacksl` plus its facts) and each variant
  batch the model offered (`speculations/`).
- `files/indigo-amber-clip.mp4` — the exported clip. The 2048² still is not committed; ask for it
  again, or export it from the card's revision.
- `prompts/` — the requests, as sent.

`StackSLConversationExamplesTest` replays this document on the CPU interpreter: every revision still
passes the check, the recorded comparison gives the same verdict, and each person-side action
produced the revision it records.
