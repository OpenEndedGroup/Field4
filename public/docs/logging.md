# Logging: channels, sinks and the console

Field talks a lot. Boot narrates itself, the renderer reports a frame rate, the Kotlin
compiler reports how long it took, the file watcher says what changed. All of it goes
through one small layer, and once you know the layer you can send output to a new place,
pull one subsystem's chatter out of the noise, or stop a channel from filling your
terminal without losing it anywhere else.

There is one function to know:

```diffstatic {data-lang=kotlin}
lprintln("info,mypage", "something happened")
```

`"info,mypage"` is a **channel**. It is not a level, and it is not a logger name. It is a
comma-separated list of *tags*, and every consumer downstream selects on those tags. There
are **1060** `lprintln` call sites in `src/jvmMain`, spelling **216** distinct channel
strings between them, so the vocabulary is already large and already load-bearing.

By convention the first tag is a severity, drawn from six the console knows how to colour
(`PinnedConsole.kt:1024#val LEVELS = listOf("error", "warning", "warn", "info", "debug", "trace")`),
and the tags after it say where the line came from. Only the colouring depends on that
convention; nothing enforces it, nothing parses a hierarchy, and `"error,disk,retry"` is
three flat tags rather than a path.

## The dispatcher and its sinks

`lprintln` builds a `LogEvent` and hands it to `LogDispatcher`, which forwards it to every
registered `LogSink`
(`logger.kt:18#fun lprintln(channel: String, message: Any?) {`).
The sink list starts with just the terminal
(`Logging.kt:38#private val sinks = CopyOnWriteArrayList<LogSink>().apply { add(StdoutLog) }`)
and is open at runtime: `addSink`, `removeSink`, `setSinks`. A sink is a `fun interface`
with one method, so a lambda is a sink.

Eight of them exist in the tree — `StdoutLog`, `PinnedConsoleSink`, `LogStream`,
`NotifyLog` (which also raises a desktop notification), `SilentLog`, two CLI sinks and
`TputLog`. Adding a ninth from a box takes one line:

<!--
FIELD_TEST
id: docs.logging.sinks
runner: backdoor-box
tags: [docs, logging]
timeoutSec: 90
waitSec: 1.5
expect:
  outputContains:
    - "info,mypage"
    - "error,mypage,disk"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/sinks.png" wait="1.5" wait-output="true" capture="box" embed="true" open-outputs="true" w="1200" name="logging-sinks"}
:::static
import afield.ai.core.LogDispatcher
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import afield.logger.lprintln

// A sink is a fun interface, so a lambda will do. This one keeps only my own lines:
// the dispatcher is global and a live Field is emitting frame-rate lines the whole time.
val seen = mutableListOf<LogEvent>()
val probe = LogSink { e -> if ("mypage" in e.channel) seen += e }

LogDispatcher.addSink(probe)
try {
    lprintln("info,mypage", "a plain line")
    lprintln("warning,mypage", "something to look at")
    lprintln("error,mypage,disk", "three tags, and no hierarchy among them")
} finally {
    LogDispatcher.removeSink(probe) // always. The sink list outlives your box.
}

seen.forEach { e ->
    println("channel=${e.channel.padEnd(20)} tags=${e.channel.split(",")} -> ${e.message}")
}
```

That `finally` is not decoration. The dispatcher is a global, a leaked sink keeps receiving
events for the life of the JVM, and a leaked sink that throws is merely swallowed rather
than noticed — `LogDispatcher.log` wraps each sink in `runCatching`, so a broken sink fails
silently forever.

## The one thing to get right: three matchers, one word

Everything downstream selects on tags, and here is the part that will catch you. **Three
mechanisms select on the channel, and all three match differently.**

| Mechanism | What it compares | Rule | Case |
|---|---|---|---|
| `ConsoleMute.isMuted` | the **whole** channel string | exact set membership | sensitive |
| the pinned console's `matchedPin` | each `,`-split **tag** | exact membership | sensitive |
| `LogStream.Filter.channels` | the whole channel string | **substring** | **insensitive** |

Each is one line of source, and the three lines are the whole story:

- `Logging.kt:86#fun isMuted(channel: String): Boolean = channel in channels`
- `PinnedConsole.kt:961#val tokens = channel.split(',').mapTo(HashSet(), String::trim)`
- `logstream.kt:142#if (!wanted.any { token -> e.channel.contains(token, ignoreCase = true) }) return false`

So muting wants the exact string you wrote at the call site, pinning wants one of its tags,
and the log widget will take any substring of it. Start with muting, which is the strictest
and the most surprising:

