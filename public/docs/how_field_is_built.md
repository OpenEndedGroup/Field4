# How Field Is Built

Field is written by a few people and a lot of agents, all at once, in many separate checkouts.
That only works because the machinery around the code is unusually strict about one thing: a
green result has to come from running the real code, now. This page tours that machinery, with
emphasis on the parts that are unusual, that you can see and use from Field itself, and that
are worth copying into a project of your own:

- documentation whose every figure is a test,
- numbers in prose that are recomputed from the code,
- smoke tests written as annotations next to the code they exercise,
- a landing queue that is the only way onto `main`,
- documents that are scripts, and scripts that become the Welcome screen's starting points,
- agents that drive Field through the same protocol the browser uses,
- notes you leave on these pages, revised by agents,
- an AI assistant that learns from its own tool use.

For the commands you type day to day, see [Building the Docs & Running the Tests](building_and_testing.md)
and [Landing on `main`](landing_queue.md).

## Every figure in these docs ran

Almost every image, video and sound on this site is the output of a code block on the same page,
executed by a real Field while the site was being built. A block like that is written as an
ordinary fence with a few attributes saying what to capture. These are the attributes of the
completion example on [AI features](aiFeatures.md), which types into the editor and records the
popup:

```diffstatic {data-lang=text}
:::static
{data-lang=kotlin capture="ui" region="editor" warm-completion=true
 screenshot="assets/ai-autocomplete.png" wait="0" timeout="90" embed=true hidden=true}
```

