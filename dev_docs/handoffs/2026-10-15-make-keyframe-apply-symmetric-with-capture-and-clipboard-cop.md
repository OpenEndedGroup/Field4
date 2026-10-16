# Make keyframe apply symmetric with capture and clipboard copy headless-safe

- **Handoff:** `dac81d9b-f4d8-4a4f-8929-f6bab1aa9e8e` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframer_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close dac81d9b "what happened"`

Rule 25 sharp edge on keyframer_examples.md, aside "`capture()` reads either kind of property, and writes back a binding":

> The write side is not symmetric. Applying a key always goes through `Structure.bind`, so a value-held property comes back as a constant binding with the old `values` entry shadowed underneath.

Also: "`copyUpdateCodeToClipboard()` ... goes through AWT: it throws on a headless JVM".

Fix in `keyframer.kt`: apply writes a value-held property back as a value (or clears the shadowed `values` entry); make `copyUpdateCodeToClipboard` degrade (log + return the string) when headless. Delete the paragraphs in the same change.
