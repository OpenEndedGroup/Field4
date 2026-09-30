package afield

/** Latest rendered panels owned by a live box; never persisted or used to execute code. */
internal class OutputAreaReplay(private val maxCharacters: Int = 8_000_000) {
    private data class Panel(val html: String, var open: Boolean? = null)
    private val panels = linkedMapOf<String, Panel>()
    private val visibility = mutableMapOf<String, Boolean>()

    @Synchronized fun put(channel: String, html: String) {
        val open = panels[channel]?.open ?: visibility[channel]
        panels[channel] = Panel(html, open)
        while (panels.values.sumOf { it.html.length.toLong() } > maxCharacters || panels.size > 128) {
            panels.remove(panels.keys.first())
        }
    }

    @Synchronized fun control(action: String, channel: String?) {
        when (action) {
            "clear-all" -> { panels.clear(); visibility.clear() }
            "clear" -> { panels.remove(channel); visibility.remove(channel) }
            "open", "close" -> if (channel != null) {
                visibility[channel] = action == "open"
                panels[channel]?.open = action == "open"
            }
        }
    }

    /** Channels that currently hold a panel, in insertion order. */
    @Synchronized
    fun channels(): List<String> = panels.keys.toList()

    @Synchronized fun snapshot(uid: String): List<Message> = buildList {
        panels.forEach { (channel, panel) ->
            add(Message("set-output").payload(Set_output(uid, "outputarea", channel, panel.html)))
            panel.open?.let { open ->
                add(Message("output-control").payload(OutputControl(uid, if (open) "open" else "close", channel)))
            }
        }
    }
}
