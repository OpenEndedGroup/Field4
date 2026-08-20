// saveKey writes pretty JSON — and, every single time, ALSO appends a numbered copy to a
// <file>.versions directory with a manifest. Nothing prunes it, so a hundred saves leave a
// hundred files. What survives the trip is a closed set; everything else is dropped silently.
import afield.stdlib.KeyframeScope
import afield.stdlib.stackSLString
import afield.stdlib.linalg.Vector3f
import java.nio.file.Files

val dir = Files.createTempDirectory("keyframer-demo")
val path = dir.resolve("look.json")

val scope = KeyframeScope(schemaVersion = 1)
scope.saveKey(path, scope.Key(mutableMapOf(
    "gain" to 0.20f,
    "pos" to Vector3f(1f, 2f, 3f),
    "voice" to stackSLString("saw 110 *")
)), note = "first pass")

scope.saveKey(path, scope.Key(mutableMapOf(
    "gain" to 0.65f,
    "pos" to Vector3f(4f, 5f, 6f),
    "voice" to stackSLString("sin 220 * fold")
)), note = "brighter")

// A third save that also carries values the KValue union cannot represent.
val lossy = scope.Key(mutableMapOf(
    "gain" to 0.90f,
    "enabled" to true,          // a Boolean is outside the union
    "taps" to listOf(1, 2, 3),  // so is a list
    "absent" to null            // and a null is dropped too
))
scope.saveKey(path, lossy, note = "with unsupported values")

val versions = scope.keyVersions(path)
versions.forEach { println("version ${it.index}  ${it.file}  ${it.note ?: "—"}") }

val reloaded = scope.loadKey(path)
val dropped = lossy.values.keys.sorted().filter { it !in reloaded.values.keys }
println("third save kept: " + reloaded.values.keys.sorted())
println("dropped in silence: " + dropped + "  <- no exception, no log line")

// An older snapshot loaded into a scope that has since grown a parameter.
val newer = KeyframeScope(schemaVersion = 1)
newer.missingKey("wet", 0.5f)              // applies to every file this scope loads
newer.missingKey(1, "mode", "legacy-v1")   // only to files stamped schemaVersion = 1
val migrated = newer.loadKey(path, version = 0)
println("version 0 migrated: " + migrated.values.toSortedMap())

// Rows built with joinToString and spliced into an indented block: markdown()'s dedent
// anchors on the first line, so the rows arriving at column 0 do not flatten the figure the
// way trimIndent would. The values table stays flat text rather than `migrated.h` — the
// Key's own HTML gives a Vector3f an interactive 3D axis widget, which reflows the card.
val versionRows = versions.joinToString("\n") { v ->
    "| ${v.index} | ${mdEscape(v.file)} | ${mdEscape(v.note ?: "—")} |"
}
val migratedRows = migrated.values.entries.sortedBy { it.key }.joinToString("\n") { (k, v) ->
    "| ${mdEscape(k)} | ${mdEscape(v)} |"
}

markdown("persist", """
    #### version history after three saves

    | # | file | note |
    | --- | --- | --- |
    $versionRows

    #### third save: what survived

    kept: **${reloaded.values.keys.sorted().joinToString(", ")}**

    dropped in silence: {bad:${dropped.joinToString(", ")}}

    #### version 0, reloaded into a scope that has since grown a 'wet' parameter (${migrated.values.size} values)

    | key | value |
    | --- | --- |
    $migratedRows

    #### version 0 on disk, as written

    ~~~json
${Files.readString(dir.resolve("look.json.versions").resolve("000000.json"))}
    ~~~
""", width = 820)
