# Logging: channels, sinks and the console

Field talks a lot. Boot narrates itself, the renderer reports a frame rate, the Kotlin
compiler reports how long it took, the file watcher says what changed. All of it goes
through one small layer, and once you know the layer you can send that output somewhere
new, pull one subsystem's chatter out of the noise, or stop a channel from filling your
terminal without losing it anywhere else.

There is one function to know:

```diffstatic {data-lang=kotlin}
lprintln("info,mypage", "something happened")
```

`"info,mypage"` is a **channel**. It is not a level, and it is not a logger name. It is a
comma-separated list of *tags*, and every consumer downstream selects on those tags. There
are **921**<!--count:lprintlnChannelCallSites--> places in `src/jvmMain` that call
`lprintln` with the channel written out as a literal, spelling
**221**<!--count:logChannelStrings--> distinct channel strings between them, so the
vocabulary is already large and already load-bearing.

By convention the first tag is a severity, drawn from six the console knows how to colour
(`PinnedConsole.kt:1024#val LEVELS = listOf("error", "warning", "warn", "info", "debug", "trace")`),
and the tags after it say where the line came from. Only the colouring depends on that
convention; nothing enforces it, nothing parses a hierarchy, and `"error,disk,retry"` is
three flat tags rather than a path.

## The dispatcher and its sinks

`lprintln` builds a `LogEvent` and hands it to `LogDispatcher`, which forwards it to every
registered `LogSink` (`logger.kt:18#fun lprintln(channel: String, message: Any?) {`). The
sink list starts with just the terminal
(`Logging.kt:38#private val sinks = CopyOnWriteArrayList<LogSink>().apply { add(StdoutLog) }`)
and is open at runtime: `addSink`, `removeSink`, `setSinks`, and `withSink`, which brackets an
add/remove pair so an early return cannot leak one. A sink is a `fun interface`
with one method, so a lambda is a sink.

Eight<!--count:logSinkImplementations--> of them exist in the tree — `StdoutLog`,
`PinnedConsoleSink`, `LogStream`, `NotifyLog` (which also raises a desktop notification),
`SilentLog`, two CLI sinks and `TputLog`. Adding a ninth from a box takes one line:

<!--
FIELD_TEST
id: docs.logging.sinks
runner: backdoor-box
tags: [docs, logging, tohtml]
timeoutSec: 120
waitSec: 1.5
expect:
  outputContains:
    - "info,mypage"
    - "error,mypage,disk"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/sinks.png" capture="box" w="860" h="220" wait="1.5" timeout="120" embed=true name="logging-sinks" alt="A Field box output panel labelled main, holding a three-column table titled 'what one sink saw', headed channel, tags, message. Three rows: info,mypage / 2 / a plain line; warning,mypage / 2 / something to look at; error,mypage,disk / 3 / three tags, and no hierarchy among them."}
:::static
import afield.ai.core.LogDispatcher
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import afield.ai.core.withSink
import afield.logger.lprintln

// A sink is a fun interface, so a lambda will do. This one keeps only my own lines: the
// dispatcher is global, and a live Field is logging its own frame rate the whole time.
val seen = mutableListOf<LogEvent>()
val probe = LogSink { e -> if ("mypage" in e.channel) seen += e }

// withSink brackets the pair for you. Doing it by hand is a leak waiting for the first
// early return: the sink list outlives your box, and LogDispatcher.log wraps every sink
// in runCatching, so a leaked sink that throws fails silently and forever.
LogDispatcher.withSink(probe) {
    lprintln("info,mypage", "a plain line")
    lprintln("warning,mypage", "something to look at")
    lprintln("error,mypage,disk", "three tags, and no hierarchy among them")
}

