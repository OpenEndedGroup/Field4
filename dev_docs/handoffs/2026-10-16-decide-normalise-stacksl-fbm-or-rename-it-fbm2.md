# Decide: normalise StackSL fbm (or rename it fbm2)

- **Handoff:** `e650637e-a2cd-4c72-a933-3a9fe2c8aad2` (project)
- **Filed from:** https://openendedgroup.com/field4/gpu_state/
- **Filed by:** human, 2026-10-16 08:33
- **Close with:** `python tools/docs_notes.py handoff-close e650637e "what happened"`

Moved to decisions on 2026-10-16. The brief is marked 'Decision + code'. Normalising fbm's amplitude sum changes every StackSL program that uses fbm, including the committed gallery float bits (StackSLCpuFloatPathManifestTest) and the flat-fbm figure. Decide that first: normalise, or rename it fbm2 and add a warning.


Rule 25 sharp edge on `public/docs/gpu_state.md`, the `fbm4, and not fbm` and "Nothing in the program corrects a bias" paragraphs: StackSL's plain `fbm` is silently 2D when given a 3D input (shears z in at 0.07) and its range is 0..0.96875 rather than 0..1, unlike the normalised `3`/`4` family (`afield/lang/stacksl/VolumetricNoise.kt`).

Decision + code: normalise `fbm`'s amplitude sum like the volumetric family, and either warn (stack.auto report) when `fbm` is fed a value whose z/w vary, or rename it `fbm2`. The teaching paragraphs about the 1.032258 correction and the bias are then deleted; the flat-fbm figure (assets/gpu_state/flat-fbm.png, transcript docs_capture/gpu_state_flat_fbm.yaml) needs re-capture or rewording if the bias goes.

## Decision, 2026-10-16 (Marc)

**Leave it.** StackSL's plain `fbm` keeps its range (0..0.96875) and its 2D behaviour. The gpu_state.md paragraphs that teach the 1.032258 correction and the bias stay, as does the flat-fbm figure. No code change, and the gallery float bits are untouched.
