import afield.ui.slides.*
import afield.document.workspace.file
import kotlinx.html.*

val talkDocument = inside.documentName
val slideStyle = "presentation.css".file.readText()

presentation(title = "Teaching with Field") {
    step("Field / present") {
        audience {
            style = slideStyle
            p(classes = "muted") { +"A LIVE TEACHING ENVIRONMENT" }
            h1 { +"FIELD / PRESENT" }
            p { +"The talk and the working material, together." }
            hr { }
            p(classes = "muted") { +"Use Next in the presenter controls." }
        }
        markdownNote("The audience has its own window. These notes stay in your Field document. [[next]]")
    }
    step("Work in the open") {
        audience {
            style = slideStyle
            h1 { +"Work in the open." }
            div(classes = "columns") {
                div { h2 { +"Explain" }; p { +"A slide can be a few lines of Kotlin and HTML." } }
                div { h2 { +"Explore" }; p { +"Bring the actual webpage or document on screen." } }
                div { h2 { +"Change" }; p { +"Run the code. Follow the question." } }
            }
        }
        markdownNote("Edit the words in the Presentation box and run it again. It keeps the current step. [[back]] [[next]]")
    }
    step("A real webpage") {
        audienceWebpage("https://example.org/")
        markdownNote("This is a real browser view. Navigate and interact with it directly. [[back]] [[next]]")
    }
    step("A live Field document") {
        audienceDocument(talkDocument, macro = "reveal_wave")
        markdownNote("Use **Run reveal_wave** to build a wave, or **Start document guide** to teach through the code. [[back]] [[next]]")
    }
    step("YouTube") {
        audienceYouTube("M7lc1UVf-VE")
        markdownNote("Replace this embed test video with one for your lesson. [[back]] [[next]]")
    }
    step("Keep teaching") {
        audience {
            style = slideStyle
            h1 { +"Keep teaching." }
            p { +"Slides, examples, guides, macros and media belong with the lesson." }
            hr { }
            p(classes = "muted") { +"Save the document. Everything stays editable." }
        }
        markdownNote("Use the previous steps to revisit an example. Close the audience or controller when finished. [[back]] [[finish]]")
    }
}.present()
