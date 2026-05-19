# Staging Area

These pages are useful, but they are still moving. Some are smoke-tested examples. Some are notes from teaching documents. Some are the first public pass over systems that are still changing shape.

The order here is meant to help you choose where to go next.

## Field systems

- [Fabricate Renderer](fabricate_renderer.md) checks the simplest path through `StandardRenderer`: a default scene, material response, shadows, and ambient occlusion.
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
