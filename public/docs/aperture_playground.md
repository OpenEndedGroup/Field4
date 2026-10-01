# Aperture playground

Defocus spreads light over an aperture-shaped footprint. A thin line should become wider
and dimmer as it loses focus; decreasing its alpha alone leaves a dim, sharp line.
This starting point makes that distinction easy to edit and inspect.

Open **aperture_playground** from the welcome screen, or
[browse its boxes and shaders](offline.html?file=assets/agent_transcripts/aperture_playground/metadata.edn).
To start the whole graph, hold **Shift–Alt** and drag a short launch stroke through
**1. Renderer**. Field's existing gesture includes its descendants and launches them in
order. Use **Stop Box** (Alt–Down in the renderer's editor) to stop. Repeat the same
Shift–Alt stroke to rebuild and restart the passes, preserving your shader edits.

You can also launch the renderer alone, then execute boxes 2–8 in number order. Relaunching
only the renderer creates a fresh graphics context, so the passes must be executed again.
Shader edits and reload use Field's existing shader properties. After changing a texture's
size, execute its downstream resource boxes again.

For an automated setup, run `dev_docs/agents/transcripts/aperture_playground.yaml` as a
workspace macro in an empty document. It launches the same renderer and executes the
same boxes. Replay restores the example code; continue editing individual boxes afterward.

```diffstatic_runner {data-lang=kotlin screenshot="assets/aperture_playground/aperture.png" capture="native-window" native-wait-frames="12" embed=true hidden=true timeout="180" name="aperture-playground" alt="Four rows of lines and discs, progressively broader and dimmer from top to bottom, rendered through Field's editable aperture passes; an analytic sprite column is on the right." macro-file="../../dev_docs/agents/transcripts/aperture_playground.yaml" capture-box="agent_graphics_context"}
// The transcript creates the real renderer, textures and editable shader graph.
```

## What each box owns

| Box | Owns | Try changing |
| --- | --- | --- |
| 1. Renderer | Window and renderer | Window size |
| 2. Source RTT | RGBA16F source and one shader pair | `mesh.look` radius and energy; line and disc shapes |
| 3–4. Reductions | One shader pair and one reduced texture each | Sampling footprint |
| 5. Medium aperture | One shader pair and a filtered texture | Disc sampling and weights |
| 6. Broad aperture | One shader pair and a filtered texture | Aperture shape or sampling pattern |
| 7. Composite | One shader pair | Sharp source, defocused result, or guide display |
| 8. Analytic sprites | One shader pair | Disc/aperture overlap and sprite radius |

The reduction boxes nest inside the source box when it runs. All wiring remains in the
Field document. There is no backend class hiding the shader stages.

RGBA store **four independent light bands**, corresponding to radii 0, 4, 10 and 24
framebuffer pixels. A source with an intermediate blur radius distributes its energy
between neighbouring bands. Half-resolution and quarter-resolution reductions make the
larger footprints affordable. Circular filters spread each band, and the final pass adds
them. Alpha carries the broadest band, so intermediate blending is disabled.

The rows increase defocus from top to bottom. The right-hand column evaluates circular
sprite/aperture overlap directly, alongside the RTT result. Use it to compare thin-line
and point behaviour. In **Composite**, `controls.x` sets exposure; `controls.y` chooses 0 for filtered output, 1 for the
sharp source, or 2 for the radius guide. The filters preserve integrated energy approximately,
so a very broad line can become faint. Increase source energy separately to inspect it.

This is a four-band approximation for additive light, not a depth-buffer camera model.
It does not solve foreground/background occlusion across overlapping layers. For a
scene with opaque depth layers, keep those layers separate or introduce depth-aware
compositing. Radius is measured in framebuffer pixels and must be reconsidered when
rendering at a different resolution.

The underlying APIs are the same ones introduced in
[render-to-texture and blur lessons](afield_four_graphics.md). Combine the graph with
[frame budgets](profiling_timeline.md#holding-a-frame-budget) when comparing CPU work, and the renderer inspector for
the render passes themselves.
