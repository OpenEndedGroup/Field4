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

## MADP Practice Core

The MADP tools are single-page browser sketchbooks for a studio class: code on the left, art on
the right, and a program short enough to read in one sitting. Each card below is one of those
tools rebuilt in Field. The program the student writes keeps its shape and its names, but it
lives in boxes, so it can run beside other boxes, take their output and hand on its own. Each is
a starting point you can open from the welcome screen as an editable copy: change a line and
run it again. The card links to its entry in the [gallery](agent_transcripts.md), which says what
every box in the document does.

<div class="grid cards" markdown>

-   ![A three-lobed flower of blue-violet petals ringed by teal and green bands, streamed into Field's renderer window](assets/agent_transcripts_gallery/webrtc_shader_jam.png)

    **[The Classroom Shader Jam](agent_transcripts.md#webrtc_shader_jam).** One box publishes
    its shader as a live stream, and another picks anyone's stream, from Field or from a browser
    ShaderTrinket, and draws it in the renderer window. Read a classmate's picture into your own
    shader and the room becomes one patch.

-   ![A rippled pale-cyan terrain of triangles floating over a dark studio ground, casting a soft shadow onto it](assets/agent_transcripts_gallery/madp_geometry_terrain.png)

    **[Geometry](agent_transcripts.md#madp_geometry).** A model is a recipe of triangles emitted
    through a transform stack with a current colour and material, drawn in a studio-lit view you
    can orbit and export as GLB. The presets run from a rippled terrain to a Menger sponge.

-   ![The Barnsley fern: a tall green frond of fine glowing dust on black, its leaflets built of smaller leaflets](assets/agent_transcripts_gallery/fern_iterated_systems_barnsley.png)

    **[Fern](agent_transcripts.md#fern_iterated_systems).** Hundreds of thousands of particles
    each follow one rule and leave a fading trace, so a fern, the Lorenz attractor and a flame
    build up out of glowing dust. The rule is six short GLSL functions.

-   ![A few hundred small glowing dots in loose schools of cyan, green, yellow and pink, with faint threads between close neighbours](assets/agent_transcripts_gallery/particles_sketchbook_boids.png)

    **[Particles](agent_transcripts.md#particles_sketchbook).** Paint particles into the window
    with the mouse, and four GLSL hooks decide what they do. Seven sketches, from flocking to
    springs to collisions, show how far a handful of force helpers go.

-   ![A sphere of about a thousand small glowing splats on black, coloured by longitude from cyan through chartreuse to red and orange](assets/agent_transcripts_gallery/splat_shader_passthrough.png)

    **[Splat Shader](agent_transcripts.md#splat_shader).** A few lines of GLSL run once per
    Gaussian splat each frame and may move, resize, recolour or turn it. The edit runs before the
    depth sort, so splats thrown outward still draw in the right order.

-   ![A soft white ring on black, centred, fading smoothly to black on both sides](assets/agent_transcripts_gallery/stacktrinket_feedback_pulse_ring.png)

    **[StackTrinket](agent_transcripts.md#stacktrinket_feedback).** Programs in a stack-based
    shader language that read their own previous frame, so a few words leave trails, fog and
    bloom. Each box shows how deep its stack ends and the GLSL its program becomes.

-   ![A 40 by 25 grid of block and box-drawing glyphs, tilted at many angles, in hues running from green through blue to magenta](assets/agent_transcripts_gallery/madp_chargrid_plasma.png)

    **[charGrid](agent_transcripts.md#madp_chargrid).** A picture as a 40 × 25 grid of glyph
    numbers, rewritten once a tick. Reading the last tick is all Conway's Life needs, and a glyph
    can also be a note, so an arpeggio draws and plays at once.

-   ![A three-by-three grid of colour tiles: two rainbow parents, stripes and spokes, above seven mutated children, each with its program written under it](assets/agent_transcripts_gallery/madp_ga_lead.png)

    **[GA](agent_transcripts.md#madp_ga).** Interactive evolution after Karl Sims: each tile is a
    short colour program run once per pixel, and you are the fitness function. Click two tiles
    and they become the parents of the next generation.

-   ![Photographs of a cat, a dog, a tiger and a red car above a tetrahedron whose edges are labelled with the distances between them](assets/agent_transcripts_gallery/madp_tetra.png)

    **[Tetra](agent_transcripts.md#madp_tetra).** CLIP turns four pictures into four points, and
    the six distances between them become the edges of a tetrahedron. Any four such points fit
    in three dimensions, so the shape keeps all six distances exactly. Drop in your own pictures.

</div>
