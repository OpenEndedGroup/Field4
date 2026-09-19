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