display("main", Table(listOf("channel", "tags", "message"), title = "what one sink saw")
    .rows(seen.map { listOf(it.channel, it.channel.split(",").size, it.message) })
    .role("channel", CellRole.ACCENT)
    .expect("tags", because = "a channel is a flat tag list, so every line has at least one") { (it as Int) >= 1 })
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
tags: [docs, logging, tohtml]
timeoutSec: 120
waitSec: 1.5
expect:
  outputContains:
    - "info,boxes"
    - "muted"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/mute-is-exact.png" capture="box" w="860" h="260" wait="1.5" timeout="120" embed=true name="logging-mute-is-exact" alt="A Field box output panel labelled main, holding a two-column table titled 'ConsoleMute compares the whole channel string', headed channel string and isMuted. Five rows: info,boxes is true; boxes,info is false; info,boxes,extra is false; info,Boxes is false; boxes is false. A footnote lists the channels the mute set holds."}
:::static
import afield.ai.core.ConsoleMute

// isMuted compares the WHOLE channel string against the mute set. Nothing is split.
val spellings = listOf("info,boxes", "boxes,info", "info,boxes,extra", "info,Boxes", "boxes")

display("main", Table(
    listOf("channel string", "isMuted"),
    title = "ConsoleMute compares the whole channel string",
    note = "the set this JVM booted with: " + ConsoleMute.channels.sorted().joinToString(", ")
)
    .rows(spellings.map { listOf(it, ConsoleMute.isMuted(it)) })
    .role("channel string", CellRole.ACCENT)
    .roleBy("isMuted") { if (it == true) CellRole.WARN else CellRole.OK }
    .expect("isMuted", because = "only the exact spelling in the set is muted") { it is Boolean })
```

Only the first spelling is muted. Reordering the tags, adding one, or changing a letter's
case all produce a channel the mute set has never heard of. That is a deliberate choice —
a mute list of exact strings can never accidentally silence a channel you meant to keep —
but it means the default set silences `"info,boxes"` and would not silence
`"info,boxes,slow"`, and it means you cannot mute *a tag*. To change the set, pass
`-Dafield.log.mute=` or `AFIELD_LOG_MUTE=` a semicolon-separated list of exact channel
strings (`Logging.kt:80#raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()`),
or a blank value to mute nothing.

The log widget's filter, at the other extreme, matches any substring. This is not a
hypothetical difference: **108**<!--count:logChannelSubstringCollisions--> pairs of a real
tag and a real channel string in the tree today are matched by a `LogStream` filter and
would not be pinned by the same word. `kotlin` is the clearest of them: it is one of the
most-used tags in the tree, and `kotlinjs` — the Kotlin/JS build — is a *separate* tag that
a filter for `kotlin` sweeps up anyway, because the one name is a prefix of the other:

<!--
FIELD_TEST
id: docs.logging.filter_is_substring
runner: backdoor-box
tags: [docs, logging, tohtml]
timeoutSec: 120
waitSec: 2.0
expect:
  outputContains:
    - "info,kotlin"
    - "info,kotlinjs"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/filter-is-substring.png" capture="box" w="900" h="240" wait="2.0" timeout="120" embed=true name="logging-filter-is-substring" alt="A Field box output panel labelled main, holding a two-column table titled 'a LogStream filter of [kotlin] against three channels', headed channel emitted and pushed to the subscriber. Three rows: info,kotlin is true; info,kotlinjs is true; info,graphics is false. A footnote records that two of the three lines matched."}
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

val emitted = listOf("info,kotlin", "info,kotlinjs", "info,graphics")
try {
    emitted.forEach { lprintln(it, "«logdoc» a line on $it") }
} finally {
    LogStream.unsubscribe("logdoc-substring")
}

val matched = pushed.filter { "«logdoc»" in it }
    .map { it.substringAfter("\"channel\":\"").substringBefore("\"") }
    .toSet()

display("main", Table(
    listOf("channel emitted", "pushed to the subscriber"),
    title = "a LogStream filter of [\"kotlin\"] against three channels",
    note = "${matched.size} of ${emitted.size} matched: kotlin is a tag in the first and only a " +
        "substring in the second. The pinned console splits on commas, so it would take only the first."
)
    .rows(emitted.map { listOf(it, it in matched) })
    .role("channel emitted", CellRole.ACCENT)
    .roleBy("pushed to the subscriber") { if (it == true) CellRole.OK else CellRole.DIM })
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
tags: [docs, logging, tohtml]
timeoutSec: 120
waitSec: 1.5
expect:
  outputContains:
    - "info,boxes"
    - "info,mypage"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/mute-is-console-only.png" capture="box" w="900" h="220" wait="1.5" timeout="120" embed=true name="logging-mute-is-console-only" alt="A Field box output panel labelled main, holding a three-column table titled 'muting keeps a line off the console, not out of the layer', headed channel, muted on the console, and reached my sink. Two rows: info,boxes is muted true and reached true; info,mypage is muted false and reached true. A footnote notes that both lines arrived."}
