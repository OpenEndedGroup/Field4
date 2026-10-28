# Box Output: print, display and channels

A box has two places to put what its code produces. `print` and `println` write a short line of
text beside the line of code that called them, in the editor. `display` and `markdown` put
content (text, a table, a picture, any HTML) in a panel under the box, on the canvas. This page
covers both, then how to name, open, close and remove panels from code and with the mouse.

## Printing beside the code

`println` is for checking a value while you work. What a run prints appears in the editor, in a
block under the line that printed first. Close the block with its ×; it comes back on the next
run that prints.

```diffstatic_runner {data-lang=kotlin screenshot="assets/box_output/print_inline.png" capture="editor" outputs="inline" wait="3" padding="10" timeout="180" embed=true name="box-output-print-inline" w="560" h="200" alt="A box's four lines of code in Field's editor. Under the first println line sits a black block holding everything the run printed, one line each: petals: 7, then 0, 1 and 4 from the loop."}
val petals = 7
println("petals: $petals")
for (i in 0 until 3) println(i * i)
petals * 2
```

Printed text belongs to the editor and never appears on the canvas. To show something that stays visible under the box,
display it.

## Displaying into a panel

`display("name", content)` puts `content` in a panel titled `name` under the box. That name is the
panel's *channel*. `markdown("name", text)` does the same with Markdown, which is usually the
easier way to write a caption or a table.

This box writes into two channels:

```diffstatic_runner {data-lang=kotlin screenshot="assets/box_output/two_channels.png" capture="box" wait="3" padding="16" timeout="180" embed=true open-outputs="false" name="box-output-two-channels" w="560" h="80" alt="A Field box with two output panels under it. The first, titled 'summary', is open and shows a short Markdown table of three readings: tempo 122, voices 4, key D minor. The second, titled 'raw', is open below it and shows the same readings as one line of plain text."}
markdown(
    "summary",
    """
    | reading | value |
    |---|---|
    | tempo | 122 |
    | voices | 4 |
    | key | D minor |
""",
)

display("raw", "<pre>tempo=122 voices=4 key=Dm</pre>")
displayOpen("raw")
```

The two calls behave differently in one way. A panel made by `display` starts closed, showing only
its title; click the title to open it, or call `displayOpen`, as the last line does here.
`markdown` opens its panel for you, unless you pass `open = false`.

A few rules cover the rest:

- **Same channel, same panel.** Displaying into a channel that already has a panel replaces what
  is in it. The panel keeps its place, whether it is open, and its size. A `loop { }` that
  displays every frame updates one panel; it does not stack up new ones.
- **Panels are listed in the order their channels first appeared.** Replacing a panel's content
  does not move it.
- **`display(content)` with no channel name** uses the channel `output`.
- **Running the box again does not remove panels.** A channel the new code no longer writes to keeps
  its last content until you remove it (below).

## What `display` accepts

A `String` is treated as HTML and inserted into the panel as it is, `<script>` tags included, so
`display` is also the way to put a small interactive page in a box. Anything else is turned into
HTML with `.h` first, which gives a readable view of numbers, lists, maps and most of Field's own
types. `display("info", someMap)` and `display("info", someMap.h)` show the same thing.

To show plain text that might contain `<` or `&`, wrap it in Markdown or escape it; a bare string
is HTML. [Visualizers & Controls](visualizers.md) lists what Field can draw into a panel: `.h`,
`.h2` explorers, Markdown cards, plots, images, sliders and other controls.

## Opening, closing and removing panels

From code, four functions act on a box's panels by channel name:

| call | what it does |
|---|---|
| `displayOpen("name")` | opens the panel. If the channel has no panel yet, it makes an empty one and opens it. |
| `displayClose("name")` | closes the panel to its title bar. Its content and size are kept. |
| `displayClear("name")` | removes the panel and its content. The next `display` into that channel makes a new, closed panel at the end of the list. |
| `displayClearAll()` | removes every panel the box has. |

The box below makes three panels, opens two of them, and then removes the third. Only `notes`
and `chart` are left:

