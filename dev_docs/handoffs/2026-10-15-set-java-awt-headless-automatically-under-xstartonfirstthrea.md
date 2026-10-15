# Set java.awt.headless automatically under XstartOnFirstThread

- **Handoff:** `a01ffe43-abf6-46f2-9d99-f47c7cba7fc4` (project)
- **Filed from:** https://openendedgroup.com/field4/typography_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close a01ffe43 "what happened"`

Sharp edge (rule 25) on `public/docs/typography_examples.md`, aside "Reading outlines in a JVM you launched yourself":

> A JVM that reads glyph outlines and passes `-XstartOnFirstThread` must also pass `-Djava.awt.headless=true`, set before any AWT class initialises ... `Typefaces.warm()` detects this in four seconds and throws ...

Fix in code: make the requirement impossible to miss — e.g. have the typography entry points set `java.awt.headless=true` themselves when running under `-XstartOnFirstThread` before AWT initialises, or have `AgentJvmMainLauncher`/any Field JVM launcher always include it alongside `-XstartOnFirstThread` (as `requiredRunJvmArgs()` does). Delete the aside in the same change.
