# Staging Area

These pages are useful, but they are still moving. Some are smoke-tested examples. Some are notes from teaching documents. Some are the first public pass over systems that are still changing shape.

The order here is meant to help you choose where to go next.

## Reproducible docs and transcript automation

The examples in this staging area are increasingly built from the same machinery that automates Field itself. A docs page can contain a runnable code block, the MkDocs build can launch Field through the backdoor, and the resulting box output, editor region, window, or native graphics frame can be captured back into the page.

For simple examples, the runner creates a scratch box, runs the code, waits for output, and clips a browser screenshot. For graphics examples, the runner now uses a reusable `OperationTranscript` path:

- launch a renderer box from a YAML transcript;
- wait until the native renderer window can advance and capture frames;
- create the example box;
- connect the renderer box as the example's parent, so `fromContext<Renderer>()` works the same way it does in a normal Field document;
- run or launch the example;
- capture the native `afield.four` window through Field's own renderer screenshot path.

That topology step matters. A graphics box is not supposed to find a renderer by accident or by sleeping long enough. It should inherit a live renderer from its parent, just like user-authored Field graphs do. This means docs captures double as real integration tests for the box graph, context lookup, Kotlin execution, Vulkan renderer startup, and screenshot plumbing.

For graphics captures, the docs runner also takes ownership of the configured `FIELD_DOCS_RUNNER_PORT`. If a stale Field process is already serving that port, the runner asks it to shut down and starts a fresh one before it launches the transcript. That keeps renderer topology from accumulating across repeated docs builds.

Native graphics screenshots can opt into background trimming with `trim-native=true` on the runner fence. Use it for `capture="native-window"` examples where the renderer produces a small subject inside a large black window.

The current build modes are:

```diffstatic {data-lang=bash}
cd public && FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 \
  uv run --project .. --group docs mkdocs build
```

Use `always` when every screenshot or video should be regenerated. Use `incremental` while writing normal docs, `strict` when runner changes should invalidate cached captures, and `never` when you only want to rebuild the site HTML from already-generated assets.

The practical test matrix is:

| Situation | Command | What it checks |
| --- | --- | --- |
| HTML-only docs build | `FIELD_DOCS_RUNNER_MODE=never uv run --project .. --group docs mkdocs build` | Markdown, navigation, links, and checked-in/generated assets, without launching Field. |
| Normal docs authoring | `FIELD_DOCS_RUNNER_MODE=incremental FIELD_DOCS_RUNNER_PORT=9477 uv run --project .. --group docs mkdocs build` | Changed captures are run through Field; unchanged captures use the cache. |
| Runner or transcript changes | `FIELD_DOCS_RUNNER_MODE=strict FIELD_DOCS_RUNNER_PORT=9477 uv run --project .. --group docs mkdocs build` | Capture behavior changes invalidate the cache. |
| Full recapture | `FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project .. --group docs mkdocs build` | Every screenshot and video is regenerated from a live Field session. |
| Offline asset ownership | `uv run python tools/check_offline_assets.py` from the repository root | Monaco, Iosevka, and offline viewer sources are present; generated public copies are not checked in twice. |

At the end of a build, the runner prints a compact summary with regenerated captures, skipped captures, failures, server starts/restarts, and elapsed time. That line is the first place to look when a docs build feels suspiciously fast, slow, or quiet.

Reusable transcript examples live under `dev_docs/agents/transcripts/`. The most important graphics one is `graphics_renderer_launch.yaml`; it builds and launches the renderer parent that native graphics docs attach their examples to. The public result is modest but important: when a page shows a rendered triangle, buffer example, material test, or ray-traced image, that image was produced by Field during the docs build rather than pasted in by hand.

## Field systems

- [Fabricate Renderer](fabricate_renderer.md) checks the simplest path through `StandardRenderer`: a default scene, material response, shadows, and ambient occlusion.
- [afield.four Graphics](afield_four_graphics.md) exercises the lower-level renderer path using the transcript-driven renderer parent described above.
- [Ray Tracing Examples](ray_tracing_examples.md) uses the same graphics-context capture route for Vulkan ray tracing examples.
- [Interactive Guides](interactive_guides.md) shows how boxes can teach a document: slide notes, selections, callbacks, templated values, chat, and callout lines.
- [Guide Chat](guide_chat.md) covers the optional chat service used by slideshow steps.

## Small languages

- [Forth Examples](forth_examples.md) covers Field's small stack language, its output helpers, and its bridge into JVM objects.
- [Nano Examples](nano_examples.md) covers the tiny assembly-like machine and its RAM/register visualizers.

## Drawing from data

- [Functions Examples](functions_examples.md) turns `Functions` stacks into graphs and parametric FLines.
- [Interpolants Examples](interpolants_examples.md) draws smoothing behavior.
- [Timeseries Examples](timeseries_examples.md) keeps raw signals next to filtered and event-detected signals.
- [Stats Examples](stats_examples.md) keeps summaries attached to distributions and point streams.
- [Colors Examples](colors_examples.md) compares color helpers on actual geometry.
- [Correlations Examples](correlations_examples.md) plots dependence measures instead of treating them as numbers in isolation.
- [Smile Neighbors Examples](smile_neighbors_examples.md) draws exact, approximate, and radius-based neighborhood queries.
- [Graph Connectivity Examples](graph_connectivity_examples.md) draws geometric graph families, 3D graph viewers, and pathing overlays.

## Networks and dynamics

- [Sorting Networks Examples](sorting_networks_examples.md) draws fixed comparator plans, disorder decay, and annealing histories.
- [Interaction Nets Examples](interaction_nets_examples.md) shows reducer rules as before/after geometry.
- [Boolean Networks Examples](boolean_networks_examples.md) connects wiring diagrams, state spaces, attractors, and rhythm grids.
- [Boolean Network Music Examples](boolean_network_music_examples.md) maps Boolean network steps into `NoteProcess` and live Dirt triggers.

Missing from this section, on purpose for now: a proper guide to `afield.four`, the Vulkan renderer, modules, buffers, targets, shader plumbing, and the fabricated layer that sits on top of it. The Fabricate page is only a renderer smoke test, not that guide.