```diffstatic_runner {data-lang=kotlin screenshot="assets/box_output/open_and_clear.png" capture="box" wait="3" padding="16" timeout="180" embed=true open-outputs="false" name="box-output-open-and-clear" w="560" h="80" alt="A Field box with two open output panels. 'notes' holds one line of text, 'three passes, no retries'. 'chart' shows a row of five blue bars of increasing height drawn as inline SVG. There is no third panel."}
display("notes", "<p>three passes, no retries</p>")
display(
    "chart",
    (1..5).joinToString("", "<svg width='220' height='90'>", "</svg>") {
        "<rect x='${it * 40 - 30}' y='${90 - it * 16}' width='28' height='${it * 16}' fill='#4a90d9'/>"
    },
)
display("scratch", "temporary")

displayOpen("notes")
displayOpen("chart")
displayClear("scratch")
```

`displayClearAll()` at the top of a box's code is a common way to start each run with no leftover
panels.

With the mouse, on a panel's title bar:

| gesture | what it does |
|---|---|
| click | opens or closes the panel |
| Shift-click | opens this panel and closes all the others |
| alt/option-click | removes this panel |
| alt/option-Shift-click | removes every other panel |

Holding alt/option or alt/option-Shift while the pointer is over a title marks the panels that a click would
remove. Removing a panel with the mouse is the same as `displayClear`: it comes back the next time
the code displays into that channel.

## One-line channels

A channel whose name starts with `*` is not a panel. It is a single line under the box, its name
on the left (without the `*`) and its content on the right, always visible, with nothing to open or
close. These suit a counter or a status that you want to see at a glance.

```diffstatic_runner {data-lang=kotlin screenshot="assets/box_output/inline_channels.png" capture="box" wait="3" padding="40" timeout="180" embed=true name="box-output-inline-channels" w="420" h="80" alt="A Field box with two one-line outputs under it and no panels. The first line reads 'status' followed by 'converged' in green; the second reads 'done' followed by a horizontal progress bar about two thirds full."}
display("*status", "<span style='color:#7fd77f'>converged</span>")
progressBar("done", 0.66)
```

`progressBar("done", value)` is built on this: it displays into `*done`, and
`progressBarClear("done")` removes it again. `displayClear("*status")` removes a one-line
channel, and alt/option-click on it does too.

## How big a panel is

An open panel sizes itself to its content as the content loads, up to 80% of the window's height,
and scrolls past that. Drag its bottom-right corner to resize it; from then on it keeps the size you
gave it, whatever is displayed into it. Content that sets its own pixel width (an `<svg
width="…">`, an `<iframe style="width:640px">`) gets a panel that wide. Content with no width of
its own (`width:100%`) gets the width of the box.

[Output Panel Sizing](output_panel_sizing.md) explains the measurement in detail and what to do
when a panel comes out the wrong size.

## When a panel is gone

Panels are not saved with the document. They are made by running code, so a document you open
shows no panels until its boxes run. While a box is running, Field remembers its latest panels, so
reloading the page or opening the document in a second browser window shows them again without
running anything. That memory keeps the most recent 128 panels per box.

A few channel names belong to Field: `nested` holds a box's nested child boxes (the display
functions never remove it), `ai-chat` is the box's AI chat, `layers` is the layers palette, and
`editor:<property>` is an embedded editor for one property. Pick other names for your own output.

## Publishing a document

Run the boxes whose outputs you want to share, then choose **Publish document for the web (.zip)**
from the command palette. The publication panel reports how many output channels were captured
and lists any capture warnings. Download the archive, unzip it, and serve the folder with any
static web host. Open its `index.html` to browse the boxes, notes, code and captured outputs.
The published document works without Field or an internet connection once served locally.

| Output | Published form |
| --- | --- |
| FLine and SVG drawings | Vector markup at the captured view |
| HTML and `.h` values | Rendered contents, styles and images |
| Plotly, including charts inside local iframes | Frozen trace data with browser zoom and hover |
| `.h2`, tree and column inspectors | Current view and materialized rows |
| Three.js | A still image of the rendered view |

Plots that depend on external image or map data are captured as still images and listed with a
capture warning.

Controls that call back into Field are disabled. Inspector rows that have not been loaded are
outside the snapshot. A channel that cannot be captured is listed with its reason in the
publication panel and in the published document. Cross-origin frames and live audio/video need
their own exported media.

## See also

- [Visualizers & Controls](visualizers.md): what to display, from `.h` to sliders.
- [Output Panel Sizing](output_panel_sizing.md): how a panel decides its width and height.
- [Connecting an output iframe to its box](output_requests.md): an iframe in a panel that sends
  requests back to its box's Kotlin.
- [Graphics in an Output Area](four_in_output_areas.md): Vulkan rendering inside a panel.
- [Where Field's Output Goes](logging.md): output that is not a box's own, from the server and its
  logs.
