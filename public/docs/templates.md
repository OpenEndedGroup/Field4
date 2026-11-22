# Templates

Make something in a box that you'd like to use again? Save it as a template, then drop a copy into
any document. Select one box or a group and choose **Save selection as template** from
the command palette. Later, press ++cmd+t++ (**Insert template**) and pick its name; Field places
a fresh copy where your mouse is.

Templates are for pieces of a document. For a whole document to start from, use a
[starting point on the Welcome screen](installation.md#personal-templates); for reusable code,
dependencies and documents packaged together, use a [kit](kits.md).

## Save boxes as a template

Select the boxes you want to keep: one box, or a group with the connections between them. Open
the command palette (`f1`) with the canvas focused, choose **Save selection as template**, type a
name and press ++enter++.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".afield-palette.is-prompt .afield-palette-shell" screenshot="assets/templates/save-name.png" padding="6" wait="0" timeout="120" embed=true hidden=true transcript="../../dev_docs/agents/transcripts/docs_capture/templates_setup.yaml" document="docs-templates" fresh-document=true name="templates-save-name" alt="the Save as template prompt in the command palette's dark frame: the title on the left, greeter typed in the name field, and a line below saying it saves the selected box"}
ui.pause(800)
ui.click("tpl_greeter")
ui.pause(400)
ui.press("F1")
ui.pause(1000)
ui.type("Save selection as template")
ui.pause(800)
ui.press("Enter")
ui.pause(1000)
ui.type("greeter")
ui.pause(800)
```

Field writes the boxes to `templates/greeter.edn` inside your workspace and confirms with
"Template saved: greeter". Boxes nested inside a selected box come along with it. The name is a
file name, so it cannot contain `/`, `\` or the characters Windows forbids in file names.
Saving under a name that already exists replaces that template.

The template is a snapshot of the boxes when you save them. You can keep editing the originals;
the template stays as it was. Save over it and future insertions use the new version, while copies
you've already made stay as they are.

### Make a template safe to insert twice

A template is dropped into documents that already have boxes of their own, often more than once.
Each copy gets new uids but keeps its box names, so a template keeps working when:

- its boxes find each other through connections (a parent's `prop`, read by the child beneath
  it), not by name. `box("Rates")` finds whichever `Rates` it reaches first, and with two copies
  that may be the other copy's.
- it writes nothing document-wide. `rootProp`, `@rootExported` and `sharedTypes` are shared by
  every box, so a second copy overwrites the first.
- it opens no fixed port or service name that a second copy would collide with.
- whatever it registers under a name — a module on a renderer, an AI tool, an OSCQuery path —
  carries the box's id, as in `renderer += "sky_" + id.take(8) to sky`. Two copies have the same
  box name, so a name built from it collides.
- it leaves **autorun** off, so inserting it changes nothing until you run it.

### Say what a template needs above it

Some boxes only work beneath another: a graphics box needs a renderer above it. Give such a box a
`parentHint` and the canvas shows it just above the box's top edge for as long as the box has no
parent, then takes it away once you connect it:

```diffstatic {data-lang=kotlin}
parentHint = "Connect under a renderer: select the renderer box, then this one, and press G."
```

The hint is saved with the box, so set it once, save the template, and every copy shows it. The
bundled `sky`, `gpu-particles`, `rectsl-playground` and `shadertoy-pass` templates carry one.

## Insert a template

Move the mouse to where you want the copy, press ++cmd+t++ and start typing the name. The
palette lists every template it can find, with the documentation of its boxes beside the
selected entry.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".afield-palette-shell" screenshot="assets/templates/insert-palette.png" padding="6" wait="0" timeout="150" embed=true hidden=true transcript="../../dev_docs/agents/transcripts/docs_capture/templates_setup.yaml" document="docs-templates" fresh-document=true name="templates-insert-palette" alt="the Insert template palette filtered to greeter, showing Workspace template and the box's documentation"}
ui.pause(800)
ui.click("tpl_greeter")
ui.pause(400)
ui.press("F1")
ui.pause(1000)
ui.type("Save selection as template")
ui.pause(800)
ui.press("Enter")
ui.pause(1000)
ui.type("greeter")
ui.pause(500)
ui.press("Enter")
ui.pause(1500)
ui.clickAt(600.0, 400.0)
ui.pause(400)
ui.press("ControlOrMeta+t")
ui.pause(1800)
ui.type("greet")
ui.pause(1000)
```

