package afield.examples

import afield.Box
import afield.autorun
import afield.logger.lprintln
import afield.markdownNote
import afield.notify
import afield.slideShow
import afield.stdlib.action
import afield.stdlib.actions
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Example slideshows demonstrating the slideshow DSL
 *
 * To use these, create boxes in Field and run the code in one of them.
 * The slideshow will guide users through the document.
 *
 * NEW FEATURES:
 * - Custom link callbacks: Links can execute Kotlin code via #custom:callbackName
 * - Template system: Use {{propertyName}} to reference box properties/variables
 * - Shared chat: Use withChat(id = "templateId") to share chat across template instances
 * - Custom chat IDs: Create shared discussion spaces with custom IDs
 *
 * See interactiveGuideExample() for a comprehensive demonstration of these features.
 */

/**
 * Simple example demonstrating custom link callbacks and templates
 *
 * This is a minimal example showing the core new features:
 * - Link callbacks that execute custom code
 * - Template system for live data display
 */
fun Box.simpleInteractiveExample() {
    // Variable that will be displayed and modified
    var count = 0
    this.properties["count"] = count

    val show = slideShow {
        step("Interactive Example") {
            markdownNote(
                """
                ### Simple Interactive Guide (Markdown)
                Current count: **{{count}}**

                [Increment](#custom:increment)
                """.trimIndent()
            )

            linkCallback("increment") {
                count++
                here.properties["count"] = count
                // Refresh the step to show new count
                show.goToStep(show.currentStepIndex)
            }

            selectBox(this@simpleInteractiveExample)
        }
    }

    GlobalScope.launch {
        show.start()
    }
}

/**
 * Example slideshow demonstrating the slideshow DSL
 *
 * To use this, create boxes in Field and run this code in one of them.
 * The slideshow will guide users through the document.
 */

fun Box.exampleSlideShow() {
    // Get references to boxes (you can also use selectBoxByName)
    val welcomeBox = this
    val codeBox = this.children.firstOrNull() ?: this
    val visualBox = this.children.iterator().next()
    this.autorun = true

    // Create the slideshow
    val show = slideShow {
        step("Welcome to Field") {
            markdownNote(
                """
                ## Welcome to Field!
                This is an interactive guided tour of Field, a live coding environment.

                Field combines high-performance graphics with powerful scripting capabilities.

                **Click the links below to navigate:**

                [[start:Start the tour →]] · [[home:🏠 Home]]
                """.trimIndent(), animated = true)

            selectBox(welcomeBox, property = "code")
        }

        step("Understanding Boxes") {
            markdownNote(
                """
                ### Boxes are the Core Abstraction
                In Field, everything is a **Box**. Boxes can:

                - Contain code in multiple languages (Kotlin, Python, JavaScript, Clojure)
                - Have properties and metadata
                - Connect to other boxes to form execution graphs
                - Execute code and produce output

                [[back]] [[next]]
                """.trimIndent()
            )

            // Example: Select box by name instead of by reference
            // selectBoxByName("My Code Box", property = "code", line = 1)
            selectBox(codeBox, property = "code", line = 1)
        }

        step("Writing Code") {
            markdownNote(
                """
                ### Live Coding
                You can write code directly in boxes and see the results immediately.

                The code editor supports:

                - Syntax highlighting
                - Code completion
                - AI-powered assistance

                Try editing the code and pressing `Cmd+Enter` (Mac) or `Ctrl+Enter` (Windows/Linux) to execute!

                [[back]] [[next]]
                """.trimIndent()
            )

            selectBox(codeBox, property = "code", line = 5, highlight = 5)

            onEnter {
                // You could run some code when entering this step
                lprintln("info,slideshow", "User entered the 'Writing Code' step")
            }
        }

        step("Visual Output") {
            markdownNote(
                """
                ### Graphics and Visualization
                Field excels at real-time graphics and visualization.

                You can create:

                - 2D and 3D graphics
                - Shaders (GLSL)
                - Interactive visualizations
                - Data plots

                [[back]] [[next]]
                """.trimIndent(),
                anchorBox = visualBox
            )

            selectBox(visualBox, property = "fragment")
        }

        step("Next Steps") {
            markdownNote(
                """
                ### Keep Exploring!
                You've completed the basic tour. Here are some things to try:

                - Create new boxes by double-clicking on the canvas
                - Connect boxes by dragging from one to another
                - Explore the example templates
                - Ask the AI assistant for help

                **Happy coding!**

                [[back]] [[finish:Finish tour]] [[home]]
                """.trimIndent(),
                persistent = true
            )

            selectBox(welcomeBox, property = "code")

            onEnter {
                lprintln("info,slideshow","User completed the tour!")
            }
        }
    }

    // Start the slideshow
    GlobalScope.launch {
        show.start()
    }
}

/**
 * Simple example showing basic API usage
 */
fun Box.simpleSlideShowExample() {
    val show = slideShow {
        step("Step 1") {
            markdownNote(
                """
                ### First Step
                Welcome!

                [[next]]
                """.trimIndent()
            )
            selectBox(this@simpleSlideShowExample)
        }

        step("Step 2") {
            markdownNote(
                """
                ### Second Step
                Look at line 10!

                [[back]]
                """.trimIndent()
            )
            selectBox(this@simpleSlideShowExample, line = 10, highlight = 10)
        }
    }

    GlobalScope.launch {
        show.start()
    }
}

