# Experimenting with AI4

*The document on this page is the [ai4_paint_studio](agent_transcripts.md#ai4_paint_studio)
starting point: the Studio box from [AI4 Paints](ai4_painting.md) and the Scribbler box shown
below. It needs the paint library (`./gradlew buildPaintNative`) and an AI provider.*

An experiment with an AI agent in Field lives in a document. The tools the agent can call, what it
is told at the start of each request, the commands it answers to and the state it works on are all
box code, and you edit them while the conversation runs. Every request is kept in a tree you can
rewind and branch, and the experiment's own state goes back with it. You can see what the agent was
sent, and measure what a change did, in the same document.

## A tool written in the middle of a conversation

The Studio box gives the chat a set of paint tools for a simulated oil canvas. After the painter had
laid in a simple landscape, a second box was added beside it, Scribbler, which defines one more tool
with `aiTool { }`: a scribble that fills a region with looping, wandering lines of paint. Running
the box registered the tool. The next request asked the painter to use it, and it did, in the same
panel and the same conversation. Here is the result, with the Scribbler's code open in the editor:

![the Field window. Top left, the Studio and Scribbler boxes. Below them the Studio's output: a landscape whose pale sky, blue hills and green field are made of small looping scribbles, with a dark green tree on the right; and under it the AI Chat, ending with a list of scribble calls and an inline picture of the canvas as the painter last looked at it. On the right, the code editor open on the Scribbler box, showing the aiTool("scribble") block with its describe text, its four parameters and the start of its action](assets/ai4_experiments/scribbler.jpg)

The whole tool is ordinary box code. It finds the Studio's canvas, works out the scribble, strokes
it with a paint brush, and asks the Studio to show the result:

```diffstatic {data-lang=kotlin}
aiTool("scribble") {
    describe("Scribble inside a region: rows of looping, wandering line that fill the outline the way a quick hand " +
        "does. Dense scribbles read as tone, loose ones as texture; layer colours for depth.")
    effects(ToolEffect.CONTROL_RUNTIME)
    val outline by param<List<Double>>("the region, [x0, y0, x1, y1, ...] in canvas units")
    val color by param<String>("#rrggbb, or a tube name")
    val density by param<Double>("rows of loops per 100 units: 1 loose .. 8 dense", default = 3.0)
    val width by param<Double>("brush width", default = 5.0)
    action {
        val canvas = studio.properties["studio.canvas"] as PaintCanvas
        // ... the scribble, row by row, inside the outline ...
        (studio.properties["studio.refresh"] as () -> Unit)()
        "scribbled ${color()} over the region at density ${density()}"
    }
}
```

`describe` and the parameter descriptions are what the model reads, so they are where you say what
the tool is for. The painter read them and chose its own colours, densities and widths: fine, pale,
loose scribbles for the sky, wide and dense ones for the tree and the field, which close up into
almost solid masses. It painted twice with the tool, once over the landscape it had laid in and once,
asked for a painting made almost entirely of scribble, on a fresh canvas:

![two paintings side by side on black. Left: the first landscape with scribbles laid over it, looping blue lines in the sky and hills, dense green loops across the field and a scribbled edge to the tree. Right: the same landscape painted on a fresh canvas almost entirely in scribbles, the sky a dense white mesh of loops over the warm ground, the hills in blue loops, the field in green, and the tree a nearly solid mass of wide, dense scribble](assets/ai4_experiments/scribbled.jpg)

Re-running the box replaces the tool in place, so you can change how the scribble
looks, or what it takes, between one request and the next. A tool lives as long as the box that
registered it. [Give the AI a tool of your own](aiFeatures.md#give-the-ai-a-tool-of-your-own) has
the whole DSL, and [More than a tool](aiFeatures.md#more-than-a-tool-agentextension) adds context,
chat commands, checks before and after each call, and state that follows the conversation.

## Many calls at once

The painter did not spend a request on each of the 43 scribbles in the second painting. It wrote
four short JavaScript programs over the Studio's tools, one per stage, and Field ran them: each
program called `scribble` as often as it needed and came back with a single result. In the chat each
program is one line, and opening the line shows every call it made and how long each took. The
pictures the painter took of the canvas between stages appear in the chat too, as in the window
above:

![the AI Chat panel with one program's trace open: the header "RunToolProgramRequest finished (4355 ms)", eleven lines each reading "scribble" with its outline coordinates, colour and density and a time between 203 and 837 ms, then "program ok: 11 call(s), 0 failed, 4355 ms", and below it a line of the turn's last calls: paint_stroke, paint_dab, paint_look and paint_hang, each finished](assets/ai4_experiments/chat.jpg)

A program sees only the conversation's tools, with no files, network or Java. A call that fails
stops the program, so a failed check cannot be followed by the edit it was guarding.
`tools.X.raw(...)` returns a failure without throwing, and `check(...)` stops on a result that ran
but is not acceptable. `progress(...)` shows a line in the panel while the program runs. A result too
large to keep in the conversation is stored whole, and the call's record says where.
[Many calls at once](aiFeatures.md#many-calls-at-once) has the details.

## Branching an experiment

Hover over one of your requests in the chat to rewind to just before it, or to edit it and send it
again. The later requests are kept as a branch, and `/branches` and `/branch <n>` switch between
them. What the box keeps for the conversation goes back too: the Studio's canvas is restored to how
it was before the request you rewound to, and switching branch brings back that branch's canvas.
[AI4 Paints](ai4_painting.md) shows one conversation that branched into a night and a stormy morning
from the same dusk painting.

`branchState` is how a box hands its state to the conversation. Before restoring, Field saves the
state as it is now and compares it with the state the conversation last left. If something else
changed it, a person dragging or a tool run from outside the chat, the state is left as it is and the
chat says so, unless the box asks for the conversation's version to win. After a rewind, Field also
offers to undo the document edits the abandoned requests made, and leaves alone any box someone has
edited, moved or connected since.

## Seeing what the agent was told

`/context` in a box's chat lists what the last request sent besides the conversation: each part of
the instructions, its size, whether it changes from turn to turn, and what the provider keeps of it.
The last differs by provider. OpenAI keeps the conversation on its side, so context that changes
between turns is added with every turn. `/help` lists the commands the document's extensions add,
such as the Studio's `/canvas`, `/palette` and `/gallery`.

Each turn's record in the conversation tree also notes the model, the instructions, the tools that
were offered and the source revision of every box that contributed a tool, so a result can be traced
back to what produced it.

## Measuring a change

The same AI chat can be driven without the panel, from a script, to compare two versions of an
experiment. `agent-task` runs one request in a document and checks the result. `agent-eval` runs a
suite of tasks, each in its own scratch document. Both report rounds, tokens (cached and not), time
and every tool call, including the calls inside programs. Both come from `tools/field_backdoor.py`
in the Field repository:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch \
  --command "agent-task --goal 'Make a box named Squares that prints the squares of 1 to 8, and run it.' \
     --check box-exists:Squares --check output-ints-include:Squares:1,4,9,16,25,36,49,64"
```

The verdict comes from the checks, whatever the agent reports. Checks can test boxes, outputs, pictures
of outputs, and which tools and effects a turn used. `--mode scripted` replays a list of tool calls
without a model, which is how a tool is tested. `--chat-box <box>` runs the request as that box's
real panel turn. Two switches compare versions in the same build: `--programs off` withholds tool
programs, and `--context legacy` sends the instructions once, in the conversation's stored message, instead of
assembling them each turn.

Measured this way, on OpenAI `gpt-6-astra`, one or two runs per arm:

| tasks | change | rounds | input tokens |
| --- | --- | --- | --- |
| three tasks of many similar calls | tool programs off → on | 29 → 10 | 260k → 119k |
| two conversations of three and four requests | instructions in the stored message → assembled per turn | 17 → 15 | 308k → 192k |

These are small samples on one model, on tasks chosen to need those features. Repeat them on your own
work before relying on them.

## Where it stops

- A program is not a transaction. Edits made before a failure stay. They are recorded and undoable
  like any other agent edit, but nothing rolls them back.
- State a box hands to the conversation is saved after every request that changes it, and nothing
  deletes old copies yet. A canvas the size of the Studio's takes about 28 MB each.
- A check that must hold, such as a design revision, belongs in the tool that does the work. A
  `beforeTool` guard is a second line, and it sees only calls that go through the chat's tools.
- The chat is not a place for long jobs. A tool call waits at most as long as the program it is in
  has left, so a tool for a minutes-long job should start it and return a handle. For a
  [derived build](studies.md#build-derived-results-without-blocking-edits), the agent can then end
  its turn and let Field wait: see [Let AI4 Wait for Long Builds](ai4_long_builds.md).

See also: [AI4 Paints](ai4_painting.md), [AI Overview](aiFeatures.md),
[Automating Field](automating_field.md).

??? aside "How these figures were made"
    From live panel turns on 2026-10-04, OpenAI `gpt-6.1-sol`, in one conversation: a quick landscape
    with the paint tools, then the Scribbler box created and run, then two requests to paint with the
    new tool, the second on a fresh canvas. The requests were sent as real panel turns from
    `tools/field_backdoor.py` (`agent-task --chat-box Studio`). The screenshots are from that
    document reopened in the backdoor's browser: the editor opened on the Scribbler's code, the Studio's
    painting and chat panels sized through the output panels' own `setSize`, a program's trace opened,
    the canvas panned with `setCanvasZoom` so the boxes sit beside the editor, and the page captured
    with a transcript `drive` step (`ui.shot`), then cropped. The measurements are the end-state runs
    recorded in `dev_docs/ai/AI4_PI_LESSONS.md`.