:::static
import afield.ai.core.ConsoleMute
import afield.ai.core.LogDispatcher
import afield.ai.core.LogSink
import afield.ai.core.withSink
import afield.logger.lprintln

val arrived = mutableSetOf<String>()
val probe = LogSink { e -> if ("«logdoc»" in e.message) arrived += e.channel }

val channels = listOf("info,boxes", "info,mypage") // the first is muted by default
LogDispatcher.withSink(probe) {
    channels.forEach { lprintln(it, "«logdoc» a line on $it") }
}

display("main", Table(
    listOf("channel", "muted on the console", "reached my sink"),
    title = "muting keeps a line off the console, not out of the layer",
    note = "${arrived.size} of ${channels.size} arrived. Mute is for a channel too chatty " +
        "for a terminal, not for one you want gone."
)
    .rows(channels.map { listOf(it, ConsoleMute.isMuted(it), it in arrived) })
    .role("channel", CellRole.ACCENT)
    .roleBy("muted on the console") { if (it == true) CellRole.WARN else CellRole.DIM }
    .roleBy("reached my sink") { if (it == true) CellRole.OK else CellRole.BAD }
    .expect("reached my sink", because = "muting is a console policy, so every line still arrives") { it == true })
```

Muted, and delivered. Four<!--count:consoleMuteDefaults--> channels are muted out of the
box — `info,boxes`, `info,persist`, `info,ai.boxnames` and `debug,boxes` — and every one of
them is still in the log widget and still in the archive when you go looking for it.

## Blocks, and what the indent actually holds

`lblock` brackets a scope with a BEGIN and an END event sharing a `blockId`, so a consumer
can fold the lines between them:

<!--
FIELD_TEST
id: docs.logging.blocks
runner: backdoor-box
tags: [docs, logging, tohtml]
timeoutSec: 120
waitSec: 1.5
expect:
  outputContains:
    - "BEGIN"
    - "END"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/blocks.png" capture="box" w="900" h="300" wait="1.5" timeout="120" embed=true name="logging-blocks" alt="A Field box output panel labelled main, holding a four-column table titled 'two nested lblocks, as the dispatcher saw them', headed depth, boundary, block and message. Six rows stepping down and back up: depth 0 BEGIN outer; depth 1 with no boundary, inside outer; depth 1 BEGIN inner; depth 2 with no boundary, inside inner; depth 1 END; depth 0 END. The two BEGIN rows carry short block ids that the two END rows repeat, and each END row's message is only its own full block id, since that is all an END event carries. A footnote shows the deepest line's raw indent as two tab-separated UUIDs."}
:::static
import afield.ai.core.LogDispatcher
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import afield.ai.core.withSink
import afield.logger.lblock
import afield.logger.lprintln

val all = mutableListOf<LogEvent>()
val probe = LogSink { e -> all += e }

LogDispatcher.withSink(probe) {
    lblock("info,logdoc", "«logdoc» outer") {
        lprintln("info,logdoc", "«logdoc» inside outer")
        lblock("info,logdoc", "«logdoc» inner") {
            lprintln("info,logdoc", "«logdoc» inside inner")
        }
    }
}

// An END event's message is just its own id, so it is picked up by blockId rather than by text.
val mineIds = all.filter { "«logdoc»" in it.message }.mapNotNull { it.blockId }.toSet()
val mine = all.filter { "«logdoc»" in it.message || it.blockId in mineIds }
val deepest = mine.filter { it.boundary == null }.maxByOrNull { it.indent.length }

display("main", Table(
    listOf("depth", "boundary", "block", "message"),
    title = "two nested lblocks, as the dispatcher saw them",
    note = "the deepest line's raw indent: '" +
        (deepest?.indent?.replace("\t", "\\t") ?: "") +
        "' — block ids and tabs, not spaces"
)
    .rows(mine.map { e ->
        listOf(
            e.indent.split("\t").count { it.isNotEmpty() },
            e.boundary?.name ?: "—",
            e.blockId?.take(8) ?: "—",
            e.message.substringBefore(" - '")
        )
    })
    .role("message", CellRole.ACCENT)
    .expect("depth", because = "two nested blocks cannot nest deeper than two") { (it as Int) <= 2 })
```

