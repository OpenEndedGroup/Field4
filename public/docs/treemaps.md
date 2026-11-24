# Treemaps

A treemap shows a tree as nested rectangles. Each rectangle's area is its share of the total, so you can
see at a glance where the bulk of something is: which folders hold the code, which accounts take the
budget, which branches of a search did the work. `treemap(tree)` draws one into a box's output, and you
can zoom it, select from it, label it, colour it and animate it.

Field draws treemaps with WebGL, so a treemap of a hundred thousand cells pans, zooms and animates as
smoothly as one of a hundred. Every change morphs from the old picture to the new one instead of jumping.

## A first treemap

You build a tree with `treemapTree`, giving branches with `branch` and sized leaves with `leaf`, and hand it
to `treemap`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/treemaps/first.png" wait="1.0" capture="box" open-outputs="true" w="1200" embed=true alt="A treemap of a monthly budget: a large pink home branch dominated by rent, a green food branch, a blue getting-about branch, and books and savings as single cells, each labelled with its name and amount"}
val budget = treemapTree("month") {
    branch("home") {
        leaf("rent", 1450.0)
        leaf("power", 95.0)
        leaf("internet", 60.0)
    }
    branch("food") {
        leaf("groceries", 520.0)
        leaf("eating out", 210.0)
    }
    branch("getting about") {
        leaf("transit", 110.0)
        leaf("bike repairs", 45.0)
    }
    leaf("books", 80.0)
    leaf("savings", 600.0)
}

treemap(budget, w = 800.0, h = 460.0)
```

Each top-level branch gets its own hue, and its descendants are lighter shades of it. A branch's name and
total sit in a header strip along its top. A leaf is labelled when its name fits, with its value too if
there is room, and its name is cut short with an ellipsis when the value will not fit. Hovering a cell
lights it and shows its full path and share in a tooltip. The strip above the cells is the breadcrumb
trail for zooming.

## Trees from your data

You rarely build a tree by hand. Four functions turn something you already have into one:

- `treemapTree(name) { ... }` is the builder above.
- `treemapTreeOfFiles(dir)` maps a folder, with each file sized by its bytes.
- `treemapTreeOfMap(value)` takes nested maps and lists, which is what JSON and EDN parse to. A number is a
  leaf of that size, and a map or list is a branch.
- `treemapTreeOf(root, children = { ... }, weight = { ... }, name = { ... })` walks any object that has
  children: a scene graph, a parse tree, a clustering. Each node keeps the object it came from in `data`.

Here is Field's own source code, by size:

```diffstatic_runner {data-lang=kotlin screenshot="assets/treemaps/source_tree.png" wait="1.5" capture="box" open-outputs="true" w="1200" embed=true alt="Field's source tree as a treemap, one hue per top-level package (graphics, audio, lang, examples, geometry and more), with sub-packages as labelled headers and files as small unlabelled cells"}
import java.io.File

val source = treemapTreeOfFiles(File("src/jvmMain/kotlin/afield"))

treemap(source, w = 900.0, h = 560.0)
```

That is nearly two thousand files, and the treemap does not draw them all at once. See
[Level of detail](#level-of-detail) below.

A tree can also show itself as a value. A box whose last expression is a tree, `display(channel, tree)`
and `tree.h` all draw a smaller treemap that you can hover but not click.

## Zooming, selecting and hovering

The mouse does what you would expect:

| | |
|---|---|
| click | select a cell |
| ++shift++-click | add to the selection |
| ++cmd++-click | toggle a cell in the selection |
| double-click | zoom one level towards the cell |
| ++alt++-click, ++esc++, or a breadcrumb | zoom out |
| click the background | clear the selection |
| wheel, drag | zoom and pan the camera |
| double-click the background | fit the drawing to its panel again |

The block after `treemap(...)` sets properties before the first draw. `onSelect` hears about every change
to the selection, and `infoChannel` names a channel where the hovered cell (or else the last selected one)
is described:

```diffstatic_runner {data-lang=kotlin screenshot="assets/treemaps/zoomed_and_selected.png" wait="1.5" capture="box" open-outputs="true" w="1200" embed=true alt="The graphics package zoomed to fill the treemap, with breadcrumbs reading afield then graphics, the render and text branches outlined in yellow as selected, and an about panel below describing text: its value, its share of graphics, and its children and leaves"}
import java.io.File

