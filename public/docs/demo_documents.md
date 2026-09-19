# The nineteen demo documents

`dev_docs/agents/demos/` holds **19 saved Field documents** — 65 boxes between them — written as
demonstrations of one subsystem each. Until 18 September 2026 they were openable with `./field.sh` and
invisible everywhere else. They are now all in the [Agent Transcript Gallery](agent_transcripts.md),
all offered on the welcome screen, and all checked on every build.

This page is the map. It exists because nineteen documents arriving in one evening is a corpus you
cannot browse: the gallery lists them in the order the subsystems happen to fall, and nothing says
which two to open first or what each one is *for*.

Every number below was produced by running something on 19 September 2026, not read out of prose.

## Open these two first

**[`read_only_session`](agent_transcripts.md#read_only_session)** — two boxes, thirty seconds, and it
is about the flag the other eighteen ask you to pass. A `saved{}` counter that counts up in an ordinary
session and refuses to in a read-only one, and a box on what `--read-only` is and which writes it
refuses rather than silently drops. Opening a committed document without it means Field writes a
backup, a screenshot, history metrics and an operation transcript back into the folder you opened.
Read this one and the other eighteen stop dirtying themselves.

**[`interaction_nets_lambda`](agent_transcripts.md#interaction_nets_lambda)** — three boxes, no window,
no GPU, no setup, and the most result per keystroke in the corpus. A λ-term compiled to an interaction
net, reduced, and read back, with every answer checked against a substitution evaluator that shares
nothing with it: 25 of 25 named closed terms agree with the oracle and 25 of 25 agree under four
different schedulers. Then open its companion,
[`confluence_grid`](agent_transcripts.md#confluence_grid), which runs twenty of those reductions at
once in 3D and lands each row on one shape.

## Grouped by what they teach

### Search, and its controls

| document | what it teaches | subsystem |
| --- | --- | --- |
| [`alife_evolution`](agent_transcripts.md#alife_evolution) | one population-search engine over four unrelated genomes, each arm against a control at the same budget and seed | [ALife](alife_examples.md) |
| [`completion_rank_at_scale`](agent_transcripts.md#completion_rank_at_scale) | re-scoring 578 recorded completion carets through the shipped weights | [Completion ranking](completion_ranking.md) |
| [`solidsl_inverse`](agent_transcripts.md#solidsl_inverse) | a solid you can differentiate, and therefore solve — three parameters recovered from a 42/45/112 % wrong seed | [Solid notation](solid_notation.md) |
| [`pattern_condense`](agent_transcripts.md#pattern_condense) | searching mini-notation for the shortest string that *plays* a given rhythm | [Pattern language](pattern_language.md) |

### Agents in a world

| document | what it teaches | subsystem |
| --- | --- | --- |
| [`alife_script`](agent_transcripts.md#alife_script) | giving one agent a program: sequencing, phases, composed steering drives | [ALife](alife_examples.md) |
| [`alife_walls`](agent_transcripts.md#alife_walls) | obstacles, *swept* collision, and routing round a barrier rather than into it | [ALife](alife_examples.md) |

### Reading a picture honestly

| document | what it teaches | subsystem |
| --- | --- | --- |
| [`chart_primitive`](agent_transcripts.md#chart_primitive) | `Chart`, the FLine plot primitive, in one call and four ways | [Charts](chart.md) |
| [`chart_clip`](agent_transcripts.md#chart_clip) | why a figure that does not fit inside itself shrinks rather than crops | [Charts](chart.md) |
| [`confluence_grid`](agent_transcripts.md#confluence_grid) | laying out isomorphic graphs by a structural invariant, so the picture cannot invent a difference the theorem forbids | [Three.js output](three_output.md), [Output panel sizing](output_panel_sizing.md) |
| [`keyframe_key_discovery`](agent_transcripts.md#keyframe_key_discovery) | whether the keyframe browser can find your own keys, and how it says it could not | [Keyframer](keyframer_examples.md) |

### Language and notation

| document | what it teaches | subsystem |
| --- | --- | --- |
| [`interaction_nets_lambda`](agent_transcripts.md#interaction_nets_lambda) | β-reduction as a local graph rule, with duplication as visible work | [Interaction nets](interaction_nets_examples.md) |
| [`python_cells`](agent_transcripts.md#python_cells) | a Kotlin root whose children run as Python, in one shared interpreter | [Python](python.md) |

### The machinery underneath

| document | what it teaches | subsystem |
| --- | --- | --- |
| [`logging_layer`](agent_transcripts.md#logging_layer) | three different rules that select on a channel, each made to prove itself | [Logging](logging.md) |
| [`read_only_session`](agent_transcripts.md#read_only_session) | read-only mode from inside a box | — |
| [`midi_clip`](agent_transcripts.md#midi_clip) | Standard MIDI Files written and read back, and what counts as a port | — |

### Rendering, and seeing it go wrong

All four need a GPU window. They are the only demos CI cannot run.

| document | what it teaches | subsystem |
| --- | --- | --- |
| [`path_tracing_emitters`](agent_transcripts.md#path_tracing_emitters) | emitter sampling on and off at the same sample budget | [Path tracing](path_tracing.md) |
| [`path_tracing_medium`](agent_transcripts.md#path_tracing_medium) | a participating medium, cycled through four densities over fixed geometry | [Path tracing](path_tracing.md) |
| [`path_tracer_instrument`](agent_transcripts.md#path_tracer_instrument) | what a renderer can say about itself when it has a value printer | [Path tracing](path_tracing.md) |
| [`frame_history_reprojection`](agent_transcripts.md#frame_history_reprojection) | debug views for reprojection, because a wrong answer still renders a plausible picture | [Fabricate renderer](fabricate_renderer.md) |

## What CI checks, exactly

All 19 are registered in `DemoTranscriptExecSmokeTest`: **14 are replayed end to end** and **5 are
built and compiled** with the terminal evaluation skipped, because they open a GLFW window or need an
interpreter the build cannot assume. Across the 19 documents' 65 boxes that is 46 executed, 15
compiled, and 4 neither — the four Python cells in `python_cells`, which are not Kotlin and are
skipped by name rather than fed to K2. So that scene is the one where CI verifies one box in five and
the rest is yours to run.

Every one of the 14 executed demos runs **every box it creates**: there is no box in this corpus that
is built by a transcript and then left unexecuted. A filtered replay of all 19 took 128 s of wall
clock on an Apple M1 Max, the whole corpus of 136 transcripts rather more.

## Two things to know before you edit one

**Nine of the nineteen ship a `build.yaml` beside the saved document**, and the script says, in its
own words, that re-running it rebuilds the document. Seven of the nine still do. Two do not:
`logging_layer` and `frame_history_reprojection` were edited in Field afterwards and re-saved with the
build script left behind — five boxes between them, and `logging_layer`'s older code has lost an
import and no longer compiles. Take the *document* as the truth for those two.
`tools/test_demo_build_scripts.py` now holds the other seven to it.

**Do not hand-edit a `metadata.edn`.** `AgentDemoDriftGuard` requires it to be byte-for-byte what
Field's own EDN codec writes, so an edit that looks right comes back modified from the next
regenerate, in every branch. Change the loose `<box>_code_.kt` sidecar, or change it in Field.
