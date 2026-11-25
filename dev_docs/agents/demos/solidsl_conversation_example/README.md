# SolidSL conversation — a nautilus lamp

A recorded conversation that talked a SolidSL solid into existence, starting from the
`solidsl_conversation` starter (a sphere). Five AI4 turns (gpt-6.1-sol through OpenAI), recorded
2026-11-25 with the window running, so every tool image came from its GPU. The public walkthrough is
`public/docs/solid_conversation.md`.

| Step | What was asked | Revisions |
| --- | --- | --- |
| 01-vessel | A small cast lamp, a nautilus-inspired shade with a spiral of openings, on a stem with a flared foot. | r2, r3 |
| 02-coil | Make it coil like a real nautilus, openings growing toward the mouth; check from above and in section. | r4–r6 |
| 03-patina | Patinated bronze as shading: verdigris in the hollows, polished on the high edges. | r7 |
| 04-openings | Three characters for the openings side by side; adopt one and say why. | r8, r9 |
| 05-glow | Warm light from inside, spilling out through the openings; check from a low angle. | r10 |

To look at it: copy this folder into your workspace and open it; the window opens and turns the
lamp, and the recorded chat is in the study box's AI4 chat.

- `conversations/` — each turn's prompt, transcript (`.md`), full record (`.json`) and the tool I/O
  AI4 logged (`tool-io/`). Transcript images are `images/<hash>`; the bytes are in the saved chat
  (`solid_source_ai4_chat_history_.md`).
- `studies/solid_source/solid/` — every checked revision (`r<n>-<hash>.kt` plus its facts) and the
  variant batches (`speculations/`).
- `prompts/` — the requests, as sent.

`solid_source_code_.kt` was laid out by `formatBoxCode` after recording (the docs guard requires
it), so it differs from revision r10 in whitespace only, and Inspect reports the reopened box as
`dirty`. Running the box, or the next accepted edit, checks it in.
