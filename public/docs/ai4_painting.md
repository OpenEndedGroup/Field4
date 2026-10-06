# AI4 Paints

*The document on this page is the [ai4_paint_studio](agent_transcripts.md#ai4_paint_studio)
starting point. It needs the paint library (`./gradlew buildPaintNative`, see
[Paint and pencil from FLines](paint_canvas.md#building-the-library)) and an AI provider.*

The Studio is one box that turns the [paint simulator](paint_canvas.md) into a place where the AI
chat can paint. The box puts an oil canvas on an easel and gives the agent tools to paint on it. You
open the box's AI panel and ask for a painting, and the agent lays it in, looks at it, corrects it
and hangs it in the box's gallery. The three paintings below came from one conversation with the
Studio:

![three paintings of the same harbour on a black ground. Left: dusk, a violet sky warming to apricot at the horizon, dark headlands on either side, three small moored boats with reflections on pale still water. Middle: the same harbour at night, deep blue sky and water, a pale moon, a broken column of silver reflections, and tiny amber lights on the boats. Right: a stormy morning, heavy grey-violet clouds over a pale band of light, dark teal water broken by white caps, the boats heeling](assets/ai4_painting/three.jpg)

The first request asked for a quiet harbour at dusk. The second asked to turn that painting into
night, and the agent painted over the dusk canvas. Then the chat was rewound to before the second
request, and the canvas went back to the dusk painting with it. The new second request was for a
stormy morning, so the storm was painted on top of dusk too. The night painting was not lost:
`/branch` switches the conversation back to it, and the canvas comes back with it.

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

The Studio's `branchState("canvas", ...)` registration also enables **States** in the
AI chat. A completed request adds a node and its saved canvas snapshot. While the first
painting is still being made, the tree is empty: the agent's intermediate looks are
progress images, not saved conversation branches. The open tree updates when a turn finishes.

Select an earlier node to inspect its conversation and snapshot identity. The cyan
**current** circle stays at the active conversation position; browsing another node
leaves the easel alone. **Reference in chat** inserts a compact reference so AI4 can
retrieve that state's recorded contents when asked. **Keep for comparison**, followed
by **Discuss differences** on another node, prepares references to both in your draft.
The inspector shows saved text and ancestry; it does not reconstruct a canvas preview.

For a named alternative, ask AI4 to fork a saved state under a name such as
*Harbour at dusk*. `StateForkRequest` creates the saved child without activating it.
`StateReadRequest` can then retrieve it by that exact name. Continue the alternative
with the existing `/branches` and `/branch <number>` commands, which restore the
Studio's canvas as well as its conversation. [Browsing saved states](aiFeatures.md#browsing-saved-states)
describes the tools and how other documents expose their own histories.

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
- `/gallery` lists the hung paintings, which the box shows in its `gallery` panel.
- `/branches` lists the versions of the conversation, and `/branch <n>` switches to one.

To start a version from an earlier point, use the rewind button on one of your own requests. The
conversation, and the canvas with it, go back to how they were before that request.

!!! note "A canvas is large"
    Each saved state of the Studio's canvas is about 28 MB on disk, kept in the document's `ai4_blobs/state`
    folder. Every request that changes the painting adds one, and none is deleted yet.

## What the box defines

All of this is ordinary box code, in one `agentExtension` block. A tool is an
[`aiTool`](aiFeatures.md), here the one that dabs a loaded brush at a list of points. `canvas()`,
`paintOf()`, `brushOf()`, `rad()` and `done()` are small helpers defined above it in the same box:

```diffstatic {data-lang=kotlin}
tool("paint.dab") {
    describe("Touch the brush down at points and lift it: dabs, petals, leaves, highlights. One load for all of them.")
    effects(ToolEffect.CONTROL_RUNTIME)
    val points by param<List<Double>>("[x0, y0, x1, y1, ...]")
    val color by param<String>("#rrggbb, a tube name, or tubes by parts")
    val brush by param<String>("Round, HogFlat, Filbert, Fan, Rigger or Badger", default = "Round")
    val width by param<Double>("brush width in canvas units", default = 12.0)
    val pressure by param<Double>("harder makes a bigger, fuller mark", default = 0.6)
    val angle by param<Double>("a flat's wide axis, degrees", default = 0.0)
    action {
        val c = canvas(); val p = paintOf(color())
        val b = c.brush(brushOf(brush()), width = width())
        if (p.tubes != null) b.load(p.tubes) else b.load(p.color)
        points().chunked(2).filter { it.size == 2 }.forEach { c.touch(b, it[0], it[1], pressure = pressure(), angle = rad(angle())) }
        done("${points().size / 2} dab(s)")
    }
}
```

Colours can be hex, or real tube paints mixed by parts, `"lead white:4, cadmium yellow:1"`, which
mix the way paint does. The Studio has eleven tools in all: strokes along curves, dabs, whole
regions worked with a painter's handling (`Broad`, `Body`, `Detail`, `Glaze`, `Scumble`, `Blend`),
glazes, pencil and chalk lines, hatching, stippling, drying time, a fresh canvas, hanging, and an
ageing tool the agent loads only when it asks for it.

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
moves:

```diffstatic {data-lang=kotlin}
branchState("canvas",
    save = { canvas().saveState(it.path) },
    restore = { f -> replace(f?.let { PaintCanvas.load(it.path) } ?: fresh()); show() },
    onConflict = BranchConflict.RESTORE, resumeOnOpen = true)
```

`restore` gets `null` when the conversation has no saved canvas, which here means starting on a
fresh one. Before restoring, the chat checks whether the canvas changed since the conversation last
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
    checked. That run also found three bugs, fixed before these figures were made; they are
    recorded in `dev_docs/ai/AI4_PI_LESSONS.md`.