val source = treemapTreeOfFiles(File("src/jvmMain/kotlin/afield"))

val map = treemap(source, w = 900.0, h = 520.0) {
    infoChannel = "about"
    onSelect = { picked -> println("selected " + picked.map { it.name }) }
}

map.zoomTo("afield/graphics")
map.select(map.focus.child("render")!!, map.focus.child("text")!!)
```

`treemap` returns the `Treemap`, and everything the mouse does is a method on it: `zoomTo`, `zoomOut`,
`zoomToRoot`, `select` and `clearSelection`. `focus`, `selection` and `hovered` read where things stand.
Zooming morphs the branch out to fill the whole treemap, while the cells outside it fade away.

When you change the code and run the box again, it keeps its zoom and selection. They are held by each
node's `key`, the path of names from the root (`afield/graphics/render`), so they survive even a tree
that has been built afresh. `resetTreemap()` forgets them.

## Level of detail

A hundred thousand cells is no trouble. This library of 40 areas, a thousand topics and a hundred
thousand modules is drawn whole:

```diffstatic_runner {data-lang=kotlin screenshot="assets/treemaps/hundred_thousand.png" wait="2.0" capture="box" open-outputs="true" w="1300" scale="2" embed=true alt="A treemap of a hundred thousand modules: forty coloured areas with their names in header strips, each packed with a grid of topics, each topic a fine mesh of module cells a few pixels across"}
val library = treemapTree("library") {
    repeat(40) { a ->
        branch("area $a") {
            repeat(25) { t ->
                branch("topic $a.$t") { repeat(100) { leaf("module $it", 1.0 + (a * 31 + t * 7 + it) % 17) } }
            }
        }
    }
}

treemap(library, w = 1200.0, h = 760.0)
```

A larger tree still would be millions of slivers, so each display opens cells largest first, and stops at
whichever of these comes first:

- `maxCells`, 150,000 by default: the most cells one display draws;
- `minCellSize`, 2 screen pixels by default: a cell smaller than this is drawn, but its children are not;
- `maxDepth`: the most levels below the focus that are opened.

Labels appear wherever they fit, at the same size on screen however far you zoom, and fade in over the last
few pixels before they fit, so labels coming in with a zoom do not pop. A label keeps to the part of its
cell that is on screen, so a branch you have zoomed right into is still named. When you stop zooming or
panning, the treemap is laid out again for where you are looking: cells that have grown on screen open
their children, and cells that are off screen stay closed.

## Colour

`colors` decides each cell's fill. There are five to choose from:

- `TreemapColors.byBranch()`, the default: a hue per top-level branch, lighter with depth.
- `TreemapColors.byCategory("title") { node -> ... }`: one colour per category you name for each leaf, the
  biggest categories getting the most distinct colours; leaves you return null for are "Not classified".
- `TreemapColors.byValue("viridis", log = true)`: leaves through a [colormap](colors_examples.md) by their
  value, or by any `measure = { node -> ... }` you give; branches stay grey.
- `TreemapColors.byDepth("magma")`: every cell by how deep it is.
- `TreemapColors.by { node -> ... }`: whatever you return.

The first three come with a legend, which the treemap shows above the cells: each category's share of what
you are zoomed into, or the colormap's range. A node's own `color`, set in the builder or later, wins over
all of them for it and for its descendants. Labels are dark or light, whichever reads better on the fill.

Give the treemap several colourings in `colorings` and it shows a "Colour by" switch for them. A switch
sends only the new colours, and the cells fade from one colouring to the next. This offers three ways of
looking at Field's graphics code, and starts on the file type, read from the file each node keeps in `data`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/treemaps/colorings.png" wait="1.5" capture="box" open-outputs="true" w="1200" embed=true alt="The graphics package coloured by file type, with a Colour by switch offering package, file type and last changed above the cells, and a legend showing Kotlin files as nearly all of it and a few GLSL, Markdown and JavaScript files as small coloured cells"}
import java.io.File

val source = treemapTreeOfFiles(File("src/jvmMain/kotlin/afield/graphics"))

treemap(source, w = 900.0, h = 520.0) {
    colorings = linkedMapOf(
        "package" to TreemapColors.byBranch(),
        "file type" to TreemapColors.byCategory { node -> (node.data as? File)?.extension?.ifEmpty { null } },
        "last changed" to
            TreemapColors.byValue(
                "cyanorange",
                measure = { node -> (node.data as? File)?.lastModified()?.toDouble() ?: 0.0 },
            ),
    )
    colorBy("file type")
}
```

