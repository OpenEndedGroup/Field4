# Layers

Every box is on one or more layers. Layers decide two things: which boxes are on the canvas right
now, and, for the active layer, where each box sits. Use them to keep sketches and scratch boxes out
of the way of a finished piece, or to lay the same boxes out twice, once for writing them and once
for performing with them.

## Putting a box on a layer

A box's layers are its `layers` property, a comma-separated list of names. Set it from inside the
box:

```diffstatic {data-lang=kotlin}
layers = "draft,final"
```

A box that names no layer is on `default`. A box is on the canvas when any one of its layers is
showing, so this box shows whenever `draft` or `final` does. Layer names need no declaring: a layer
exists as soon as some box names it.

## The layers panel

`alt/option-L` (**Show Layers Panel** in the command palette) opens the layers panel. It lists every
layer some box is on, with the number of boxes on each. Here three boxes are on both `draft` and `final`,
one is on `sketch`, and the rest name no layer and so are on `default`. Every
layer is showing, and `draft` is active:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#layers-panel" screenshot="assets/layers/panel.png" transcript="../../dev_docs/agents/transcripts/docs_capture/layers_guide.yaml" padding="8" wait="0" timeout="240" hide-toasts=true embed=true hidden=true name="layers-panel-guide" alt="The layers panel, titled Layers, with the status line 4 layers, 4 shown, active: draft. Four rows, default, draft, final and sketch, each with an eye button, a radio button, the layer name, its box count and a Solo button. default has 2 boxes, draft 3 boxes, final 3 boxes, sketch 1 box. draft's radio is checked and its name is in the accent colour. Below the rows are Show all and Move selection to active layer buttons."}
ui.eval("if (document.activeElement) document.activeElement.blur(); return 'blurred'")
ui.down("Alt")
ui.press("KeyL")
ui.up("Alt")
ui.waitForSelector("#layers-panel.is-visible", 8000.0)
// A fresh document shows only `default`. Show every layer, then make `draft` active, through the
// panel's own buttons.
ui.eval(
    """
  const all = Array.from(document.querySelectorAll("#layers-panel button")).find(b => b.textContent === "Show all");
  all.click();
  return "showed all";
""",
)
ui.pause(700)
ui.eval(
    """
  const rows = Array.from(document.querySelectorAll("#layers-panel .layer-row"));
  const draft = rows.find(r => r.querySelector(".layer-name").textContent === "draft");
  if (!draft) return "rows: " + rows.map(r => r.querySelector(".layer-name").textContent).join(",");
  draft.querySelector(".layer-active").click();
  return "activated draft";
""",
)
ui.pause(900)
```

Each row has:

- an eye, which shows or hides the layer's boxes. Shift-click it to show that layer alone. You
  cannot hide the last layer that is showing.
- a radio button that makes the layer active (below). Making a layer active also shows it.
- the number of boxes on the layer.
- **Solo**, which shows only this layer and makes it active.

Under the rows, **Show all** shows every layer, and **Move selection to active layer** puts the
selected boxes on the active layer, and only on it: their `layers` becomes that one name.

The panel floats over the canvas, so hiding a layer never hides the panel. `showLayers()` in a box
puts the same controls in that box's output area instead.

From the keyboard, `alt-]` solos the next layer in alphabetical order and `alt-[` the previous one;
in the command palette, **Solo Next Layer** and **Solo Previous Layer** name the layer they will go
to. **Show All Layers** in the palette shows every layer and opens the panel. With a layer active
and boxes selected, the palette also offers **Move N boxes to active layer**, which does the same as
the panel's button.

## A different layout on each layer

The active layer has its own set of box positions. When you make another layer active, Field
remembers where every box on the old layer is, then moves each box on the new layer to where it was
the last time that layer was active. So a box on two layers can sit in one place on one and
somewhere else on the other.

Below, `draft` is active and the three drum boxes are dragged into a row. Then `final` is made
active and they are dragged into a diagonal. After that, choosing `draft` and `final` in turn in
the panel moves them between the two layouts. `notes` and `mix` are on neither layer, so they stay
put:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1600x600" clip="0,0,1600,600" video="assets/layers/two_layouts.mp4" fps="10" transcript="../../dev_docs/agents/transcripts/docs_capture/layers_guide.yaml" padding="0" wait="0" timeout="300" hide-toasts=true embed=true hidden=true name="layers-two-layouts" alt="Field's canvas, with the code editor of the selected box open at the right and the layers panel over it. Three boxes, kick, snare and hats, stand in a column at the left, with notes and mix below them. With draft active, snare and hats are dragged up beside kick into a row. final is chosen in the panel, and kick is dragged down to the right and hats back to the top left, leaving the three on a diagonal. Choosing draft in the panel slides them back into the row; choosing final slides them onto the diagonal again. notes and mix never move."}
ui.eval("if (document.activeElement) document.activeElement.blur(); return 'blurred'")
if ((ui.eval("return !!document.querySelector('#layers-panel.is-visible')") ?: "").toString() != "true") {
    ui.down("Alt")
    ui.press("KeyL")
    ui.up("Alt")
}
ui.waitForSelector("#layers-panel.is-visible", 8000.0)
val activate = { layer: String ->
    ui.eval(
        """
      const rows = Array.from(document.querySelectorAll("#layers-panel .layer-row"));
      const row = rows.find(r => r.querySelector(".layer-name").textContent === "$layer");
      if (!row) return "no row $layer";
      row.querySelector(".layer-active").click();
      return "activated $layer";
    """,
    )
}
ui.eval(
    """
  const all = Array.from(document.querySelectorAll("#layers-panel button")).find(b => b.textContent === "Show all");
  all.click();
  return "showed all";
""",
)
ui.pause(300)
activate("draft")
ui.pause(400)
// On draft: a row.
ui.drag("lg_snare", 180.0, -100.0, 60)
ui.pause(150)
ui.drag("lg_hats", 360.0, -200.0, 60)
ui.pause(400)
activate("final")
ui.pause(400)
// On final: a diagonal.
ui.drag("lg_kick", 360.0, 200.0, 60)
ui.pause(150)
ui.drag("lg_hats", -360.0, 0.0, 60)
ui.pause(150)
ui.drag("lg_snare", 0.0, 100.0, 60)
ui.pause(400)
activate("draft")
ui.pause(700)
activate("final")
ui.pause(700)
activate("draft")
ui.pause(700)
```

A layer's first activation leaves boxes where they are; it starts remembering from then on. The
positions are saved in each box's `layerFrames` property, a map from layer name to frame, and are
written to the document with everything else, so the two layouts are still there next time you open
it.

!!! note "Leaving every layer inactive"
    No layer is active when a document opens. Positions are only remembered while a layer is
    active, and only for boxes on it. Choosing a layer and then going back to no active layer
    leaves boxes where they were last, not where they started.

## What layers do not change

- **Running code.** Hiding a layer hides its boxes and their output panels; it does not stop them
  running. A hidden box that is drawing or playing sound carries on.
- **What is showing is per window.** Which layers are showing and which is active belong to the
  window you are working in, not to the document. They are not saved: a reopened document shows
  `default` with no layer active. A box's own `layers` and `layerFrames` are saved.
- **Bookmarks** ([Moving Around the Canvas](moving_around_the_canvas.md)) record the view, the selection and open panels, not
  which layers are showing. Restoring a bookmark leaves the layers as they are.
- **New boxes** are on `default`, whichever layer is active. Use **Move selection to active layer**
  to put them on it.

See also [Moving Around the Canvas](moving_around_the_canvas.md) for panning, zooming, bookmarks and
split panes.
