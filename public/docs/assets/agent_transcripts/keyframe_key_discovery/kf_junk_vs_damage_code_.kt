// Two files fail to decode. Only one of them is your problem.
//
// This directory holds four good keys, two files that were never keys, and one key that Field wrote
// and that is now truncated. A `.DS_Store` — a file the operating system wrote without asking — is
// not a corrupt key, and a list that files both under one red heading is a permanent false alarm.
// A permanent false alarm teaches you to stop reading the alarm.

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
// have filed it as "not JSON, not a key": a damaged key reported as junk, which is the
// misclassification that loses work. What proves Field wrote it
// is the `.versions` directory still sitting beside it, which is also exactly what you now want.
println("e is unparseable, and e.versions still holds " +
        (dir.resolve("e.versions").toFile().list()?.count { it.endsWith(".json") } ?: 0) +
        " file(s) — so the damage is recoverable, and the row says to open the version rail")

// One red row, two grey ones. Hover the notice to read the per-file reasons.
keyframeBrowser(dir, channel = "junk is not damage", scope = kfs, height = 380)
