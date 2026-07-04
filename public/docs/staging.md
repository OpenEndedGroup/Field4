# Staging Area

These pages are useful, but they are still moving. Some are smoke-tested examples. Some are notes from teaching documents. Some are the first public pass over systems that are still changing shape.

The order here is meant to help you choose where to go next.

## Reproducible docs and transcript automation

The examples in this staging area are increasingly built from the same machinery that automates Field itself. A docs page can contain a runnable code block, the MkDocs build can launch Field through the backdoor, and the resulting box output, editor region, window, or native graphics frame can be captured back into the page.

For simple examples, the runner creates a scratch box, runs the code, waits for output, and clips a browser screenshot. For graphics examples, the runner now uses a reusable `OperationTranscript` path:

- launch a renderer box from a YAML transcript;
- wait until that renderer reports that it is actually running;
- create the example box;
- connect the renderer box as the example's parent, so `fromContext<Renderer>()` works the same way it does in a normal Field document;
- run or launch the example;
- capture the native `afield.four` window through Field's own renderer screenshot path.

That topology step matters. A graphics box is not supposed to find a renderer by accident or by sleeping long enough. It should inherit a live renderer from its parent, just like user-authored Field graphs do. This means docs captures double as real integration tests for the box graph, context lookup, Kotlin execution, Vulkan renderer startup, and screenshot plumbing.

The current build modes are:

```bash
cd public && FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 \
  uv run --project .. --group docs mkdocs build
```

Use `always` when every screenshot or video should be regenerated. Use `incremental` while writing normal docs, `strict` when runner changes should invalidate cached captures, and `never` when you only want to rebuild the site HTML from already-generated assets.

Reusable transcript examples live under `dev_docs/agents/transcripts/`. The most important graphics one is `graphics_renderer_launch.yaml`; it builds and launches the renderer parent that native graphics docs attach their examples to. The public result is modest but important: when a page shows a rendered triangle, buffer example, material test, or ray-traced image, that image was produced by Field during the docs build rather than pasted in by hand.

## Field systems

- [Fabricate Renderer](fabricate_renderer.md) checks the simplest path through `StandardRenderer`: a default scene, material response, shadows, and ambient occlusion.
- [afield.four Graphics](afield_four_graphics.md) exercises the lower-level renderer path using the transcript-driven renderer parent described above.
- [Ray Tracing Examples](ray_tracing_examples.md) uses the same graphics-context capture route for Vulkan ray tracing examples.
- [Interactive Guides](interactive_guides.md) shows how boxes can teach a document: slide notes, selections, callbacks, templated values, chat, and callout lines.
- [Guide Chat](guide_chat.md) covers the optional chat service used by slideshow steps.
- [VR Drawing Sketch](vr_sketch2.md) is a first OpenXR drawing exercise, built around the `field.sketch2` templates.

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
