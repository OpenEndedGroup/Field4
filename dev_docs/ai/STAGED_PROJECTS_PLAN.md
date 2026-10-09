# Staged agent projects: what to upstream from the house machine

Plan agreed with Marc on 2026-10-09. A private project (the "house machine", 2026-10-04 to 10-09) drove in-Field
AI4 agents through a long pipeline of derived builds: plan, checked panels, services layout, routes, coordination,
native fit, cuts. Seven agent-designed houses were cut, and a model-free corpus made 135 runs. Most of what made that
work was not about houses. This note records what goes into Field core, the public pages that go with it, and a
starting place for a user who wants the same strategy in another domain. The example user does 3D level design for
a puzzle shooter-looter: building-like, with none of the house domain.

Steps 1 and 2 of the plan concern the house machine itself (moving its hosts to main; adopting the chat archive and
the tool-description rubric) and are not covered here. Steps 3 and 4 are, and each ships with public docs.

## The strategy, in one paragraph (the starting point's own brief)

The document holds one editable source of truth, changed only through guarded tools: a tool refuses an edit made
against a revision that is no longer current. Everything else is a derived build with recorded inputs, so a result
is either current or visibly stale. Every build reports measurements in the notes the agent reads. A measurement
becomes a gate (the next stage cannot start) once it is trusted. Solvers' claims are re-checked independently. Rule
changes (a placement recipe, a fallback) are qualified against a fixture corpus with a case that must fail, and a
person approves any change to a fixture's recorded outcome. A model-free corpus runs the same machine on many
variants, and a gallery shows them side by side. The agent is asked to evaluate the results themselves, not only the
checks. On the house machine it optimised for exactly the checks it was shown (backlog: six socket loops passed
every check until route shape was measured).

## Step 3: Field core

### 3a. A turn that waits for its builds without the model

`WaitStudyBuildRequest` observes a job for at most 900 s. A 40-minute build therefore costs a model round every
15 minutes, each re-sending the whole context (about 80k tokens on the house machine). The house machine's external
driver works around it: the agent ends its turn naming the jobs it started, the driver polls `ListStudyBuildsRequest`
and sends "The builds you started have finished: ..." when they do.

