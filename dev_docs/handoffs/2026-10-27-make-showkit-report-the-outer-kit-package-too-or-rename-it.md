# Make showKit() report the outer kit package too, or rename it

- **Handoff:** `22f0b257-6886-4304-9faa-653160563f6a` (project)
- **Filed from:** https://openendedgroup.com/field4/presentation_studio/
- **Filed by:** human, 2026-10-27 18:20
- **Close with:** `python tools/docs_notes.py handoff-close 22f0b257 "what happened"`

Rule 25 sharp edge in public/docs/presentation_studio.md ("Reuse the starter as a kit"):

> `showKit()` inspects this inner helper/style kit; it does not report the outer package's installation or update status.

The paragraph exists because `showKit()` is named as if it reports "the kit", but a presentation document has two kits (the inner `kit.json` role kit and the outer `field-kit.json` package). Fix in code: either have `showKit()` also report the outer package instance (`.field-kit-instance.json`: package, revision, update status), or rename/add a distinct call (e.g. `showRoleKit()` / `showPackage()`) so the name says what it shows. Delete the warning sentence from the page in the same change.
