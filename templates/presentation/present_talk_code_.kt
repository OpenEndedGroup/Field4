import kotlinx.html.*

val talkDocument = inside.documentName
val waveExperiment = inside.root.children.single { it.name == "Wave experiment" }
val waveBaseline = LessonCheckpoint.capture(waveExperiment)
val lessonDirectory = files.documentDirectory.toString()
fun runWaveExperiment() {
    val result = OperationTranscript.runMacro(lessonDirectory, "run_wave")
    check(result.success) { result.toString() }
}

presentation(title = "Teaching with Field") {
        section("Opening") {
            step("Field / present") {
                audience {
                    stylesheet("presentation.css")
                    p(classes = "muted") { +"A LIVE TEACHING ENVIRONMENT" }
                    h1 { +"FIELD / PRESENT" }
                    p { +"The talk and the working material, together." }
                    hr {}
                    p(classes = "muted") { +"Use Next in the presenter controls." }
                }
                markdownNote("The audience has its own window. These notes stay in your Field document. [[next]]")
            }
            step("Work in the open") {
                audience {
                    stylesheet("presentation.css")
                    h1 { +"Work in the open." }
                    div(classes = "columns") {
                        div {
                            h2 { +"Explain" }
                            p { +"A slide can be a few lines of Kotlin and HTML." }
                        }
                        div {
                            h2 { +"Explore" }
                            p { +"Bring the actual webpage or document on screen." }
                        }
                        div {
                            h2 { +"Change" }
                            p { +"Run the code. Follow the question." }
                        }
                    }
                }
                markdownNote(
                    "Edit the words in the Presentation box and run it again. It keeps the current step. [[back]] [[next]]",
                )
            }
        }
        section("Pictures and ideas") {
            step("A figure, fullscreen") {
                audience {
                    stylesheet("presentation.css")
                    fullScreenImage(
                        "wave.svg",
                        alt = "A white sine wave on black",
                        citation = "Field / Present · sine wave study · editable SVG",
                    )
                }
                reveal("Look more closely", dimPrevious = 0.25) {
                    fadeIn(durationMs = 600) {
                        h1 { +"One curve. Many questions." }
                        p { +"Keep the figure in view while adding the next idea." }
                    }
                }
                markdownNote(
                    "One image, with its credit. The figure stays a shared file, not a copy inside a slide. [[back]] [[next]]",
                )
            }
            step("A figure and an idea") {
                audience {
                    stylesheet("presentation.css")
                    imageBeside("wave.svg", alt = "A sine wave", citation = "Field / Present · sine wave study") {
                        +"""
                        # A small change.
                        The picture and the explanation live together.

                        1. Predict the effect of a frequency change.
                        2. Try it in the live document.
                    """
                            .md
                    }
                }
                markdownNote(
                    "Markdown mixes with HTML and image layouts. Edit presentation.css in its Field box, then Refresh slide. [[back]] [[next]]",
                )
            }
        }
        // Editable Markdown slides: AI4 can revise one slide without rewriting this Kotlin.
        slides("talk.slides.json")
        section("A repeatable experiment") {
            step("Predict a wave", id = "wave-experiment") {
                audience {
                    stylesheet("presentation.css")
                    +"# Twice the frequency?\nPredict what happens to the wavelength.".md
                }
                markdownNote(
                    "Next reveals the explanation, then opens the experiment. Change frequency and run Wave experiment. Reset restores only its source and runs setup again. [[back]] [[next]]",
                )
                cue("Explain") {
                    audience {
                        stylesheet("presentation.css")
                        +"# Half the wavelength.\nAt the same propagation speed, twice the frequency means half the wavelength."
                            .md
                    }
                }
                cue("Try it live") {
                    audienceDocument(talkDocument, macro = "run_wave", interactiveGuide = false)
                    onEnter { runWaveExperiment() }
                    selectBox(waveExperiment)
                }
                checkpoint("Wave baseline") {
                    waveBaseline.restore()
                    runWaveExperiment()
                }
            }
        }
        section("Working material") {
            step("A real webpage") {
                audienceWebpage("https://example.org/")
                markdownNote("This is a real browser view. Navigate and interact with it directly. [[back]] [[next]]")
            }
            step("A live Field document") {
                audienceDocument(talkDocument, macro = "reveal_wave")
                markdownNote(
                    "Use **Run reveal_wave** to build a wave, or **Start document guide** to teach through the code. [[back]] [[next]]",
                )
            }
            step("YouTube") {
                audienceYouTube("M7lc1UVf-VE")
                markdownNote("Replace this embed test video with one for your lesson. [[back]] [[next]]")
            }
        }
        step("Keep teaching") {
            audience {
                stylesheet("presentation.css")
                h1 { +"Keep teaching." }
                p { +"Slides, examples, guides, macros and media belong with the lesson." }
                hr {}
                p(classes = "muted") { +"Save the document. Everything stays editable." }
            }
            markdownNote(
                "Use the previous steps to revisit an example. Close the audience or controller when finished. [[back]] [[finish]]",
            )
        }
    }
    .present()
