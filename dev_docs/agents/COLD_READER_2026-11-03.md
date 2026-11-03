# Cold-reader review, 2026-11-03

Continues `COLD_READER_2026-11-01.md` with the cross-cutting chain pages (`public/docs/chain_*.md`,
17 pages, enumerated with `ls`). Method: each page's opening read cold (where newcomers stall),
plus a relative-link check over all 17 (no dead `.md` links found) and spot checks of numeric
claims against their source. Pages not listed had no finding in the parts read; their bodies were
not all read in full, so a later run should continue below the openings.

| Page | Finding | Category | Fix |
|---|---|---|---|
| `chain_ceres_manifold_contours.md` | "canonical" used in the intro; defined 110 lines later | forward-ref | glossed and linked to the section |
| `chain_ceres_manifold_contours.md` | "`relaxWithCeres` does reach a canonical form", then the bottom row shows it self-intersecting | contradicting claim | "meets every canonical condition, though not as a solid" |
| `chain_life3d_render.md` | `LIFE3D_BLOB` named, rule never stated | undefined-term | B5/S45 stated in words (checked against `alife_ca.kt`) |
| `chain_scene_to_light.md` | RGBWA, DMX, sACN unexplained | undefined-term | glossed |
| `chain_petri_sparks.md` | "Dirt-Samples" | undefined-term | glossed |
| `chain_skinned_volume.md` | "linear blend skinning", "candy wrapper" | undefined-term | glossed |
| `chain_pattern_particles.md` | mini-notation string shown before it is explained | code-before-intro | read aloud in prose |

Counts: ceres_manifold_contours 2, life3d_render 1, scene_to_light 1, petri_sparks 1,
skinned_volume 1, pattern_particles 1; total 7.

Numeric claims checked: `chain_pattern_particles.md` "24,000 particles" = `val N = 24000` in
`pattern_particles.yaml`; `chain_life3d_render.md` "eight generations" = `val generations = 8`.
Cross-page false claims found: none.

`gaussian_processes.md` "Cost and numerical behaviour": left as body text. It tells a reader which
method to call for which need and what the guards limit; by the AGENTS.md section 8 test it is
reader-facing, not commentary on how the page was made, so it is not an aside.

## Second pass: full bodies, and the rest of *Working in a Document*

Every chain page's prose read in full (fence bodies skipped, captions and alt text read), then the
*Working in a Document* nav section except `moving_around_the_canvas.md` (done 2026-11-01).

| Page | Finding | Category | Fix |
|---|---|---|---|
| `chain_pattern_particles.md` | "sixteen kicks are louder ... than four stabs": the bar is `~ 52 ~ 52` at cps 2 over two seconds (`PatSynthDocs.kt`), so eight stabs | false claim | "eight stabs" |
| `chain_photo_to_solid.md` | "hands Manifold three doubles per vertex"; `TriMesh.toSolid` allocates `JAVA_FLOAT` (`Solid.kt`), and the polytope and skinned chains say floats | false claim, cross-page contradiction | "three floats" |
| `chain_ca_sound.md` | "compression" numbers read before the ratio is defined | undefined-term | defined at first use |
| `chain_ca_sound.md` | "Euclidean figures", `x(2,7,5)` | undefined-term | glossed |
| `chain_fn_sound_roundtrip.md` | "certified range" | undefined-term | glossed |
| `chain_fn_sound_roundtrip.md` | "Dough graph" | undefined-term | glossed |
| `chain_lambda_sound.md` | "interaction net", "reaction" | undefined-term | glossed |
| `chain_lambda_sound.md` | "β-step" | undefined-term | glossed |
| `chain_petri_sculpture.md` | "marking" used in the intro, defined 100 lines later | forward-ref | glossed at first use |
| `chain_life3d_render.md` | "genus" | undefined-term | glossed |
| `chain_scene_to_light.md` | "universe" | undefined-term | glossed |
| `chain_ceres_manifold_contours.md` | "kis" | undefined-term | glossed |
| `running_code.md` | runaway box stopped "after five seconds"; `BoxInterrupt.Watchdog.interruptAfterMs` defaults to 30 000 | false claim | "thirty seconds" |
| `agent_transcripts.md` | same "about five seconds" claim (found from `running_code.md`) | false claim, cross-page | "about thirty seconds" |
| `filming_the_canvas.md` | "three.js view" unexplained | undefined-term | explained, linked |
| `filming_the_canvas.md` | "`shot()` ... (the dolly, explode and x-ray loop)" opaque | undefined-term | explained, linked |
| `demo_document.md` | "FLines" | undefined-term | glossed |
| `templates.md` | "uid" | undefined-term | glossed |
| `why.md` | "preamble" | undefined-term | glossed |
| `editing_from_outside.md` | "Saving three times, a second apart, gave three reloads" | lab-notebook voice | removed |
| `editing_from_outside.md` | "In a test with the hello triangle lesson ..." | lab-notebook voice | removed, instruction kept |
| `history_overview.md` | "AI4" | undefined-term | "Field's built-in AI assistant" |
| `semantic_index.md` | "sidecar-backed" | undefined-term | plain wording, linked |
| `profiling_timeline.md` | "Monaco" | undefined-term | "the editor" |

Second-pass counts: ca_sound 2, fn_sound_roundtrip 2, lambda_sound 2, pattern_particles 1,
photo_to_solid 1, petri_sculpture 1, life3d_render 1, scene_to_light 1, ceres_manifold_contours 1;
running_code 1, agent_transcripts 1, filming_the_canvas 2, editing_from_outside 2, demo_document 1,
templates 1, why 1, history_overview 1, semantic_index 1, profiling_timeline 1. Total 24
(both passes: 31). No finding: drawn_morph, drawn_paint, drawn_score, petri_sparks, physics_render,
polytope_render, rectsl_spacetime, skinned_volume; layers, files_and_paths, reactive_properties,
document_history, history_lens.

Cross-page false claims: the runaway-box timeout (two pages), floats vs doubles (photo_to_solid
against the polytope and skinned chains), and the stab count.

Numeric claims checked and correct: drawn_paint 560 strokes (18+35+5+22+420+60) and the 0/2/10/33/100 h
waits; drawn_score 120 bpm; fn_sound 375 Hz = 128 samples at 48 kHz; photo_to_solid vertex/triangle
formulas give chi = 2; polytope_render 62 faces, 120 vertices; scene_to_light 128 pixels,
17-pixel sample spacing, 22.7 ms at 44 Hz, sRGB 0.25 -> 0.54; skinned_volume 206 vertices,
408 triangles, rest volume 3.0, the 2.0 at a half turn and 22.5 degrees per band; pattern_particles
sixteen kicks; physics_render genus = (n-1)^2. The Working in a Document numbers checked are listed
in the worklog: canvasFlight defaults, 100,000 particles, 1024 history entries, 24 shadowed boxes,
10 backups, 65,536 resumes / 1,024 lanes.

Left open: `why.md`'s JSON field list omits `found` and `crossesEdges`, which `ContextWhy.kt` emits.
