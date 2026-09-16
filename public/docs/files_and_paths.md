# Where Field looks for a file

A Field document is a folder, not a single file, and the code in a box runs from wherever that
folder happens to be sitting. So a path typed into a box has a problem the same path in a compiled
program does not: `File("/Users/you/Desktop/scan.ply")` is correct exactly once, on one machine,
until the document is copied, moved into a project, or sent to somebody else.

Field's answer is a resolver. You hand it a short name, and it looks in the document first and the
workspace second.

## The one line to learn

```diffstatic {data-lang=kotlin}
val rows = "data.csv".file.readLines()
```

`"data.csv".file` is a `java.io.File`. Two more extensions run the same resolution and hand back a
different type:

| Spelling | You get | Reach for it when |
| --- | --- | --- |
| `"data.csv".file` | `java.io.File` | almost always |
| `"data.csv".path` | `java.nio.file.Path` | you are going on to use `java.nio.file` |
| `"data.csv".absolutePath` | `String`, absolute and normalised | you are handing a path to a subprocess |

There is a spelled-out form too, for when the name is in a variable rather than a literal:

```diffstatic {data-lang=kotlin}
val a = files.file("data.csv")          // `files` is the current session's file manager
val b = fileManager.file(name)          // `fileManager` is the same object under a longer name
val c = files.path("data.csv")
```

## Where a plain name is looked for

In order, first existing file winning:

1. `files/` inside the document folder;
2. `files/` inside each workspace project the document belongs to, which lives at
   `_projects/<project>/files/` in the workspace.

If neither has it, you still get a path back: the one the name *would* have inside the document's
`files/`. That is what makes writing a new file work.

```diffstatic {data-lang=kotlin}
// Resolves to <document>/files/out/frame-0001.png whether or not anything is there yet.
// Intermediate directories are yours to create.
val out = "out/frame-0001.png".file
out.parentFile.mkdirs()
out.writeBytes(png)
```

A resolution is cached only once the file exists, so resolving a name before you create it does not
pin the wrong answer for the rest of the session.

## Naming the root yourself

Two roots have built-in names, and a leading `%` asks for one of them by hand rather than searching:

```diffstatic {data-lang=kotlin}
val mine   = "%document/data.csv".file   // this document's files/ only, skipping the projects
val shared = "%project/palette.json".file // the project files/ only, skipping the document
val dir    = "%document".path             // the files/ directory itself
```

`%project` needs the document to be in exactly one workspace project, or in one whose `files/`
already holds the name. In a document that is in no project it throws; in a document that is in two
and where the file is in neither, it throws as ambiguous.

Asking for `%document/...` creates the document's `files/` directory if it is missing, as does
reading `files.documentFilesDirectory`.

## Roots of your own

```diffstatic {data-lang=kotlin}
FileManager["textures"] = "~/Pictures/field-textures"

val brick = "%textures/brick.png".file
```

Registration is process-global rather than per-document, so every open document sees `%textures`,
and registering the same name again replaces it without saying so. The directory has to exist
already unless you ask for it:

```diffstatic {data-lang=kotlin}
FileManager.add("renders", "~/Desktop/field-renders", create = true)
FileManager["renders"]                   // the absolute Path stored, or null
FileManager.remove("renders")            // unregisters; nothing on disk is touched
```

A name has to look like an identifier: letters, digits, `_` and `-`, not starting with a digit. A
leading `%` or `$` is stripped for you. `document` and `project` are reserved, since the resolver
owns them.

## Globs

A spec containing `*`, `?`, `[` or `{` is matched by walking each search root recursively and taking
the first match in sorted path order:

```diffstatic {data-lang=kotlin}
val anyScan = "**/*.ply".file            // first .ply under the document, then under the projects
val inFolder = "scans/*.ply".file
```

Sorting first makes the choice repeatable between runs, but a glob is still a poor way to ask for
one specific file. Note that a glob matching nothing throws, where a plain name matching nothing
gives you a path to write to: there is no sensible path to invent for a pattern.

## Paths that skip all of this

Absolute paths, and paths starting `~/`, are expanded and normalised and otherwise passed straight
through:

```diffstatic {data-lang=kotlin}
val big = "~/Datasets/city.laz".file
```

Use these when the file genuinely lives outside the document, such as a dataset too large to copy
in, and accept that the document stops being self-contained at that point. If the same outside
directory comes up more than once, register it as a named root instead and the document at least
says what it is looking for.

## When resolution fails

| Spec | What happens |
| --- | --- |
| a blank name | throws |
| `"**/*.ply"` matching nothing | throws, naming the roots it walked |
| `"%textures/x.png"` with nothing registered as `textures` | throws |
| `"%project/x.csv"` in a document in no project | throws |
| `"%project/x.csv"` in a document in two projects, file in neither | throws as ambiguous |
| `"%document/../../elsewhere"` | throws: the spec climbs out of its root |
| any of the above from a plain unit test or a bare thread | throws: no session to be relative to |

That last row follows from the first paragraph of this page. The resolver answers "where is
`data.csv`" with respect to a specific open document, so it needs a live session or an active box
context, and there is no way to use it outside one.

## Where did that path actually go

Every lookup is appended to the session root's `fileManagerAccessHistory` property: the request, the
resolved path, which root answered it, whether the file existed, and which box asked. Read it when a
name resolved somewhere you did not expect.

It holds the most recent `FileManager.maxAccessHistoryEntries` lookups, 1024 by default, and is
emptied when the session's file manager is first created, so it is a recent-activity log rather than
a complete one.

## `abslutePath`, the misspelling that still works

`String.abslutePath` resolves exactly as `absolutePath` does. It is deprecated rather than deleted
because a live-coded document is a file on somebody's disk, not a call site that can be recompiled.
If you meet it in old box code, it does nothing different.

---

Full signatures and the rest of the API are in
[FileManager.kt in the Data reference](reference/data.md#filemanager-kt).
