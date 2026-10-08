// No grid this time: the box reopens the one saved by the previous run's write. The AI tools,
// single cells, a held variable and the change feed are driven from Kotlin, as a player would.
val o = orca(cols = 24, rows = 10)
val tools = o.exposeTools(iframeTools = false)
val synth = PatSynthBank()
var played = 0
var changed = 0
o.onNote { synth.play(it); played++ }
o.onChange { changed++ }
go {
    o.sync(10_000)
    val written = tools[1](20, 0, "#ai#")
    val read = tools[0]()
    o[22, 1] = 'x'
    o.variable('z', '7')
    val immediate = o[22, 1]
    o.sync()
    display("tools", """<pre id="orca-tools">{"written":${"#ai#" in written.toString()},""" +
        """"read":${"#ai#" in read.toString() && "D4" in read.toString()},"names":"${tools.joinToString(",") { it.name }}",""" +
        """"played":$played,"immediate":"$immediate","reported":"${o[22, 1]}","changed":$changed}</pre>""")
}
displayOpen("tools")
