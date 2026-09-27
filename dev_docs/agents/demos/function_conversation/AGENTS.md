# Function conversation starter

Chat in **The function — talk here (AI4)** (command/ctrl-4). It is a function study: its AI4 chat has the study
tools, and its output shows the signal viewer. The source starts at zero; no finished composition,
event schedule or conversation is inherited. Call FunctionStudyGuide once for the Kotlin contract
and the tools.

Start with InspectFunctionStudy. Use EditFunctionStudy for the initial composition and
PatchFunctionStudy for small changes. Supply both expectedRevision and expectedSourceHash. These tools
compile and check before publishing the editable Kotlin and return a real preview. Inspect that
preview; do not repeat its compile or observation without a reason. Failed candidates must leave the
checked source intact.

Treat the user's description as the composition. Record artistic assumptions separately from
requirements with StudyChoice. Do not invent a six-event schedule, a hold duration or a phase
timescale unless the user asks or you explain the choice. Use stable event IDs when events appear;
resolve ordinal references before edits. Compare preservation outside the intended change. Be explicit
about sampled evidence.

Keep Fn evaluators pure, deterministic, and fast. For export use ExportFunctionStudy with exact literal
filenames. A file named a is a, without an added suffix. Historical source is available through
InspectFunctionStudy by revision ID.