`tools/build_zensical_docs.py` starts a Field, sends each block to it through
[the backdoor](#agents-drive-field-the-way-the-browser-does), and captures the result: the box's
output panel, the whole window, the editor, a native Vulkan window, a scripted walk through the
UI, a video of any of these, or a WAV rendered by the audio engine. 909 blocks on 140 pages run
this way. A block that throws, prints an error, or produces an empty or all-black picture fails
the build; `expect-error` turns that around for the pages that show an error on purpose.

Four modes decide how much is re-run:

| `FIELD_DOCS_RUNNER_MODE` | What runs |
| --- | --- |
| `never` | nothing; the committed captures are used |
| `incremental` | blocks whose code, options or reachable sources changed |
| `strict` | the same, and also everything when the capture machinery itself changed |
| `always` | everything |

What makes `incremental` trustworthy is what goes into a capture's cache key: the block's own
code and options, a hash of the capture machinery (the hooks file is hashed as a syntax tree, so
editing a comment does not invalidate 830 captures), and a hash of the source files the code
reaches, found through the generated [codebase reference](reference/index.md). Edit the
implementation of a stdlib function and the figures that call it are re-captured on the next
build, without anyone having to know which those are.

One rule sits over all of it: **a capture runs the real code, or it does not exist.** No mocked
output, no image drawn by a stand-in, no hand-edited screenshot. When a capture will not come out,
the fix goes into the code or into the capture system, or the figure is left out with a sentence
saying why. Captures that need special hardware carry a `capture-profile` (`linux-nvidia`,
`macos-electron`) and are kept as committed until a build on that hardware regenerates them.

`tools/docs_capture_audit.py` answers the question a green build cannot, whether a committed
figure is still true, by re-capturing a page and comparing pixel by pixel against a measured
noise floor:

```diffstatic {data-lang=bash}
:::static
uv run --group docs python tools/docs_capture_audit.py --page functions_examples --repeat 2
```

Inside Field, the docs open in an overlay at `/field-docs/`, served from your own checkout's build
when there is one and from the published site otherwise.

## Numbers in prose are recomputed from the code

A documentation page that says "there are 1029 call sites" is true the day it is written. Here,
a number like that carries a marker naming where it comes from. This is the source of a sentence
on [Where Field's Output Goes](logging.md):

```diffstatic {data-lang=text}
:::static
are **1029**<!--count:lprintlnChannelCallSites--> places in `src/jvmMain` that call
```

A test counts `lprintlnChannelCallSites` from the source tree and fails if the page disagrees.
When `main` moves, the landing step rewrites every such numeral from its derivation, and a git
merge driver settles them on both sides of a merge, so two branches that each changed the count
never conflict over it.

Three relatives of this run in the same suite, `citationGates`:

- **Citations** of the form `path:line#symbol` resolve by symbol, so the line number is a hint
  that can drift without breaking the citation, and a deleted symbol breaks it loudly.
- **Arithmetic.** A number marked with `derive:` and an expression has to equal that expression.
  It catches the sentence whose two inputs are cited correctly and whose sum is wrong.
- **Claims of impossibility** in the developer notes ("there is no way to ...", "X was ruled out")
  have to carry a reproducer test, a citation, or a dated `(conjecture, YYYY-MM-DD)` marker. An
  agent reading the notes later can then tell a measured limit from a guess.

These guards read Markdown, which is not on any classpath, so the task declares every page it
reads as a Gradle input, and a separate test parses the build file to check that it does. A
Markdown-only edit can never be answered with a cached pass about yesterday's text.

## Tests live next to what they test

A smoke test is an annotation in a comment beside the code it exercises, or in an HTML comment
beside a code block on a docs page. This one sits at the top of the file that builds the starting
points:

```diffstatic {data-lang=text}
:::static
/* FIELD_TEST
id: examples.agent-transcripts.materialize
runner: jvm-main
entrypoint: afield.examples.AgentTranscriptMaterializer
tags: [smoke, agents, transcript, docs, welcome, verification]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "AgentTranscriptMaterializer ok"
*/
```

`tools/run_field_tests.py` collects 325 of these, from Kotlin sources and from docs pages, into a
catalog and runs them against one warm Field. A `jvm-main` test runs a `main()` in a child JVM
with the same flags as Field; a `backdoor-box` test runs a block of box code and can check its
output text, the kinds of events it produced, and regions of its screenshot. The runner watches
disk, power and display while it works, so a laptop that went to sleep mid-run reports "not run"
instead of a failure.

```diffstatic {data-lang=bash}
:::static
uv run --project . --group backdoor python tools/run_field_tests.py --tag docs
```

Run unfiltered, it goes through the tests nobody has run yet first, then the least recently run,
so a partial run still makes progress through the whole catalog.

## Green means green

A few rules keep a passing result from meaning less than it says:

- **Accepted failures live in a register, and the register is checked both ways.** A failing test
  may be accepted only by an entry in `dev_docs/testing/expected_test_failures.json` (or the smoke
  equivalent) with an exact name, a reason and a document to read. A registered test that starts
  passing, or stops running, fails the build until its entry is removed. Both registers are
  currently empty.
- **Tests may not write into the source tree.** Every test task is wrapped in a check that
  snapshots the size and modification time of every tracked file and fails on any change,
  including a rewrite with identical bytes. Generators opt in by name.
- **Every guard's population is written down.** `dev_docs/GUARD_CORPORA.md` records how many
  transcripts, pages and test suites each guard scans, regenerated at every landing, so a guard
  that quietly stops seeing its inputs shows up as a changed row.
- **Generated files are regenerated, never merged.** The reference pages, the starting points, the
  completion corpus and the derived numbers all use a "keep ours" merge driver, and are rebuilt
  from both sides' sources after the merge.

## A queue is the only way onto `main`

Nobody, person or agent, merges into `main`. You commit on a branch and hand it over:

```diffstatic {data-lang=bash}
:::static
uv run python tools/land.py enqueue
```

A single process, the lander, takes up to eight queued branches at a time, merges them, regenerates
the generated files, runs the guards the changed paths select, and fast-forwards `main`. A git hook
refuses every other attempt to move `main`, so the queue is a fact rather than a convention. Its
live view is a terminal dashboard:

![The landing queue's live view: the lander is up under launchd, one branch is landing with a progress bar against the typical landing time and the step it is on, one more is queued with an estimated start, and the five most recent landings are listed with how long each took](assets/landing_queue/status_watch.webp)

The details that make it pleasant to use:

- **Red batches are bisected.** When a batch of eight fails, the lander halves it and retries
  from the oldest branch on the current `main` until the one responsible bounces alone, with the
  failing test's message attached ("found by bisecting a red batch of 8"). The other seven land.
- **A bounce explains itself.** `land.py mine` lists every attempt to land the current branch;
  `land.py huh` and `land.py bouncy` hand the queue's logs to Claude and come back with a
  diagnosis and a fix to paste into the branch's session.
- **It does not fight you.** A branch that touches a file you have uncommitted changes to waits
  instead of bouncing; a build that goes silent for 30 minutes is killed and retried once.
- `land.py doc` rebuilds this site from `main` at that point in the queue.

In its first week the queue landed 232 branches, a median of about nine minutes each. Field's
Welcome screen shows the result as its changelog.

## Documents are scripts, and scripts become starting points

A Field document is saved as `metadata.edn` ([What a Document Is on Disk](document_on_disk.md)).
Alongside it there is a second format, the operation transcript, which says how to *make* a
document: create these boxes, connect them, run this one, wait for it. It is plain YAML. This one
creates a single box and runs it:

```diffstatic {data-lang=yaml}
:::static
name: hello_display
description: Minimal blank-canvas macro that creates one runnable output box.
operations:
  - create:
      box: agent_hello_display
      name: Agent Hello Display
      frame: { x: 80.0, y: 80.0, w: 300.0, h: 100.0 }
      properties:
        code: |
          markdown("main", "hello from OperationTranscript")
  - execute:
      box: agent_hello_display
      property: code
      type: fragment
      code: |
        markdown("main", "hello from OperationTranscript")
  - wait:
      millis: 250
```

Transcripts are how agents build documents, how docs captures set up a scene, and how a person
can replay a construction step by step. They are atomic in the direction that matters: the first
failing operation stops the rest, so a box whose prerequisite failed is never launched. Field also
records your own edits, moves and launches as a transcript, which you can page through and turn
into a macro.

The repository holds 206 of them, and every one is verified: 105 are replayed in full and must
produce no error output, 94 are built and compiled, 7 are compiled to JavaScript. 200 of them are
turned into ready-made documents and committed. With the canonical graphics lessons, they make up
the **Starting points** list on Welcome:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-starting-points-crop" screenshot="assets/how_field_is_built/starting_points.png" padding="0" wait="0" timeout="120" embed=true hidden=true name="welcome-starting-points" alt="The Starting points list on the Welcome page, opened: its title with a count of 201, the line Curated example scenes, open one to get an editable copy in your workspace, and the first dozen entries, canonical_graphics with 37 boxes and then two-box scenes such as youtube_kaleidoscope, wfc_worked_example and vision_geometry_shapes, each with its box count and age."}
// /welcome is its own page: load it into a frame on the capture page at a desktop window's size,
// open Starting points the way a click on its title does, and mark the list's rectangle so the
// capture can be clipped to it (the clip selector sees only the top document).
ui.eval("""
    document.getElementById("docs-welcome-frame")?.remove()
    const f = document.createElement("iframe")
    f.id = "docs-welcome-frame"
    f.src = "/welcome"
    f.setAttribute("style", "position:fixed;left:0;top:0;width:1100px;height:1000px;border:0;background:#111;z-index:2147483000")
    document.body.appendChild(f)
    return true
""".trimIndent())
var state = ""
for (i in 0 until 80) {
    ui.pause(250)
    state = ui.eval("""
        const d = document.getElementById("docs-welcome-frame")?.contentDocument
        const section = d && d.querySelector("#starting-points-dock details.starting-points-section:not([data-section])")
        if (!section) return "loading"
        if (!section.open) section.querySelector("summary").click()
        return section.querySelectorAll(".starting-point-entry").length > 0 ? "ready" : "empty"
    """.trimIndent()).toString()
    if (state.contains("ready")) break
}
check(state.contains("ready")) { "the Starting points list did not load: " + state }
ui.pause(800)
ui.eval("""
    const f = document.getElementById("docs-welcome-frame")
    const r = (f.contentDocument.getElementById("starting-points-dock")).getBoundingClientRect()
    const o = f.getBoundingClientRect()
    document.getElementById("docs-starting-points-crop")?.remove()
    const m = document.createElement("div")
    m.id = "docs-starting-points-crop"
    m.setAttribute("style", "position:fixed;pointer-events:none;background:transparent;border:0;z-index:2147483001;" +
        "left:" + (o.left + r.left) + "px;top:" + (o.top + r.top) + "px;width:" + r.width + "px;height:" + r.height + "px")
    document.body.appendChild(m)
    return [r.width, r.height]
""".trimIndent())
ui.pause(300)
```

The [Agent Transcript Gallery](agent_transcripts.md) has one entry per transcript, each with an
**Open** link to its starting point; a guard keeps the gallery and the directory in exact
agreement. Going the other way, `afield.examples.DocumentToTranscript` turns any saved document
back into a transcript.

## Agents drive Field the way the browser does

The browser talks to Field over one WebSocket. `tools/field_backdoor.py` speaks the same protocol,
plus a set of agent commands, so anything you can do in the UI an agent can do from a terminal,
and capture:

```diffstatic {data-lang=bash}
:::static
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --command 'new-box --name Visual' \
  --command 'run @last --value "display(\"main\", \"<div>hello</div>\")"' \
  --command 'screenshot-box @last /tmp/field-smoke.png'
```

Its 49 commands cover documents, boxes, connections, running and launching, waiting on output,
screenshots and video of a box, the editor or the window, keyboard and mouse input, and Field's
own AI agent. A run command answers with the box's own verdict, so an agent learns that its code
failed from the reply rather than from reading a screenshot. Two safety rules make it usable by
many agents in many checkouts at once:

- **It knows which Field it is talking to.** It reuses a running Field only if that Field reports
  this checkout and started after the last change to the sources; otherwise it starts its own on
  the first free port from 9477 and stops it on exit.
- **One verification per checkout.** The runners take a lock, and the Gradle build waits on it,
  so two builds never trample each other's compiled classes.

`tools/field_mcp.py` offers the same reach as an MCP server: 22 tools of its own (snapshot,
outline, read and create boxes, run transcripts and macros, screenshots, a debugger probe that
samples a variable at a source line in the running JVM) plus every tool Field's in-editor AI has,
discovered when it attaches.

Browsing is done by a Playwright that belongs to the Field JVM: one shared, GPU-accelerated
Chromium (on macOS through Metal, about two and a half times faster for a full docs build than the
software renderer), or Field's own Electron app when a capture needs something only the app shows.

The same harness runs 60 browser UI regressions (`tools/run_ui_regressions.py`): the command
palette, Monaco, output panels, controllers, selection, split canvases, the three.js viewer, AI
chat, completion. Each is a list of backdoor commands whose JavaScript assertions must all pass,
and each keeps its last passing and failing screenshots.

## Notes on these pages, revised by agents

Reading these docs inside Field, you can select a passage, press `Ctrl+Shift+A` and leave a note.
Here is one being left on [Landing on `main`](landing_queue.md); the **DOC NOTES** panel beside the
page lists it with its state and where it sits in the source:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-notes-crop" screenshot="assets/how_field_is_built/notes_panel.png" padding="0" wait="0" timeout="120" embed=true hidden=true name="docs-notes-panel" alt="Landing on main open in Field's docs pane with the DOC NOTES panel on the right. The sentence That queues the current branch at its current commit and returns immediately is highlighted in the text, and the panel lists one OPEN note on it: Show what a bounce looks like here, and where its failures file is, tagged @example, with the quoted sentence, the date, a reply box, and resolve and delete buttons."}
// The docs open inside Field at /field-docs/. Load Landing on main into a frame wide enough for the
// DOC NOTES panel to sit beside the text, select a sentence, and leave a note with the real keys.
val note = "Show what a bounce looks like here, and where its failures file is. @example"
val sentence = "That queues the current branch at its current commit and returns immediately."
ui.eval("""
    document.getElementById("docs-notes-frame")?.remove()
    const f = document.createElement("iframe")
    f.id = "docs-notes-frame"
    f.src = "/field-docs/landing_queue/"
    f.setAttribute("style", "position:fixed;left:0;top:0;width:1720px;height:720px;border:0;background:#111;z-index:2147483000")
    document.body.appendChild(f)
    return true
""".trimIndent())
var state = ""
for (i in 0 until 80) {
    ui.pause(250)
    state = ui.eval("""
        const d = document.getElementById("docs-notes-frame")?.contentDocument
        if (!d || !d.getElementById("f4-annotations") || !d.querySelector("article p")) return "loading"
        return "ready"
    """.trimIndent()).toString()
    if (state.contains("ready")) break
}
check(state.contains("ready")) { "the docs page did not load with its notes panel: " + state }
// Fail closed: this capture runs against the automation server's own notebook
// (AFIELD_DOCS_ANNOTATIONS_STORE). Any note it did not write means it is looking at someone's real
// notes, which must neither be shown nor touched. Its own note from an earlier run is removed.
val store = ui.eval("""
    const items = await (await fetch("/api/docs-annotations?resolved=true")).json()
    if (items.some(a => a.note !== "$note")) return "foreign"
    for (const a of items) await fetch("/api/docs-annotations/" + encodeURIComponent(a.id), { method: "DELETE" })
    return "clean"
""".trimIndent()).toString()
check(store.contains("clean")) { "the notes store holds notes this capture did not write; refusing to capture it" }
ui.eval("""
    document.getElementById("docs-notes-frame")?.remove()
    const f = document.createElement("iframe")
    f.id = "docs-notes-frame"
    f.src = "/field-docs/landing_queue/"
    f.setAttribute("style", "position:fixed;left:0;top:0;width:1720px;height:720px;border:0;background:#111;z-index:2147483000")
    document.body.appendChild(f)
    return true
""".trimIndent())
for (i in 0 until 80) {
    ui.pause(250)
    state = ui.eval("""
        const f = document.getElementById("docs-notes-frame")
        const d = f?.contentDocument
        if (!d || !d.getElementById("f4-annotations") || !d.querySelector("article p")) return "loading"
        const p = [...d.querySelectorAll("article p")].find(e => e.textContent.includes("$sentence"))
        if (!p) return "no sentence"
        const t = [...p.childNodes].find(n => n.nodeType === 3 && n.nodeValue.includes("$sentence"))
        const at = t.nodeValue.indexOf("$sentence")
        const range = d.createRange()
        range.setStart(t, at)
        range.setEnd(t, at + "$sentence".length)
        const selection = f.contentWindow.getSelection()
        selection.removeAllRanges()
        selection.addRange(range)
        f.contentWindow.focus()
        return "ready"
    """.trimIndent()).toString()
    if (state.contains("ready")) break
}
check(state.contains("ready")) { "could not select the sentence to annotate: " + state }
ui.press("Control+Shift+A")
ui.pause(400)
ui.typeSlowly(note, 15)
ui.press("Enter")
for (i in 0 until 40) {
    ui.pause(250)
    state = ui.eval("""
        const d = document.getElementById("docs-notes-frame").contentDocument
        return d.querySelectorAll("#f4-annotations .f4a-note").length > 0 ? "saved" : "saving"
    """.trimIndent()).toString()
    if (state.contains("saved")) break
}
check(state.contains("saved")) { "the note did not appear in the panel: " + state }
ui.pause(800)
// Clip to the page's text column and the panel, from the title down past the annotated paragraph.
ui.eval("""
    const f = document.getElementById("docs-notes-frame")
    const d = f.contentDocument
    const title = d.querySelector("article h1").getBoundingClientRect()
    const panel = d.getElementById("f4-annotations").getBoundingClientRect()
    const card = d.querySelector("#f4-annotations .f4a-note").getBoundingClientRect()
    const p = [...d.querySelectorAll("article p")].find(e => e.textContent.includes("$sentence")).getBoundingClientRect()
    const o = f.getBoundingClientRect()
    const left = o.left + title.left - 24, top = o.top + Math.max(0, title.top - 16)
    const bottom = o.top + Math.max(p.bottom, card.bottom) + 40
    document.getElementById("docs-notes-crop")?.remove()
    const m = document.createElement("div")
    m.id = "docs-notes-crop"
    m.setAttribute("style", "position:fixed;pointer-events:none;background:transparent;border:0;z-index:2147483001;" +
        "left:" + left + "px;top:" + top + "px;width:" + (o.left + panel.right - left) + "px;height:" + (bottom - top) + "px")
    document.body.appendChild(m)
    return true
""".trimIndent())
ui.pause(300)
```

The notes are a revision queue ([Notes on the Documentation, Revised by Agents](docs_notes.md)):

- A note remembers which build of the site you were reading, finds your selection in that version
  of the page, and from then on follows those lines through `git blame`. Edits and moves elsewhere
  in the page do not lose it. A note on a generated reference page lands on the KDoc in the source
  file it came from.
- `tools/docs_notes.py revise` divides the notes so that each file belongs to exactly one agent,
  and runs one agent per group, four at a time. Agents never edit the notes store: they report
  "resolved", "question" or "remember this about the page", and the harness applies those after
  checking them. When the agents hit a usage limit, the remaining notes wait for the reset.
- A revision pass runs in its own worktree, rebuilds the site there so you can read the result,
  and lands through the queue. The **Revision pass** button on Welcome drives the same thing.
- Work bigger than a page becomes a handoff: a short document with the notes it holds, grouped
  into batches that a separate session picks up.

The **Revision pass** page, `/docs-pass.html`, is where a pass is started, watched, reviewed and
landed. It reads the same queue, so the note above is already waiting in it:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-pass-crop" screenshot="assets/how_field_is_built/docs_pass.png" padding="0" wait="0" timeout="150" embed=true hidden=true name="docs-pass-page" alt="The first three steps of the documentation revision pass page. 1, Worktree: no pass is open, with Start a pass, Finish and Abandon buttons. 2, Drain the queue: 1 open note, the agent (claude), how many at a time (4), tag and page filters, and buttons to drain the queue, stop, and import completion KDoc drafts. 3, Handoffs: how handoffs are worked in batches, a button to group them, and None open."}
// /docs-pass.html is its own page: load it into a frame and wait for the status it fetches from
// tools/docs_notes.py (the queue, the counts, the handoffs), then take its first three steps.
ui.eval("""
    document.getElementById("docs-pass-frame")?.remove()
    const f = document.createElement("iframe")
    f.id = "docs-pass-frame"
    f.src = "/docs-pass.html"
    f.setAttribute("style", "position:fixed;left:0;top:0;width:1200px;height:1400px;border:0;background:#111;z-index:2147483000")
    document.body.appendChild(f)
    return true
""".trimIndent())
var state = ""
for (i in 0 until 240) {
    ui.pause(250)
    state = ui.eval("""
        const d = document.getElementById("docs-pass-frame")?.contentDocument
        if (!d || !d.getElementById("posture")) return "loading"
        if (/Loading/.test(d.getElementById("posture").textContent)) return "posture"
        if (!d.getElementById("counts")?.children.length) return "counts"
        if (!d.getElementById("handoffs")?.children.length) return "handoffs"
        return "ready"
    """.trimIndent()).toString()
    if (state.contains("ready")) break
}
check(state.contains("ready")) { "the revision pass page did not finish loading: " + state }
ui.pause(800)
ui.eval("""
    const f = document.getElementById("docs-pass-frame")
    const r = ((() => {
        const d = f.contentDocument
        const first = d.getElementById("pass-summary").closest("section").getBoundingClientRect()
        const last = d.getElementById("handoffs").closest("section").getBoundingClientRect()
        return { getBoundingClientRect: () => ({ left: first.left, top: first.top, width: first.width, height: last.bottom - first.top }) }
    })()).getBoundingClientRect()
    const o = f.getBoundingClientRect()
    document.getElementById("docs-pass-crop")?.remove()
    const m = document.createElement("div")
    m.id = "docs-pass-crop"
    m.setAttribute("style", "position:fixed;pointer-events:none;background:transparent;border:0;z-index:2147483001;" +
        "left:" + (o.left + r.left) + "px;top:" + (o.top + r.top) + "px;width:" + r.width + "px;height:" + r.height + "px")
    document.body.appendChild(m)
    return [r.width, r.height]
""".trimIndent())
ui.pause(300)
```

The rules every page agent reads are the distilled notes from earlier passes, and an agent works
through the site in navigation order every three hours, three pages at a time, against the same
rules.

## An assistant that learns from its own tool use

Every session of Field's in-editor AI logs its tool calls ([AI tool-use history](ai_tool_use_history.md)).
The AI panel on Welcome shows these improvement tasks side by side, each with its current state:
completion's KDoc drafts waiting for review, the documentation notes, and the tool-use logs:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-welcome-cards-crop" screenshot="assets/how_field_is_built/welcome_cards.png" padding="12" wait="0" timeout="120" embed=true hidden=true name="welcome-improvement-cards" alt="Three task cards from the AI panel on the Welcome page. KDoc drafts: 30 drafts in 13 files, with Review in docs pass. Documentation notes: 1 open, with Revision pass. Agent tool use: no logs yet in this checkout, with Review logs, Generate proposal and Mark reviewed."}
// /welcome is its own page: load it into a frame, open the AI panel the way a click on its summary
// does, and mark the rectangle from the KDoc drafts card to the Agent tool use card.
ui.eval("""
    document.getElementById("docs-welcome-frame")?.remove()
    const f = document.createElement("iframe")
    f.id = "docs-welcome-frame"
    f.src = "/welcome"
    f.setAttribute("style", "position:fixed;left:0;top:0;width:1700px;height:2000px;border:0;background:#111;z-index:2147483000")
    document.body.appendChild(f)
    return true
""".trimIndent())
var state = ""
for (i in 0 until 80) {
    ui.pause(250)
    state = ui.eval("""
        const d = document.getElementById("docs-welcome-frame")?.contentDocument
        const panel = d && d.getElementById("ai-settings-details")
        if (!panel) return "loading"
        if (!panel.open) panel.querySelector("summary").click()
        const states = ["completion-doc-polish-state", "docs-pass-state", "ai-tool-use-improvement-state"]
            .map(id => d.getElementById(id)?.textContent || "checking")
        return states.some(s => /checking/i.test(s)) ? "pending" : "ready"
    """.trimIndent()).toString()
    if (state.contains("ready")) break
}
check(state.contains("ready")) { "the welcome AI panel did not finish loading: " + state }
ui.pause(1000)
ui.eval("""
    const panel = document.getElementById("docs-welcome-frame").contentDocument.getElementById("ai-settings-details")
    panel.querySelectorAll(".ai-docs-panel")[0].scrollIntoView({ block: "start" })
    return true
""".trimIndent())
ui.pause(300)
ui.eval("""
    const f = document.getElementById("docs-welcome-frame")
    const r = ((() => {
        const cards = [...f.contentDocument.querySelectorAll("#ai-settings-details .ai-docs-panel")]
        const a = cards[0].getBoundingClientRect(), b = cards[2].getBoundingClientRect()
        const box = f.contentDocument.getElementById("ai-settings-details").getBoundingClientRect()
        return { getBoundingClientRect: () => ({ left: box.left, top: a.top, width: box.width, height: b.bottom - a.top }) }
    })()).getBoundingClientRect()
    const o = f.getBoundingClientRect()
    document.getElementById("docs-welcome-cards-crop")?.remove()
    const m = document.createElement("div")
    m.id = "docs-welcome-cards-crop"
    m.setAttribute("style", "position:fixed;pointer-events:none;background:transparent;border:0;z-index:2147483001;" +
        "left:" + (o.left + r.left) + "px;top:" + (o.top + r.top) + "px;width:" + r.width + "px;height:" + r.height + "px")
    document.body.appendChild(m)
    return [r.width, r.height]
""".trimIndent())
ui.pause(300)
```

Two things are built on those logs:

- **Distilled lessons.** `agent-eval --distil` finds tool misuse that recurs across sessions and
  writes it, mechanically and without a model, into the "Distilled" section of the lessons file
  that every AI turn with the graph tools is given. A "Curated" section is for people. Seventeen
  scripted and live evaluation tasks measure whether a lesson helped.
- **Proposals.** The **Generate proposal** button on Welcome gives a model the recent tool logs and
  a map of the source tree and asks it for one small improvement, made in its own branch and
  worktree; **Try** restarts Field from that worktree so you can use the change before keeping it.

## While nobody is looking

Two scheduled agent runs use all of the above every night. One builds three small improvements,
each in its own branch, and a fourth agent reviews them and writes a verdict on each. The other
builds `main` from clean, fixes any new compiler warnings without suppressing them, and runs the
full gates, the complete docs recapture and the smoke catalog. Both keep their plans and their
memory on branches of their own, never touch `main`, and leave their work for a person to land.

## Ideas worth taking with you

If you build something similar, these are the parts that lift most easily into another project:

1. Make the documentation's examples the test suite, and key each capture on the source it reaches.
2. Put a derivation next to every number you write in prose.
3. Keep accepted failures in a register that fails when an entry stops failing.
4. Let one process write `main`, and enforce it with a hook rather than a rule.
5. Bisect red batches rather than landing branches one by one.
6. Regenerate generated files after a merge instead of merging them.
7. Give agents the same protocol as your UI, and have every verb return the real verdict.

??? aside "Where the numbers on this page come from"
    Measured on 2026-10-09 at `main` cc10af8b5. Runner blocks: fence-opening lines naming
    `diffstatic_runner` or `diff_runner` under `public/docs`. Smoke annotations: lines that are
    exactly `FIELD_TEST` under `src/jvmMain` and `public/docs`. Transcripts: `*.yaml` at the top of
    `dev_docs/agents/transcripts/`, classified in `DemoTranscriptExecSmokeTest`; starting points:
    `metadata.edn` files under `public/docs/assets/agent_transcripts/`. Landings: first-parent
    `Land` commits on `main` since 2026-10-03, median time from the queue's own records.
    Backdoor commands: the subcommands of `build_command_parser()` in `tools/field_backdoor.py`.
    MCP tools: the `TOOLS` list in `tools/field_mcp.py`. UI regressions: `UiRegression(` in
    `tools/run_ui_regressions.py`. None of these is pinned by a count marker yet, so they will
    drift; the internal inventory with the full commands is `dev_docs/BUILD_INFRASTRUCTURE.md`.
