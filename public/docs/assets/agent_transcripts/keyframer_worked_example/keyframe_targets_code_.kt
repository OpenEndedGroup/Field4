// A KeyframeScope holds no values of its own. It holds *targets*: places to read from and
// write to. The simplest is a uniform Structure — and none of this needs a GPU, because
// Structure is constructible bare and bind/bindings are plain maps.
import afield.four.buffers.Structure
import afield.stdlib.KeyframeScope

val glow = Structure()
val blur = Structure()
glow.bind("radius") { 4.0f }
glow.bind("intensity") { 0.35f }
blur.bind("radius") { 12.0f }

val scope = KeyframeScope()
// The part after the last '@' is the property driven on the structure. Both passes have a
// `radius`; the qualifier is the only thing keeping them apart inside the key.
scope.target("glow@radius", glow)
scope.target("glow@intensity", glow)
scope.target("blur@radius", blur)

// A snapshot. Reading only — capture() writes nothing anywhere.
val captured = scope.capture()
println("captured: " + captured.values.toSortedMap())

// Fiddle, as you would with the sketch running, then make it so.
val edited = captured.copy().update {
    this["glow@radius"] = 9.0f
    this["glow@intensity"] = 0.80f
}
edited()
println(captured.diffText(edited, "captured", "edited"))

// Read the live structures back. Note the write landed on the BARE property name.
println("glow.radius    = " + glow.bindings["radius"]?.invoke())
println("glow.intensity = " + glow.bindings["intensity"]?.invoke())
println("blur.radius    = " + blur.bindings["radius"]?.invoke())
println("glow.'glow@radius' = " + glow.bindings["glow@radius"]?.invoke() + "  <- never bound")

// THE TRAP: capture() reads Structure.bindings and never Structure.values. A property
// written by value rather than bound is invisible to a snapshot, and says nothing.
val sneaky = Structure()
sneaky.bind("bound") { 1.0f }
sneaky["set"] = 2.0f
val trapScope = KeyframeScope()
trapScope.target("bound", sneaky)
trapScope.target("set", sneaky)
val trap = trapScope.capture()
println("bound -> " + trap["bound"] + " ; set -> " + trap["set"] + " (present: " + ("set" in trap) + ")")

// Put the original back and re-capture: a Key is a complete description of the scope.
captured()
val restored = scope.capture()

fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
fun row(k: String, v: Any?) =
    "<tr><td style='padding:1px 14px 1px 0;color:#9cdcfe'>" + esc(k) +
        "</td><td style='color:#d4d4d4'>" + esc(v.toString()) + "</td></tr>"

display(
    "keys",
    "<div style='font:12px/1.5 Iosevka, Menlo, monospace;color:#d4d4d4;display:grid;gap:14px;width:800px'>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>1 &mdash; capture()</div>" +
        captured.h + "</div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>2 &mdash; edited, then applied</div>" +
        captured.diffHtml(edited, "captured", "edited") + "</div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>3 &mdash; the live structures, read back</div>" +
        "<table style='border-collapse:collapse'>" +
        row("glow.radius", glow.bindings["radius"]?.invoke()) +
        row("glow.intensity", glow.bindings["intensity"]?.invoke()) +
        row("blur.radius", blur.bindings["radius"]?.invoke()) +
        row("glow.glow@radius", glow.bindings["glow@radius"]?.invoke() ?: "<unbound>") +
        "</table></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>4 &mdash; the bindings/values trap</div>" +
        "<table style='border-collapse:collapse'>" +
        row("bound (via bind)", trap["bound"]) +
        row("set (via values)", trap["set"].toString() + "  <- captured as null, silently") +
        "</table></div>" +
        "<div><div style='color:#c586c0;margin-bottom:4px'>5 &mdash; captured() again, then re-captured</div>" +
        esc(captured.diffText(restored, "captured", "restored")) + "</div>" +
    "</div>"
)
