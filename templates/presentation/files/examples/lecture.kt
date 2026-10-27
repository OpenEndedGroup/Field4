// Paste into a new Field box and run. Uses shared files beside this example's parent folder.
import afield.ui.slides.*
import kotlinx.html.*

presentation(title = "Lecture layouts", transitionMs = 450) {
    step("A large image") {
        audience {
            stylesheet("themes/lecture.css")
            fullScreenImage("examples/lecture-comparison.svg",
                alt = "Two editable line studies: a regular field and a field with interruptions",
                citation = "Field · editable SVG line studies · examples/lecture-comparison.svg")
        }
    }
    step("A figure and some text") {
        audience {
            stylesheet("themes/lecture.css")
            imageBeside("examples/lecture-lines.svg",
                alt = "A square field of short diagonal lines, with a few intentional gaps",
                citation = "Field · editable line study · examples/lecture-lines.svg") {
                h2 { +"A rule with room for interruption" }
                p { +"A small omission changes the rhythm of the whole field." }
                p(classes = "muted") { +"Make a prediction. Change the rule. Look again." }
            }
        }
    }
    step("Mapping") {
        audience {
            stylesheet("themes/lecture.css")
            div(classes = "lecture-banner") {
                h1 { +"Mapping" }
                p(classes = "lecture-tagline") { +"New physics" }
            }
        }
    }
    step("Media art and design") {
        audience {
            stylesheet("themes/lecture.css")
            div(classes = "lecture-list") {
                h1 { +"Media art and design = everything else" }
                ul {
                    for (topic in ["Net art", "Installation art", "VFX", "Experimental film",
                        "Computer games", "Odd music", "Emerging and dying technologies"]) {
                        li { +topic }
                    }
                }
            }
        }
    }
}.present()
