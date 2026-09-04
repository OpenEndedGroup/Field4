# Six keys — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`)
and run the three boxes. No GPU, no window, no sidecar: a `KeyframeScope` needs none of them, and each
box writes its key directory into a fresh temp dir, so nothing lands in this document or in your
workspace.

## What went wrong

`KeyframeScope.saveKey` writes the path it is handed and appends nothing. So
`kfs.capture().save("d3".path)` produces a file called **`d3`** — no extension. The browser's default
glob asked for `**/*.json`, which matches none of that; the one place a `.json` suffix is guaranteed
is inside each key's `.versions` history, which the browser excludes on purpose. A directory of six
perfectly good keys therefore opened as **"No key files matched"**.

Narrowing by hand does not rescue it either. `?` is exactly one character and `??` exactly two, so
over `a b c d d2 d3` the first shows four and the second shows two — and nothing in the old header
said that switching between them had traded four keys for two.

| box | what it does | what to notice |
| --- | --- | --- |
| **1 — six keys, and the pattern that showed two** | six keys named `a b c d d2 d3` plus the `.DS_Store` macOS leaves behind, browsed twice in one box | the **top** panel is `pattern = "??"`: two keys, and now a line saying *"4 more keys here are hidden by this pattern · 1 file ignored"* with a **show every key** button that widens it in one click. The **bottom** panel is the default: six keys, with the `.DS_Store` counted as *ignored* rather than reported as an error |
| **2 — junk is not damage** | four good keys, two files that were never keys, and one key Field wrote and then truncated | exactly **one** red row. What tells the truncated key apart from the junk is not its bytes — it is unparseable either way — but the `.versions` directory still beside it, which is both the proof Field wrote it and the thing you now want |
| **3 — a saved slot that applies to nowhere** | three keys saved back when the pass had a `blur`; the scope today watches `radius` and a `feather` nothing has ever saved | three rows, three states. `glow@feather` is the amber *never saved* row the browser already had. `glow@blur` is saved in all three keys, draws its real range, and is watched by nothing — so it is **struck through** and named in the header as one Apply would drop. `glow@radius` is plain |

Box 1 is the before/after. Box 3 is the one to read twice: the browser had always drawn *one* half
of a scope/file disagreement — the slot the scope watches that no key holds — and the other half was
silent, rendering as an ordinary working row. Box 2 is why the junk/damage split earns its keep: before it, a `.DS_Store` in a
directory where nothing was wrong produced a red row indistinguishable from a corrupt key, in every
key directory a Finder window had ever opened. A permanent false alarm teaches you to stop reading
the alarm.

The printed numbers in box 1 are the whole story, and they are measured rather than asserted — one
directory, four globs:

```
the old default, '**/*.json':  0 keys  <- 'No key files matched'
the last pattern tried, '??':  2 keys  (4 more keys here are hidden by this pattern · 1 file ignored)
brace alternation, '{?,??}':   6 keys  <- this always worked, and nothing said so
the default now, '**':         6 keys  (1 file ignored)
```

That third line is worth dwelling on. Java's glob has supported `{a,b}` alternation all along, so
`{?,??}` spans both name lengths and always did. The expressiveness was never missing — only
findable. That is why nothing was added to the pattern syntax, and why
`KeyframeBrowserTest.braceAlternationAlreadySpansOneAndTwoCharacterKeyNames` pins it: a future
proposal to invent a comma syntax should be answered by a passing test rather than by an argument.

`build.yaml` beside this file is the `OperationTranscript` that built the document. Replay it into a
fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --port 9477 \
  --command 'macro write keyframe_key_discovery --file dev_docs/agents/demos/keyframe_key_discovery/build.yaml' \
  --command 'macro run keyframe_key_discovery'
```

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable inline copies of
the same text, and `./gradlew checkAgentDemos` fails if the two ever disagree.

The written-up version is [`dev_docs/keyframe_key_discovery.md`](../../../keyframe_key_discovery.md).
Companion tests: `afield.stdlib.KeyframeBrowserTest`, `afield.server.KeyframeBrowserProtocolTest`.
