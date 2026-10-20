# Automating Field

Everything you do to a Field document (creating a box, editing its code, wiring one box
into another, running it, stopping it) is an *operation*. Field can record those
operations, replay them, and let programs perform them from outside. There are three
ways to use that:

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
The [Agent transcript gallery](agent_transcripts.md) is a whole corpus of them: documents agents
built from a blank canvas this way, each one a transcript you can open, run and change.

The **Macro Recorder** panel (in the command palette) is where that recording happens. Name
the macro, press **Record**, and work as normal: making a box, typing its code, dragging it,
connecting it to another with `g`, deleting one, running one. Press **Save** and what you did
is saved as a named macro at document, project or workspace scope, written as the same
operations you would write by hand: a new box is one `create` with its name, frame and code,
not a stream of keystrokes.

Saved macros are listed by scope, each with its own **Run**, **Rename** and **Delete** (a
deleted macro is kept in the scope's `.deleted` folder). Click a macro's name to open its YAML
beside a readable list of its steps; edit the YAML there and **Save** to keep your version.
**Promote** copies a macro to a wider scope.

Replaying a recording in a fresh document rebuilds the same document. To use a recording as a
stamp instead, tick **as a copy**: the boxes it creates get new identities, so every run makes
another set, shifted by the **offset** you give. **Step** replays one operation at a time and
marks where you are in the list, which is the easiest way to see what a macro does before you
trust it with a document.

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
native Field 4 graphics window — which is how this site's figures are made. It
records short videos of the first three. The native graphics window is stills only.

[What a Capture Can and Cannot Show](capture_limits.md) lists what a capture cannot see, and
the reply fields that tell you when you have hit one of those limits.

## Where to go next

- [How these pages are built](staging.md#how-these-pages-are-built) says how the docs
  build itself uses this machinery.
- [Building & Testing](building_and_testing.md) covers the backdoor as a test harness.
- For the complete reference — every operation, the observable results, and worked
  examples on the canvas and in Field 4 Vulkan graphics — see the
  [operation transcript reference](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/agents/OPERATION_TRANSCRIPT.md)
  and the [backdoor reference](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/agents/FIELD_BACKDOOR.md)
  in the Field repository. The [agent transcript gallery](agent_transcripts.md) has a worked
  entry for every transcript Field ships. Field's default
  workspace setup also writes a short `AGENTS.md` for agents working inside that workspace.
