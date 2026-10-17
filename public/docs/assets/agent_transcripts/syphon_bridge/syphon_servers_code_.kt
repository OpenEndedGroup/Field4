import afield.io.syphon.*

run {
    if (!Syphon.isAvailable) {
        markdown(Syphon.describe())
        return@run
    }

    val servers = Syphon.servers(waitMs = 1000)
    markdown(
        if (servers.isEmpty()) "No Syphon servers are running."
        else servers.joinToString("\n") { "- **${it.appName}** ${it.name}" },
    )
}
