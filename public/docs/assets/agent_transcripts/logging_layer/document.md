# Field Document: logging_layer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. Blocks, and what the indent holds

<!-- field-box id="log_blocks" index="0" -->

```edn [field-box id="log_blocks" role="properties"]
{"id" "log_blocks" "index" 0 "name" "Blocks, and what the indent holds" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Blocks, and what the indent holds" "_tombstone" false "frame" #afield/rect [60.0 4040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "log_blocks_code_.kt" "lines" 48 "characters" 1997 "sha256" "62c42ae130244fc73b4e87d25c5f73da17858920eeafe1d351e94380eedebabd"}]}
```

```kotlin [field-property box="log_blocks" name="code" path="log_blocks_code_.kt"]
import afield.ai.core.LogDispatcher
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import afield.ai.core.withSink
import afield.logger.lblock
import afield.logger.lprintln

// lblock brackets a scope with a BEGIN and an END event sharing a blockId, so a
// consumer can fold what is between them.
val all = mutableListOf<LogEvent>()
val probe = LogSink { e -> all += e }

LogDispatcher.withSink(probe) {
    lblock("info,logdemo", "«logdemo» outer") {
        lprintln("info,logdemo", "«logdemo» inside outer")
        lblock("info,logdemo", "«logdemo» inner") {
            lprintln("info,logdemo", "«logdemo» inside inner")
        }
    }
}

// An END event's message is only its own id, so it is picked up by blockId rather
// than by text.
val mineIds = all.filter { "«logdemo»" in it.message }.mapNotNull { it.blockId }.toSet()
val mine = all.filter { "«logdemo»" in it.message || it.blockId in mineIds }
val deepest = mine.filter { it.boundary == null }.maxByOrNull { it.indent.length }

// The table's footnote prints the deepest line's raw indent. The nesting lives in a
// thread-local `indent` string, and what it holds is not spaces: it is one block UUID
// and a tab per level. PinnedConsole parses those tokens back out to find the open
// block. StdoutLog does not — it concatenates the indent into the line, so on a plain
// terminal a line inside an lblock is prefixed by a raw 36-character UUID.
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
    .role("message", CellRole.ACCENT))
```

## 2. Muting compares the whole string

<!-- field-box id="log_mute" index="1" -->

```edn [field-box id="log_mute" role="properties"]
{"id" "log_mute" "index" 1 "name" "Muting compares the whole string" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Muting compares the whole string" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "log_mute_code_.kt" "lines" 21 "characters" 975 "sha256" "8f947fa6b1e36e45d6a0a7cee4f6b72c64f15fc54a00b249f0378789991e1cef"}]}
```

```kotlin [field-property box="log_mute" name="code" path="log_mute_code_.kt"]
import afield.ai.core.ConsoleMute

// The first of the three matchers, and the strictest: isMuted is
//
//     fun isMuted(channel: String): Boolean = channel in channels
//
// Exact membership of a set of whole channel strings. Nothing is split, so you cannot
// mute a *tag* — only the exact spelling written at the call site.
val spellings = listOf("info,boxes", "boxes,info", "info,boxes,extra", "info,Boxes", "boxes")

display("main", Table(
    listOf("channel string", "isMuted"),
    title = "ConsoleMute compares the whole channel string",
    note = "the set this JVM booted with: " + ConsoleMute.channels.sorted().joinToString(", ")
)
    .rows(spellings.map { listOf(it, ConsoleMute.isMuted(it)) })
    .role("channel string", CellRole.ACCENT)
    .roleBy("isMuted") { if (it == true) CellRole.WARN else CellRole.OK })

// Change the set with -Dafield.log.mute=a;b or AFIELD_LOG_MUTE=a;b (semicolon
// separated, exact strings), or a blank value to mute nothing.
```

## 3. Muting is a console policy, not a filter

<!-- field-box id="log_mute_is_console_only" index="2" -->

```edn [field-box id="log_mute_is_console_only" role="properties"]
{"id" "log_mute_is_console_only" "index" 2 "name" "Muting is a console policy, not a filter" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Muting is a console policy, not a filter" "_tombstone" false "frame" #afield/rect [60.0 3040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "log_mute_is_console_only_code_.kt" "lines" 27 "characters" 1297 "sha256" "2f0ccc6945a5db41eb8edf08822ee49aed83c4fed57ffdd09703a2fa289a7ce0"}]}
```

```kotlin [field-property box="log_mute_is_console_only" name="code" path="log_mute_is_console_only_code_.kt"]
import afield.ai.core.ConsoleMute
import afield.ai.core.LogDispatcher
import afield.ai.core.LogSink
import afield.ai.core.withSink
import afield.logger.lprintln

// Muting does not drop the event. Both console sinks check the mute set and return
// early; no other sink checks it at all. So a muted line is still in the log widget,
// still in the archive, and still delivered to a sink of yours.
val arrived = mutableSetOf<String>()
val probe = LogSink { e -> if ("«logdemo»" in e.message) arrived += e.channel }

val channels = listOf("info,boxes", "info,mypage") // the first is muted by default
LogDispatcher.withSink(probe) {
    channels.forEach { lprintln(it, "«logdemo» a line on $it") }
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
    .roleBy("reached my sink") { if (it == true) CellRole.OK else CellRole.BAD })
```

## 4. A channel, and a sink of your own

<!-- field-box id="log_channels" index="3" -->

