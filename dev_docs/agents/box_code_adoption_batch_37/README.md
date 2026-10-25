# Defined, readable particle-disc falloff

Baseline `9b0b6e368`. Two frozen GLSL findings addressed: `ca367739ec6d-01` and `403fc75454d9-01`.

Both particle fragments now compute `radius`, then `alpha = 1.0 - smoothstep(0.12, 0.5, radius)`. Increasing smoothstep edges define the intended centre plateau and soft rim; the prior reversed edges were undefined GLSL behavior. The comment describes coverage, while the final 0.30 opacity and discard threshold remain explicit. Both authored fragments are identical.

Actual import/ktfmt and GLSL formatting covers six Kotlin bodies and six shader properties (two each of compute, vertex and fragment). Two collection constructors become literals. Reviewed tokens/comments, raw StackAuto programs, graph properties and fragment boundaries are preserved by verify.py. The collection/idiom audit is clean across both files.

Real native GPU verification on private Field 9703:

- Execute the authored fragment through a fullscreen-triangle diagnostic on the same real renderer. Native RGB samples at radii 0, 0.1, 0.25, 0.4 and 0.49 are (62,97,145), (62,97,145), (52,83,126), (23,39,62) and black. This verifies the plateau, fading coverage and outside discard through actual compiled GLSL, not a CPU stand-in.
- Remove/release the diagnostic module, launch the complete 40,000-particle compute/draw graph, and assert 240,000 shader-generated quad vertices. Capture and inspect the real fbm3 result.
- Hot-bind the exact authored flat-fbm force expression into that same running graph, then capture and inspect it. This verifies the second force expression on the live graph, not an independent fresh launch of the entire flat fixture. Both captures contain actual visible particles.
- Stop the private graph after capture. The scoped transcript guard verifies both transcripts.

The diagnostic uses normalized UVs over the whole viewport, so its circle stretches with viewport aspect; this is a shader-coverage fixture, not a replacement particle image. All captures are private real native views, and no retained documentation capture is fabricated or replaced. The initial harness assumed the flat force was a triple-quoted string; it is actually one quoted line. The selector is repaired and the follow-up continues the existing live graph, avoiding renderer recreation.

Reusable formatting, preservation and native rehearsal harnesses and evidence.tar.gz retain exact checks, images and diagnostics. The full corpus sweep remains incomplete.
