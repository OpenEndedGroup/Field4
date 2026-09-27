# Function conversation — start here

This is a blank starting point: three zero-valued channels over one hour in a function study box. It
contains no saved conversation, checked revision, or finished musical gesture.

1. Open this document in Field. The box **The function — talk here (AI4)** shows three flat lines in its viewer.
2. Select it and press **command/ctrl-4**.
3. Try:

> Let's start with a 3d channel function, lasting an hour — sine waves from 0 to 1
> at around 0.2 Hz, drifting slowly in and out of phase.

Here “3d” means x, y and z as functions of time. The drifting phase rate is an artistic choice for
the conversation to settle. Accepted Kotlin appears in the source box; the Plotly overview follows
it. Zoom into about 25 seconds to see the individual cycles. Each accepted edit is a revision under
`studies/function_source/` in this document.

The box is a function study because its `aiStudy` property is `function`; any box whose Kotlin
returns a `StudyComposition` can be made one with **Make a Function Study**. The study tools ship
with Field (`afield.stdlib.FunctionStudyTools`), and `FunctionStudyGuide` returns the working guide.

The separate **function_conversation_example** preserves the developed one-hour composition, its
saved conversations and its checked revisions. The public guide is **Talking a function into
existence** (function_conversation.md in Field's docs).
