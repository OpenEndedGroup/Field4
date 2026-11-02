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