/**
 * Example showing how to use images in slideshow notes
 *
 * To use this:
 * 1. Place an image (e.g., "example.png") in your project directory
 * 2. Create a box and run this code
 * 3. The slideshow will display the image in a note
 */
fun Box.slideShowWithImagesExample() {
    val show = slideShow {
        step("Welcome") {
            markdownNote(
                """
                ## Slideshow with Images
                This example demonstrates how to include images in your slideshows.

                Images are served from your project directory (workspace).

                [[next:Continue →]]
                """.trimIndent()
            )
            selectBox(this@slideShowWithImagesExample)
        }

        step("Image Example") {
            markdownNote(
                """
                ### Including Images
                Place images in your project directory and reference them with `/project/`:

                ![Example image](/project/example.png)

                _Note: If the image doesn't appear, make sure "example.png" exists in your project directory._

                [[back]] [[next]]
                """.trimIndent()
            )
            selectBox(this@slideShowWithImagesExample)
        }

        step("Subdirectories") {
            markdownNote(
                """
                ### Organizing Images
                You can organize images in subdirectories:

                - `/project/images/diagram.png`
                - `/project/screenshots/ui.jpg`
                - `/project/docs/architecture.svg`

                Always use forward slashes, even on Windows.

                [[back]] [[finish]]
                """.trimIndent()
            )
            selectBox(this@slideShowWithImagesExample)
        }
    }

    GlobalScope.launch {
        show.start()
    }
}

/**
 * Helper to install slideshow controls as actions on a box
 */
fun Box.installSlideShowControls() {
    // Add actions to control the slideshow
    this.actions["▶ Start Slideshow"] = action(desc="▶ Start Slideshow", detail = "Start the slideshow associated with this box") {
        GlobalScope.launch {
            this@installSlideShowControls.slideShow?.start()
        }
    }

    this.actions["→ Next Step"] = action(desc="→ Next Step", detail = "Advance slide show forwards") {
        GlobalScope.launch {
            this@installSlideShowControls.slideShow?.next()
        }
    }

    this.actions["← Previous Step"] = action(desc="← Previous Step", detail = "Go back one slide") {
        GlobalScope.launch {
            this@installSlideShowControls.slideShow?.previous()
        }
    }

    this.actions["■ Stop Slideshow"] = action("■ Stop Slideshow", detail = "Hide the slideshow") {
        GlobalScope.launch {
            this@installSlideShowControls.slideShow?.stop()
        }
    }
}

/**
 * Advanced slideshow example with annotations and animations
 */
fun Box.advancedSlideShowExample() {
    val hostBox = this
    val codeBox = this.children.firstOrNull() ?: this
    val secondBox = this.children.elementAtOrNull(1) ?: codeBox

    val show = slideShow {
        step("Introduction with Animation") {
            markdownNote(
                """
                ## Welcome!
                This guide demonstrates advanced slideshow features.

                Notice how this text animates in letter by letter!

                [[next:Continue →]]
                """.trimIndent(),
                animated = true
            )

            selectBox(hostBox)
        }

        step("Drawing Attention with Annotations") {
            markdownNote(
                """
                ### Pointing Things Out
                You can draw lines from the guide to boxes!

                <span id="annotation-target">Look at the line connecting this guide to the box below.</span>

                [[back]] [[next]]
                """.trimIndent()
            )

            selectBox(codeBox)

            // Draw an annotation line from the guide to the target box
            annotate(
                id = "main-annotation",
                targetBox = codeBox,
                color = "rgba(128, 255, 164, 0.8)",
                strokeWidth = 2.5
            )
        }

        step("Pointing to Specific Code Lines") {
            markdownNote(
                """
                ### Code-Level Annotations
                Lines can point to specific code locations!

                The annotation below targets line 5 in the editor.

                [[back]] [[next]]
                """.trimIndent(),
                animated = true
            )

            selectBox(codeBox, line = 5, highlight = 5)

            // Draw annotation to a specific line
            annotateToLine(
                id = "line-annotation",
                targetBox = codeBox,
                property = "code",
                line = 5,
                color = "rgba(255, 180, 100, 0.8)",
                strokeWidth = 2.0,
                dashed = true
            )
        }

        step("Multiple Annotations") {
            markdownNote(
                """
                ### Complex Guides
                You can have multiple annotation lines at once.

                This helps explain relationships between different parts of your document.

                [[back]] [[finish]]
                """.trimIndent(),
                persistent = true
            )

            selectBox(hostBox)

            // Multiple annotations
            annotate(
                id = "box1",
                targetBox = codeBox,
                color = "rgba(100, 200, 255, 0.7)"
            )
            annotate(
                id = "box2",
                targetBox = secondBox,
                color = "rgba(255, 100, 200, 0.7)"
            )
        }
    }

    GlobalScope.launch {
        show.start()
    }
}

