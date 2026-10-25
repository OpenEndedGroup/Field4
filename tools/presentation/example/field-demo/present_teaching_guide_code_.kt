val teachingGuide = slideShow {
    step("Change the live code") {
        markdownNote("""
            ## A real document on the screen
            The editor shows the source of **Live Field**. Change a sentence and run the box.

            [[next:Next: use the saved macro]]
        """.trimIndent())
        selectBoxByName("Live Field", property = "code", line = 1, highlight = 1)
    }
    step("Build something with a macro") {
        markdownNote("""
            ## Make the next part live
            Press **Run reveal_wave** in the presenter controls. The macro creates and runs a wave box.
            Select **A wave, live**, change `0.17`, and run it again.

            [[back]] [[next]]
        """.trimIndent())
    }
    step("Teach from the working material") {
        markdownNote("""
            ## Keep the working material
            This document, its source, this guide, and the macro live in the talk repository.
            Close this guide and advance the presentation cue to return to the talk.

            [[back]] [[finish]]
        """.trimIndent())
    }
}
Unit
