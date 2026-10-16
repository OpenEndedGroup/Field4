# Shader contracts, source explanations and comment-safe formatting

Baseline `94245dbf4`. Nine saved findings addressed. [Reviewed replacements](comment_changes.json),
[shader before/after](shader-before-after.md), [verification](verification.json),
[source diff](sources.diff.gz), [evidence](evidence.tar.gz).

The ray-reconstruction guide shader now names its pixel, position/depth and world normal, with
explicit intermediate UV motion and input size. Comments explain the sign change, pixel units,
Vulkan depth/background convention, opaque diffuse material and packed roughness. Inlining the
intermediates and reversing local renames reproduces the original executable tokens. Both versions
compile and validate as SPIR-V; their binaries are not claimed identical and GPU output was not
recaptured.

Other explanations distinguish fixed decimal places from significant digits, introduce the noise
surrogate experiment directly, describe an actual exact-zero comparison, correct packed output
byte addressing, name the timeline controller, explain Set browsing, replace disabled matrix
scaffolding with the clip-space contract, and label remote-scene sections by what they do.

## Formatter repair

Applying the editor formatter to Nano exposed damaged aligned comment tables and trailing comments
moved below their statements. The formatter now protects comment/literal contents, keeps trailing
comments attached, counts braces outside protected text, and retains blank lines after directives.
Seven real regression tests pass, including repeated formatting, quoted comment-like text,
placeholder collisions and more than ten separate comments. The latter catches a prefix collision
found while applying the first repair to the full shader. The final application additionally
asserts exact comment/literal preservation before writing GLSL back.

## Verification

- All nine source paths use the real Kotlin/import or GLSL formatter. Four eligible factories
  became collection literals; the explicit checker is clean.
- All nine bodies match reviewed replacements, local-name edits and literal conversion;
  GLSL comments/literals and authored fragment boundaries are checked explicitly.
- Revised Nano, triangle and reconstruction shaders compile with `glslc` and validate with
  `spirv-val`. The baseline Nano shader fails at its local variable `flat`, a reserved GLSL
  qualifier; renaming it to `byteOffset` repairs compilation without changing packing operations.
  Nano's separate output-capacity finding remains open.
- Actual round-trip fitting, surrogate-cost and surrogate-wall programs execute in private Field.
  The round-trip reports loss `0.18468 -> 0.0018277` and maximum source/wire difference `5.96e-08`.
  The wall reports an exact zero gradient. H2 and the timeline-controller body also execute.
- Four documents' inline fallbacks are refreshed through Field's actual codec, asserting unchanged
  box IDs, graph/non-sidecar properties and exact sidecar contents. The remote-scene transcript
  passes its compile guard.

Runtime programs use private Field/browser port 9700, separate from the user's Electron session.
There is no GPU-render acceptance claim for the unchanged reconstruction shader math.
