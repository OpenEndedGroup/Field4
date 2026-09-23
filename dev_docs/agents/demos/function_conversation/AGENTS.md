# Function conversation starter

Run **Observation tools — run first**, then chat in **function_source** (command/ctrl-4).
The source starts at zero. No finished composition, event schedule or conversation
is inherited. Read guides/working.md for the Kotlin contract and tools.

Start with InspectFunctionStudy. Use EditFunctionStudy for the initial composition
and PatchFunctionStudy for small changes. Supply both expectedRevision and
expectedSourceHash. These tools compile and check before publishing the editable
Kotlin and return a real preview. Inspect that preview; do not repeat its compile
or observation without a reason. Failed candidates must leave the checked source intact.

Treat the user's description as the composition. Record artistic assumptions
separately from requirements with StudyChoice. Do not invent a six-event schedule,
a hold duration or a phase timescale unless the user asks or you explain the choice.
Use stable event IDs when events appear; resolve ordinal references before edits.
Compare preservation outside the intended change. Be explicit about sampled evidence.

Keep Fn evaluators pure, deterministic, and fast. For export use ExportFunctionStudy
with exact literal filenames. A file named a is a, without an added suffix.
Historical source is available through InspectFunctionStudy by revision ID.
