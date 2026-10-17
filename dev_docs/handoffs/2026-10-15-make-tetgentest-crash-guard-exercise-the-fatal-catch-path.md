# Make TetgenTest crash guard exercise the fatal catch path

- **Handoff:** `3a9c9c24-43da-45c6-a687-b8419ef0a3d9` (project)
- **Filed from:** https://openendedgroup.com/field4/tetgen/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close 3a9c9c24 "what happened"`

public/docs/tetgen.md, aside "The fatal path", says:

> `TetgenTest.anOpenSurfaceIsRefusedWithoutTakingTheProcessDown` guards that the process survives — though note that on this build the five-faced cube it uses comes back through the *empty mesh* path rather than the throw, so the two inputs that genuinely exercise the catch are the self-intersecting pair and the degenerate one.

That is a test that does not test what its name and comment claim. Fix: change the test (src/jvmTest/kotlin/afield/geometry/mesh/tetgen/TetgenTest.kt) to use the interpenetrating cubes and the zero-volume solid (the inputs that reach TetGen's fatal handler and the catch in field_tetgen.cpp), and correct its comment. Delete the "though note ..." clause from the aside in the same change.

Related, same aside: TetGen prints `Exception: All vertices are (nearly) collinear ...` to process stdout despite `Q`. Consider silencing that print in the build-time source patch so the only message is the wrapper's; delete that paragraph too if fixed.