<!--
FIELD_TEST
id: docs.logging.mute_is_exact
runner: backdoor-box
tags: [docs, logging]
timeoutSec: 90
waitSec: 1.5
expect:
  outputContains:
    - "isMuted"
    - "info,boxes"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/mute-is-exact.png" wait="1.5" wait-output="true" capture="box" embed="true" open-outputs="true" w="1200" name="logging-mute-is-exact"}
:::static
import afield.ai.core.ConsoleMute

println("the console mute set this JVM booted with:")
ConsoleMute.channels.sorted().forEach { println("    '$it'") }
println()

// isMuted compares the WHOLE channel string against that set. Nothing is split.
listOf("info,boxes", "boxes,info", "info,boxes,extra", "info,Boxes", "boxes")
    .forEach { println("isMuted(${"'$it'".padEnd(20)}) = ${ConsoleMute.isMuted(it)}") }
```

Only the first spelling is muted. Reordering the tags, adding one, or changing a letter's
case all produce a channel the mute set has never heard of. That is a deliberate choice —
a mute list of exact strings can never accidentally silence a channel you meant to keep —
but it means the default mute set silences `"info,boxes"` and would not silence
`"info,boxes,slow"`, and it means you cannot mute *a tag*. To change the set, pass
`-Dafield.log.mute=` or `AFIELD_LOG_MUTE=` a semicolon-separated list of exact channel
strings (`Logging.kt:80#raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()`),
or a blank value to mute nothing.

The log widget's filter, at the other extreme, matches any substring. This is not a
hypothetical difference: **101 pairs of a real tag and a real channel string in the tree
today** are matched by a `LogStream` filter and would not be pinned by the same word.
`kotlin` is the clearest, because both spellings are heavily used — `info,kotlin` is the
Kotlin compiler and `info,kotlinjs` is the Kotlin/JS build:

<!--
FIELD_TEST
id: docs.logging.filter_is_substring
runner: backdoor-box
tags: [docs, logging]
timeoutSec: 90
waitSec: 2.0
expect:
  outputContains:
    - "info,kotlin"
    - "info,kotlinjs"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/filter-is-substring.png" wait="2.0" wait-output="true" capture="box" embed="true" open-outputs="true" w="1200" name="logging-filter-is-substring"}
:::static
import afield.logger.lprintln
import afield.server.LogStream

// Subscribe with the real filter the log widget uses. sinceEpochMs keeps the existing
// 20,000-record archive out of the replay, so what arrives is only what I emit below.
val pushed = mutableListOf<String>()
LogStream.addSubscription(
    "logdoc-substring",
    LogStream.Filter(
        channels = listOf("kotlin"),
        sinceEpochMs = System.currentTimeMillis(),
        limit = 50,
        tail = true
    )
) { json -> pushed += json }

try {
    lprintln("info,kotlin", "«logdoc» the Kotlin compiler: 'kotlin' is a tag here")
    lprintln("info,kotlinjs", "«logdoc» the Kotlin/JS build: 'kotlin' is only a SUBSTRING here")
    lprintln("info,graphics", "«logdoc» unrelated, and correctly not matched")
} finally {
    LogStream.unsubscribe("logdoc-substring")
}

val mine = pushed.filter { "«logdoc»" in it }
println("filter channels=[\"kotlin\"] matched ${mine.size} of the 3 lines I emitted:")
mine.forEach { println("    " + it.substringAfter("\"channel\":\"").substringBefore("\"")) }
println()
println("the pinned console splits on ',' instead, so pinning \"kotlin\" would take only the first.")
```

Two of three. The third is correctly rejected; the second is matched because `kotlin` is a
prefix of `kotlinjs`, and no amount of tag discipline at the call site changes that. If you
want one subsystem in the widget and its name is a prefix of another's, filter on the full
channel string (`"info,kotlin"`) rather than the bare tag.

## Muting is a console policy, not a filter

The second thing to get right. Muting a channel does **not** drop the event. Both console
sinks check the mute set and return early —
`Logging.kt:95#if (ConsoleMute.isMuted(event.channel)) return` for plain stdout and
`PinnedConsole.kt:316#if (ConsoleMute.isMuted(event.channel)) return` for the pinned
console — and no other sink checks it at all. The event still reaches the web archive, and
still reaches yours:

<!--
FIELD_TEST
id: docs.logging.mute_is_console_only
runner: backdoor-box
tags: [docs, logging]
timeoutSec: 90
waitSec: 1.5
expect:
  outputContains:
    - "isMuted"
    - "my sink received"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/mute-is-console-only.png" wait="1.5" wait-output="true" capture="box" embed="true" open-outputs="true" w="1200" name="logging-mute-is-console-only"}
:::static
import afield.ai.core.ConsoleMute
import afield.ai.core.LogDispatcher
import afield.ai.core.LogSink
import afield.logger.lprintln

