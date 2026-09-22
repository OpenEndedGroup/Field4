# Automating Field: macros, transcripts, and the backdoor

Everything you do to a Field document (creating a box, editing its code, wiring one box
into another, running it, stopping it) is an *operation*. Field can record those
operations, replay them, and let programs perform them from outside. That single idea
powers three things you can use:

- **Macros** — a saved sequence of operations you can replay on a document.
- **The OperationTranscript format** — the small YAML language those operations are
  written in, so a macro is something you (or an agent) can read, write, and share.
- **The backdoor** — a command-line way for scripts and AI agents to drive a running
  Field: build documents, run code, capture output and errors, and take screenshots.

The backdoor reaches Field over the same command channel Field's own editor uses, so it can do
whatever that channel can do. Which defences stand in front of that channel, and what each one
actually stops, is [What Field Exposes, and What Stops It](server_surface.md).

## Operations as data

A transcript is a list of operations. Each one names a *verb* and the box it acts on.
Here is a whole small document that creates a box, gives it code, runs it, and saves the
result as a reopenable document:

```diffstatic {data-lang=yaml}
operations:
  - create:
      box: greeter
      name: Greeter
      frame: { x: 40.0, y: 40.0, w: 320.0, h: 170.0 }
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

The **Macro Recorder** panel is where that recording happens: press **Start** and work as
normal while each operation you perform is appended to the transcript, then **Save** the
sequence as a named macro at document, project, or workspace scope. Saved macros are
listed along the bottom and **Run** replays them; **Promote** moves one to a wider scope.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".macro-recorder-panel" screenshot="assets/automating_field/macro-panel.png" transcript="assets/transcripts/greeter_macro.yaml" alt="Field's Macro Recorder panel listing recorded operations" padding="8" wait="0" timeout="90" embed=true hidden=true name="macro-panel"}
ui.eval("window.macroTranscriptPanel()")
ui.waitForSelector(".macro-recorder-panel", 8000.0)
ui.pause(1800)
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
and an `output` list with the `println` text of every box that ran, tagged by box and
line. That is how a script *knows* the code ran, and it is where compile errors and
exceptions appear. Running a box that isn't there, or that has no code, comes back as a
failure that names the box, never as a silent skip.

The backdoor also captures pictures — a single box, the editor, the whole window, or the
native `afield.four` graphics window — which is how this site's figures are made. It
records short videos of the first three. The native graphics window is stills only.

That asymmetry is not the only thing a capture will not do for you, and some of the rest
fail quietly: a `screenshot-box` of a box whose outputs were never opened comes back
`ok: true`, clipped to the collapsed box rather than to the output you wanted. **[What a
Capture Can and Cannot Show](capture_limits.md)** is the boundary, with the reply field
that tells you which side of it you are on.

## Where to go next

- The **[Staging Overview](staging.md)** section "Reproducible docs and transcript
  automation" explains how the docs build itself uses this machinery.
- **[Building & Testing](building_and_testing.md)** covers the backdoor as a test harness.
- For the complete reference — every operation, the observable results, and worked
  examples on the canvas and in `afield.four` Vulkan graphics — see `dev_docs/agents/`
  in the Field repository (`OPERATION_TRANSCRIPT.md` and `FIELD_BACKDOOR.md`). Field's default
  workspace setup also writes a short `AGENTS.md` for agents working inside that workspace.
