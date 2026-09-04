# The keyframe browser could not show you your own keys

A note on a discovery failure, what caused it, and the four changes that answer it. The loadable
version is [`dev_docs/agents/demos/keyframe_key_discovery/`](agents/demos/keyframe_key_discovery/) —
open its `metadata.edn` and run the two boxes.

## The evidence

A live session's `transcript.edn` records nine `execute` events. The last three, in order, were the
last thing done that evening:

```
20:09:17Z  kfs.capture().save("d3".path)
20:09:20Z  keyframeBrowser(".".path, pattern = "?",  scope = kfs)
20:09:36Z  keyframeBrowser(".".path, pattern = "??", scope = kfs)
```

The directory held six keys — `a`, `b`, `c`, `d`, `d2`, `d3` — plus a `.DS_Store` and six
`*.versions/` history directories. `?` is a Java glob for *exactly one* character, so it matched
`a b c d`; `??` matched `d2 d3`. **The last action of the day traded four keys for two, and neither
pattern showed all six.** The root box's `fileManagerAccessHistory` corroborates the fumbling: four
entries, one of them a request for `"d3"` with `"exists" false`.

## Step zero: the brief's premise was half wrong

The obvious diagnosis is that the pattern surface cannot express "one- or two-character names", and
the obvious fix is to add an alternation syntax. That diagnosis is **wrong**, and it was falsified by
running it rather than by reasoning about it:

```java
PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:{?,??}");
// matches a, b, c, d, d2, d3
```

Java's `getPathMatcher("glob:")` has supported `{a,b}` brace alternation all along, and
`keyFilesMatching` passes the pattern through with no preprocessing beyond separator normalization.
So `{?,??}` worked on the evening it was needed. **The expressiveness was never missing; it was
unfindable.** Nothing was added to the pattern syntax as a result, and
`KeyframeBrowserTest.braceAlternationAlreadySpansOneAndTwoCharacterKeyNames` pins the fact so that a
future proposal to invent a comma list is answered by a passing test rather than by an argument.

## The actual cause: a default that could not match a saved key

The reason a pattern was being hand-typed at all is worse than the pattern.

`KeyframeScope.saveKey` writes the path it is *given*, verbatim. It appends no extension:

```kotlin
fun saveKey(path: Path, key: Key, note: String? = null) {
    path.parent?.createDirectories()
    val text = json.encodeToString(key.toDTO())
    saveKeyVersion(path, text, note)
    Files.writeString(path, text)   // <- `path`, not `path + ".json"`
}
```

So `kfs.capture().save("d3".path)` produces a file called `d3`. The browser's default glob was
`**/*.json`, which matches none of that. Worse, the one place a `.json` suffix *is* guaranteed is the
numbered snapshots inside each key's `.versions` directory — and `keyFilesMatching` excludes those on
purpose, for good reasons documented on the function. **The single place the old default could have
found a match was the single place the browser refuses to look.** A directory of six good keys
therefore opened as "No key files matched", which is what sent a person hand-typing globs at 20:09.

Measured, one directory, four globs (this is box 1 of the demo, printing real numbers):

```
the old default, '**/*.json':  0 keys  <- 'No key files matched'
the last pattern tried, '??':  2 keys  (4 more keys here are hidden by this pattern · 1 file ignored)
brace alternation, '{?,??}':   6 keys  <- this always worked, and nothing said so
the default now, '**':         6 keys  (1 file ignored)
```

## What changed

### 1. `everyKeyGlob` — a default that means "every key here"

The default is now `**`, at `keyFilesMatching`, `KeyframeCollection.load` and both `Box.keyframeBrowser`
overloads. Matching everything is affordable because `Files.walk` already visited every file whatever
the pattern was; the pattern only ever decided which visited paths were *kept*. What matching
everything costs is junk, which is what change 2 pays for.

### 2. `SkipKind` — junk is not damage

There used to be one list, `problems`, and three different things landed in it. A `.DS_Store` — a file
the operating system wrote without asking, in a directory where nothing is wrong — produced a red row
indistinguishable from a corrupt key, in every key directory a Finder window had ever opened. **A
permanent false alarm is worse than no alarm: it trains you to stop reading the box it appears in.**

Three kinds now, and the ladder that decides between them (`classifyNonKey`) puts an *ownership* test
first, before any content sniffing:

