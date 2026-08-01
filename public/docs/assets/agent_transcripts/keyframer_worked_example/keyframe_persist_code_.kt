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

fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
val versionRows = versions.joinToString("") { v ->
    "<tr><td style='padding:1px 14px 1px 0;color:#9cdcfe'>${v.index}</td>" +
        "<td style='padding-right:14px;color:#888'>${v.file}</td>" +
        "<td style='color:#d4d4d4'>${esc(v.note ?: "—")}</td></tr>"
}
// Rendered as flat text rather than through `migrated.h`: the Key's own HTML gives a
// Vector3f an interactive 3D axis widget, which is lovely in a box and reflows the card.
val migratedRows = migrated.values.entries.sortedBy { it.key }.joinToString("") { (k, v) ->
    "<tr><td style='padding:1px 14px 1px 0;color:#9cdcfe'>" + esc(k) + "</td>" +
        "<td style='color:#d4d4d4'>" + esc(v.toString()) + "</td></tr>"
}

display(
    "persist",
    "<div style='font:12px/1.5 Iosevka, Menlo, monospace;color:#d4d4d4;display:grid;gap:14px;width:820px'>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>version history after three saves</div>" +
        "<table style='border-collapse:collapse'>" + versionRows + "</table></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>third save: what survived</div>" +
        "kept: <b>" + reloaded.values.keys.sorted().joinToString(", ") + "</b><br>" +
        "dropped in silence: <b style='color:#f88'>" + dropped.joinToString(", ") + "</b></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>version 0, reloaded into a scope that " +
        "has since grown a 'wet' parameter (" + migrated.values.size + " values)</div>" +
        "<table style='border-collapse:collapse'>" + migratedRows + "</table></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>version 0 on disk, as written</div>" +
        "<pre style='margin:0;padding:6px 8px;background:rgba(255,255,255,0.04);" +
        "border-left:2px solid #555;white-space:pre-wrap;width:800px'>" +
        esc(Files.readString(dir.resolve("look.json.versions").resolve("000000.json"))) +
        "</pre></div>" +
    "</div>"
)