/**
 * Example demonstrating interactive links with callbacks and template system
 *
 * This example shows:
 * 1. Custom link callbacks defined in code
 * 2. Template system that references box properties and variables
 *
 * All slides use the Markdown front end, so you can exercise advanced features
 * without writing raw HTML.
 */
fun Box.interactiveGuideExample() {
    val hostBox = this
    // Set up some properties and variables that can be referenced in templates
    var clickCount = 0
    var userChoice = "none"
    val version = "1.0.0"

    // Store properties on the box for template access
    this.properties["clickCount"] = clickCount
    this.properties["userChoice"] = userChoice
    this.properties["version"] = version

    val show = slideShow {
        slideshowId("__test_0")
        step("Welcome to Interactive Guides") {
            markdownNote(
                """
                ## Interactive Guides with Callbacks
                Written entirely in Markdown (no custom HTML required).

                - **Custom link callbacks** trigger Kotlin code
                - **Template system** shows live data (version **{{version}}**)
                - **Navigation shortcuts** via `[[next]]` and `[[back]]`

                [[next:Let's explore →]]
                """.trimIndent()
            )

            selectBox(hostBox)
        }

        step("Custom Link Callbacks") {
            markdownNote(
                """
                ### Clickable callbacks using Markdown links
                Use Markdown links with `#custom:name` to run Kotlin from the slide.

                - [Increment](#custom:incrementCounter)
                - [Reset](#custom:resetCounter)

                Times clicked: **{{clickCount}}**

                [[back]] [[next]]
                """.trimIndent()
            )

            // Register callbacks for the links
            linkCallback("incrementCounter") {
                clickCount++
                here.properties["clickCount"] = clickCount
                // Refresh the current step to show updated count
                show.goToStep(show.currentStepIndex)
                lprintln("info,slideshow", "Counter incremented to $clickCount")
            }

            linkCallback("resetCounter") {
                clickCount = 0
                here.properties["clickCount"] = clickCount
                // Refresh the current step to show updated count
                show.goToStep(show.currentStepIndex)
                lprintln("info,slideshow", "Counter reset")
            }

            selectBox(hostBox)
        }

        step("Template System") {
            markdownNote(
                """
                ### Live Data Display
                Templates pull values from box properties or variables in scope.

                - Counter value: **{{clickCount}}**
                - Version: **{{version}}**
                - User choice: **{{userChoice}}**

                [[back]] [[next]]
                """.trimIndent()
            )

            selectBox(hostBox)
        }

        step("Live Chat") {
            markdownNote(
                """
                ### Per-slide live chat (Markdown)
                Open the chat pane to ask questions while staying on this slide.
                
                This chat is specific to this slideshow instance.

                [[back]] [[next]]
                """.trimIndent()
            )
            withChat()
            selectBox(hostBox)
        }

        step("Shared Chat Across Templates") {
            markdownNote(
                """
                ### Shared chat across template instances
                Use `withChat(id = "templateId")` to create a shared discussion space
                across all boxes created from the same template.
                
                This is useful for:
                - Collaborative learning materials
                - Shared project discussions
                - Multi-user experiences

                [[back]] [[next]]
                """.trimIndent()
            )
            // This chat would be shared across all instances of this template
            withChat(id = "templateId")
            selectBox(hostBox)
        }

        step("Make a Choice") {
            markdownNote(
                """
                ### Interactive Choices
                Pick one option; the callback updates `{{userChoice}}` via Markdown links.

                - [Option A: Performance Mode](#custom:choiceA)
                - [Option B: Quality Mode](#custom:choiceB)
                - [Option C: Balanced Mode](#custom:choiceC)

                Your choice: **{{userChoice}}**

                [[back]] [[next]]
                """.trimIndent()
            )

            linkCallback("choiceA") {
                userChoice = "Performance Mode"
                here.properties["userChoice"] = userChoice
                show.goToStep(show.currentStepIndex)
                notify("Selected: Performance Mode", "slideshow")
            }

            linkCallback("choiceB") {
                userChoice = "Quality Mode"
                here.properties["userChoice"] = userChoice
                show.goToStep(show.currentStepIndex)
                notify("Selected: Quality Mode", "slideshow")
            }

            linkCallback("choiceC") {
                userChoice = "Balanced Mode"
                here.properties["userChoice"] = userChoice
                show.goToStep(show.currentStepIndex)
                notify("Selected: Balanced Mode", "slideshow")
            }

            selectBox(hostBox)
        }

        step("Advanced: Computed Properties") {
            markdownNote(
                """
                ### Computing Display Values
                Values are prepared in `onEnter` and shown via Markdown templates.

                - Click count: {{clickCount}}
                - Doubled: {{doubledCount}}
                - Status: **{{status}}**

                [[back]] [[finish:Finish]]
                """.trimIndent()
            )

            onEnter {
                // Compute derived values when entering this step
                hostBox.properties["doubledCount"] = clickCount * 2
                hostBox.properties["status"] =
                    if (clickCount > 10) "Very Active!"
                    else if (clickCount > 5) "Active"
                    else "Getting Started"
            }

            selectBox(hostBox)
        }
    }

    GlobalScope.launch {
        show.start()
    }
}

