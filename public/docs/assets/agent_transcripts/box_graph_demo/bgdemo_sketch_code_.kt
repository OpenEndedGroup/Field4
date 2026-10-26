val ink = fromContext<java.awt.Color>()
val title = fromContextWithNameOrNull<String>("title")
val missing = fromContextWithNameOrNull<String>("subtitle")
markdown("ink = (${ink.red}, ${ink.green}, ${ink.blue}), title = $title, subtitle = $missing")
