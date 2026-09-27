# StackSL conversation — an aurora over hills

A recorded conversation that talked an animated StackSL program into existence, starting from the
blank `stacksl_conversation` document. Five AI4 turns (gpt-6-astra through OpenAI) and one action by
a person, recorded 2026-09-26 with the window running, so every tool render came from its GPU. The
public walkthrough is `public/docs/stacksl_conversation.md`.

| Step | What happened | Revision |
| --- | --- | --- |
| 01-aurora | Asked for a green-to-violet aurora over dark hills with faint stars; an edit, a trace with a probe at one point, a patch, and a look at the window. | r3 |
| 02-still-hills | Asked that the hills stay perfectly still and black; a diagnostic variant found no motion below the ridge but blue-black hills; a patch to exact black, a stage comparison, and a second diagnostic variant. | r4 |
| 03-mound | Asked what the dark mound under the curtain was; diagnostic variants *sky-only* and *cutoff* showed it was the curtain's lower-edge mask, and the model adopted its *open-sky* fix. | r5 |
| 04-folds | Asked for fabric-like vertical folds; three variants (fine pleats, broad drapes, gathered silk). | r5 |
| 04a (person) | Adopted variant C, gathered silk, in the card. | r6 |
| 05-slower | Asked for half speed with everything else unchanged, and a measurement; three time rates halved with `SetStackSLNumbers`, mean frame change 0.693 → 0.348. | r7 |

To look at it: copy this folder into your workspace and open it; the window opens and draws the
program, and the program box shows its study card. The recorded chat is in the program box's AI4 chat. Continuing the chat
adds to this document's history, which is why a copy is better than opening it in place.

What is here:

- `conversations/` — each turn's prompt, transcript (`.md`) and full record (`.json`), the tool I/O
  AI4 logged for the turn (`tool-io/`), and the person-side action (`*.human.json`). Transcript
  images are referenced as `images/<hash>`; the bytes are inside the saved chat
  (`stacksl_source_ai4_chat_history_.md`), and `tools/package_stacksl_conversation.py` extracts them.
- `studies/stacksl_source/` — every checked revision and each variant batch the model offered, diagnostics
  included (`speculations/`).
- `prompts/` — the requests, as sent.

`StackSLConversationExamplesTest` replays this document on the CPU interpreter, and checks the half
speed directly: r7 at 2t matches r6 at t.