Note the footnote. The nesting is carried in a thread-local `indent` string, and what it
holds is not spaces — it is one block UUID and a tab per level
(`logger.kt:69#val added = "${u}\t"`). `PinnedConsole` parses those tokens back out to find
which open block a line belongs to, which is what they are for. `StdoutLog` does not: it
concatenates the indent into the line as-is
(`Logging.kt:106#val full = prefix + channelPart + event.indent + boundaryMarker + event.message`),
so on a plain terminal a line logged inside an `lblock` is prefixed by a raw 36-character
UUID. `lblock` is called with a channel literal in only six<!--count:lblockCallSites-->
places, which is presumably why this has not annoyed anyone into fixing it.

Being thread-local, `indent` also means a block does not nest across threads: work handed
to another thread inside an `lblock` logs at depth zero.

## Stat lines and the heatmap

A channel that reports a number every frame gets special treatment, and the contract is
just a string format: `key=value` or `key: value`, optionally with a unit
(`StatsHeatmap.kt:19#private val TOKEN = Regex(`). `StatsHeatmap` colours each numeric
token by whether it rose or fell since the same channel last reported that key — blue for a
sharp fall, through grey for unchanged, to red for a sharp rise, saturating at ±50%. Use
`formatMillis` for durations and the unit comes out matching that grammar, so a slow compile
reads `1.63s` rather than `1629ms`:

<!--
FIELD_TEST
id: docs.logging.stats
runner: backdoor-box
tags: [docs, logging, tohtml]
timeoutSec: 120
waitSec: 1.5
expect:
  outputContains:
    - "ms"
    - "fps"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/logging/stats.png" capture="box" w="900" h="320" wait="1.5" timeout="120" embed=true name="logging-stats" alt="A Field box output panel labelled main holding two stacked tables. The first, titled 'formatMillis keeps digits where they matter', has columns nanoseconds and formatted, with six rows running from 120,000 nanoseconds shown as 0.120ms up to 12,400,000,000 nanoseconds shown as 12.4s. The second, titled 'how a stats line is torn into colourable tokens', has columns fragment and treated as, and splits the line fps=61.2 upload=3.4MB net:102% dropped=0 into eleven fragments: the four keys with their separators and the three single spaces between them are plain text, while 61.2, 3.4MB, 102% and 0 are marked as numbers."}
:::static
import afield.logger.StatsHeatmap
import afield.logger.formatMillis

val durations = listOf(120_000L, 3_400_000L, 47_000_000L, 250_000_000L, 1_630_000_000L, 12_400_000_000L)
val ladder = Table(
    listOf("nanoseconds", "formatted"),
    title = "formatMillis keeps digits where they matter",
    note = "more digits below a millisecond, plain integers past 100ms, decimal seconds past a second"
)
    .rows(durations.map { listOf(it, formatMillis(it)) })
    .role("formatted", CellRole.ACCENT)
    .expect("formatted", because = "the unit is part of the returned string") {
        (it as String).endsWith("ms") || it.endsWith("s")
    }

val line = "fps=61.2 upload=3.4MB net:102% dropped=0"
val torn = Table(
    listOf("fragment", "treated as"),
    title = "how a stats line is torn into colourable tokens",
    note = "history is kept per channel and per key, so two unrelated metrics sharing one " +
        "channel do not colour each other"
)
    .rows(StatsHeatmap.fragments("logdoc-demo", line).map {
        listOf("'${it.text}'", if (it.style == null) "plain text" else "number")
    })
    .roleBy("treated as") { if (it == "number") CellRole.ACCENT else CellRole.DIM }