val seen = mutableListOf<String>()
val probe = LogSink { e -> if ("«logdoc»" in e.message) seen += "${e.channel} -> ${e.message}" }

LogDispatcher.addSink(probe)
try {
    lprintln("info,boxes", "«logdoc» on a channel muted by default")
    lprintln("info,mypage", "«logdoc» on a channel nobody mutes")
} finally {
    LogDispatcher.removeSink(probe)
}

println("ConsoleMute.isMuted(\"info,boxes\") = ${ConsoleMute.isMuted("info,boxes")}")
println("my sink received ${seen.size} of the 2 lines:")
seen.forEach { println("    $it") }
```

Muted, and delivered. Which is the point: mute is for a channel that is too chatty *for a
terminal*, not for one you want gone. Four channels are muted out of the box —
`info,boxes`, `info,persist`, `info,ai.boxnames`, `debug,boxes` — and every one of them is
still in the log widget and still in the archive when you go looking for it.

## Blocks, and what the indent actually holds

`lblock` brackets a scope with a BEGIN and an END event sharing a `blockId`, so a consumer
can fold the lines between them:

<!--
FIELD_TEST
id: docs.logging.blocks
runner: backdoor-box
tags: [docs, logging]
timeoutSec: 90
waitSec: 1.5
expect:
  outputContains:
    - "BEGIN"
    - "END"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/blocks.png" wait="1.5" wait-output="true" capture="box" embed="true" open-outputs="true" w="1200" name="logging-blocks"}
:::static
import afield.ai.core.LogDispatcher
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import afield.logger.lblock
import afield.logger.lprintln

val seen = mutableListOf<LogEvent>()
val probe = LogSink { e -> if ("«logdoc»" in e.message) seen += e }

LogDispatcher.addSink(probe)
try {
    lblock("info,logdoc", "«logdoc» outer") {
        lprintln("info,logdoc", "«logdoc» inside outer")
        lblock("info,logdoc", "«logdoc» inner") {
            lprintln("info,logdoc", "«logdoc» inside inner")
        }
    }
} finally {
    LogDispatcher.removeSink(probe)
}

seen.forEach { e ->
    val depth = e.indent.split("\t").count { it.isNotEmpty() }
    println("depth=$depth ${(e.boundary?.name ?: "-").padEnd(6)} block=${e.blockId?.take(8) ?: "-"}  ${e.message}")
}
println()
println("raw indent of the deepest line: '" + seen.last { it.boundary == null }.indent.replace("\t", "\\t") + "'")
```

Note the last line. The nesting is carried in a thread-local `indent` string, and what it
holds is not spaces — it is one block UUID and a tab per level
(`logger.kt:69#val added = "${u}\t"`). `PinnedConsole` parses those tokens back out to find
which open block a line belongs to, which is what they are for. `StdoutLog` does not: it
concatenates the indent into the line as-is
(`Logging.kt:106#val full = prefix + channelPart + event.indent + boundaryMarker + event.message`),
so on a plain terminal a line logged inside an `lblock` is prefixed by a raw 36-character
UUID. There are only 12 `lblock` call sites, which is presumably why this has not annoyed
anyone into fixing it.

Being thread-local, `indent` also means a block does not nest across threads: work handed
to another thread inside an `lblock` logs at depth zero.

## Stat lines and the heatmap

A channel that reports a number every frame gets special treatment, and the contract is
just a string format: `key=value` or `key: value`, optionally with a unit
(`StatsHeatmap.kt:19#private val TOKEN = Regex(`).
`StatsHeatmap` colours each numeric token by whether it rose or fell since the same channel
last reported that key — blue for a sharp fall through grey for unchanged to red for a
sharp rise, saturating at ±50%. Use `formatMillis` for durations and the unit comes out
matching the grammar, so a slow compile reads `1.63s` rather than `1629ms`:

<!--
FIELD_TEST
id: docs.logging.stats
runner: backdoor-box
tags: [docs, logging]
timeoutSec: 90
waitSec: 1.5
expect:
  outputContains:
    - "ms"
    - "fps"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/stats.png" wait="1.5" wait-output="true" capture="box" embed="true" open-outputs="true" w="1200" name="logging-stats"}
:::static
import afield.logger.StatsHeatmap
import afield.logger.formatMillis

println("formatMillis keeps digits where they matter and rolls over past a second:")
listOf(120_000L, 3_400_000L, 47_000_000L, 250_000_000L, 1_630_000_000L, 12_400_000_000L)
    .forEach { ns -> println("    ${ns.toString().padStart(14)} ns -> ${formatMillis(ns)}") }

