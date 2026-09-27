# RectSL conversation — lanterns at dusk

A recorded conversation that talked an animated RectSL scene into existence, starting from the blank
`rectsl_conversation` document. Five AI4 turns (gpt-6-astra through OpenAI) and two actions by a
person, in the scene's study card and its editor, recorded 2026-09-27 with the window running, so
every tool image came from its GPU. The public walkthrough is `public/docs/rectsl_conversation.md`.

| Step | What happened | Revision |
| --- | --- | --- |
| 01-lanterns | Asked for paper lanterns drifting up a night sky; one `EditRectSLStudy` (a lantern built from nine rectangles in a swaying group, and an `array` making five staggered lanterns of it), checked against the live window. | r2 |
| 02-scatter | Asked for a less orderly sky; a patch varying start places, sizes and speeds. | r3 |
| 03-moods | Asked for three sky moods without changing the lanterns' motion; three variants (dusk, midnight, festival), nothing published. | r3 |
| 03a (person) | Adopted variant **dusk** in the card. | r4 |
| 04-loop | Asked for a seamless loop, shown; frames straddling the wrap and the timeline showed it already looped, and a two-loop clip went to `files/`. | r4 |
| 04a (person) | Put the editor's cursor on line 36, the flame's; the window outlined every flame. | r4 |
| 05-flame | Asked "make this one glow a little warmer"; Inspect reported the person at line 36, and a patch changed that line's colour, which every flame shares, and nothing else. | r5 |

To look at it: copy this folder into your workspace and open it; the window opens and plays the
scene, and the scene box shows its study card. The recorded chat is in the scene box's AI4 chat.
Continuing the chat adds to this document's history, which is why a copy is better than opening it
in place.

What is here:

- `conversations/` — each turn's prompt, transcript (`.md`) and full record (`.json`), the tool I/O
  AI4 logged for the turn (`tool-io/`), and the person-side action (`*.human.json`). Transcript
  images are referenced as `images/<hash>`; the bytes are inside the saved chat
  (`rectsl_source_ai4_chat_history_.md`), and `tools/package_rectsl_conversation.py` extracts them.
- `studies/rectsl_source/` — every checked revision (`r<n>-<hash>.rectsl` plus its facts) and each
  variant batch the model offered (`speculations/`).
- `files/lanterns-seam-check-two-loops.mp4` — the two-loop clip the last turn exported.
- `prompts/` — the requests, as sent.

`RectSLConversationExamplesTest` replays this document's revisions on the CPU reference.
