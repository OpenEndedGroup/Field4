# Field Document: keyframe_key_discovery

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isDatalogRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. 2 - Junk is not damage

<!-- field-box id="kf_junk_vs_damage" index="0" -->

### Narrative

From `code`

Two files fail to decode. Only one of them is your problem.

This directory holds four good keys, two files that were never keys, and one key that Field wrote
and that is now truncated. Before, all three failures were one red list, so a `.DS_Store` — a file
the operating system wrote without asking, in a directory where nothing is wrong — looked exactly
like a corrupt key. A permanent false alarm teaches you to stop reading the alarm.

```edn [field-box id="kf_junk_vs_damage" role="properties"]
{"id" "kf_junk_vs_damage" "index" 0 "name" "2 - Junk is not damage" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Junk is not damage" "_tombstone" false "frame" #afield/rect [840.0 40.0 760.0 520.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kf_junk_vs_damage_code_.kt" "lines" 45 "characters" 2272 "sha256" "e8746b71948343c3f4d24718699f816d9d04aa96d543eed546abca72666319e4"}]}
```

```kotlin [field-property box="kf_junk_vs_damage" name="code" path="kf_junk_vs_damage_code_.kt"]
// Two files fail to decode. Only one of them is your problem.
//
// This directory holds four good keys, two files that were never keys, and one key that Field wrote
// and that is now truncated. Before, all three failures were one red list, so a `.DS_Store` — a file
// the operating system wrote without asking, in a directory where nothing is wrong — looked exactly
// like a corrupt key. A permanent false alarm teaches you to stop reading the alarm.

import afield.stdlib.KeyframeCollection
import afield.stdlib.KeyframeScope
import afield.stdlib.everyKeyGlob
import java.nio.file.Files

val dir = Files.createTempDirectory("keyframe-junk-vs-damage")
val kfs = KeyframeScope()

// Four keys, saved properly.
listOf("a" to 1f, "b" to 2f, "c" to 3f, "d" to 4f).forEach { (n, r) ->
    kfs.saveKey(dir.resolve(n), kfs.Key(mutableMapOf("glow@radius" to r)), "look $n")
}

// One key, saved properly and then truncated — a write that stopped halfway.
kfs.saveKey(dir.resolve("e"), kfs.Key(mutableMapOf("glow@radius" to 5f)), "look e")
val e = dir.resolve("e")
Files.writeString(e, Files.readString(e).take(14))

// Two files that were never keys.
Files.writeString(dir.resolve(".DS_Store"), "Bud1 (not a key)")
Files.writeString(dir.resolve("notes.txt"), "remember to try the wider glow")

val c = KeyframeCollection.load(dir, everyKeyGlob)

println("rows:     " + c.files.map { it.relative })
println("problems: " + c.problems)
println("ignored:  " + c.skipped.filter { it.kind.name == "NotAKey" }.map { it.relative })
println("notice:   " + c.skipSummary())
println()
// The ownership test, spelled out. `e` is not valid JSON any more, so a content-only ladder would
// have filed it as "not JSON, not a key" — the important case, backwards. What proves Field wrote it
// is the `.versions` directory still sitting beside it, which is also exactly what you now want.
println("e is unparseable, and e.versions still holds " +
        (dir.resolve("e.versions").toFile().list()?.count { it.endsWith(".json") } ?: 0) +
        " file(s) — so the damage is recoverable, and the row says to open the version rail")

// One red row, two grey ones. Hover the notice to read the per-file reasons.
keyframeBrowser(dir, channel = "junk is not damage", scope = kfs, height = 380)
```

## 2. 3 - A saved slot that applies to nowhere

<!-- field-box id="kf_orphaned_slots" index="1" -->

### Narrative

From `code`

A saved slot that applies to nowhere.

The browser has always drawn one half of this: a slot the scope watches that no key holds — the
"you have never saved this" row. The other half was silent. A slot a *key* holds that the scope no
longer watches renders as an ordinary row, with its real saved range across every key, and
`KeyframeScope.loadKey` is explicit about what happens next: "a file whose values have since left
the scope loads fine — the extra names simply have nowhere to go on apply."

So a rename half-done looks exactly like a working slot, and Apply quietly drops it.

