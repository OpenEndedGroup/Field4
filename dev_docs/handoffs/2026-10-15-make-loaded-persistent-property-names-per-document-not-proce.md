# Make loaded persistent-property names per document, not process-global

- **Handoff:** `e8859a01-b909-457b-ae0d-f82ed94ff6c2` (project)
- **Filed from:** https://openendedgroup.com/field4/document_on_disk/
- **Filed by:** human, 2026-10-15 13:50
- **Close with:** `python tools/docs_notes.py handoff-close e8859a01 "what happened"`

Rule 25 sharp edge on public/docs/document_on_disk.md, "The persistent-property list is sticky, and process-global":

> open a document whose header lists a property name, and every other document open in that Field will persist that name too, until the process exits. Nothing removes a name.

`readEdnTransfers` merges each loaded document's `:persistentProperties` into the global `Box.ioPersistentProperties` (saving.kt, `Box.ioPersistentProperties.addAll(propsList)`). Fix: keep a per-document persistent set (global declared set + that document's own header), so one document cannot change what another saves. Then shorten/delete the section's warning in the same change.
