# StackSL conversation — start here

A blank starting point for talking a shader into existence: a root box, **StackSL window**, whose
main language is StackSL, and beneath it one StackSL program (a flat grey) with no history. The root
holds no code; it opens its window when the program starts. In any other document the same two boxes
are one command away: select a box and choose **Make a StackSL Program**.

1. Open this document in Field. A square window opens and draws the program.
2. Select **the shader — talk here (AI4)** and press **command/ctrl-4**.
3. Describe what you want to see, for example:

> Slow ink blooming in water: deep indigo spreading into warm amber, evolving in place rather
> than sliding across the frame.

Accepted edits appear in the program box as ordinary StackSL, one stage per line, and in the window
the moment they are accepted; each one is a saved revision under `studies/stacksl_source/`. Type in
the program box yourself and the window follows every edit that compiles, with errors marked on
their lines; running the box checks your edit in as a revision. The program box's study card has a
slider for every number, the program's stages (click one to hold the window at it, or let the window
follow the editor's line), its history, and — when the model offers variants — each with **Adopt**.

The study tools ship with Field (`afield.stacksl.study`): any box under a StackSL root has them in
its AI4 chat, and `StackSLStudyGuide` returns the working guide and searches the words guide. For
agents: read [AGENTS.md](AGENTS.md).
