# Connecting boxes

A box on its own keeps its variables to itself: nothing you write in one box changes what another
box sees. To let boxes work together, connect one beneath another. The upper box is the **parent**,
the lower one its **child**, and a child can read the values its parent publishes. Most documents
bigger than a sketch are built this way: one box holds the settings, the window or the data, and
the boxes beneath it use them.

## Connect two boxes

Click the box that should be the parent, ++shift++-click the child, and press `g`. A line now runs from
the parent down to the child:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="900x560" clip="0,0,460,360" video="assets/tour_connecting_boxes/connect.mp4" fps="10" transcript="assets/transcripts/tour_connecting_setup.yaml" padding="0" wait="0" timeout="180" embed=true hidden=true name="tour-connect-g" alt="Two boxes on the canvas, Palette above Swatch, with no line between them. A click selects Palette, a shift-click adds Swatch to the selection, and pressing g draws a connection line from Palette down to Swatch. Swatch is then dragged off to the right, and the line bends to follow it."}
// The setup transcript makes two unconnected boxes, Palette and Swatch.
fun boxAt(name: String): List<Double> =
    ui.eval(
            """
    const b = [...document.querySelectorAll(".box")].find(b => (b.name || "") === "$name" || (b.textContent || "").trim() === "$name")
    if (!b) return null
    const r = b.getBoundingClientRect()
    return [r.left + r.width / 2, r.top + r.height / 2].join(",")""".trimIndent(),
        )
        .toString()
        .trim('"')
        .split(",")
        .let { [it[0].toDouble(), it[1].toDouble()] }
fun splineCount(): Int =
    ui.eval("return document.querySelectorAll('#connectionsplinelayer *').length")
        .toString()
        .trim('"')
        .toDouble()
        .toInt()
// Lay the editor pane out for this window, as a user's browser would: screenshot prep pins it to a
// size meant for a larger page, and pinned it covers Swatch.
fun freeEditorLayout() {
    ui.eval(
        """
        const c = document.querySelector(".monaco-editor-container")
        if (c) for (const k of ["width", "height", "top", "right", "left", "bottom", "transform"]) c.style[k] = ""
        if (window.globalEditor && window.globalEditor.layout) window.globalEditor.layout()
        return true""".trimIndent(),
    )
}
// The page-load toasts are hidden the way screenshot prep hides them.
ui.eval(
    "document.querySelectorAll('.bubble').forEach(b => { b.style.visibility = 'hidden'; b.style.opacity = '0' }); return true",
)
freeEditorLayout()
val palette = boxAt("Palette")
val swatch = boxAt("Swatch")
val before = splineCount()
ui.keypressDisplay()
ui.pause(800)
ui.moveMouse(palette[0], palette[1], 10)
ui.clickAt(palette[0], palette[1])
ui.pause(700)
freeEditorLayout()
ui.pause(500)
ui.moveMouse(swatch[0], swatch[1], 10)
ui.clickAt(swatch[0], swatch[1], "Shift")
ui.pause(700)
ui.press("g")
ui.pause(1200)
check(splineCount() > before) { "no connection line appeared after g" }
ui.pause(1000)
// Drag Swatch aside: the line follows it.
ui.drag("tour_swatch", 220.0, -30.0, 30)
ui.pause(600)
ui.drag("tour_swatch", -120.0, 40.0, 30)
ui.pause(1200)
```

The line follows the boxes when you move them. To disconnect them, select both and press `u`.
A box can have several parents and several children. The connections are part of the document and
are saved with it.

## A parent publishes, a child reads

This example uses two boxes, named **Palette** and **Swatch**, with Swatch connected beneath
Palette. `prop` declares a value that children can read. Palette declares `petals`, an `Int`, and
sets it:

```diffstatic {data-lang=kotlin}
prop<Int>(petals) = 5
```

**Swatch**, connected beneath Palette, can now use `petals`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/tour_connecting_boxes/five.png" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="tour-connect-prop" macro-file="../../dev_docs/agents/transcripts/docs_capture/tour_connect_prop.yaml" capture-box="tc_swatch" w="560" h="80" alt="Swatch's output, one line: A flower with 5 petals, followed by five flower symbols"}
markdown("A flower with $petals petals: " + "✿".repeat(petals))
```

