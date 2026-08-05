import afield.stdlib.extractParameters
import afield.stdlib.graphNode
import afield.stdlib.graphText

// Each atom is a kernel: g = Gaussian, adsr = a full envelope. The numbers in the
// text are the gesture; `cps` is how fast the pattern runs in seconds.
val gesture = patFn("g:(0.3,0.12) adsr:(0.8,0.01,0.08,0.4,0.2)", cps = 2.0, range = 0.9)
val node = gesture.graphNode()

val report = """
op        ${node.op}
source    ${node.strings["source"]}
knobs     ${node.children.joinToString("  ") { it.role }}
values    ${gesture.extractParameters().joinToString("  ")}

${gesture.graphText()}
""".trimIndent()

println(report)
display("<pre>" + report + "</pre>")
