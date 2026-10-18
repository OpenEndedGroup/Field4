# Editing a document from outside Field

You can edit a box's code in your own editor, or have a script or an agent write it, and Field
picks up the change while the document is open. That works because a document is a folder on
disk and each box's code is a plain file in it. This page covers which file to edit, what Field
does when it changes, and the few cases where your edit does not take effect or is overwritten.

[What a Document Is on Disk](document_on_disk.md) explains the format itself: every file a save
writes, and which copy wins on load.

## Find the file

A document called `sketch` in your workspace is the folder `<workspace>/sketch/`. Each box's
code is saved beside `metadata.edn` as its own file, named after the box's id:

```diffstatic {data-lang=text}
sketch/
  metadata.edn
  f2b585c8-0009-44cd-8181-06ac28784e2d_code_.kt
  7a2b3e64-f79b-4ef1-bd50-5eeb48d30886_code_.kt
  7a2b3e64-f79b-4ef1-bd50-5eeb48d30886_fragment_.glsl
  7a2b3e64-f79b-4ef1-bd50-5eeb48d30886_vertex_.glsl
  backups/
```

The name is `<box id>_<property>_.<extension>`. The id is the box's uid, not its name, so
renaming a box does not rename its files. To find a box's file, search for its code with
`grep -l` or look the id up in `metadata.edn`, where each box's `"name"` sits beside its `"id"`.

These properties get their own file:

| Property | File extension |
| --- | --- |
| `code` | `.kt` |
| `fragment`, `vertex`, `compute`, `task`, `mesh` and the ray-tracing stages | `.glsl` |
| `vertex_fragment`, `fragment_s` and the other `_s` stages | `.slang` |
| `metadata` | `.yaml` |
| `customization` | `.js` |

`Box.ioSeparateProperties` in `Boxes.kt` holds the full list. The root box never gets these
files: its properties live in `metadata.edn`.

## Edit a box's code

Save the file. Within about two seconds the box's `code` holds what you wrote. Field sends the new text to any
editor showing that box, a notification says *Reloaded external change to code in ...*, and Field's
next autosave updates the copy in `metadata.edn`. Saving three times, a second apart, gave three
reloads, each within two seconds.

Field reloads the code but does not run it. A box that is not running runs your version the
next time you run it. A box that is running carries on with the code it started with: a `loop`
printing `tick v1` kept printing `tick v1` after its file was changed to print `tick v2`.
Stop the box and launch it again to run the new version.

## Edit a shader

A `.glsl` file works the same way: the box's `fragment` (or `vertex`, ...) property is replaced,
and the shader on screen does not change. The renderer recompiles a shader only when you ask it
to, so after saving the file, select the box and use **Reload Shader** (`alt r`). In a test with
the *hello triangle* lesson, the triangles kept their colours after the fragment file was changed
to solid red, and turned red after **Reload Shader**.

## Add a box by writing a file

A new file named for an id that is not in the document creates a box. Writing
`from-script_code_.kt` into an open document added a box with id `from-script`, put it in a free
spot on the canvas, gave it that code and saved the document. The box is named after its id.
This is the simplest way for a script to add a box: choose a readable id, write the file, and the
box appears.

## When your edit loses

Field and your editor can disagree about a box. These cases decide which version is kept.

**You edited the same box in Field and on disk.** If you change a box in Field and then change
its file before Field has autosaved, the autosave writes Field's version over your file. The file
you wrote is not lost: Field backs up the previous contents of a file before overwriting it, so
your version is the newest `<id>_code__backup_*.kt` in `backups/`. To avoid this, edit a box in
one place at a time, or wait a few seconds after typing in Field before editing the file.

**The document was not open.** Field watches a document only while a Field window (or another
client) has it open. A file you change while the document is closed is not lost: it is read
when the document next opens, and that file wins over the copy in `metadata.edn`.

**You deleted the file.** Deleting a box's file does not delete its code. The box keeps the code
it had, and the next save writes the file back. To delete a box, delete it in Field.

**You edited the code inside `metadata.edn`.** `metadata.edn` keeps a copy of each box's code
as `"inline"`, but while the box's own file exists, that file is what counts. Changing the inline
copy has no effect, and the next save puts it back. Edit the file instead.

## Edit `metadata.edn` itself

Everything else about a box — its name, frame, connections and other properties — lives in
`metadata.edn`, and Field applies hand edits to it while the document is open. In a test, changing
a box's `"name"` and `"frame"` renamed and moved it on the canvas, and adding a new
`{"id" ... "properties" {...}}` entry to the `"boxes"` list created the box. Field does not rewire
the connections of a box already open from an edit to `metadata.edn`, and it ignores deletion of
the file. [What a Document Is on Disk](document_on_disk.md#editing-the-directory-under-a-running-field)
gives the exact rules.

Field also writes a `document.md` beside `metadata.edn`: a readable copy of the boxes and their
code, meant for tools that read documents. Field never reads it, so editing it changes nothing.

??? aside "How this page was checked, and why it has no figures"
    Every behaviour above was run on 2026-10-18 against a live Field on a scratch document,
    editing files from a shell while the backdoor held the document open, and checking the box
    with the backdoor's `boxes`, `run`, `wait-output` and `native-screenshot` commands and
    `~/.afield/logs/save_reload.log`. The checks interleave a shell and a running Field over
    several seconds, which a docs-runner capture fence cannot stage, so no figure was made rather
    than one that only appears to show it.

    The same runs found two bugs, both fixed alongside the page. A second edit to a file made
    before the autosave that followed the first was skipped as an unsaved Field edit, and that
    autosave then wrote the first edit back over the file (`examples.file-watch.sidecar-reedit`).
    And a box created from a dropped file was named after its property (`Code`) rather than its id.
