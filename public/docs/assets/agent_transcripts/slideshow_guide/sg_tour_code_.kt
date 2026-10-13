// A four-step guide to this document. Run it, then follow the links in the note.
val guide = thisBox
val numbersBox = inside.boxes.values.first { it.name == "numbers" }
val squaresBox = inside.boxes.values.first { it.name == "squares" }
var visits = 0
var stars = 0
//%-

val show = slideShow {
    step("Welcome") {
        markdownNote(
            """
            ## A tour of this document
            This guide has four steps. Each one selects the box it is about.

            [[next:Start →]]
            """.trimIndent(),
            animated = true
        )
        selectBox(guide)
    }
    step("numbers") {
        onEnter {
            visits += 1
            guide.properties["visits"] = visits
        }
        markdownNote(
            """
            ## `numbers` makes a list
            The green line points at it. Visits to this step: **{{visits}}**.

            [[back]] [[next]]
            """.trimIndent(),
            animated = true
        )
        selectBox(numbersBox)
        annotate(id = "numbers", targetBox = numbersBox, color = "rgba(128, 255, 164, 0.8)", dashed = false)
    }
    step("squares") {
        markdownNote(
            """
            ## Line 3 does the work
            `squares` maps each number to its square.

            [[back]] [[next]]
            """.trimIndent(),
            animated = true
        )
        selectBox(squaresBox, property = "code", line = 3, highlight = 3)
        annotateToLine(id = "line3", targetBox = squaresBox, line = 3, color = "rgba(255, 180, 100, 0.8)", dashed = true)
    }
    step("Your turn") {
        markdownNote(
            """
            ## Your turn
            [Give this guide a star](#custom:star). Stars so far: **{{stars}}**.

            [[home:Start again]] [[finish]]
            """.trimIndent(),
            animated = true
        )
        linkCallback("star") {
            stars += 1
            here.properties["stars"] = stars
            show.goToStep(show.currentStepIndex)
        }
        selectBox(guide)
    }
}

runBlocking { show.start() }
