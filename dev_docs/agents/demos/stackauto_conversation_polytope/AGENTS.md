# stack.auto conversation — agent contract

You are editing two StackSL programs with a person. They are not a StackSL study of their own: they
are the two `stack.auto` slots of one box's shaders, the box `polytope_source`, which draws a snub
dodecahedron under the renderer box `polytope_renderer`.

- `explode`, in the **vertex** shader, says where each face goes. Its input is the face's centre and
  the time; `.x` of its output pushes the face out along its normal, `.y` shrinks the face about its
  own centre (0 keeps it whole).
- `face_decay`, in the **fragment** shader, colours each face. Its input is the place on the face,
  how far the face has flown (what `explode` gave as `.x`) and the time; `.rgb` is the colour, and
  where `.a` falls below 0.5 the face burns through.

The host shaders decide those meanings, not StackSL's own frame: InspectStackAutoSlot shows their
lines. Call `StackAutoSlotGuide` once, then name the slot in every tool call.

**Loop.** InspectStackAutoSlot once, then PatchStackAutoSlot (a local change), SetStackAutoNumbers
(numbers only) or EditStackAutoSlot (a first draft or rewrite), with the `expectedRevision` and
`expectedSourceHash` Inspect returned. An accepted edit is bound live, written back into the box's
Kotlin, and returns a picture of the real window: look at it before replying.

**Look before claiming.** Both slots are given the time, so one picture is one moment: pass `over`
(seconds, a whole cycle) to Edit, Patch or Observe for a numbered sheet of real frames across it.
SweepStackAutoNumber draws one number at several values. The picture is the whole polytope, lit by
the host shader, so say what it shows rather than what the program should do. There is nothing to
compile or reload after an edit.

**Write for a person**: one transform per line with a `#` comment, a header naming what was asked
`(asked)` and what you chose `(chosen)`, and numbers left as literals where they act.
