# stack.auto conversation — start here

A polytope to talk to, where the recorded conversation `stackauto_conversation_polytope` began. Two
boxes:

- **renderer — launch me first** opens the window the polytope draws in.
- **the polytope — talk here (AI4)** builds a snub dodecahedron, every face its own, and draws it
  with its own vertex and fragment shaders. Each shader calls one StackSL program, a `stack.auto`
  slot: `explode` in the vertex shader says where each face goes, and `face_decay` in the fragment
  shader colours each face and says where it burns through. Both start inert: the solid whole, one
  warm grey. Their programs are the two `module.… = """…"""` strings at the end of the box's code.

1. Open this document in Field. Select the renderer box and choose **Launch Box** from the command
   palette; the window opens. Then select the polytope box and run it (**command/ctrl-return**). The
   window shows the grey solid, and the polytope box shows a card with both slots' numbers.
2. Select the polytope box and press **command/ctrl-4** to open its AI4 chat.
3. Ask for something, naming the slot, for example:

> In the explode slot (the vertex shader): make the polytope burst. Every few seconds the faces
> should fly out from the centre and come back together, and faces nearer the top should go first.

An accepted edit is bound into the running shader and written back into the box's code; each is a
saved revision under `studies/polytope_source/stack.auto/<slot>/`. The walkthrough is
`public/docs/stackauto_conversation.md`. For agents: read [AGENTS.md](AGENTS.md).
