// A guided tour of the boxes connected beneath this one, top to bottom. Connect the boxes you
// want to show under it, write a line about each in its notes, then run this box.
val stops = children.sortedBy { it.frame.y }

if (stops.isEmpty()) {
    markdown("Connect the boxes to tour beneath this one, then run it again.")
} else {
    val show = slideShow {
        stops.forEachIndexed { i, stop ->
            step(stop.name.ifBlank { "Box ${i + 1}" }) {
                val links = when (i) {
                    0 -> "[[next]]"
                    stops.lastIndex -> "[[back]] [[home:Start again]] [[finish]]"
                    else -> "[[back]] [[next]]"
                }
                markdownNote("## ${stop.name}\n${stop.notes}\n\n$links")
                selectBox(stop)
            }
        }
    }
    runBlocking { show.start() }
}
