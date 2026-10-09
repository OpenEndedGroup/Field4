/*
The last step of the loop closes it: Key.updateCode() emits the snapshot as pasteable
Kotlin, so a state you arrived at by fiddling becomes a diff you can commit. Every
literal is chosen so the paste round-trips as the same TYPE, not merely the same number.
*/
import afield.stdlib.KeyframeScope
import afield.stdlib.SourceBackedBlock
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import afield.stdlib.stackSLString

val scope = KeyframeScope()
val key = scope.Key(
    mutableMapOf(
        "gain" to 0.65f, // a Float — and the 'f' suffix is load-bearing
        "steps" to 12,
        "seed" to 99L,
        "pos" to Vector3f(1.5f, 0f, -2.25f),
        "spin" to Quaternionf().setAngleAxis(0.7f, 0f, 1f, 0f),
        "voice" to stackSLString("sin 220 * fold"),
        /*
        A source block whose body mentions a '$'. Emitted inside a Kotlin raw string it
        would interpolate at paste time, so it must come out escaped.
        */
        "block" to SourceBackedBlock<Any?>("box-1", "val total = 2\nprintln(\"sum: \$total\")"),
        "notes" to listOf("unsupported", "on purpose"),
    ),
)

val code = key.updateCode("look")
println(code)

// The round trip is about types: without the 'f', a captured Float pastes back a Double,
// and Key.set deliberately does not re-coerce numbers.
println("Float keeps its suffix: " + code.contains("0.65f"))
println("Long keeps its suffix:  " + code.contains("99L"))
// Check only the source-block line: the /* unsupported */ comment names the dropped Java
// class, and a nested name like Arrays$ArrayList has a harmless '$' inside that comment.
val blockLine = code.lines().first { it.contains("""this["block"]""") }
println(
    "the source block's $ came out escaped: " + Regex("""(?<!\\)\$""").findAll(blockLine).none(),
)

display("code", "<pre>" + code + "</pre>")
