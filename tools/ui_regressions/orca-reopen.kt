// No grid this time: the box reopens the one saved by the previous run's write. The AI tools,
// single cells, a held variable, the change feed and JavaScript in the page are driven from Kotlin, as a player would.
val o = orca(cols = 24, rows = 10)
val tools = o.exposeTools(about = "channel 0 is the melody")
val synth = PatSynthBank()
var played = 0
var changed = 0
o.onNote { synth.play(it); played++ }
o.onChange { changed++ }
go {
    o.sync(10_000)
    fun tool(name: String) = tools.single { it.name == name }
    val written = tool("orca_write")(20, 0, "#ai#")
    val read = tool("orca_read")()
    val look = tool("orca_look")().toString()
    o[22, 1] = 'x'
    o.variable('z', '7')
    val immediate = o[22, 1]
    o.sync()
    val width = o.eval("client.orca.w")
    val failed = runCatching { o.eval("nope()") }.exceptionOrNull()?.message.orEmpty().contains("nope is not defined")
    o.js("client.theme.set('b_med', '#ff4fa0')")
    o.sync()
    display("tools", """<pre id="orca-tools">{"written":${"#ai#" in written.toString()},""" +
        """"read":${"#ai#" in read.toString() && "D4" in read.toString()},"names":"${tools.joinToString(",") { it.name }}",""" +
        """"played":$played,"immediate":"$immediate","reported":"${o[22, 1]}","changed":$changed,"width":$width,"failed":$failed,"look":${"data:image/png;base64," in look && "#ai#" in look}}</pre>""")
}
displayOpen("tools")