println()
println("and this is how a stats line is torn into colourable tokens:")
StatsHeatmap.fragments("logdoc-demo", "fps=61.2 upload=3.4MB net:102% dropped=0").forEach { f ->
    println("    ${if (f.style == null) "plain " else "number"}  '${f.text}'")
}
```

Each numeric token is styled and everything between them is left alone. History is kept per
channel *and* key, so two unrelated metrics on one channel do not colour each other.

## The pinned console

When Field runs in a terminal, the channels that report every frame do not scroll. They sit
in a small fixed region at the top while everything else scrolls beneath, which is what
makes a frame-rate line useful rather than an assault.

The mechanism is one ANSI scroll region (DECSTBM) rather than a render loop: the top rows
are fenced off, so an ordinary `println` can only scroll in the region below them and
"just works" with no cooperation. The header is repainted only when a pinned value actually
changes. Six channels are pinned by default
(`PinnedConsole.kt:69#private val DEFAULT_PINS = listOf("fps", "upload", "perf", "net", "compile", "evaluate")`),
in that order, and the order matters: `matchedPin` takes the *first* pinned tag present in
a channel, so `info,perf,fps` lands on the `fps` row and only the leftover `perf` lines
fall through to the `perf` row. Add your own with `AFIELD_PIN_CHANNELS`, or at runtime with
`PinnedConsole.pin("mytag")`.

It degrades rather than breaking. If stdout is not an interactive TTY, or ANSI control is
unavailable, or Mordant reports a legacy Windows console, pinning switches off and every
event scrolls, with would-be-pinned lines tagged `pin` so they stay visible.
`AFIELD_PIN_DISABLE=1` forces that path.

!!! note "Not captured here"
    The five figures above are real captures of real runs. There is no figure of the pinned
    header, because a docs capture runs a box inside a headless Field and the pinned console
    deliberately switches itself off when stdout is not an interactive TTY — the fallback is
    exactly the thing there would be nothing to photograph of. `matchedPin` and
    `PinnedConsoleSink` are also both private, so the row-routing rule is stated above from
    its source line rather than executed. Run Field in a terminal to see it.

## Stderr is not part of any of this

`System.err` never passes through the dispatcher. Ktor logs through SLF4J, slf4j-simple
writes to stderr, and so an unhandled-request stack trace reached none of the machinery on
this page — and then three separate things were true at once, each fatal on its own: it was
not in the console's scrollback ring, so the pager could not find it; the pager repaints
that viewport from the ring and clears each row as it goes, so the trace was overwritten
milliseconds later; and lines that scroll out of a DECSTBM region are discarded rather than
kept in the terminal's own scrollback. An exception was visible for an instant and then
gone, with nothing left to copy.

`SessionErrorLog` is the answer, and it privileges stderr rather than merely tolerating it.
It tees the stream (`SessionErrorLog.kt:113#System.setErr(PrintStream(TeeStderr(System.err), true, StandardCharsets.UTF_8))`)
so every byte still reaches the real stderr and nothing watching this process sees a
change; it appends every line to `build/logs/session-<stamp>-pid<pid>.log`, which is the
durable greppable record, flushed per line so the last thing stderr said survives a crash;
and it recognises a stack trace or an slf4j `ERROR` line and raises it as a sticky pinned
panel, the one region of the screen a repaint will not reclaim. Twenty session files are
kept and one is capped at 32 MB.

So when something has gone wrong and the terminal is no help, the file is where to look.

## Where the lines end up

| Sink | Where | Honours the mute set |
|---|---|---|
| `StdoutLog` | the terminal, with the channel dimmed and italic | yes |
| `PinnedConsoleSink` | the terminal, pinned rows plus scrollback | yes |
| `LogStream` | a 20,000-record ring (`logstream.kt:27#private const val MAX_RECORDS = 20_000`), served to the log widget over the `__logs__` websocket | no |
| `NotifyLog` | stdout, plus a desktop notification, deduped within a second | via `StdoutLog` |
| `SilentLog` | nowhere, on purpose | n/a |

Two things that look like this layer and are not: `SaveReloadLog` writes save/reload timings
straight to `~/.afield/logs/save_reload.log` without touching the dispatcher, and
`TraceLogger` / `AiToolIoLogger` write AI request traces under `~/.afield/logs/` when their
env vars are set.

## Reference

The generic core is `afield.ai.core.Logging` — `LogEvent`, `LogSink`, `LogDispatcher`,
`ConsoleMute`, `StdoutLog`. The entry points and the terminal live in `afield.logger` —
`lprintln`, `lprint`, `lblock`, `indent`, `formatMillis`, `PinnedConsole`, `StatsHeatmap`,
`SessionErrorLog`, `SaveReloadLog`. The web sink is `afield.server.LogStream`, which serves
the log widget over the `__logs__` websocket handler.