In core:
- A tool `AwaitStudyBuildsRequest(boxId, name, jobIds, maxHours)` that ends the model's turn in a suspended state.
  The host resumes the same conversation with a generated continuation (each job's verdict, notices and facts) when
  every job is terminal or the limit passes. No model call happens while it waits. Survives a host restart: the
  suspension is saved with the chat, and a restarted host re-arms it.
- `running` and `suspendedOn` in the chat reply (`AiBoxChatReply`), so a client never infers "busy" from refusal
  text.
- `queueIfBusy` delivery of notices into a running turn already exists (`AiAssist`). Consider using it for "a job
  you started finished" while the agent is still working.
- Tests: suspension and resumption with a fake job; a restart during suspension; a cancelled job; the limit.

Public docs: a new page, `public/docs/ai4_long_builds.md` ("Let AI4 wait for long builds"). Cover starting a build,
awaiting it, what the continuation carries, how to see a suspended turn, and cost (no model calls while waiting).
Link it from `studies.md` "Build derived results" and from the AI4 pages.

### 3b. StudyBuilds additions

Each of these was written privately at least once, and most more than once:

- **Gates.** `StudyBuildGate(name) { inputs -> problem or null }` publishes an input that exists only when the
  predicate holds. The stage that needs it re-checks the gate's evidence when it starts, and `inputHelp` explains
  the wait. House examples: cuts waited for "cleared route contacts" and "loop-free routes".
- **Per-attempt requests.** `start(stage, fingerprint, request: Map<String,Any?>)`. The request is part of the
  fingerprint and is recorded in the attempt. The house machine kept requests (a resume job, retained routes) as box
  properties, and a later build silently reused a stale one (house-machine-8, 2026-10-08: coordination resumed a
  checkpoint from routes the agent had replaced and failed three minutes in).
- **Dependencies across runners:** `dependsOn(runner, stage)` in the same document, with the upstream current job
  id in the fingerprint and `context.dependencyFile` across runners.
- **Resume.** `context.resumeFrom(jobId, paths)` copies a hash-checked inventory from a terminal attempt.
- **External processes.** `context.runProcess(command, progressFile, stall, deadline)` provides the progress
  polling, stall watchdog, deadline and child kill that five private providers each wrote.
- **One build at a time** per runner, as an option (`exclusive = true`).
- **Identity helpers:** `fileIdentity(files)`, `classIdentity(classes)`. Fingerprint the build code and not the
  whole provider: hashing a provider file made every read-only tool edit stale every document's builds.
- **Pruning.** `StudyBuilds.prune(pins)` keeps heads, the latest record, the latest success, everything they
  reference, and pinned jobs; it deletes other attempts. A tool goes with it. The house machine freed 32 GB with a
  script doing this.

Public docs: extend `public/docs/studies.md` "Build derived results" with gates, requests, cross-runner
dependencies, resuming, external processes and pruning. Each gets one small working example and a capture of the
build card showing the gate's wait. If the section outgrows the page, move it to `public/docs/study_builds.md`.

## Step 4: shared machinery and a starting place

### 4a. Qualified sources: changing the rules safely

`QualifiedSource` wraps a `SourceStudy` (the rule: a recipe, a placement policy, a solver strategy), a fixture
`SourceStudy` (retained real cases with recorded outcomes) and a replay stage. It generates the agent tools
`<ns>.inspect`, `.candidate`, `.test`, `.apply`, `.retainFixture`, `.proposeRebaseline`.

- A candidate records its base revision and hash, its source, the fixture snapshot, dependency hashes and the
  baseline audit sources. Its id is the hash of all of these.
- Admission needs a current replay covering every fixture, a passing must-fail control, and unchanged dependencies.
  `apply` goes through `SourceStudy.edit(expectedRevision, expectedSourceHash)`.
- A frozen-region policy keeps the audit out of the candidate's reach: a file whitelist, a marker after which the
  source must match byte for byte, or a frozen baseline copy. The baseline copy needs an explicit upgrade path. The
  house machine's froze at a document's first launch and went stale (backlog 19).
- Only a person changes a fixture's recorded outcome: the proposal draws the diff with one Approve button per
  fixture and records who approved it and when.

Public docs: `public/docs/qualified_sources.md` ("Change a rule without breaking what already works"). Walk a small
rule through a candidate, a failing replay, a passing one, a proposed rebaseline and its approval, with captures of
the replay card and the approval view.

### 4b. Kits: one machine, many documents

A kit is the set of providers, assets and box-held sources every document of a kind receives:

- a declarative `kit.json`: roles (by box property), launch order, the sources held in properties, and a revision
  that is the hash of the rest;
- `tools/field_kit.py rehash | stage | migrate | activate | publish`;
- a stdlib `RuntimeIdentity.capture/verify` for pinned classes and native libraries.

The rule throughout is that the document owns a copy and every upgrade is explicit. A migration refuses local edits
(a three-way hash) and running builds, and records the new revision last. A core `kit.inspect` tool tells an agent
which kit its document runs.

Public docs: `public/docs/kits.md` ("Give many documents the same machine"). Cover making a kit from a document,
publishing it as a starting point, migrating a document, and what a migration refuses and why.

### 4c. A starting place: "Staged project"

A Welcome starting point, `templates/staged-project/`, small enough to run in seconds and close enough to
buildings to be useful to a level designer, with none of the house domain:

- **Plan box:** a level as rooms and doors on a grid, edited through guarded tools (`level.inspect`,
  `level.edit(expectedRevision, operations)`), drawn in a plan view.
- **Stages:**
  - blockout (Solid meshes for floors and walls);
  - traversal (a walkable-cell graph, a rough path and a relaxed path between spawn and each loot point);
  - placement (loot and encounters, by a qualified rule).
- **Measurements in the build notes:** reachability, each relaxed path's length and turn against its rough path
  (the house machine's route-shape measure, re-used), sightline counts.
- **A gate:** the placement stage cannot run until every loot point is reachable within its path budget.
- **A qualified rule:** the loot-placement rule with three fixtures and a must-fail control.
- **A corpus box:** seeded variants (room count, door shifts), model-free. A gallery sheet shows them with their
  measurements.
- **An agent brief** in the document's start-here: the strategy paragraph above, including "evaluate the results,
  not only the checks".

Public docs: `public/docs/staged_projects.md` ("Start a staged project"), the entry page for steps 3 and 4. It
explains the strategy, opens the starting point, walks one edit through every stage, shows a gate holding and
clearing, runs the corpus, and links the long-builds, studies, qualified-sources and kits pages.

## Order and landing

1. 3b StudyBuilds additions with the `studies.md` section; one branch per two or three features.
2. 3a turn suspension with `ai4_long_builds.md`.
3. 4a qualified sources with their page (uses 3b's replay stage and requests).
4. 4b kits with their page.
5. 4c the starting place and `staged_projects.md`, which use all of the above. The house machine's private kit
   then adopts the core versions piece by piece.

Every page follows `dev_docs/agents/DOCS_WRITING_RULES.md`: written for the user, the common case first, code
introduced before it is shown, real captures only. Every API lands with tests. Captures come from the docs runner,
never hand-made.
