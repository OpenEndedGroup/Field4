# stack.auto conversation — a polytope that bursts and burns

A recorded conversation with the two StackSL programs *inside* one box's own shaders. The box
`polytope_source` draws a snub dodecahedron with every face its own. Its vertex shader calls
`{{ stack.auto.explode }}` to say where each face goes, and its fragment shader calls
`{{ stack.auto.face_decay }}` to colour each face and say where it burns through. Both start inert:
the solid whole, one warm grey. Six AI4 turns (gpt-6-astra through OpenAI) and one action by a person
talked them into a burst that passes down the solid as a wave, faces glowing hot as they fly and
burning out to ragged frames at the peak. Recorded 2026-09-28, with the window running, so every
picture came from the real shaders on its GPU. The public walkthrough is
`public/docs/stackauto_conversation.md`, built by `tools/package_stackauto_conversation.py`; the
document it starts from is `stackauto_conversation`.

The two programs are two conversations, but they share one chat: AI4 keeps one transcript per box,
and both slots belong to this box's one module. Each turn names its slot, and the tools take it.

| Step | What happened | Revision |
| --- | --- | --- |
| explode-01-burst | Asked for faces to fly out and back every few seconds, the top first. A 4.8-second cycle whose phase leads with the face centre's height. | explode r2 |
| explode-02-shrink | Asked for faces to shrink as they fly, opening the solid up, and to go out fast and back slowly. The model looked at a sheet of nine moments across the cycle. | explode r3 |
| decay-01-embers | Asked for dull iron at rest, glowing orange where a face has flown far. Heat follows the travel the host passes in. | face_decay r2 |
| decay-02-burn | Asked for the furthest faces to burn through at the burst and heal. The holes came out small and round; the model called them ragged. | face_decay r3 |
| decay-03-ragged | Asked for holes eating most of each face, leaving a torn frame. Two patches, then the model found the host gave no distance to a face's edge and rebuilt the box's mesh as centre-to-edge fans to supply one; the change could not take live, and it said so. | face_decay r4, r5, r6 |
| decay-03a (person) | Ran the box. The new mesh took, and the frames appeared. | — |
| decay-04-look | Asked whether it is now what was asked for. It observed a whole cycle and said yes, noting some rims are almost wire-thin. | face_decay r6 |

To look at it: copy this folder into your workspace and open it. Launch **renderer — launch me
first**, then run **the polytope — talk here (AI4)**; the recorded chat is in the polytope box's AI4
chat, and its card shows both slots' numbers. Continuing the chat adds to this document's history,
which is why a copy is better than opening it in place.

What is here:

- `conversations/`: each turn's prompt, transcript (`.md`) and full record (`.json`), the tool I/O
  AI4 logged for the turn (`tool-io/`), and the person's action (`decay-03a-run.human.json`).
  Transcript images are referenced as `images/<hash>`; the bytes are inside the saved chat
  (`polytope_source_ai4_chat_history_.md`).
- `studies/polytope_source/stack.auto/<slot>/`: every revision of each slot.
- `prompts/`: the requests, as sent.

What the recording found, and what changed because of it (all fixed before the turn after):

- The first attempt at explode-01 never reached the model: `StackAutoSlotGuide` had no properties,
  and the OpenAI client refuses such a tool. It now takes `search`, which also makes the model's
  word lookups work (they had silently returned the guide). The failed attempt was removed from the
  chat and the turn recorded again.
- explode-01's edit showed one frame of an animated slot, which caught the solid at rest. Edit,
  Patch and Observe now take `over` (seconds) for a numbered sheet of real frames.
- decay-01's sheet shows the previous program in its first frame: the edit waited for the slot's text
  to reach the shader, not for MoltenVK to finish the pipeline. Edits now wait for the pipeline
  update itself and report `boundLive`, which also excuses the model from `ReloadShaders` (explode-01
  to decay-01 called it anyway).
- The person's run is new in `tools/conversation_demo.py` (`act … run`); decay-03's mesh change
  needed it. Before recording it, the box had been run once by hand to see whether the change worked.
