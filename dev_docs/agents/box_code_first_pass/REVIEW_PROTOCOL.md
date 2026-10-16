# First sweep: review protocol

This sweep reviews human-facing teaching code and proposes helper/convention changes. Teaching
sources remain unchanged during review. Mechanical formatting candidates are collected separately.

Read `AGENTS.md`, `BOX_CODE_REVISION_PASS.md` and `BOX_CODE_HELPER_IDEAS.md` before reviewing.
Each assigned group has a packet with every distinct body and the locations of its exact copies.
Read all bodies, then inspect original source context and line numbers for actionable findings.
Check source hashes against the assignment before recording a disposition.

## Required lenses

1. Code smells and Field idioms: typed/inherited properties, raw-property escape hatches, box aliases,
   lifecycle ownership, graph context, asynchronous capture and rerun behavior.
2. Imports: use the deterministic analyzer as evidence. A star import or uncertain library import
   needs resolution evidence before proposing removal. Coordinator runs the mechanical stage.
3. Kotlin layout: readable groups, named intermediate values, creative controls, authored data rows.
4. Comments: useful intent, units, dependencies, setup/iteration boundaries and editing gestures.
5. GLSL layout and comments, including shaders embedded in Kotlin strings: stages, coordinate spaces,
   attributes, bindings, buffers, dispatch/barrier intent, and readable mathematical expressions.
6. Live coding: preserve declaration/assignment reset pairs and every authored fragment marker.
7. Helpers: reuse existing helpers, respect declined proposals, and consolidate new ideas by pattern.

A source review proposes a check; it does not establish that an example ran. Separate readability
findings from suspected behavior defects and specify the verification an edit would require.
Avoid generic boilerplate findings and comments that only repeat the next statement.

## Record format

Each worker owns one JSONL file. One JSON object per group:

- `group`, `key`, `source_commit`, `source_hashes`, `occurrence_ids`, `reviewed_body_count`.
- `disposition`: `keep`, `revise` or `needs-context`; `summary` explains the decision.
- `lenses`: observations for `smells_idioms`, `imports`, `whitespace`, `comments`, `glsl`,
  `live_coding`, `helpers`. Give a concrete observation or explain why the lens does not apply.
- `findings`: objects with `category`, `priority` (1-3), `path`, verified `line`, `snippet`,
  `issue`, `proposed_change`, `verification`, and optional `helper_key`.
- `helper_proposals`: objects with a stable `helper_key`, `title`, `rationale`, `before`, `after`,
  `evidence` (source path and occurrence/body references), `existing_or_declined_check`, and
  `verification`. Mark already-approved follow-ups distinctly from new proposals.
- `verification_needed`: concrete runtime/compile/capture checks for the later edit pass.
- `review_basis`: `source-reading`; `source_hashes_verified`: true after checking actual bytes.

`keep` is a valid outcome. A review is complete only after every body in the group was read.
Large groups may be read in chunks; a partially read group remains pending.

## Coordination

Workers write only their own review file. They do not change examples, generated copies, build
configuration, helper APIs or other workers' output. Coordinator validates coverage, challenges
sample findings and consolidates proposals. The first 12 groups calibrate the process before the
remaining groups are dispatched. Mechanical ktfmt/import/GLSL output stays distinct from model
review so a clean formatter result does not stand in for a readability judgment.
