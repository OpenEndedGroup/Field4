# Check the JEP native library version against the shipped jar

- **Handoff:** `aa147473-9328-4731-a552-e8be19f701d1` (project)
- **Filed from:** https://openendedgroup.com/field4/python/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close aa147473 "what happened"`

Rule 25 sharp edge on python.md:

> Install the `jep` version that matches the jar Field ships (`libs/jep-4.3.1.jar`). JEP does not check that its native library and jar come from the same release.

Fix: at Python startup Field compares the libjep/site-packages jep version with the jar version and refuses with a clear message naming both (and Install JEP pins the matching version). Delete the paragraph in the same change.
