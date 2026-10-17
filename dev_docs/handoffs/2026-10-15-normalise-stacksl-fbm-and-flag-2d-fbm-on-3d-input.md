# Normalise StackSL fbm and flag 2D fbm on 3D input

- **Handoff:** `bf7769dc-2406-4cce-84f9-9aa6d2c0aa97` (project)
- **Filed from:** https://openendedgroup.com/field4/gpu_state/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close bf7769dc "what happened"`

Rule 25 sharp edge on `public/docs/gpu_state.md`, the `fbm4, and not fbm` and "Nothing in the program corrects a bias" paragraphs: StackSL's plain `fbm` is silently 2D when given a 3D input (shears z in at 0.07) and its range is 0..0.96875 rather than 0..1, unlike the normalised `3`/`4` family (`afield/lang/stacksl/VolumetricNoise.kt`).

Decision + code: normalise `fbm`'s amplitude sum like the volumetric family, and either warn (stack.auto report) when `fbm` is fed a value whose z/w vary, or rename it `fbm2`. The teaching paragraphs about the 1.032258 correction and the bias are then deleted; the flat-fbm figure (assets/gpu_state/flat-fbm.png, transcript docs_capture/gpu_state_flat_fbm.yaml) needs re-capture or rewording if the bias goes.
