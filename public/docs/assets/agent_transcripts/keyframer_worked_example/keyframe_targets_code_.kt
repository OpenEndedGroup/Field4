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

// capture() reads binding-first-then-value, so both of these land in the key. It used to
// read Structure.bindings alone, and `set` snapshotted as a silent null.
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

// markdown() rather than a hand-built card: headings for the captions, GFM tables for the
// key/value pairs, a fence for the multi-line diff. `key.h` and diffHtml() are HTML from
// other Field APIs and are *meant* to pass through, so they interpolate directly; every
// computed value carries `.md`, which a table cell effectively requires.
fun bound(s: Structure, k: String) = s.bindings[k]?.invoke().md

markdown("keys", """
    #### 1 — capture()
    ${captured.h}

    #### 2 — edited, then applied
    ${captured.diffHtml(edited, "captured", "edited")}

    #### 3 — the live structures, read back

    | binding | value |
    | --- | --- |
    | glow.radius | ${bound(glow, "radius")} |
    | glow.intensity | ${bound(glow, "intensity")} |
    | blur.radius | ${bound(blur, "radius")} |
    | glow.glow@radius | ${(glow.bindings["glow@radius"]?.invoke() ?: "<unbound>").md} |

    #### 4 — the bindings/values trap

    | binding | value |
    | --- | --- |
    | bound (via bind) | ${trap["bound"].md} |
    | set (via values) | ${trap["set"].md} {warn:← captured as null, silently} |

    #### 5 — captured() again, then re-captured

    ~~~
${captured.diffText(restored, "captured", "restored")}
    ~~~
""", width = 800)