```edn [field-box id="kf_orphaned_slots" role="properties"]
{"id" "kf_orphaned_slots" "index" 1 "name" "3 - A saved slot that applies to nowhere" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - A saved slot that applies to nowhere" "_tombstone" false "frame" #afield/rect [40.0 600.0 760.0 540.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kf_orphaned_slots_code_.kt" "lines" 64 "characters" 2945 "sha256" "64804ca66f37c662e8eaf23f552c7f6ca740fc84f2c1a4b7281bc4072c022a32"}]}
```

```kotlin [field-property box="kf_orphaned_slots" name="code" path="kf_orphaned_slots_code_.kt"]
// A saved slot that applies to nowhere.
//
// The browser has always drawn one half of this: a slot the scope watches that no key holds — the
// "you have never saved this" row. The other half was silent. A slot a *key* holds that the scope no
// longer watches renders as an ordinary row, with its real saved range across every key, and
// `KeyframeScope.loadKey` is explicit about what happens next: "a file whose values have since left
// the scope loads fine — the extra names simply have nowhere to go on apply."
//
// So a rename half-done looks exactly like a working slot, and Apply quietly drops it.

import afield.stdlib.KeyframeCollection
import afield.stdlib.KeyframeScope
import afield.stdlib.everyKeyGlob
import afield.four.buffers.Structure
import java.nio.file.Files

val dir = Files.createTempDirectory("keyframe-orphaned-slots")

// Three keys saved back when the pass had a `blur` as well as a `radius`.
run {
    val old = Structure()
    val past = KeyframeScope()
    past.target("glow@radius", old)
    past.target("glow@blur", old)
    listOf(1f to 4f, 6f to 5f, 11f to 6f).forEachIndexed { i, (r, b) ->
        old.bind("radius") { r }
        old.bind("blur") { b }
        past.capture().save(dir.resolve(listOf("a", "b", "c")[i]), "look ${listOf("a", "b", "c")[i]}")
    }
}

// The pass as it is today: `blur` is gone, and a `feather` has arrived that nothing has ever saved.
val glow = Structure()
glow.bind("radius") { 7f }
glow.bind("feather") { 0.5f }
val kfs = KeyframeScope()
kfs.target("glow@radius", glow)
kfs.target("glow@feather", glow)

val c = KeyframeCollection.load(dir, everyKeyGlob, kfs)

println("slots in view:      " + c.slotNames)
println("watched by the scope: " + c.scopeSlots.sorted())
println("saved, and wired to nothing: " + c.orphanedSlots + "   <- Apply drops these")
println()
c.slots().forEach { s ->
    val tag = when {
        s.orphaned -> "ORPHANED  (saved in ${s.presentIn}/${s.total} keys, applies to nowhere)"
        s.inScope && s.presentIn == 0 -> "never saved (the scope watches it, no key holds it)"
        else -> "live"
    }
    println("  ${s.name.padEnd(14)} ${tag}")
}
println()
// The flag is not just "scopeSlots is empty". A read-only browse cannot know which slots are live,
// and must not accuse every row; an attached scope watching nothing must accuse every row. Both
// leave scopeSlots empty, so `scopeAttached` is what tells them apart.
val readOnly = KeyframeCollection.load(dir, everyKeyGlob, scope = null)
println("read-only browse: scopeAttached=${readOnly.scopeAttached}, orphaned=${readOnly.orphanedSlots}")
println("  (no scope, so no accusation — this is why scopeAttached exists as its own flag)")

// `glow@blur` is struck through and named in the header; `glow@radius` is plain; `glow@feather` is
// the amber "never saved" row this browser already had.
keyframeBrowser(dir, channel = "saved, but wired to nothing", scope = kfs, height = 400)
```

## 3. 1 - Six keys, and the pattern that showed two

<!-- field-box id="kf_before_after" index="2" -->

### Narrative

From `code`

Six saved keys, and whether the browser can show them to you.

The names are not invented. They are copied from a live session: `a`, `b`, `c`, `d`, `d2`, `d3`,
saved with `kfs.capture().save("d3".path)` — which writes a file called `d3`, with no extension,
because `saveKey` writes the path it is handed and appends nothing.

