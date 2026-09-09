# The log layer — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`)
and run the six boxes. No GPU, no window, no sidecar: every box reads the log objects the running
Field is already using — `LogDispatcher`'s live sink list, `ConsoleMute`'s actual mute set,
`LogStream`'s actual subscription filter — so what you get is that JVM's own answer rather than a
description of one.

| box | what it does | what to notice |
| --- | --- | --- |
| **1 — a channel, and a sink of your own** | registers a `LogSink` lambda, emits three lines, removes it | a channel is a flat comma-separated tag list. `error,mypage,disk` is three tags, not a path, and nothing parses a hierarchy |
| **2 — muting compares the whole string** | `ConsoleMute.isMuted` over five spellings of one channel | only `info,boxes` is muted. Reorder the tags, add one, or change a letter's case and the mute set has never heard of it — you cannot mute a *tag* |
| **3 — the log widget takes any substring** | a real `LogStream.addSubscription` with `channels = ["kotlin"]` | **the one to actually run.** `info,kotlinjs` is matched, because the filter asks `contains(tag, ignoreCase = true)`. The pinned console splits on commas and would not pin it — one word, two answers |
| **4 — muting is a console policy, not a filter** | emits on a muted channel with a sink attached | muted *and* delivered. Both console sinks return early; no other sink checks the set, so the line is still in the widget and still in the archive |
| **5 — blocks, and what the indent holds** | two nested `lblock`s, read back off the dispatcher | the footnote. `indent` is not spaces — it is one block UUID and a tab per level. `PinnedConsole` parses those tokens; `StdoutLog` concatenates them into the line, so on a plain terminal a line inside an `lblock` carries a raw 36-character UUID |
| **6 — stat lines and the heatmap** | `formatMillis` across six magnitudes, and `StatsHeatmap.fragments` | the unit is part of what `formatMillis` returns, which is what makes it match the heatmap's `key=value unit` grammar. A slow compile reads `1.63s`, not `1629ms` |

Box 3 is the point of the whole document. Three mechanisms in Field select on "the channel" and all
three match differently — exact whole string, exact comma-split tag, and case-insensitive substring —
and 108 pairs of a real tag and a real channel string in this tree are matched by the third and not
the second. Reading that is not the same as watching a real subscriber do it.

Every box cleans up after itself in a `finally`. That is not decoration: `LogDispatcher` is a global,
a leaked sink keeps receiving events for the life of the JVM, and `LogDispatcher.log` wraps each sink
in `runCatching`, so a leaked sink that throws fails silently rather than loudly.

`build.yaml` beside this file is the `OperationTranscript` that built the document. Replay it into a
fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch \
  --command 'macro write logging_layer --file dev_docs/agents/demos/logging_layer/build.yaml' \
  --command 'macro run logging_layer'
```

That replay is how this document was made, and it reported `applied=13 skipped=0 errors=0` with all
six boxes `ok`.

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable inline copies of
the same text, and `./gradlew checkAgentDemos` fails if the two ever disagree.

The written-up version, with the source citations and the captured figures, is the published page
[`public/docs/logging.md`](../../../../public/docs/logging.md) ("Where Field's Output Goes", under
Internals).
