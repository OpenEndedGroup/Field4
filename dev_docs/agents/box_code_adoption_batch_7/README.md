# Documentation code explanations and layout

Baseline `2949d2615`. [Reviewed edits](comment_changes.json), [source diff](sources.diff.gz),
[verification index](verification.json), [mechanical evidence](evidence.tar.gz).
Sixteen saved findings addressed on fifteen documentation pages.

Corrections cover schema-fragment prerequisites, actual reinforcement units, shaped sine samples,
four preference dials, the camera's 60-degree swing, proper raw-string comment placement,
aggregate-versus-geometry agreement, indexed mesh closure, scalar-field interpolation, angular
axis units, default brush width, blended source colors, membrane winding/material coordinates,
fixed shader feather width, selected slice levels and three sine cycles. Geometry, sampling,
solver and animation calculations are unchanged. The raw-string example now contains just its
intended two lines; comments no longer become part of the literal.

## Mechanical coverage and verification

- **407 Kotlin code states** were extracted separately, including before/after states and
  display-only snippets. Imports and the actual ktfmt box formatter were applied; existing
  fold directives were carried through formatting, including indented directives.
- The final formatter `--check` reports **zero pages changed**. One state is explicitly an
  equation (`score_i(x) = |x - p_i|² / w_i²`); it is mathematical pseudocode despite the Kotlin
  fence, so the Kotlin formatter rejects and leaves it unchanged. All other states formatted.
- **Four GLSL states** use the real editor shader formatter. Their executable tokens match.
- A checked comparison over all 407 states applies only the reviewed edits, then verifies
  unchanged body tokens and authored fragment markers. Import lines and formatter-added
  separators are normalized; raw-string interpolation expressions are compared as code, with
  literal text retained. No extra algorithmic differences remain.
- The explicit collection-literal audit passes. Two multiline `listOf` notes in spatial examples
  are retained because their calls contain spreads, which collection literals do not support.

This is static evidence for comment/layout changes, not a claim that all 407 fragments independently
compile or run. Many display-only snippets intentionally depend on nearby setup. Runtime algorithms
and captures were not rerun here; the earlier concrete formatter regression has a passing 10-test
suite, and transcript/compiler coverage is recorded in the preceding batch.

Prose/capture follow-up `6f2ac4dc` covers reinforcement budgets, cycle labels and the angular axis.
The pass changes code states only; committed captures and surrounding prose remain untouched.
