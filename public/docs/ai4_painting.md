# AI4 Paints

The Studio is one box that turns the [paint simulator](paint_canvas.md), built on alice's
[claude-paint](https://github.com/aliceisjustplaying/claude-paint), into a place where the AI chat
can paint. The box puts an oil canvas on an easel and gives the agent tools to paint on it. You
open the box's AI panel and ask for a painting, and the agent lays it in, looks at it, corrects it
and hangs it in the box's gallery. The three paintings below came from one conversation with the
Studio. Open the [ai4_paint_studio](agent_transcripts.md#ai4_paint_studio) starting point to try
it yourself; it needs an AI provider and the paint library (`./gradlew buildPaintNative`, see
[Paint and pencil from FLines](paint_canvas.md#building-the-library)).

![three paintings of the same harbour on a black ground. Left: dusk, a violet sky warming to apricot at the horizon, dark headlands on either side, three small moored boats with reflections on pale still water. Middle: the same harbour at night, deep blue sky and water, a pale moon, a broken column of silver reflections, and tiny amber lights on the boats. Right: a stormy morning, heavy grey-violet clouds over a pale band of light, dark teal water broken by white caps, the boats heeling](assets/ai4_painting/three.jpg)

The first request asked for a quiet harbour at dusk; the second turned it into night. Then the
chat was rewound to before the second request, restoring the dusk canvas. A new request painted a
stormy morning over it. The night painting remains on its own branch: `/branch` brings back both
the conversation and the canvas.

## Asking for a painting

Open the Studio box's AI panel and describe what you want. The tools are already there, so you
do not need to mention them:

> Paint a quiet harbour at dusk on the easel: a warm sky going from apricot to violet, still water
> with soft reflections, a dark far headland, and two or three small moored boats. Work in stages
> the way a painter does, and look at the canvas as you go. When you are happy with it, hang it as
> 'Harbour at dusk'.

The agent can see the canvas, and it uses that the way a painter steps back from the easel. Each
frame below is the canvas as the agent saw it when it looked, and the last is the painting it hung:

![eight frames on black showing the dusk painting being made: the bare warm-brown oil ground; four broad patches of violet, apricot and grey-blue with the ground showing between them; the same colours laid as continuous bands and blended; the sky in soft horizontal layers above a level waterline; the water with dark headlands left and right; three boat hulls with masts and moorings; mast reflections and glints on the water; and the hung painting](assets/ai4_painting/dusk.jpg)

It worked from big shapes to small ones without being told to: four broad masses of colour first,
then body colour blended into bands, then the waterline and headlands, then the boats, and the
reflections last. Between looks it mostly painted through [tool programs](aiFeatures.md#many-calls-at-once),
one short program for each stage, such as "lay in four broad dusk colour masses".

Looking is also how it recovers from a mistake. Turning the dusk into night, it laid a navy glaze
over the whole canvas, and the glaze came out almost black. It saw that on the next look and
painted the night's midtones back over it:

![eight frames on black showing the night painting: the dusk canvas; an almost black canvas after a navy glaze; deep blue sky and water repainted over it; a pale moon in a round stippled halo above a broad silver column on the water; the halo fainter and amber lanterns on the boats; the halo gone and the column broken into ripples; the waterline and mast tips refined; and the hung night painting](assets/ai4_painting/night.jpg)

The storm started from the dusk canvas the rewind restored. The first frame is that canvas, and it
is identical to the hung dusk painting:

![six frames on black showing the storm painting: the dusk canvas as the rewind restored it; heavy dark clouds and teal water blocked in over it; cloud banks and rows of wave strokes; the boats repainted heeling, with white caps; foam and spray stippled over the crests; and the hung storm painting](assets/ai4_painting/storm.jpg)

## Browsing the saved paintings

Because the Studio saves its canvas with the conversation (see
[What the box defines](#what-the-box-defines)), the AI chat's **States** tree works here too. Each
completed request adds a node with its saved canvas. While the first painting is still being made,
the tree is empty: the agent's looks along the way are progress images, not saved states. The open
tree updates when a request finishes.

Select an earlier node to see its conversation and which saved canvas it holds. The cyan
**current** circle stays where the conversation is; browsing another node leaves the easel alone. **Reference in chat** inserts a compact reference so AI4 can
retrieve that state's recorded contents when asked. **Keep for comparison**, followed
by **Discuss differences** on another node, prepares references to both in your draft.
The inspector shows saved text and ancestry; it does not reconstruct a canvas preview.

![The States panel for the harbour conversation: the Studio · Conversation history with 11 saved states drawn as a branching graph, each request a turn node followed by append nodes; the earlier "Harbour at dusk" node is selected, and the inspector beside it lists its parent turn, the registered canvas snapshot branch:ai4_paint_studio:studio:canvas, and the start of its saved transcript, with Reference in chat and Keep for comparison above](assets/ai4_painting/states.png)

??? aside "How this picture is made"
    `tools/capture_ai4_painting_states.py` restores the saved harbour conversation from
    `assets/ai4_chat_archive/chats/harbour` on a Field it starts, checks the restored chat matches
    the saved one, opens **States**, selects *Harbour at dusk*, and crops the window to the panel.
    It calls no model, so it can be rerun; the docs build cannot, because the conversation itself
    came from a paid live run, so the PNG is committed.

For a named alternative, ask AI4 to fork a saved state under a name such as
*Harbour at dusk*. The fork is saved without switching the easel to it, and AI4 can read it
back later by that exact name. Continue the alternative
with the existing `/branches` and `/branch <number>` commands, which restore the
Studio's canvas as well as its conversation. [Browsing saved states](aiFeatures.md#browsing-saved-states)
describes the tools and how other documents expose their own histories.

## Brushes the conversation keeps

A fresh brush comes clean from the palette. A brush the agent keeps (`paint_tool`, then
`paint_load` and the `tool` argument of `paint_stroke` and `paint_dab`) stays dirty between
requests: dragged through wet paint, it carries some of that colour into its next stroke, and a
reload mixes into what is left in it unless the agent wipes it. A kept knife keeps its bead and a
kept rag its load. The canvas context tells the agent what each kept tool carries, and `/tools` shows
it to you.

The kept tools belong to the conversation, like the canvas. Here a request asked for an evening
landscape painted with a kept sky brush and a kept land brush, a knife and a rag; a second request
turned it into a storm, reloading the sky brush without wiping it. Rewound to before the storm,
the canvas and all four tools went back to how the evening left them, and a new request for a
moonlit night was painted from there, with the sky brush as the evening had left it:

![three paintings on black of the same landscape: pale sky over grey-green hills and a dark field; the same field under a dark blue-grey storm sky with pale spatters of rain across the whole picture; and the evening landscape repainted as a night with a deep blue sky and a small pale moon](assets/ai4_painting/kept_tools.jpg)

## Watercolour, and brushes whose hairs move

On a sheet of paper (`paint_fresh` with `ColdPress`, `HotPress`, `Rough`, `Xuan` or `Blotting`) the
agent paints in [watercolour and ink](watercolor.md): `paint_wash` with a watercolour brush,
`paint_drop`, `paint_wet` to wet a region, and `paint_tilt` to tilt the board. The water then
moves on its own, so the agent waits for it (`paint_wait` with seconds) and can look at where the
paper is still wet (`paint_look` with `map="surface"`).

A brush kept with `paint_tool` and `moving=true` is a [brush with moving hairs](paint_gestures.md).
The agent paints with it through `paint_gesture`, giving the speed, pressure along the stroke, and
the tilt and roll of the handle, for the marks that depend on the hand.
[Paint settings, swept](paint_sweeps.md#the-hand) shows what tilt and speed do to one gesture. Below, the agent was asked
for grasses at the edge of a wood, scrubbing the wood in with a moving hog flat, flicking the
blades up with a moving rigger and rolling the flat through a path; then to scrub the hog hard
along the bottom edge and lay three light strokes with it. The conversation was then rewound to
before the scrub, and both brushes came back exactly as the grasses had left them.

![three oil paintings on black of the same scene: a dark green wood scrubbed in with broad zigzag strokes over the top half, a field of olive grass below with pale flicked grass blades, and a cream path winding up through it, laid with a flat brush rolled as it went. In the middle painting, a broad flat cream band has been scrubbed along the bottom edge and three long, light cream strokes cross the grass. The third painting is the first again, as the rewind restored it](assets/ai4_painting/moving_hairs.jpg)

??? aside "How the moving-hairs figure was made"
    One live run on 2026-10-08, OpenAI `gpt-6.1-sol`, in a fresh Studio, from one backdoor connection:
    two requests as real panel turns (`agent-task --chat-box Studio`) and the rewind as the panel sends
    it. The first and middle frames are the PNG `paint_hang` wrote and the agent's own `paint_look` after
    the second request; the third is its first look after the rewind. A probe box hashed both kept
    brushes' `state()` and the canvas between the steps: after the rewind all three hashed as they had
    after the first request (the hog's retained bend back to 0.005 rad from 0.013). The docs build
    cannot remake the figure, since that needs a model.

## Editing strokes by address

The paint tools above lay paint on the canvas, and once it is there it stays paint. Strokes kept
as data ([Strokes kept as data](paint_canvas.md#strokes-kept-as-data)) stay editable: each has an
address, `s12`, and the chat can recolour, move, bend or delete it later. `strokeTools` gives a
box's AI chat the tools for that, over a `PaintStrokes` sheet:

- `strokes_read` lists the pinned notes, each with the strokes under it, and then every stroke
  with its address, group, colour, brush and bounds;
- `strokes_apply` takes a JSON batch of edits, applied together and undone together;
- `strokes_undo`, `strokes_redo`, `strokes_near` (the strokes under a point) and `strokes_look`.

A `strokes` context tells the agent about every pinned note at the start of each request, so a
note is a way of pointing at part of the picture: "more moss on this side". This box pins that
note on a copy of the creature from the paint page and hands its chat the tools. Open its AI panel
and ask it to act on the note:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ai4_painting/strokes_note.png" alt="two copies of a round green painted creature with green leaves on its head, pale eyes, a smile, a pink cheek smudge and brown legs, side by side and identical, with a status line under them: 24 strokes, 1 notes, more moss on this side" wait="20" capture="box" open-outputs="true" padding="20" requires="paint" name="ai4-strokes-note" embed=true}
:::static
:::fold the creature from the first example
// points around an ellipse from angle a0 to a1 (radians), as [x0, y0, x1, y1, ...]
fun arc(cx: Double, cy: Double, rx: Double, ry: Double, a0: Double, a1: Double, n: Int = 24) =
    (0..n).flatMap { val a = a0 + (a1 - a0) * it / n; [cx + rx * cos(a), cy + ry * sin(a)] }

val sheet = PaintStrokes(700, aspect = 1.4, seed = 3)
// the body: rows of a hog flat, filling an ellipse
for (i in -5..5) {
    val y = 400.0 + i * 26
    val w = 190 * sqrt(1 - (i / 6.0).pow(2))
    sheet.add([500 - w, y, 500 + w, y + 6], color = if (i % 2 == 0) "#8a9a3a" else "#a0a848", brush = BrushKind.HogFlat, width = 34.0, group = "body")
}
// legs, and leaves sprouting from the head
sheet.add([430.0, 540.0, 420.0, 640.0], color = "#4a3a20", brush = BrushKind.HogFlat, width = 26.0, group = "legs")
sheet.add([570.0, 540.0, 585.0, 640.0], color = "#4a3a20", brush = BrushKind.HogFlat, width = 26.0, group = "legs")
for (a in [-2.3, -1.9, -1.57, -1.25, -0.85]) {
    val x = 500 + 60 * cos(a); val y = 290 + 30 * sin(a)
    sheet.add([x, y, x + 60 * cos(a) - 12, y + 60 * sin(a), x + 120 * cos(a), y + 110 * sin(a)],
        color = "#2f6a2a", brush = BrushKind.Filbert, width = 30.0, pressure = 0.9 to 0.4, group = "leaves")
}
// eyes, a smiling mouth and a pink smudge on one cheek
for (x in [440.0, 560.0]) {
    sheet.add([x - 8, 360.0, x + 8, 362.0], color = "#f4f0e4", width = 44.0, pressure = 1.0 to 1.0, group = "eyes")
    sheet.add([x, 364.0, x + 8, 365.0], color = "#1a1a1a", width = 16.0, pressure = 1.0 to 1.0, group = "eyes")
}
sheet.add(arc(500.0, 420.0, 70.0, 45.0, 0.4, PI - 0.4), color = "#3a1a10", width = 10.0, group = "mouth")
sheet.add([400.0, 440.0, 410.0, 445.0], color = "#e07080", brush = BrushKind.Badger, width = 50.0, hiding = 0.6, group = "smudge")
:::endfold
val edited = thisBox.properties.getOrPut("strokes.sheet") {
    sheet.copy().also { it.pin(660.0, 430.0, "more moss on this side") }
} as PaintStrokes

fun show() {
    display("main", pngRow([sheet.render(), edited.render()]))
    markdown("status", "${edited.strokes.size} strokes, ${edited.notes.size} notes " + edited.notes.joinToString { "\"${it.text}\"" })
}

agentExtension("strokes") { strokeTools(sheet = { edited }, changed = { show() }) }
show()
```

The sheet is kept on the box, so running the box again keeps what the chat did. After each edit
the tools call `changed`, here `show()`, which paints the sheet again.

The Studio has the same tools. Its strokes are painted over the canvas as it was when the first
one was added, so the chat can block a picture in with the paint tools and then lay strokes it
can come back to. The next paint tool paints on the canvas itself and takes those strokes into
it, after which they can no longer be edited. The strokes are part of the conversation as the
canvas is: rewinding the chat brings back the strokes as they were.

## The same request, another model

The Studio works with whichever model the AI chat is set to use. The painting on the left is the dusk
painting above, by OpenAI's `gpt-6-astra`. The one on the right is the same request in a fresh Studio,
by `gpt-6.1-sol`:

![two paintings of the harbour at dusk side by side on black. Left, by gpt-6-astra: a clear apricot glow at the horizon under violet bands, dark headlands at both edges, three moored boats with thin reflections on pale still water. Right, by gpt-6.1-sol: the same violet-to-apricot sky over a band of mist along the horizon, one dark headland on the left, a long streak of light on the water, and three boats sitting in heavier, darker reflections](assets/ai4_painting/two_models.jpg)

Sol worked the same way, from broad masses to boats and reflections, also in six tool programs, and
also took about three minutes. Its painting is mistier. At the fourth look it had laid a few pale
patches of reflected light, and by the next look it had worked them into the water:

![eight frames on black showing gpt-6.1-sol painting the harbour: the bare oil ground; violet, apricot and grey-blue laid in as loose patches; continuous body colour in bands; a dark headland on the left and pale patches of reflected light on the water; three hulls with slender masts; darker reflections under the boats and fine rigging; the reflections softened; and the hung painting](assets/ai4_painting/sol_dusk.jpg)

These commands work in the Studio's chat:

- `/canvas` shows the canvas in the chat.
- `/palette` lists the tube paints with a swatch of each.
- `/tools` lists the kept tools and what each one carries.
- `/gallery` lists the hung paintings, which the box shows in its `gallery` panel.
- `/branches` lists the versions of the conversation, and `/branch <n>` switches to one.

To start a version from an earlier point, use the rewind button on one of your own requests. The
conversation, and the canvas and the kept brushes with it, go back to how they were before that
request.

!!! note "A canvas is large"
    Each saved state of the Studio's canvas is about 28 MB on disk, kept in the document's `ai4_blobs/state`
    folder. Every request that changes the painting adds one; `pruneAiStates(dryRun = false)` frees all
    but the latest three along each branch. The kept tools are small beside it, some 20 kB a request.

## What the box defines

All of this is ordinary box code, in one `agentExtension` block. A tool is an
[`aiTool`](aiFeatures.md), here the one that dabs a loaded brush at a list of points. `canvas()`,
`brushFor()`, `loadInto()`, `rad()` and `done()` are small helpers defined above it in the same box:
`brushFor` takes a kept brush by name, or a fresh one of the kind asked for.

```diffstatic {data-lang=kotlin}
tool("paint.dab") {
    describe("Touch the brush down at points and lift it: dabs, petals, leaves, highlights. One load for all of them.")
    effects(ToolEffect.CONTROL_RUNTIME)
    val points by param<List<Double>>("[x0, y0, x1, y1, ...]")
    val color by param<String?>(
        "#rrggbb, a tube name, or tubes by parts; null dabs a kept tool with what it carries",
        default = null,
    )
    val tool by param<String?>("a kept brush's name to dab with; null for a fresh brush", default = null)
    val brush by
        param<String>("Round, HogFlat, Filbert, Fan, Rigger, Badger or Stippler (a fresh brush)", default = "Round")
    val width by param<Double>("brush width in canvas units", default = 12.0)
    val pressure by param<Double>("harder makes a bigger, fuller mark", default = 0.6)
    val angle by param<Double>("a flat's wide axis, degrees", default = 0.0)
    action {
        val c = canvas()
        val b = brushFor(tool(), brush(), width(), color())
        loadInto(b, color())
        points()
            .chunked(2)
            .filter { it.size == 2 }
            .forEach { c.touch(b, it[0], it[1], pressure = pressure(), angle = rad(angle())) }
        done("${points().size / 2} dab(s)")
    }
}
```

Colours can be hex values or tube paints mixed by parts, such as
`"lead white:4, cadmium yellow:1"`. The Studio gives the agent tools for strokes, filled regions
with painterly handling (`Broad`, `Body`, `Detail`, `Glaze`, `Scumble`, `Blend`), glazes, pencil,
chalk, hatching and stippling, as well as a knife, rag and spatter.

It can keep and reload a brush, make timed gestures with moving hairs, paint washes and drops,
wet or tilt the paper, and wait for paint to dry. Other tools start a fresh surface, hang a painting
or age it; the ageing tool is loaded when the agent asks for it.

The agent also needs to know the canvas it is painting on. A `context` block is text the agent
reads at the start of every request, computed then, so it describes the canvas as it is:

```diffstatic {data-lang=kotlin}
context("canvas") {
    val c = canvas()
    "The canvas on the Studio easel is 1000 units wide and ${c.heightUnits.roundToInt()} tall (aspect ${c.aspect}), " +
        "x to the right, y DOWN from the top edge. ${state()}."
}
```

`state()` reports how much of the paint is still wet. A second `context` block tells the agent how
to paint and which tubes there are. That one never changes, so it is marked
`changesOften = false` and the providers can cache it.

The canvas follows the conversation because of one more call. `branchState` hands the canvas to
the chat, which saves it before and after every request and restores it when the conversation
moves. The kept tools have a second `branchState` of their own, `PaintToolbox.save` and `load`:

```diffstatic {data-lang=kotlin}
branchState(
    "canvas",
    save = { canvas().saveState(it.path) },
    restore = { f ->
        replace(f?.let { PaintCanvas.load(it.path) } ?: fresh())
        show()
    },
    onConflict = BranchConflict.RESTORE,
    resumeOnOpen = true,
)
// and so do the kept tools, each bristle's paint and all
branchState(
    "tools",
    save = { tools().save(it) },
    restore = { f -> thisBox.properties["studio.tools"] = f?.let { PaintToolbox.load(it) } ?: PaintToolbox() },
    onConflict = BranchConflict.RESTORE,
    resumeOnOpen = true,
)
```

`restore` gets `null` when the conversation has no saved canvas, which here means starting on a
fresh one (and, for the tools, an empty toolbox). Before restoring, the chat checks whether the canvas changed since the conversation last
saw it, for instance because a tool was run on it from outside the chat. By default a changed state
is left as it is, and the chat says so. The Studio's canvas belongs to the conversation, so it asks
for `BranchConflict.RESTORE`: the chat restores anyway, after saving the canvas it replaces.
`resumeOnOpen = true` puts the canvas back where the latest conversation left it when the document
is opened again. Leave both at their defaults for state a person also edits by hand, or that the
document saves itself, such as a design. [Agent extensions](aiFeatures.md#more-than-a-tool-agentextension)
has the rest of what `agentExtension` can add.

??? aside "How these figures were made"
    The Astra figures are from one live run on 2026-10-03, OpenAI `gpt-6-astra`, and the Sol figures
    from a second, later that day, `gpt-6.1-sol`, given only the dusk request in a fresh Studio. In the
    first run the three requests were sent as
    real panel turns (`agent-task --chat-box Studio` in `tools/field_backdoor.py`) and the rewind and
    branch switch sent as the panel sends them. The frames are the images the agent's `paint_look`
    calls returned, which the Studio also writes to the document's `progress/` folder, and the last
    frame of each sheet is the PNG `paint_hang` wrote. `tools/ai4_painting_sheets.py` lays them out
    on black and adds the captions; it draws nothing on the frames. The docs build cannot remake
    them, since that needs a model. Hanging the canvas after the rewind and after the switch back
    gave PNGs byte-identical to the hung dusk and night paintings, which is how the restores were
    checked.

??? aside "How the kept-tools figure was made"
    One live run on 2026-10-08, OpenAI `gpt-6.1-sol`, in a fresh Studio: the three requests were sent
    as real panel turns (`agent-task --chat-box Studio`), and the rewind as the panel sends it, all
    from one backdoor connection. The paintings are the PNGs `paint_hang` wrote. Between the steps a
    probe box hashed every kept tool's `state()` and the canvas: after the rewind the four tools and
    the canvas hashed exactly as they had after the first request, and `/branch` back to the storm
    brought back the storm's hashes. The docs build cannot remake the figure, since that needs a model.
