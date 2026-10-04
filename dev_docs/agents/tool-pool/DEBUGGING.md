# Debugging and improving tools inside Field

A useful debugging run leaves enough evidence for another agent to reproduce the
finding, question it and continue. The conversation, provider source, tool calls,
tests and output images belong to the same Field workflow.

## Start from the failed attempt

Retain the exact inputs, source/dependency identities, runtime identity, failing
check and its measured value. Keep failed intermediate artifacts before the check
throws. These are diagnostic candidates; they do not replace accepted output.
An error string alone is insufficient when the operation acted on structured data
or geometry that the next agent cannot recover.

Use the existing inspection tools to look at the real output. Record the artifact
identity alongside the picture. A model's explanation should distinguish observed
facts from a proposed cause; the next experiment follows from that distinction.

## Turn repeated inspection into a tool

First try a short JavaScript tool program over the existing tools. Return the
decisions and their evidence references, not every intermediate report. A failed
call stops the program; a successful inspection reporting an unqualified verdict
needs an explicit `check(...)` before a dependent edit. The native tool must enforce
its own prerequisites too. Retrieve retained large results by their evidence IDs
when investigating a detail; a compact summary is not a replacement for evidence.

Programs have their own deadline. Start long builds with `waitSeconds=0`, return
the handle, and observe that same job separately with streamed progress. Requesting
a longer nested wait does not extend the program deadline. Verify final status,
currency and qualification facts before consuming the result. Test this with a
real mouse edit during a build: the older result must remain inspectable but must
not publish over the newer design.

Search the tool pool and existing APIs first. Put a reusable operation in an ordinary
provider box with `aiTool`, explicit inputs and a description of its dependencies.
Application-specific fixture names belong to the invocation or a private adapter.

Attach tests that distinguish the intended behavior from plausible mistakes. A
positive example alone cannot establish a detector. Useful controls include a
known failure, a boundary case and an input where a tempting shortcut gives the
wrong answer. `pool.test` invokes the actual binder and action and records results
against that registered source.

Use a separate candidate box or build attempt to try a repair. The candidate retains
its base source identity; tests run on the actual candidate. Existing independent
acceptance checks and saved negative controls remain part of the decision. A local
improvement is evidence for the next step, not automatic approval of the full result.

## Retain what was learned

Curate a useful provider explicitly with `pool.publish`, then use `pool.note` to
attach an evidence-linked account of what it does, its limits and any unresolved
issue. A note can describe a hypothesis, but should identify it as such. Test results
remain the recorded outcomes of executions rather than claims in a note.

A playbook change records the general procedure and the evidence that established
it. Use existing `DocumentGuideReadRequest` and `DocumentGuidePatchRequest` or
`DocumentGuideAppendRequest` for guides owned by the Field document: the read's
revision guards the entire file, and a conflict requires rereading and reconciling.
`DocumentGuideCreateRequest` creates a new guide without overwriting an existing
one. These tools already support a durable playbook inside Field; they do not edit
arbitrary external repository files. Project recipes, fixture data and private
subject knowledge stay in their owning project. Importing a provider into another document starts with destination tests;
original-project checks and notes help select the test, not bypass it.

## Capability gaps become concrete work

When an agent cannot complete one of these steps, preserve the failed operation and
identify the missing capability: an artifact was discarded, an input cannot be read,
the source cannot be patched safely, a job has no progress or result identity, an
image was not delivered to the model, or a successful repair has no promotion path.
That makes the next infrastructure change testable instead of adding a second
application around the agent.

For source repair, distinguish a normal box edit from promotion of a project file.
The mounted chat's `EditFileRequest` edits Field property references; its presence
alone does not establish a workspace-file patch or promotion service. The remaining
project adapter should accept an expected base hash, retain a candidate, replay it
against frozen inputs and independent checks, and refuse promotion if the source or
design has moved. Document playbook updates use the existing guarded guide tools
described above; external repository-file changes require a separately established
path. A generic Kotlin file write can perform an experiment, but does not establish
these concurrency and acceptance guarantees.

Where a recipe can live in Field, prefer one persistent source property backed by
the existing `SourceStudy` transaction. Freeze that property's text into each
`StudyBuilds` attempt and include its hash in the stage inputs. A project adapter
can test an isolated candidate, then admit it from the verified complete result;
partial diagnostic success must remain distinct from activation. This avoids
inventing workspace-file transactions when the source already belongs in a box.

## Continue long work without starting it again

Keep source, large meshes and full reports in retained artifacts. Return compact
job IDs, source identities, measured failures and links from progress queries.
Read bounded summaries first, then the particular witness needed for the next
experiment. Repeating whole artifacts in each tool response consumes the model's
working context without adding evidence.

The existing `AttachmentRequest` supports bounded excerpts through `offset` and
`maxChars`, returning the whole-source hash, total size and `nextOffset`. Pass
`expectedSourceHash` when continuing so a changed resolver source is refused.
An excerpt is explicitly marked and must not be used as a complete-file replacement.
Tool availability still depends on context: the low-level backdoor tool harness
does not supply the mounted chat's attachment resolver. Verify context-dependent
behavior in the actual AI4 chat as well as testing the shared handler.

An observer's wait limit is not the agent or build's lifecycle. A `running` result
identifies continuing work, so inspect that same chat or job. Do not turn that
result into a failure, append a spurious refusal to the live conversation or repeat
the request. A provider failure is a separate terminal event; preserve the completed
jobs and recover from their evidence rather than rerunning them.
The panel task wrapper reads the turn's structured failure outcome, not error words
in its prose reply. A provider failure returns `error` with the retained partial
reply and skips completion checks; a successful explanation of an earlier failure
must not itself be classified as failed.

For a bounded continuation, the relevant provider box's existing AI4 chat can read
the saved job identities and reports. Record what was independently verified, what
remains a hypothesis and the next experiment. A handoff prompt's claims are not
evidence. A successfully written note also does not establish that the underlying
repair passed its acceptance checks.