| kind | meaning | example |
| --- | --- | --- |
| `NotAKey` | never was a key, never going to be | `.DS_Store`, `notes.txt`, JSON with no `values` |
| `Damaged` | Field wrote this as a key and it will not read back | a truncated `d3` |
| `Hidden` | a perfectly good key that the *pattern* excluded | `a` under `pattern = "??"` |

Only `Damaged` reaches `problems`. The ownership test is whether a `<file>.versions` directory sits
beside the file, and it comes first because the obvious content-first ladder gets the important case
**backwards**: a truncated key is not valid JSON, so a content sniff reaches "not JSON, not a key" and
files real damage as an OS turd. Its `.versions` directory is still there, still holding every earlier
save, so the file that most needs a red row is exactly the one a content sniff would have swallowed.
`saveKey` appends a numbered snapshot on every save without exception, which is what makes that test
authoritative rather than a heuristic.

This is deliberately the same move as `keyFilesMatching`'s `.versions` exclusion: decide what is not a
key, and write down why.

### 3. The browser says what it did not show

`KeyframeCollection.skipped` names every file under the root that is not a row, with a one-line
reason; `skipSummary()` renders the headline. A narrowed pattern surveys the files it excluded — up to
`HIDDEN_SCAN_BUDGET` of them — so it can count the keys the narrowing hid. That survey costs nothing
under the default, because the default excludes nothing, so the extra reads appear exactly when the
answer is wanted and vanish when it is not. A truncated survey reports "at least N" rather than "N".

On screen this is a `.kb-notice` line: the same card shape as `.kb-problems` and none of its colour,
because the whole point of splitting the two is that a `.DS_Store` must stop looking like a corrupt
key. The per-file reasons are the notice's hover title.

The failure being fixed here was *invisible*. Nothing told anyone that switching `?`→`??` had dropped
four keys. "6 keys, 1 file ignored" beats silence, and "4 more keys here are hidden by this pattern"
beats both.

### 4. One click to widen

`KeyframeBrowserHandle.pattern` is now mutable — and only ever widened, by a `showAll` op that sets it
to `everyKeyGlob` and reloads. The browser already knew how many keys the pattern was hiding; refusing
to let a click act on that number was the un-toolish half of a viewer. `showAll` folds into the same
branch as `open`, so the button and a reload share a code path and cannot drift apart. The box's own
argument is untouched: re-running the box registers a fresh handle at the pattern the source still
says.

## Two smaller things

**Row ids are dense over the rows**, not over the matched paths. They used to be the index into the
matched-path list, so every skipped file left a hole and the chips numbered 1, 2, 4. Under a default
that matches everything there is more junk to step over — and `.DS_Store` sorts first, which would
have made the *first* chip read 2.

**The empty state tells the truth.** "No key files matched. Save one with `scope.saveKey(path, key)`"
is the right advice for an empty directory and a lie in a directory full of keys your glob cannot see.
That case now says what the glob was and that keys are saved under the exact name you pass.

## Why the tests did not catch this

Every fixture in `KeyframeBrowserTest` was named `looks.json`, `top.json`, `nested.json` — eight
characters and up, every one. **A fixture where every name is at least eight characters makes `?` match
nothing, so `?` looks harmless.** The bug is only reachable from a directory shaped like a real one, so
`humansKeyDirectory()` is now the real thing, copied name for name: six keys `a b c d d2 d3`, one and
two characters, `d`/`d2`/`d3` sharing a prefix, plus the `.DS_Store`. A claim true of a fixture is not
true of the reader unless the fixture is shaped like the reader's disk.

One existing test, `anUnreadableFileIsReportedAndTheRestStillLoad`, asserted the old conflation
directly — that a stray JSON file and a non-JSON blob both land in `problems`. It is now
`aFileThatWillNotDecodeIsNamedAndTheRestStillLoad` and asserts the inverse, with the reason written
into its KDoc. A companion, `damageAndJunkInOneDirectoryReadDifferently`, puts all three kinds in one
directory, because the value of the split is precisely that they coexist and read differently.

## Not done

- **`slotHistory` has no surface for a long history.** One live directory has nine saves of `d`; the
  version rail is reached per slot per file, which is right, but nothing summarises "you have saved
  this nine times and the last three did not move".
- **A scope slot and a file slot can still disagree quietly** in one direction: `scopeSlots` marks a
  slot the scope watches and no file holds, but a file holding a slot the *scope* no longer watches is
  shown as an ordinary row. It applies to nowhere.
- The `Hidden` survey does not distinguish a key hidden by the pattern from one hidden by the
  `.versions` exclusion, because the second is never a mistake.
