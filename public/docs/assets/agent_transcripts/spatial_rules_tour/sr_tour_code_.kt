val filament = inside.boxes.values.first { it.name == "1 · One filament" }
val loom = inside.boxes.values.first { it.name == "2 · Loom with memory" }
val torus = inside.boxes.values.first { it.name == "3 · A thousand moving points" }

val show = slideShow {
    step("Welcome") {
        markdownNote(
            """
            ## Spatial rules
            Follow a line from the instant its two inputs meet to the moment it fades. Then build a loom from that rule and watch a thousand points wire themselves into a moving surface.

            The tour selects each box. **Alt+Up** launches the selected box, and **Alt+Down** stops it.

            [[next:Start with two points →]]""".trimIndent(),
        )
        selectBox(thisBox)
    }
    step("A match ends") {
        markdownNote(
            """
            ## 1 · One filament
            Launch this box. Watch the two tips separate, then watch them disappear while still close. The filament remembers its last positions and fades in both cases.

            Click the filament while it fades to see which tips are still present and what kept the line alive. Set `fadeSeconds` to 2.0, then relaunch.

            Stop this box before you continue. [[back]] [[next:Build a loom →]]""".trimIndent(),
        )
        selectBox(filament)
        annotate(id = "filament", targetBox = filament, color = "rgba(122, 213, 255, 0.8)")
    }
    step("Patterns compose") {
        markdownNote(
            """
            ## 2 · Loom with memory
            Launch the loom and watch all three stations for a few seconds. The gold knots come from cyan stitches. Violet echoes run alongside taut stitches. Pale ellipses fit live knots, and the tapered ribbons span their centres.

            Click a gold knot to see its two source tips. Change `departureFade` to set how long old stitches remain on the page.

            Stop the loom before you continue. [[back]] [[next:Scale it up →]]""".trimIndent(),
        )
        selectBox(loom)
        annotate(id = "loom", targetBox = loom, color = "rgba(250, 190, 104, 0.8)")
    }
    step("A thousand moving points") {
        markdownNote(
            """
            ## 3 · A thousand moving points
            Launch the torus. Cyan threads join samples in one ring. Amber threads join different rings. Watch the dense bands travel as the tube breathes and check the live edge count below it.

            Try changing `joinRadius` from 19 to 16 units. Relaunch and compare which threads survive.

            [[home:Start again]] [[finish]]""".trimIndent(),
        )
        selectBox(torus)
        annotate(id = "torus", targetBox = torus, color = "rgba(122, 213, 255, 0.8)")
    }
}
runBlocking { show.start() }