markdown("main", """
    ${ladder.h}

    ${torn.h}
""".trimIndent())
```

Each numeric token is styled and everything between them is left alone.

## The pinned console

When Field runs in a terminal, the channels that report every frame do not scroll. They sit
in a small fixed region at the top while everything else scrolls beneath, which is what
makes a frame-rate line useful rather than an assault.

The mechanism is one ANSI scroll region (DECSTBM) rather than a render loop: the top rows
are fenced off, so an ordinary `println` can only scroll in the region below them and "just
works" with no cooperation. The header is repainted only when a pinned value actually
changes. Six channels are pinned by default
(`PinnedConsole.kt:69#private val DEFAULT_PINS = listOf("fps", "upload", "perf", "net", "compile", "evaluate")`),
in that order, and the order matters: `matchedPin` takes the *first* pinned tag present in a
channel, so `info,perf,fps` lands on the `fps` row and only the leftover `perf` lines fall
through to the `perf` row. Add your own with `AFIELD_PIN_CHANNELS`, or at runtime with
`PinnedConsole.pin("mytag")`.

It degrades rather than breaking. If stdout is not an interactive TTY, or ANSI control is
unavailable, or Mordant reports a legacy Windows console, pinning switches off and every
event scrolls, with would-be-pinned lines tagged `pin` so they stay visible.
`AFIELD_PIN_DISABLE=1` forces that path.

!!! note "Why there is no figure of the header"
    The six figures above are captures of real runs in a real Field. There is no figure of
    the pinned header itself, and there cannot be one from this page: a docs capture drives
    a box in a headless Field whose stdout is a pipe, which is exactly the condition under
    which the pinned console switches itself off. The fallback is what would be
    photographed. `matchedPin` and `PinnedConsoleSink` are private as well, so the
    row-routing rule above is stated from its source line rather than executed. Run
    `./gradlew runJvm` in a terminal to see the real thing.

    The build log for this page is itself the evidence for the fallback: every pinned line
    in it arrives tagged `pin`, as `[pin,info,kotlin,compile] compile=455ms`.

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
It tees the stream
(`SessionErrorLog.kt:113#System.setErr(PrintStream(TeeStderr(System.err), true, StandardCharsets.UTF_8))`)
so every byte still reaches the real stderr and nothing watching this process sees a change;
it appends every line to `build/logs/session-<stamp>-pid<pid>.log`, which is the durable
greppable record, flushed per line so the last thing stderr said survives a crash; and it
recognises a stack trace or an slf4j `ERROR` line and raises it as a sticky pinned panel,
the one region of the screen a repaint will not reclaim. Twenty session files are kept and
one is capped at 32 MB.

So when something has gone wrong and the terminal is no help, that file is where to look.

## Where the lines end up

| Sink | Where | Honours the mute set |
|---|---|---|
| `StdoutLog` | the terminal, with the channel dimmed and italic | yes |
| `PinnedConsoleSink` | the terminal, pinned rows plus scrollback | yes |
| `LogStream` | a 20,000-record ring (`logstream.kt:27#private const val MAX_RECORDS = 20_000`), served to the log widget over the `__logs__` websocket | no |
| `NotifyLog` | stdout, plus a desktop notification, deduped within a second | via `StdoutLog` |
| `SilentLog` | nowhere, on purpose | n/a |

Two things that look like this layer and are not. `SaveReloadLog` writes save and reload
timings straight to `~/.afield/logs/save_reload.log` without touching the dispatcher, so it
is invisible to every sink above. `TraceLogger` and `AiToolIoLogger` write AI request traces
under `~/.afield/logs/` when their environment variables are set, and are wired to a
`LogSink` only for the one line that announces the file.

## Reference

The generic core is `afield.ai.core.Logging` — `LogEvent`, `LogSink`, `LogDispatcher`,
`ConsoleMute`, `StdoutLog`. The entry points and the terminal live in `afield.logger` —
`lprintln`, `lprint`, `lblock`, `indent`, `formatMillis`, `PinnedConsole`, `StatsHeatmap`,
`SessionErrorLog`, `SaveReloadLog`. The web sink is `afield.server.LogStream`, which serves
the log widget over the `__logs__` websocket handler.
