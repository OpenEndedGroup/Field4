# Send StackSL slot regions to the editor so slot strings are coloured

- **Handoff:** `aa29f119-7350-4187-8096-820e658434bf` (project)
- **Filed from:** https://openendedgroup.com/field4/stacksl_examples/
- **Filed by:** human, 2026-10-16 06:14
- **Close with:** `python tools/docs_notes.py handoff-close aa29f119 "what happened"`

Refiled from the code batch (original: "Colour StackSL in slot strings without a #stacksl header"). Sharp edge on stacksl_examples.md ("In the editor"): "Only a `#stacksl` string is coloured as StackSL; a slot's string without the header is still coloured as a Kotlin string."

What the attempt on 2026-10-16 found:

- Colouring is client-only: `updateStackSLEmbeddedHighlight` in `src/commonMain/resources/stacksl.js`. It finds regions with `findStackSLEmbeddedRegions`, which matches only a `"""` string whose first line is `#stacksl`.
- Slot recognition is server-only: `EmbeddedStackSL.regions(code, slots(box))` in `afield/lang/stacksl/study/EmbeddedStackSL.kt`. It takes the slot names from `stack.auto.<name>` tags in the box's shader properties, which the frontend does not have.

So the fix needs plumbing between the two. One way: the server sends the regions it computes (`Region.bodyStart`/`bodyEnd` for the selected box's `code`) alongside the line notes, or as a small `stacksl-regions` message on selection and edit. stacksl.js then decorates those ranges as well as its own `#stacksl` ones. The other way: send the slot names and repeat `assignedTo` in JS. The first keeps one implementation. Delete the sentence in the same change.
