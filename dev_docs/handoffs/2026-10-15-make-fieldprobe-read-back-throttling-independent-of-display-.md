# Make fieldProbe read-back throttling independent of display channel

- **Handoff:** `9b234d2d-6a4b-4da0-bfc9-afef7ad8bb1e` (project)
- **Filed from:** https://openendedgroup.com/field4/field_probes/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 9b234d2d "what happened"`

`public/docs/field_probes.md` ("Kotlin side") says: "`maxDisplayHz` always limits how often the card is sent, but it limits the GPU-to-CPU read only for a probe that has a display channel and no callback. With `onSnapshot` registered, or with `readLatest()` and no display channel, the read runs every frame, so a callback that does real work runs at full frame rate."
A setting whose name says "display" silently governs read-back only in one configuration (rule 25). Fix in `src/jvmMain/kotlin/afield/graphics/vulkan/util/FieldProbe.kt`: give the read-back its own rate (e.g. `maxReadHz`, defaulting to `maxDisplayHz`) applied uniformly, or throttle `onSnapshot` too. Delete the paragraph's exception clause (and the matching "Throttling" bullet wording) in the same change.