## Mapping a code base

A treemap is a good way to see a large code base whole. This one maps Field's own source and writing,
twenty thousand files with `git ls-files`, and colours each file by when it last changed. Branches are named in
badges over their cells, which keeps a dense map dense, and clicking a file opens its code underneath:

```diffstatic_runner {data-lang=kotlin screenshot="assets/treemaps/code_base.png" wait="3.0" capture="box" open-outputs="true" w="1500" scale="2" timeout="240" embed=true alt="Field's source tree as a dense treemap in a serif face: a Colour by switch for last changed, kind of file, top-level folder, size and depth, a legend of how recently files changed with their shares, and thousands of small cells in pink, ochre, green, blue and violet under dark badges naming src, render, audio, dev_docs, tools, native and their folders"}
import java.io.File

fun git(vararg args: String) = ProcessBuilder("git", *args).start().inputStream.bufferedReader().readLines()

val roots = ["src", "tools", "python", "native", "dev_docs", "routines", "templates"]
// Code and writing only: no images, archives, fonts or other binaries.
val binary = [
    "png",
    "jpg",
    "gif",
    "webp",
    "mp4",
    "wav",
    "jar",
    "zip",
    "gz",
    "tar",
    "pdf",
    "ttf",
    "woff2",
    "wasm",
    "bin",
    "onnx",
    "npy",
]
val tree = treemapTree("Field") {}
for (path in git("ls-files")) {
    val parts = path.split('/')
    if (parts[0] !in roots || "/monaco/" in path || "/artifacts/" in path) continue
    if (path.substringAfterLast('.').lowercase() in binary) continue
    var node = tree
    for (dir in parts.dropLast(1)) node = node.child(dir) ?: node.branch(dir)
    node.leaf(parts.last(), File(path).length().coerceAtLeast(1).toDouble(), data = path)
}

// When each file last changed, from the most recent 6000 commits.
val changed = HashMap<String, Long>()
var stamp = 0L
for (line in git("log", "-n", "6000", "--format=@%ct", "--name-only")) {
    if (line.startsWith("@")) stamp = line.drop(1).toLong() else if (line.isNotBlank()) changed.putIfAbsent(line, stamp)
}
fun age(node: TreemapNode): String {
    val days = (System.currentTimeMillis() / 1000 - (changed[node.data] ?: 0L)) / 86400.0
    return when {
        days < 7 -> "this week"
        days < 31 -> "this month"
        days < 92 -> "this quarter"
        else -> "older"
    }
}

treemap(tree, w = 1400.0, h = 900.0) {
    branchLabels = TreemapBranchLabels.BADGE
    labelFont = "\"Iowan Old Style\", Charter, Georgia, serif"
    paddingInner = 0.0
    paddingOuter = 1.0
    leafLabelMinWidth = 90.0
    hint = "Scroll over the map to zoom, drag to move, click a file to read its code."
    colorings = linkedMapOf(
        "last changed" to TreemapColors.byCategory { age(it) },
        "kind of file" to
            TreemapColors.byCategory {
                (it.data as? String)?.substringAfterLast('.', "")?.takeIf { e -> e.length in 1..5 }
            },
        "top-level folder" to TreemapColors.byBranch(),
        "size" to TreemapColors.byValue("viridis", log = true),
        "depth" to TreemapColors.byDepth("magma"),
    )
    onSelect = { picked ->
        val path = picked.lastOrNull()?.data as? String
        if (path != null)
            markdown(
                "code",
                "**$path**\n\n```${path.substringAfterLast('.')}\n${File(path).readLines().take(40).joinToString("\n")}\n```",
            )
    }
}
```