Press Enter and the copy appears, centred on the mouse. Insert it again and you get a second
copy: each insertion gives every box a new identity, so the copies never share edits or
state with each other or with the template. Inserted boxes do not run, unless a box was saved with
**autorun** on.

The palette names where each entry comes from:

| Entry | Where it lives |
| --- | --- |
| Workspace template | `templates/<name>.edn` (or a folder) in your workspace, which is where **Save selection as template** writes |
| Bundled template | `templates/` in the Field checkout, such as `first-steps` and `first-graphics` |
| Personal template | `~/.afield/templates/` (or `-Dafield.personalTemplates.dir`), and `src/personal/templates/` in the checkout: the same folders as [Personal templates](installation.md#personal-templates) on the Welcome screen |
| Workspace file | another document in your workspace (not the one you are in): inserting it copies all of its boxes into this one |
| Remote template | a template server you configured (see [Share templates](#share-templates)) |

The palette looks in the folders in the order of this table, and the first folder holding a name
owns it: its entry, its documentation and what is inserted all come from there. The same name
further down is listed once more with the folder added, so a personal `first-steps` next to the
bundled one appears as `first-steps-personal`, and a workspace document called `greeter` next to a
saved template appears as `greeter-workspace`. A remote template whose name a local one already
has is not listed.

### Describe a template

The text beside an entry comes from its boxes' `metadata` property, which is YAML. Field
reads `doc` from every box in the template, top box first, so a few lines on each box explain the
whole group:

```diffstatic {data-lang=yaml}
doc: |
  Prints a greeting that names its box.
```

A template with no `doc` anywhere shows "No documentation available".

## Templates that come with Field

The checkout's `templates/` folder holds these, listed in the palette as Bundled templates. Most are
one to three boxes; none runs when inserted.

| Template | Boxes | What it adds |
| --- | --- | --- |
| `python-cell` | root, cell | A Python root and one Python cell under it. More boxes connected under the root are more cells in the same namespace. [Python](python.md) |
| `lua-cell` | root, box | A Lua root and one Lua box under it. [Lua](lua.md) |
| `pd-patch` | root, patch | A Pure Data root and a filtered saw drone; launch it, then run a `freq 330` line on its own. [Pure Data](pure_data.md) |
| `clojure-cell` | root, box | A Clojure root and one Clojure box with a slider. [Clojure](clojure.md) |
| `csound-instrument` | root, instrument | A Csound root and a plucked string with a reverb, played by its score and by any MIDI keyboard. [Csound](csound.md) |
| `parameter-parent` | parent, child | A parent that publishes `level` with a slider, and a child that draws it. Any box connected under the parent reads the same `level`. [Property inheritance](running_code.md#property-inheritance-how-children-see-a-parents-values) |
| `keyframe-lane` | 1 | A hand-edited curve played on a four-second loop into a meter, and published as `lane` to the boxes under it. [Keyframe editor](keyframe_editor.md) |
| `midi-learn` | 1 | A control group with a MIDI learn button, listening to the first hardware MIDI input while it runs. [MIDI learn](visualizers.md#midi-learn) |
| `watch` | 1 | A live `tree()` of values you choose, kept current while the box runs. [Trees and columns](visualizers.md#trees-and-columns) |
| `drawing-listener` | 1 | A handler for each stroke drawn by hand on the drawing layer. [Listening to the drawing](drawing_and_notation.md#listening-to-the-drawing) |
| `record-and-explore` | 1 | Records three seconds of everything running in the document and opens the profiling timeline. [Profiling timeline](profiling_timeline.md) |
| `orca` | 1 | An ORCA grid in a box output, playing through a synth. [ORCA](orca.md) |
| `control-surface` | 1 | A logarithmic slider, a bipolar slider, a momentary gate and a meter, to read from your own code. [Visualizers](visualizers.md) |
| `tour` | 1 | A slideshow over the boxes connected beneath it, top to bottom, showing each box's notes. [Interactive guides](interactive_guides.md) |
| `rates-and-sheet` | parent, sheet | A parent that publishes `taxRate` and `discountRate`, and a spreadsheet beneath it that prices a bill with them. [Spreadsheets](spreadsheets.md) |
| `oscquery-controls` | 1 | A slider and a checkbox that are also OSCQuery parameters, under a path of their own. [OSCQuery](oscquery.md) |
| `mqtt-listener` | 1 | The latest messages on an MQTT topic, from Field's embedded broker or another one. [MQTT](mqtt.md) |
| `renderer` | 1 | A window and a Vulkan renderer for graphics boxes to hang beneath. [Field 4 graphics](afield_four_graphics.md) |
| `sky` | 1 | A physically based sky with sliders for the sun, altitude and exposure, drawn behind everything else under its renderer. [An atmospheric sky](afield_four_graphics.md#atmospheric-sky) |
| `gpu-particles` | compute, draw | 100 000 particles moved by a compute shader, and a box beneath it that draws them. |
| `rectsl-playground` | 1 | A live RectSL program with play, loop, speed and time controls, drawn under its renderer. [RectSL examples](rectsl_examples.md) |
| `shadertoy-pass` | 1 | A ShaderToy-style Image pass: paste a `mainImage` and it draws in the renderer's window. |
| `first-steps` | 1 | A moving drawing to edit while it runs. |
| `first-graphics` | renderer, triangle | A Vulkan window and a triangle beneath it. |
| `timeSlider` | 1 | A vertical line that launches and stops the boxes it passes over. |
| `h2-explorer-examples` | 9 | Six numbered boxes that show `.h2` on a data class, a box, a function, an `FLine`, an exception and a `Set`, with notes. [Visualizers](visualizers.md) |
| `presentation` | 5 | The presentation starter as a plain copy, outside the kit. [Presenting with Field](presentation_studio.md) |
| `animate-examples` | root, 2 | An `animate { }` block that stops after 60 frames, and a box of notes on the syntax. |
| `field.sketch2_examples` | many | An older collection of sketches: bouncing points, noise, falling lines, geometry and stereo. |

## A copy remembers where it came from

Every inserted box records two properties, and both are saved with the document:

- `templateId` is the name of the template it was inserted from (`greeter`).
- `source_id` is the uid (the unique id every box carries) the box had inside the template, which is the uid of the box you saved.

So two copies of `greeter` share a `templateId` and a `source_id`, but have their own uids.
Here `Greeter` was saved as `greeter` and two copies were inserted below it with ++cmd+t++:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip="0,0,480,600" screenshot="assets/templates/two-copies.png" padding="0" wait="0" timeout="180" embed=true hidden=true transcript="../../dev_docs/agents/transcripts/docs_capture/templates_setup.yaml" document="docs-templates" fresh-document=true name="templates-two-copies" alt="the canvas with the original Greeter box and, below it, two Greeter copies inserted from the greeter template"}
ui.pause(800)
ui.click("tpl_greeter")
ui.pause(400)
ui.press("F1")
ui.pause(1000)
ui.type("Save selection as template")
ui.pause(800)
ui.press("Enter")
ui.pause(1000)
ui.type("greeter")
ui.pause(500)
ui.press("Enter")
ui.pause(1500)
for (y in [300.0, 440.0]) {
    ui.clickAt(250.0, y)
    ui.pause(400)
    ui.press("ControlOrMeta+t")
    ui.pause(1800)
    ui.type("greeter")
    ui.pause(800)
    ui.press("Enter")
    ui.pause(1500)
}
ui.pause(1000)
```

Slideshow chats use the same property: `withChat(id = "templateId")` gives every copy of a
template one shared discussion ([Interactive Guides](interactive_guides.md)).

## Share templates

A saved template is one `.edn` file holding its boxes and their code. Copy
`templates/greeter.edn` into another workspace's `templates/` folder and it appears in that
workspace's palette.

To offer templates from a server, start Field with `AFIELD_TEMPLATE_SERVERS` (or
`-Dafield.templateServers=`) set to a comma-separated list of URLs. At startup Field asks each
server's `/list` for a JSON list of `{"name": ..., "has_edn": true}` entries and adds them to the
palette as Remote templates; choosing one downloads `/download/<name>`. The list is read once, so
templates added to a server later appear after a restart.

## Templates, starting points and kits

Field uses the word "template" for two things.

- **Templates** (this page) are boxes you insert into the document you are in, with ++cmd+t++.
- **Starting points**, listed under **Templates** on the Welcome screen, are whole documents.
  Opening one makes a new document, a copy, in your workspace. Your own go in `~/.afield/templates/`
  ([Personal templates](installation.md#personal-templates)). Those folders and the checkout's
  `templates/` appear in both places: on the Welcome screen as starting points, and in the ++cmd+t++
  palette as templates whose boxes you insert into the document you are in.

A [kit](kits.md) packages starting points with the code, dependencies and assets they need, and
keeps track of which version each document received. Reach for a kit when the thing you want to
reuse is more than a few boxes, or when copies should be able to take updates; a template copy
never changes after it is inserted.
