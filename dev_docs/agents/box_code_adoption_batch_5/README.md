# Demo comment corrections

Baseline `7c28456fd`. [Reviewed edits](comment_changes.json), [source diff](sources.diff.gz),
[real runtime/codec results](evidence.tar.gz). Twelve saved findings addressed.

Seventeen comment edits correct the slider API path, record-holder terminology, current steering
point support, chart tick/bounds guidance, completion declaration-container explanation, Jacobi
ping-pong intent, MIDI bars, tracer inspection/rerun instructions, read-only defaults and the face
shrink clamp. Comments describe current behavior instead of dated implementation history.
The alife README's obsolete error example is filed as prose handoff `17a1a6b6`.

The MIDI and Datalog bodies and chart example use collection literals. The audition body's list
was converted too; it compiled without opening an audio device. Imports and mechanical ktfmt
were applied to all edited Kotlin states; the two shaders use Field's editor formatter.
All authored fragment boundaries and the read-only example's separate assignment remain.

Verification:

- Every scripted correction preserves executable lines. API claims were checked against source:
  slider factories, SteeringScope.point forwarding, niceTicks/plotRect and the visible shader clamp.
- Both GLSL files have identical non-comment lexical tokens before/after; no shader math, barriers
  or dispatch order changed. Their native renderers were not rerun for comment/layout changes.
- Real Datalog aggregate/absence proofs, MIDI notes/file readback and chart bounds match before/after.
  MIDI has 16 lead and 8 bass notes, last onset at beat 15, and 4/4; readback tempo is within 0.001
  BPM of 104 because MIDI stores integer microseconds per quarter note. No DAW audition is claimed.
- Field's persistence codec refreshed existing metadata fallbacks and machine-readable Markdown
  mirrors. Assertions preserve box IDs and every non-sidecar property. The loose shader-only
  flow_grain directory has no document metadata and was handled as source.
- The explicit collection-literal audit is clean over all selected authored/mirror paths, with
  no remaining list-literal notes. Compilation/codec harness failures and their repairs are kept
  alongside the successful verdicts. Sources were exercised on the private server at 9700.

This batch resolves these comment findings, not all findings in the affected document groups.
