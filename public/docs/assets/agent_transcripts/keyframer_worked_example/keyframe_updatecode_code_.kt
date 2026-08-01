// The last step of the loop closes it: Key.updateCode() emits the snapshot as pasteable
// Kotlin, so a state you arrived at by fiddling becomes a diff you can commit. Every
// literal is chosen so the paste round-trips as the same TYPE, not merely the same number.
import afield.stdlib.KeyframeScope
import afield.stdlib.SourceBackedBlock
import afield.stdlib.stackSLString
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f

val scope = KeyframeScope()
val key = scope.Key(mutableMapOf(
    "gain" to 0.65f,           // a Float — and the 'f' suffix is load-bearing
    "steps" to 12,
    "seed" to 99L,
    "pos" to Vector3f(1.5f, 0f, -2.25f),
    "spin" to Quaternionf().setAngleAxis(0.7f, 0f, 1f, 0f),
    "voice" to stackSLString("sin 220 * fold"),
    // A source block whose body mentions a '$'. Emitting this as a Kotlin *raw* string
    // would have let the '$' interpolate at paste time — a real defect, fixed alongside
    // public/docs/keyframer_examples.md. It must come out escaped.
    "block" to SourceBackedBlock<Any?>("box-1", "val total = 2\nprintln(\"sum: \$total\")"),
    "notes" to listOf("unsupported", "on purpose")
))

val code = key.updateCode("look")
println(code)

// The round trip is about types: without the 'f', a captured Float pastes back a Double,
// and Key.set deliberately does not re-coerce numbers.
println("Float keeps its suffix: " + code.contains("0.65f"))
println("Long keeps its suffix:  " + code.contains("99L"))
println("no bare '\$' escaped the emitter: " + Regex("""(?<!\\)\$""").findAll(code).none())

fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
display(
    "code",
    "<pre style='font:12px/1.5 Iosevka, Menlo, monospace;color:#d4d4d4;margin:0;padding:8px 10px;" +
        "background:rgba(255,255,255,0.04);border-left:2px solid #555;white-space:pre-wrap;" +
        "width:820px'>" + esc(code) + "</pre>"
)
