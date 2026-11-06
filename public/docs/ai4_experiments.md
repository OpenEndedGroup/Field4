# Experimenting with AI4

What happens if you give an agent a new tool halfway through a conversation? In Field you can
write that tool in a box, run it, and ask the agent to use it straight away. You can also change
the instructions and state it works with, rewind a conversation, and try another branch.

We'll do that with an agent that paints, then look at ways to inspect and measure the experiment.

*The document on this page is the [ai4_paint_studio](agent_transcripts.md#ai4_paint_studio)
starting point: the Studio box from [AI4 Paints](ai4_painting.md) and the Scribbler box shown
below. It needs the paint library (`./gradlew buildPaintNative`) and an AI provider.*

## A tool written in the middle of a conversation

The Studio box gives the chat a set of paint tools for a simulated oil canvas. After the painter had
laid in a simple landscape, a second box was added beside it, Scribbler, which defines one more tool
with `aiTool { }`: a scribble that fills a region with looping, wandering lines of paint. Running
the box registered the tool. The next request asked the painter to use it, and it did, in the same
panel and the same conversation. Here is the result, with the Scribbler's code open in the editor:

![the Field window. Top left, the Studio and Scribbler boxes. Below them the Studio's output: a landscape whose pale sky, blue hills and green field are made of small looping scribbles, with a dark green tree on the right; and under it the AI Chat, ending with a list of scribble calls and an inline picture of the canvas as the painter last looked at it. On the right, the code editor open on the Scribbler box, showing the aiTool("scribble") block with its describe text, its four parameters and the start of its action](assets/ai4_experiments/scribbler.jpg)

The whole tool is ordinary box code. It finds the Studio's canvas, works out the scribble, strokes
it with a paint brush, and asks the Studio to show the result. Studio publishes typed
`studioCanvas` and `refreshStudio` callbacks; the canvas callback reads the current easel even
after a conversation rewind replaces it:

```diffstatic {data-lang=kotlin}
prop<() -> PaintCanvas>(studioCanvas)
prop<() -> Unit>(refreshStudio)

aiTool("scribble") {
    describe(
        "Scribble inside a region: rows of looping, wandering line that fill the outline the way a quick hand " +
            "does. Dense scribbles read as tone, loose ones as texture; layer colours for depth.",
    )
    effects(ToolEffect.CONTROL_RUNTIME)
    val outline by param<List<Double>>("the region, [x0, y0, x1, y1, ...] in canvas units")
    val color by param<String>("#rrggbb, or a tube name")
    val density by param<Double>("rows of loops per 100 units: 1 loose .. 8 dense", default = 3.0)
    val width by param<Double>("brush width", default = 5.0)
    action {
        val canvas = studio.studioCanvas()
        // ... the scribble, row by row, inside the outline ...
        studio.refreshStudio()
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

For the second painting, the painter wrote four short JavaScript programs using the Studio's
tools. Each called `scribble` as often as it needed and returned one result. Open a program's line
in the chat to see its individual calls and their timings; the pictures taken between stages appear
there too:

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

### Testing a tool without a model

A tool written in a box can be tested before any model sees it. This box registers `room_area`, a
tool that refuses a room with a negative side:

```diffstatic {data-lang=kotlin}
aiTool("room_area") {
    describe("Floor area of a rectangular room, in square metres.")
    val width by param<Double>("width in metres")
    val depth by param<Double>("depth in metres")
    action {
        require(width() > 0 && depth() > 0) { "width and depth must be positive" }
        "area %.2f m²".format(width() * depth())
    }
}
println("room_area registered")
```

A script for `--mode scripted` is the list of calls a model would have made, followed by its final
answer. This one, saved as `script.json`, makes one good call and one bad one:

```diffstatic {data-lang=json}
[
  {"tool": "room_area", "arguments": {"width": 4.2, "depth": 3.1}},
  {"tool": "room_area", "arguments": {"width": -1, "depth": 3.1}},
  {"final": "The room is 13.02 m²."}
]
```

One backdoor invocation makes the box, runs it, and replays the script through the same tool
dispatch the chat uses. A scripted task still needs a `--goal`, which is recorded with the result:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch \
  --command 'new-box --name Rooms' --command 'set @last code --file area.kt' --command 'run Rooms' \
  --command "agent-task --goal 'How big is a 4.2 by 3.1 m room?' --mode scripted --script script.json \
     --check tool-used:room_area --check tool-ok:room_area"
```

The result of that run, shortened:

```diffstatic {data-lang=json}
"verdict": "pass",
"checks": [
  {"check": {"kind": "tool-used", "text": "room_area"}, "passed": true, "detail": "call 1 was room_area"},
  {"check": {"kind": "tool-ok", "text": "room_area"}, "passed": true, "detail": "call 1 succeeded; 1 of 2 ok"}
],
"toolCalls": [
  {"index": 1, "tool": "room_area", "arguments": "{\"width\":4.2,\"depth\":3.1}", "ok": true, "result": "\"area 13.02 m²\""},
  {"index": 2, "tool": "room_area", "arguments": "{\"width\":-1,\"depth\":3.1}", "ok": false,
   "result": "{\"error\":true,\"tool\":\"room_area\",\"message\":\"width and depth must be positive\"}"}
],
"rounds": 2, "elapsedMs": 90, "inputTokens": 0, "misuseCalls": 1,
"toolTally": {"room_area": {"calls": 2, "failed": 1}}
```

`toolCalls` lists each call with its own `ok`. The exception thrown by `require` reached the model
as an error result; the turn carried on. `toolTally` counts calls and failures per tool, and
`misuseCalls` counts the failed calls across all tools.

The two checks differ when every call fails. Leave out `run Rooms` and the tool is never registered.
Both calls then come back `No tool registered for: room_area`, and the same checks give:

```diffstatic {data-lang=json}
"verdict": "fail",
"checks": [
  {"check": {"kind": "tool-used", "text": "room_area"}, "passed": true, "detail": "call 1 was room_area"},
  {"check": {"kind": "tool-ok", "text": "room_area"}, "passed": false,
   "detail": "all 2 call(s) to room_area failed, first: {\"error\":true,\"tool\":\"room_area\",\"message\":\"No tool registered for: room_area\"}"}
],
"toolTally": {"room_area": {"calls": 2, "failed": 2}}
```

`tool-used` passes because the agent asked for the tool. Use `tool-ok` when you mean the tool did its
job. The command exits 1 whenever the verdict is not `pass`.

To send the same request to a model, drop `--mode scripted` and `--script`. A live `agent-task`
uses the `local` provider ([Local AI](local_ai.md)) unless you add `--allow-remote-provider`.

### Running the suite

`agent-eval` runs seventeen built-in tasks, each in its own scratch document: make a box that prints
primes, fix a compile error, wire two boxes, stop a box that runs forever, draw bars in an output and
check the picture, run one box over a sweep of values, and so on. With no options it replays a script
for each task, so it needs no model; it tests the tools and the checks:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --command "agent-eval"
```

That took 50 seconds. The top of the report:

```diffstatic {data-lang=json}
"mode": "scripted", "population": 17, "passed": 17,
"rounds": 57, "imagesSeen": 9, "programCalls": 3, "nestedCalls": 50,
"inputTokens": 0, "outputTokens": 0,
"documentsBefore": 1, "documentsAfter": 1, "capturePagesOpenAfter": 0,
```

`results` holds one `agent-task` result per task, each with its own `toolTally`. A task can pass with
failed calls in its tally. In `fix-runtime-error` the first run of the box throws, the agent changes
the code, and the second run succeeds:
`{"RunBoxRequest": {"calls": 2, "failed": 1}, "SetBoxPropertyRequest": {"calls": 1, "failed": 0}}`.
Equal `documentsBefore` and `documentsAfter`, with no capture pages left open, mean every task's
scratch document was closed.

`--mode live` sends the same tasks to the configured model, `--ids primes,wire-two` picks tasks, and
`--max-rounds` caps each one. In a live run, `rounds`, tokens and `misuseCalls` are what tell two
models, or two versions of your tools, apart.

## Scoring AI4 as a reader of these docs

A measurement does not have to be about boxes. The
[ai4_cold_reader](agent_transcripts.md#ai4_cold_reader) starting point is one box with two tools:
`cold_read_page` returns a page of this site as it stood at a git revision, with numbered lines, and
`report_stall` records one place a newcomer would get stuck: a term used before it is explained, code
shown before the text says what it does, a sentence about how the page was made. Each stall is a
line of JSON with the page, line, category, the quoted words and a note. Run the box, then ask its
chat to cold-read a page, or hand the same request to `agent-task` in the backdoor invocation that ran
the box:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json --document coldreader \
  --command 'transcript run ai4_cold_reader' \
  --command "agent-task --goal-file cold_read_staging.txt --max-rounds 48 --check tool-ok:report_stall"
```

`tool-ok:report_stall` passes only if at least one `report_stall` call succeeded; `tool-used` would
pass on calls that all failed. A live `agent-task` uses only the `local` provider unless you add
`--allow-remote-provider` (or set `AFIELD_AGENT_TASK_ALLOW_REMOTE=1`), because any other provider
receives the page text. The result's `offMachine` field records whether the page text left the machine.

`tools/cold_reader_score.py` compares the stalls with a list someone else made of the same page at
the same revision. A stall counts as found when the quoted words overlap (one quote contains the
other, or they share four words in a row), or when it is within a line and has the same category.
Four pages, read by OpenAI `gpt-6.1-sol` at the revision an earlier review of them used, once each:

| page | stalls reported | stalls in the review | found in both | precision | recall |
| --- | --- | --- | --- | --- | --- |
| `installation.md` | 10 | 6 | 1 | 0.10 | 0.17 |
| `staging.md` | 8 | 12 | 6 | 0.75 | 0.50 |
| `sound.md` | 13 | 10 | 2 | 0.15 | 0.20 |
| `kotlin_overview.md` | 16 | 10 | 5 | 0.31 | 0.50 |
| all four | 47 | 38 | 14 | 0.30 | 0.37 |

The earlier review is not a complete list of what is wrong with a page, so low precision here
mostly means the two readers stopped at different places. Of the 20 stalls on `installation.md` and
`sound.md` the review did not have, 17 read as real on a second look: terms such as JEP,
`personalJar` and onset recall used without a word of what they are. Of the other three, two were weak
complaints about the page's own making, and one was an `??? aside` reported as being about how the
page was made, which is where such text belongs. Each
page took one turn of two to nine rounds and under a minute.

The same model given the same prompt, tools and pages through the plain OpenAI API, without Field,
scored the same: 0.30 and 0.32 precision and 0.37 recall in two runs, and its stalls overlapped
AI4's about as much as its two runs overlapped each other. For reading a page, running inside Field
does not make the model a better reader; it puts the reading in a box you can open and continue.

## Where it stops

- A program is not a transaction. Edits made before a failure stay. They are recorded and undoable
  like any other agent edit, but nothing rolls them back.
- State a box hands to the conversation is saved after every request that changes it: about 28 MB
  each for a canvas the size of the Studio's. States no conversation refers to are deleted on their
  own; to free the rest, `pruneAiStates(dryRun = false)` in the box keeps the latest three along every
  branch and the state at every fork. Without `dryRun = false` it only reports what it would free.
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
