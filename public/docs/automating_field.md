# Automating Field: macros, transcripts, and the backdoor

Everything you do to a Field document — creating a box, editing its code, wiring one box
into another, running it, stopping it — is an *operation*. Field can record those
operations, replay them, and let programs perform them from outside. That single idea
powers three things you can use:

- **Macros** — a saved sequence of operations you can replay on a document.
- **The OperationTranscript format** — the small YAML language those operations are
  written in, so a macro is something you (or an agent) can read, write, and share.
- **The backdoor** — a command-line way for scripts and AI agents to drive a running
  Field: build documents, run code, capture output and errors, and take screenshots.

The screenshots and rendered figures throughout this documentation are produced by
exactly this machinery — Field building and running the examples during the docs build.

## Operations as data

A transcript is a list of operations. Each one names a *verb* and the box it acts on.
Here is a whole small document — create a box, give it code, run it, and save the result
as a reopenable document:

```diffstatic {data-lang=yaml}
operations:
  - create:
      box: greeter
      name: Greeter
      frame: { x: 40.0, y: 40.0, w: 220.0, h: 120.0 }
      properties:
        code: |
          println("hello from a transcript")
  - launch:
      box: greeter
      property: code
  - await:
      wait: pending
  - save:
      path: MyWorkspace/greeter
```

The verbs you will use most: `create`, `edit`, `move`, `connect` (wire a parent box to a
child), `execute` (run once), `launch` (start a running job), `stop`, `await` (wait for
everything to settle), and `save` (write a document you can open again later). A `launch`
or `execute` with no inline code simply runs the code the box already has.

Because the transcript is just data, a macro is legible: you can open one, see what it
does, change a name or a value, and run your version. Field records what you do as you
work, so you can turn a sequence you just performed into a macro and replay it elsewhere.

## The macro recorder

The **Macro Recorder** panel is where that recording surfaces. It shows the live
transcript as you work — each operation rendered as a legible line — with controls to
name and save the sequence, choose its scope (document, project, or workspace), and run
or undo saved macros. What you save here is the same OperationTranscript YAML shown
above.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" screenshot="assets/automating_field/macro-recorder-panel.png" padding="0" wait="0" timeout="90" embed=true hidden=true name="macro-recorder-panel" alt="The Macro Recorder panel showing a live transcript and macro controls"}
ui.openEditor(id)
ui.pause(500)
ui.clickSelector(".monaco-editor")
ui.pause(200)
ui.press("Control+A")
ui.type("val a = vec(1.0, 2.0)\nval b = a * 2.0\nb.plot(0.0..1.0)")
ui.pause(400)
// Open the Macro Recorder panel (same as the "Macro: Recorder Panel" palette command).
ui.eval("window.macroTranscriptPanel(); return 'open';")
ui.waitForSelector("#macro-recorder-panel.is-visible", 8000.0)
ui.pause(400)
// Name the macro and start recording so the panel shows an active session.
ui.eval("var p=document.getElementById('macro-recorder-panel'); var n=p.querySelector(\"[data-role='name']\"); if(n){n.value='scale-and-plot';} var s=p.querySelector(\"[data-action='start']\"); if(s){s.click();} return 'started';")
ui.pause(500)
// A recordable edit while recording, so the transcript picks up an operation.
ui.clickSelector(".monaco-editor")
ui.pause(200)
ui.type("\nb.plot(0.0..2.0, channel = \"wide\")")
ui.pause(1200)
// Expand the fixed panel to fill the viewport so the window still frames the
// panel tightly (the overlay clip path can't resolve this bottom-docked panel).
ui.eval("var p=document.getElementById('macro-recorder-panel'); if(p){p.style.top='8px';p.style.left='8px';p.style.right='8px';p.style.bottom='8px';p.style.width='auto';p.style.height='auto';p.style.maxWidth='none';p.style.maxHeight='none';} return 'filled';")
ui.pause(600)
```

## Driving Field from a script or agent

The backdoor (`tools/field_backdoor.py` in the Field repository) attaches to a running
Field — or starts one — and applies transcripts, then hands back a machine-readable
result. Write the transcript above to a macro and run it:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch \
  --command 'macro write greeter --file greeter.yaml' \
  --command 'macro run greeter'
```

The result tells you what happened: how many operations applied, whether anything failed,
and an `output` list with the `println` text of every box that ran — tagged by box and
line. That is how a script *knows* the code ran, and it is where compile errors and
exceptions appear. Running a box that isn't there, or that has no code, fails loudly with
a message that names the box rather than quietly doing nothing.

The backdoor also captures pictures — a single box, the editor, the whole window, or the
native `afield.four` graphics window — and short videos, which is how this site's figures
are made.

## Where to go next

- The **[Staging Overview](staging.md)** section "Reproducible docs and transcript
  automation" explains how the docs build itself uses this machinery.
- **[Building & Testing](building_and_testing.md)** covers the backdoor as a test harness.
- For the complete reference — every operation, the observable results, and worked
  examples on the canvas and in `afield.four` Vulkan graphics — see `dev_docs/agents/`
  in the Field repository (`OPERATION_TRANSCRIPT.md` and `FIELD_BACKDOOR.md`). Every Field
  workspace also carries a short `AGENTS.md` that onboards an agent working inside it.