`branchLabels`, `labelFont`, `leafLabelMinWidth` and `hint` are there for maps like this one: badges
instead of header strips, a face of your choosing, leaf names only on cells at least that many pixels
wide, and your own line under the map.

## Changing the tree, and animating it

Nodes can be edited in place. You can set `weight`, `add` or `remove` children, or assign a new `root`.
`update { ... }` makes the change and redraws, and the treemap morphs from the old layout to the new one
over `transition` seconds (0.45 by default).

To animate a treemap from a `loop { }`, set `transition = 0.0` and call `show()` every frame. The cells
then follow your values exactly. `TreemapTiling.Resquarify()` with `sort = false` keeps the cells in place
as their values change, so they grow and shrink rather than swapping places:

```diffstatic_runner {data-lang=kotlin video="assets/treemaps/animated.mp4" capture="box" open-outputs="true" execution="launch" wait="1.0" duration="6" fps="20" timeout="240" w="1200" embed=true name="treemap-animated" alt="Five coloured groups of six cells each, the cells swelling and shrinking smoothly in place as their values follow sine waves, their labels updating as they go"}
val tree = treemapTree("signals") {
    repeat(5) { b -> branch("group $b") { repeat(6) { leaf("s$it", 1.0) } } }
}

val map = treemap(tree, channel = "live", w = 760.0, h = 440.0) {
    tiling = TreemapTiling.Resquarify()
    sort = false
    transition = 0.0
}

loop {
    val t = AnimationClock.nowSeconds()
    tree.leaves().forEachIndexed { i, leaf -> leaf.weight = 1.6 + sin(t * 1.3 + i * 0.7) }
    map.show()
}
```

A change that arrives while a morph is still running starts from wherever the drawing is at that moment.
So with a small `transition`, a box can send new values as often as it likes and the motion stays smooth.

## Layouts

`tiling` chooses how a rectangle is divided among its children:

| tiling | what it does |
|---|---|
| `TreemapTiling.Squarify()` | rows chosen to keep cells near square; the default, and the easiest to read |
| `TreemapTiling.Resquarify()` | squarify once, then keep the same rows as values change |
| `TreemapTiling.Binary` | split by value in halves, along the longer side |
| `TreemapTiling.SliceDice` | strips that alternate direction with depth, keeping the tree's order exactly |
| `TreemapTiling.Slice`, `TreemapTiling.Dice` | strips stacked top to bottom, or side by side |

These are ported from [d3-hierarchy](https://d3js.org/d3-hierarchy/treemap)'s tilings. `paddingInner`, `paddingOuter` and `headerHeight` set the gaps between siblings, around
children, and above them for the branch's name, all in screen pixels. `sort = false` keeps the tree's own
order instead of largest first.

The layout is also available on its own. `layoutTreemap(root, x0, y0, x1, y1, options)` returns the cells
as rectangles, which you can draw yourself, export, or hand to the GPU.

## The drawing as FLines

`map.toFLines()` is the same layout as a list of `FLine`s: the background, the breadcrumbs, one filled
rectangle per cell with its label as a `caption`, and one outline per selected cell. Use it to put a
treemap into another drawing, to export one, or to draw it in Field's 3D output. Every line has a `key`,
so a few thousand cells drawn with `lines(...)` still morph from one display to the next:
[Keyed lines that morph](fline_examples.md#keyed-lines-that-morph-key-caption-and-transition) shows how.

## Where to go next

- [FLine examples](fline_examples.md) covers `toFLines()`'s lines, with clicks, hover and the keyed
  morph.
- [Charts as FLines](chart.md) is the same idea for plots.
- [Looking at Data](tour_data.md) shows the other ways to see values in a box.
- The generated reference lists every treemap function under
  [Visualization](reference/visualization.md).