Run Palette, then Swatch. Swatch never set `petals`; reading it walks up the connection to the
nearest box that has a value, here Palette. If Swatch assigned `petals` itself, its own value would
win for Swatch and for anything beneath it. A box that is not connected beneath Palette cannot
compile the name at all.

## Run the parent, watch the child

With plain `prop`, the child sees a new value the next time it runs: change Palette to 8 and run
it, and Swatch still says 5 until you run Swatch too. Declare the value with `propDown` instead,
and running the parent reruns every box that read it:

```diffstatic {data-lang=kotlin}
propDown<Int>(petals) = 8
```

Swatch's code is unchanged. Here Palette was first run with 5, Swatch ran once, and then Palette
was changed to 8 and run on its own:

```diffstatic_runner {data-lang=kotlin screenshot="assets/tour_connecting_boxes/eight.png" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="tour-connect-down" macro-file="../../dev_docs/agents/transcripts/docs_capture/tour_connect_down.yaml" capture-box="tcd_swatch" w="560" h="80" alt="Swatch's output after only Palette was run with 8: A flower with 8 petals, followed by eight flower symbols"}
markdown("A flower with $petals petals: " + "✿".repeat(petals))
```

Swatch reran because it had read `petals` from Palette and the value changed. A box beneath
Palette that never reads `petals` is left alone.

## Share a type between boxes

As a document grows, a value can carry several related settings. Make a box named **Garden types**,
select it, and choose **Make shared Kotlin declarations box** from the command palette. Write this
class in it and run the box:

```diffstatic {data-lang=kotlin}
data class Flower(val petals: Int, val color: String)
```

The box now has an accent-coloured frame and says **shared Kotlin** under its name. It is an
ordinary Kotlin box: it has the same imports as any other box, so a class you move into it from
another box compiles as it did there, and its errors show in its editor. When it runs, its output
lists the classes it shared.

Now every Kotlin box in this document can use `Flower`. In **Garden settings**, publish one:

```diffstatic {data-lang=kotlin}
prop<Flower>(flower) = Flower(petals = 5, color = "pink")
```

Connect **Garden view** beneath Garden settings. It reads the same `Flower`, including its fields:

```diffstatic_runner {data-lang=kotlin screenshot="assets/tour_connecting_boxes/shared-flower.png" capture="box" open-outputs="true" wait="1.5" timeout="180" embed=true name="tour-connect-shared-flower" macro-file="../../dev_docs/agents/transcripts/shared_kotlin_garden.yaml" capture-box="garden_view" w="560" h="80" alt="Garden view's output: A pink flower with 5 petals"}
check(flower.petals == 5 && flower.color == "pink")
markdown("A ${flower.color} flower with ${flower.petals} petals")
```

Run the declaration box, then Garden settings, then Garden view. You can [open the three-box
example](agent_transcripts.md#shared_kotlin_garden) and change it. After editing the class, run its
box again, then rerun the boxes that make and use its values. Field marks those boxes for you.
Opening the document runs its shared boxes first, so their classes are ready before anything else
runs.

Classes, interfaces, objects and enums are shared. A top-level `fun` or `val` in a shared box stays
in that box, and its run lists any it found. To share a function, put it in an `object`, or make it
a member of the class.

## Why it matters

The value a parent publishes can be anything Kotlin can hold: a number, a list, a function, a
loaded dataset. Graphics work the same way: the box that opens a window publishes `renderer`, and
every box connected beneath it draws with that renderer without looking it up.

See also:

- [Why does this value resolve here?](why.md), for asking Field which box supplied a value when
  there are several candidates, and for values found by type with `fromContext()`.
- [Properties that re-run boxes](reactive_properties.md), for `propDown`, `propUp` and
  `propUpDown` in full, and the layer that draws who reads what.
- [Property inheritance](running_code.md#property-inheritance-how-children-see-a-parents-values),
  for the mechanism underneath `prop`.

Next on the Tour, [Graphics on the GPU](tour_gpu_graphics.md) puts a box beneath a renderer, and the box draws into a native window.
