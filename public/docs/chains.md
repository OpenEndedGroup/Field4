# Long way round

Most pages in these docs stay inside one part of Field: the pattern language, the Boolean kernel,
the path tracer, the drawing layer. The pages in this section each take one thing and pass it
through three or four of those parts, which were not written with each other in mind. A
cellular automaton becomes a rhythm. A cloth simulation becomes a welded solid you can count the
holes in, and then a render. A photograph becomes a plaque you could print. A drawing becomes a
score and then a `.wav`, and the `.wav` is taken apart again and laid over the drawing.

Each one is also a check. Every step has to hand the next one something it can use, and the
pages say what that is, what gets lost on the way, and which number shows whether it arrived
intact. When the areas of a sliced solid fail to add up to its volume, the surface passes through
itself. A function played as sound comes back late, and the page measures by how much, to the
sample.

Many of these pages begin as an [agent transcript](agent_transcripts.md): a short YAML macro
that builds the whole document from a blank canvas. The [gallery](agent_transcripts.md) lists
them all, and you can open any of them from the welcome screen as an editable copy and
start from there.

## Ending in light

These chains end in the GPU path tracer, and each starts somewhere a renderer would not expect.

<div class="grid cards" markdown>

-   ![A path-traced truncated icosidodecahedron: brushed-gold decagons, deep blue hexagons and transparent squares](assets/chain_polytope_render/lineage.png)

    **[A Polytope, Cut Open and Traced](chain_polytope_render.md).** Conway operators build the
    shape and remember where every face came from. That record survives the Booleans, so the
    render can colour each face by its origin.

-   ![A lattice of pale gold rods draped over an unseen sphere and pooling on a floor](assets/chain_physics_render/drape.png)

    **[Particles, Welded and Traced](chain_physics_render.md).** A cloth simulation becomes
    hundreds of capsules, and the capsules are welded into one solid whose holes you can count
    exactly.

-   ![Dozens of small blue faceted lumps floating over a pale floor, one larger lump in gold](assets/chain_life3d_render/clusters.png)

    **[Three-Dimensional Life, Counted and Traced](chain_life3d_render.md).** The question you
    want to ask a 3D automaton is how many separate things are left. Here one call answers it.

-   ![A smooth sculpture: a twisted ribbon rising from a flat base, joined to a long wedge](assets/chain_rectsl_spacetime/solid.png)

    **[A RectSL Animation as a Solid](chain_rectsl_spacetime.md).** Read the time input of an
    animation's distance field as a third axis and the animation becomes a sculpture whose
    cross-sections are its frames.

-   ![A blue limb pinched by twisting, in front of an undeformed gold limb](assets/chain_skinned_volume/twist.png)

    **[A Twisted Limb, Weighed and Traced](chain_skinned_volume.md).** Linear blend skinning
    squeezes a twisted joint into a candy wrapper. Turn the posed mesh into a solid to measure
    how much of the limb is lost.

-   ![Two cage-like sculptures of thin tubes over a dark floor, one tube in each glowing orange down to a red bead](assets/chain_petri_sculpture/sculpture.png)

    **[A Deadlock, Sculpted and Traced](chain_petri_sculpture.md).** Every marking the dining
    philosophers' Petri net can reach becomes a glass bead, every firing a tube, and the shortest
    route to the deadlock glows.

</div>

## Ending in sound

These chains end in something you can listen to, or start from it.

<div class="grid cards" markdown>

-   ![Rule 30's space-time diagram, a fan of white cells spreading from one cell at the top](assets/chain_ca_sound/hero.png)

    **[A Cellular Automaton You Can Hear](chain_ca_sound.md).** A row of an elementary CA
    becomes a step mask. `patCondense` runs the mini-notation parser backwards to spell it,
    and how short the spelling is tells you how structured the rule is.

-   ![Four concentric rings of particles in blue, orange, green and amber](assets/chain_pattern_particles/rings.png)

    **[A Pattern That Moves Particles](chain_pattern_particles.md).** The sound goes the other
    way here: a drum pattern is split into four bands, and those four numbers move 24,000
    particles in a compute shader.

-   ![Three rings of dust with bursts of coloured sparks leaving them](assets/chain_petri_sparks/sparks.png)

    **[A Petri Net That Throws Sparks](chain_petri_sparks.md).** One Petri net plays a drum part
    from the Dirt-Samples, and every transition that fires throws a burst of GPU sparks in its own
    colour.

-   ![A drawn score: blue piano-roll rectangles, a red glissando, a green held note and an orange playhead](assets/chain_drawn_score/hero.png)

    **[A Drawn Score, Heard](chain_drawn_score.md).** Rectangles and pen strokes on the drawing
    layer, read as time across and pitch up, then played and analysed again so you can put
    what you hear next to what you drew.

-   ![Agent and reducible-pair counts over twenty-six interaction-net reactions](assets/chain_lambda_sound/one_reduction.png)

    **[A λ-Term You Can Hear](chain_lambda_sound.md).** The order in which an interaction net
    fires its reactions becomes a rhythm, so changing the scheduler is something you can hear.

-   ![Two drawn strokes and the curves interpolated between them](assets/chain_drawn_morph/morph.png)

    **[A Drawn Morph, Heard](chain_drawn_morph.md).** Pair the points of two strokes and play
    the morph between them as pitch.

-   ![A function beside the pitch and onsets recovered from its recording](assets/chain_fn_sound_roundtrip/roundtrip.png)

    **[A Function, Played and Read Back](chain_fn_sound_roundtrip.md).** Play a function,
    detect its pitch and onsets in the recording, and draw both on one timeline next to the
    function's certified range.

</div>

## Ending in an object

These chains end in something you could print, mount, hang or light.

<div class="grid cards" markdown>

-   ![Forty-eight extruded layers of canonical gC, coloured by height](assets/chain_ceres_manifold_contours/stack.png)

    **[A Polytope, Canonicalised and Sliced](chain_ceres_manifold_contours.md).** Make a Conway
    polytope canonical with a Ceres solve and slice it into the layers a 3D printer would print.

-   ![A relief plaque and its frame rendered with the path tracer](assets/chain_photo_to_solid/mounted_path_trace.png)

    **[A Photograph, Closed and Mounted](chain_photo_to_solid.md).** Run a depth model on one
    still photograph and close the result into a solid plaque. No depth camera is involved.

-   ![A RectSL scene, the LED rig it drives and the matching received light values](assets/chain_scene_to_light/chain.png)

    **[A RectSL Scene on a Light Rig](chain_scene_to_light.md).** Send a scene over sACN,
    receive it back, and check that what came back matches the pixels the rig was sent.

-   ![An oil painting of a meadow at evening: a sky of horizontal strokes, low clouds, grass and red poppies](assets/chain_drawn_paint/meadow.png)

    **[Drawn Strokes Become Paint](chain_drawn_paint.md).** Pen strokes on the drawing layer,
    with their pressure, drag a simulated oil brush across a canvas of wet paint.

</div>
