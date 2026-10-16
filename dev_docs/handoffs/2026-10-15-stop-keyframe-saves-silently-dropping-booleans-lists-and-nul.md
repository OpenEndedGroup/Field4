# Stop keyframe saves silently dropping Booleans, lists and nulls

- **Handoff:** `e5ed8a3d-2387-450e-abb2-5e57ace26f4d` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframer_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close e5ed8a3d "what happened"`

Rule 25 sharp edge on keyframer_examples.md, section "Saving: every save is history":

> Anything else (a `Boolean`, a list, a null) is **dropped in silence**, and the key loads back one value short with no warning anywhere.
> **The drop is silent and total.** ... If you keyframe a `Boolean` you will simply never see it again.
> The `Double` wire format does lose information in two cases ... A `Long` past 2^53 ... any `Number` that is *not* one of the six `NumKind`s ... comes back a `Double`.

Fix in `afield/animation/keyframes/keyframer.kt` (`KValue.fromAny` / `saveKey`): support Boolean (and null) as KValues, or at minimum reject/log unsupported values on save (`error,keyframer` channel) instead of dropping them; store Longs/BigDecimal losslessly (string-encoded with kind). Update the persistence fence's expectations/test and recapture `assets/keyframer_examples/persistence.png`. Delete these paragraphs in the same change.