Run this box. Two browsers appear. The top one is what you used to get.

```edn [field-box id="kf_before_after" role="properties"]
{"id" "kf_before_after" "index" 2 "name" "1 - Six keys, and the pattern that showed two" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Six keys, and the pattern that showed two" "_tombstone" false "frame" #afield/rect [40.0 40.0 760.0 520.0] "preamble" "" "nested" #{}} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kf_before_after_code_.kt" "lines" 69 "characters" 3492 "sha256" "8825e27eafc628a115c08c61f3476052adaf98d21ce04f0d01ef47bde273f076"}]}
```

```kotlin [field-property box="kf_before_after" name="code" path="kf_before_after_code_.kt"]
// Six saved keys, and whether the browser can show them to you.
//
// The names are not invented. They are copied from a live session: `a`, `b`, `c`, `d`, `d2`, `d3`,
// saved with `kfs.capture().save("d3".path)` — which writes a file called `d3`, with no extension,
// because `saveKey` writes the path it is handed and appends nothing.
//
// Run this box. Two browsers appear. The top one is what you used to get.

import afield.stdlib.KeyframeCollection
import afield.stdlib.KeyframeScope
import afield.stdlib.everyKeyGlob
import afield.four.buffers.Structure
import java.nio.file.Files

// A temp directory, so this demo writes nothing into your document.
val dir = Files.createTempDirectory("keyframe-key-discovery")

// A live scope, so both browsers get Apply and the "you have never saved this" row.
val glow = Structure()
glow.bind("radius") { 4.0f }
glow.bind("intensity") { 0.35f }
val kfs = KeyframeScope()
kfs.target("glow@radius", glow)
kfs.target("glow@intensity", glow)

// Six keys, saved the ordinary way — through the real writer, so each gets a real `.versions`
// history beside it.
listOf(1f, 3f, 6f, 10f, 15f, 21f).forEachIndexed { i, r ->
    val name = listOf("a", "b", "c", "d", "d2", "d3")[i]
    glow.bind("radius") { r }
    glow.bind("intensity") { 0.1f + i * 0.15f }
    kfs.capture().save(dir.resolve(name), "look $name")
}

// And the file macOS leaves in any folder a Finder window has opened. Nobody asked for it.
Files.writeString(dir.resolve(".DS_Store"), "Bud1 (this is not a key)")

// ---------------------------------------------------------------------------------------------
// The numbers, before the pictures.
// ---------------------------------------------------------------------------------------------

val old = KeyframeCollection.load(dir, "**/*.json")
val narrow = KeyframeCollection.load(dir, "??")
val now = KeyframeCollection.load(dir, everyKeyGlob)

println("keys on disk: " + dir.toFile().list()!!
    .filter { !it.endsWith(".versions") && !it.startsWith(".") }.sorted())
println("plus, uninvited: " + dir.toFile().list()!!.filter { it.startsWith(".") }.sorted())
println()
println("the old default, '**/*.json':  ${old.files.size} keys  <- 'No key files matched'")
println("the last pattern tried, '??':  ${narrow.files.size} keys  (${narrow.skipSummary()})")
println("brace alternation, '{?,??}':   ${KeyframeCollection.load(dir, "{?,??}").files.size} keys" +
        "  <- this always worked, and nothing said so")
println("the default now, '$everyKeyGlob':          ${now.files.size} keys  (${now.skipSummary()})")
println()
println("what '??' hid: " + narrow.skipped.filter { it.kind.name == "Hidden" }.map { it.relative })
println("problems, either way: ${now.problems.size}  <- .DS_Store is ignored, not an error")

// ---------------------------------------------------------------------------------------------
// The two browsers. Compare the headers.
// ---------------------------------------------------------------------------------------------

// BEFORE: the pattern the session ended on. Two keys of six, and — this is the fix — a line under
// the header saying so, with a button that widens it in one click.
keyframeBrowser(dir, pattern = "??", channel = "before: pattern = \"??\"", scope = kfs, height = 320)

// AFTER: the default. Every key at any depth, with the OS turd counted as ignored rather than
// reported as a problem.
keyframeBrowser(dir, channel = "after: the default", scope = kfs, height = 380)
```

