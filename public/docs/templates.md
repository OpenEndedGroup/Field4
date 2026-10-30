# Templates

A template is a box, or a group of boxes, saved under a name so you can drop copies of it into any
document. Select the boxes and run **Save selection as template** from the command palette; later,
press `cmd+t` (**Insert template**) in any document and pick the name. Each insertion is a fresh,
independent copy, placed where your mouse is.

Templates are for pieces of a document. For a whole document to start from, use a
[starting point on the Welcome screen](installation.md#personal-templates); for reusable code,
dependencies and documents packaged together, use a [kit](kits.md).

## Save boxes as a template

Select the boxes you want to keep: one box, or a group with the connections between them. Open
the command palette (`f1`) with the canvas focused, choose **Save selection as template**, type a
name and press Enter.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".quick-input-widget" screenshot="assets/templates/save-name.png" padding="6" wait="0" timeout="120" embed=true hidden=true name="templates-save-name" alt="the Save selection as template name field, with greeter typed into it"}
name = "Greeter"
metadata = "doc: |\n  Prints a greeting that names its box.\n"
ui.pause(800)
ui.click(id)
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

The template is a snapshot. Editing the boxes you saved from changes nothing in the template, and
saving again under the same name changes nothing in the copies already made.

## Insert a template

Move the mouse to where you want the copy, press `cmd+t` and start typing the name. The
palette lists every template it can find, with the documentation of its boxes beside the
selected entry.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".quick-input-widget" screenshot="assets/templates/insert-palette.png" padding="6" wait="0" timeout="150" embed=true hidden=true name="templates-insert-palette" alt="the Insert template palette filtered to greeter, showing Workspace template and the box's documentation"}
name = "Greeter"
metadata = "doc: |\n  Prints a greeting that names its box.\n"
ui.pause(800)
ui.click(id)
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
ui.moveMouse(600.0, 400.0)
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
| Workspace file | another document in your workspace: inserting it copies all of its boxes into this one |
| Remote template | a template server you configured (see [Share templates](#share-templates)) |

When two entries have the same name, a workspace template is inserted before a bundled one, and a
bundled one before a workspace document.

### Describe a template

The text beside an entry comes from its boxes' `metadata` property, which is YAML. Field
reads `doc` from every box in the template, top box first, so a few lines on each box explain the
whole group:

```diffstatic {data-lang=yaml}
doc: |
  Prints a greeting that names its box.
```

A template with no `doc` anywhere shows "No documentation available".

## A copy remembers where it came from

Every inserted box records two properties, and both are saved with the document:

- `templateId` is the name of the template it was inserted from (`greeter`).
- `source_id` is the uid the box had inside the template, which is the uid of the box you saved.

So two copies of `greeter` share a `templateId` and a `source_id`, but have their own uids.
Here two copies of `greeter` were inserted with `cmd+t`, one beside the other:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip="0,0,1100,560" screenshot="assets/templates/two-copies.png" padding="0" wait="0" timeout="180" embed=true hidden=true name="templates-two-copies" alt="the canvas with two boxes named Greeter, inserted from the greeter template at two mouse positions"}
name = "Greeter"
metadata = "doc: |\n  Prints a greeting that names its box.\n"
ui.pause(800)
ui.click(id)
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
for (x in listOf(450.0, 700.0)) {
    ui.moveMouse(x, 300.0)
    ui.pause(300)
    ui.press("ControlOrMeta+t")
    ui.pause(1800)
    ui.type("greeter")
    ui.pause(800)
    ui.press("Enter")
    ui.pause(1500)
}
```

Box code can find every copy. This box lists the boxes beside it that came from a template:

```diffstatic_runner {data-lang=kotlin screenshot="assets/templates/copies-output.png" embed=true wait="1" timeout="90" name="templates-copies-output" alt="the box's output: one line per Greeter copy with its templateId greeter and the source_id they share"}
for (b in root.children - thisBox) {
    if (b.templateId != null)
        print("${b.name}  templateId=${b.templateId}  source_id=${b.properties["source_id"]}")
}
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

- **Templates** (this page) are boxes you insert into the document you are in, with `cmd+t`.
- **Starting points**, listed under **Templates** on the Welcome screen, are whole documents.
  Opening one makes a new document, a copy, in your workspace. Your own go in `~/.afield/templates/`
  ([Personal templates](installation.md#personal-templates)). They do not appear in the `cmd+t`
  palette; the bundled ones in the checkout's `templates/` folder appear in both.

A [kit](kits.md) packages starting points with the code, dependencies and assets they need, and
keeps track of which version each document received. Reach for a kit when the thing you want to
reuse is more than a few boxes, or when copies should be able to take updates; a template copy
never changes after it is inserted.
