// Put whatever you want to watch into `watched` and open its rows in the tree below.
// Launch this box to keep the open rows current, twice a second.
val watched = linkedMapOf<String, Any?>()
tree("watch", watched)

loop {
    watched["boxes"] = root.children.map { it.name }
    watched["time"] = java.time.LocalTime.now().withNano(0)
    delay(500)
}
