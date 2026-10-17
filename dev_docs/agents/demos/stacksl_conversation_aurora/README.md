# StackSL conversation — an aurora over hills

A recorded conversation that talked an animated StackSL program into existence, starting from the
blank `stacksl_conversation` document. Five AI4 turns (gpt-6.1-sol through OpenAI) and one action by
a person, recorded 2026-10-16 with the window running, so every tool render came from its GPU. The
public walkthrough is `public/docs/stacksl_conversation.md`.

| Step | What happened | Revision |
| --- | --- | --- |
| 01-aurora | Asked for a green-to-violet aurora over dark hills with faint stars; one `EditStackSLStudy`, checked at 0, 6 and 12 s. | r2 |
| 02-still-hills | Asked that the hills stay perfectly still and black; found them still but blue-black, and patched them to exact black below the hill line. | r3 |
| 03-mound | Asked what the dark mound under the curtain was; a trace (store lines shown by the value they stored) showed the aurora's abrupt lower cutoff at line 12, and a patch replaced it with a broad fade. | r4 |
| 04-folds | Asked for fabric-like vertical folds; three variants (broad drapes, fine pleats, loose billows). | r4 |
| 04a (person) | Adopted variant C, loose billows, in the card. | r5 |
| 05-slower | Asked for half speed with everything else unchanged, and a measurement; five time rates halved with `SetStackSLNumbers`, mean frame change 1.2076 → 0.6065 over the first second. | r6 |

This session was recorded again after the first recording (2026-09-26, gpt-6-astra), so that its
trace shows the value each store line stored.

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
speed directly: r6 at 2t matches r5 at t.
