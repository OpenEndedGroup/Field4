# Stop TetGen dumping skipped-facet files into the cwd

- **Handoff:** `b5beb4ab-b55c-41b3-9707-c66a2360f263` (project)
- **Filed from:** https://openendedgroup.com/field4/tetgen/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close b5beb4ab "what happened"`

public/docs/tetgen.md carries this warning (rule 25: a sharp edge documented instead of fixed):

> **A refused surface leaves two files in your working directory.** Refusing a self-intersecting surface leaves `tetgen-tmpfile_skipped.node` and `tetgen-tmpfile_skipped.face` in the process's current working directory ... `Q` does not suppress it and `TETLIBRARY` does not redirect it. ... Delete them when you are done with them.

Fix in the wrapper build, not with chdir (TetgenWorkingDirectoryLitterTest explains why chdir is wrong): patch the pinned TetGen 1.6.0 source at build time (native/field-tetgen) so the skipped-facet dump is either disabled under TETLIBRARY or written to a caller-supplied directory (e.g. a temp dir under build/ or java.io.tmpdir), optionally surfacing the path in the TetgenException message. Flip TetgenWorkingDirectoryLitterTest from characterization to an assertion that the cwd stays clean. Delete the `!!! warning` block from tetgen.md in the same change.