```edn [field-box id="log_channels" role="properties"]
{"id" "log_channels" "index" 3 "name" "A channel, and a sink of your own" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A channel, and a sink of your own" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "log_channels_code_.kt" "lines" 27 "characters" 1297 "sha256" "150e8239e40959e5690d1d0ab4b6166ee19090de32f2a11b83e27a558fdd01fb"}]}
```

```kotlin [field-property box="log_channels" name="code" path="log_channels_code_.kt"]
import afield.ai.core.LogDispatcher
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import afield.ai.core.withSink
import afield.logger.lprintln

// A channel is a comma-separated list of tags. Not a level, not a logger name, and
// not a hierarchy — "error,mypage,disk" is three flat tags.
//
// A LogSink is a fun interface with one method, so a lambda is a sink. This one keeps
// only my own lines: the dispatcher is global, and a live Field is logging its own
// frame rate the whole time this box runs.
val seen = mutableListOf<LogEvent>()
val probe = LogSink { e -> if ("mypage" in e.channel) seen += e }

// withSink attaches and detaches around the block, on any exit. That is not a
// convenience: the sink list outlives your box, and LogDispatcher.log wraps every sink
// in runCatching — so a sink leaked by an early return throws silently, forever.
LogDispatcher.withSink(probe) {
    lprintln("info,mypage", "a plain line")
    lprintln("warning,mypage", "something to look at")
    lprintln("error,mypage,disk", "three tags, and no hierarchy among them")
}

display("main", Table(listOf("channel", "tags", "message"), title = "what one sink saw")
    .rows(seen.map { listOf(it.channel, it.channel.split(",").size, it.message) })
    .role("channel", CellRole.ACCENT))
```

## 5. The log widget takes any substring

<!-- field-box id="log_filter" index="4" -->

```edn [field-box id="log_filter" role="properties"]
{"id" "log_filter" "index" 4 "name" "The log widget takes any substring" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The log widget takes any substring" "_tombstone" false "frame" #afield/rect [60.0 2040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "log_filter_code_.kt" "lines" 42 "characters" 1823 "sha256" "16e1753768e12d7747404eb26e0a2765b26dfe1b78edcfea94ebe30653dd3abb"}]}
```

```kotlin [field-property box="log_filter" name="code" path="log_filter_code_.kt"]
import afield.logger.lprintln
import afield.server.LogStream

// The third matcher, and the loosest. LogStream is the sink behind the log widget, and
// its filter asks channel.contains(tag, ignoreCase = true) — a substring, not a tag.
//
// This is a real subscription against the real archive. sinceEpochMs keeps the
// existing 20,000-record ring out of the replay, so what arrives is only what I emit.
val pushed = mutableListOf<String>()
LogStream.addSubscription(
    "logdemo-substring",
    LogStream.Filter(
        channels = listOf("kotlin"),
        sinceEpochMs = System.currentTimeMillis(),
        limit = 50,
        tail = true
    )
) { json -> pushed += json }

val emitted = listOf("info,kotlin", "info,kotlinjs", "info,graphics")
try {
    emitted.forEach { lprintln(it, "«logdemo» a line on $it") }
} finally {
    LogStream.unsubscribe("logdemo-substring")
}

val matched = pushed.filter { "«logdemo»" in it }
    .map { it.substringAfter("\"channel\":\"").substringBefore("\"") }
    .toSet()

// info,kotlinjs matches because "kotlin" is a prefix of "kotlinjs". The pinned console,
// which splits on commas, would not pin it. Same word, two different answers — and 108
// pairs of a real tag and a real channel in this tree differ the same way.
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

## 6. Stat lines and the heatmap

<!-- field-box id="log_stats" index="5" -->

```edn [field-box id="log_stats" role="properties"]
{"id" "log_stats" "index" 5 "name" "Stat lines and the heatmap" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Stat lines and the heatmap" "_tombstone" false "frame" #afield/rect [60.0 5040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "log_stats_code_.kt" "lines" 35 "characters" 1482 "sha256" "a83f459a9e7e2dcbb235a8e688159b5fa155ddd359658a853c4f2fd5b6d438e6"}]}
```

```kotlin [field-property box="log_stats" name="code" path="log_stats_code_.kt"]
import afield.logger.StatsHeatmap
import afield.logger.formatMillis

// A channel that reports a number every frame gets coloured by whether each value rose
// or fell since that channel last reported the same key. The contract is only a string
// format: key=value or key: value, optionally with a unit.
//
// formatMillis emits a unit that matches that grammar, which is why a slow compile
// reads compile=1.63s rather than compile=1629ms.
val durations = listOf(120_000L, 3_400_000L, 47_000_000L, 250_000_000L, 1_630_000_000L, 12_400_000_000L)
val ladder = Table(
    listOf("nanoseconds", "formatted"),
    title = "formatMillis keeps digits where they matter",
    note = "more digits below a millisecond, plain integers past 100ms, decimal seconds past a second"
)
    .rows(durations.map { listOf(it, formatMillis(it)) })
    .role("formatted", CellRole.ACCENT)

val line = "fps=61.2 upload=3.4MB net:102% dropped=0"
val torn = Table(
    listOf("fragment", "treated as"),
    title = "how a stats line is torn into colourable tokens",
    note = "history is kept per channel and per key, so two unrelated metrics sharing one " +
        "channel do not colour each other"
)
    .rows(StatsHeatmap.fragments("logdemo", line).map {
        listOf("'${it.text}'", if (it.style == null) "plain text" else "number")
    })
    .roleBy("treated as") { if (it == "number") CellRole.ACCENT else CellRole.DIM }

markdown("main", """
    ${ladder.h}

    ${torn.h}
""".trimIndent())
```

