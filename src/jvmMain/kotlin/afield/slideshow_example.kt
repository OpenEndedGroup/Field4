package afield

import afield.logger.lprintln
import afield.stdlib.action
import afield.stdlib.actions
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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

    // Create the slideshow
    val show = slideShow {
        step("Welcome to Field") {
            note("""
                <h2>Welcome to Field!</h2>
                <p>This is an interactive guided tour of Field, a live coding environment.</p>
                <p>Field combines high-performance graphics with powerful scripting capabilities.</p>
                <br>
                <p><strong>Click the links below to navigate:</strong></p>
                <p><a href="#slideshow:next">Start the tour →</a></p>
            """.trimIndent(), persistent = false)

            selectBox(welcomeBox, property = "code")
        }

        step("Understanding Boxes") {
            note("""
                <h3>Boxes are the Core Abstraction</h3>
                <p>In Field, everything is a <strong>Box</strong>. Boxes can:</p>
                <ul>
                  <li>Contain code in multiple languages (Kotlin, Python, JavaScript, Clojure)</li>
                  <li>Have properties and metadata</li>
                  <li>Connect to other boxes to form execution graphs</li>
                  <li>Execute code and produce output</li>
                </ul>
                <br>
                <p><a href="#slideshow:previous">← Back</a> | <a href="#slideshow:next">Next →</a></p>
            """.trimIndent())

            // Example: Select box by name instead of by reference
            // selectBoxByName("My Code Box", property = "code", line = 1)
            selectBox(codeBox, property = "code", line = 1)
        }

        step("Writing Code") {
            note("""
                <h3>Live Coding</h3>
                <p>You can write code directly in boxes and see the results immediately.</p>
                <p>The code editor supports:</p>
                <ul>
                  <li>Syntax highlighting</li>
                  <li>Code completion</li>
                  <li>AI-powered assistance</li>
                </ul>
                <p>Try editing the code and pressing <kbd>Cmd+Enter</kbd> (Mac) or <kbd>Ctrl+Enter</kbd> (Windows/Linux) to execute!</p>
                <br>
                <p><a href="#slideshow:previous">← Back</a> | <a href="#slideshow:next">Next →</a></p>
            """.trimIndent())

            selectBox(codeBox, property = "code", line = 5, highlight = 5)

            onEnter {
                // You could run some code when entering this step
                lprintln("info,slideshow", "User entered the 'Writing Code' step")
            }
        }

        step("Visual Output") {
            note("""
                <h3>Graphics and Visualization</h3>
                <p>Field excels at real-time graphics and visualization.</p>
                <p>You can create:</p>
                <ul>
                  <li>2D and 3D graphics</li>
                  <li>Shaders (GLSL)</li>
                  <li>Interactive visualizations</li>
                  <li>Data plots</li>
                </ul>
                <br>
                <p><a href="#slideshow:previous">← Back</a> | <a href="#slideshow:next">Next →</a></p>
            """.trimIndent(), anchorBox = visualBox)

            selectBox(visualBox, property = "fragment")
        }

        step("Next Steps") {
            note("""
                <h3>Keep Exploring!</h3>
                <p>You've completed the basic tour. Here are some things to try:</p>
                <ul>
                  <li>Create new boxes by double-clicking on the canvas</li>
                  <li>Connect boxes by dragging from one to another</li>
                  <li>Explore the example templates</li>
                  <li>Ask the AI assistant for help</li>
                </ul>
                <br>
                <p><strong>Happy coding!</strong></p>
                <br>
                <p><a href="#slideshow:previous">← Back</a> | <a href="#slideshow:stop">Finish tour</a></p>
            """.trimIndent(), persistent = true)

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
            note("<h3>First Step</h3><p>Welcome!</p><p><a href='#slideshow:next'>Next →</a></p>")
            selectBox(this@simpleSlideShowExample)
        }

        step("Step 2") {
            note("<h3>Second Step</h3><p>Look at line 10!</p><p><a href='#slideshow:previous'>← Back</a></p>")
            selectBox(this@simpleSlideShowExample, line = 10, highlight = 10)
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
    this.actions["▶ Start Slideshow"] = action {
        GlobalScope.launch {
            this@installSlideShowControls.slideShow?.start()
        }
    }

    this.actions["→ Next Step"] = action {
        GlobalScope.launch {
            this@installSlideShowControls.slideShow?.next()
        }
    }

    this.actions["← Previous Step"] = action {
        GlobalScope.launch {
            this@installSlideShowControls.slideShow?.previous()
        }
    }

    this.actions["■ Stop Slideshow"] = action {
        GlobalScope.launch {
            this@installSlideShowControls.slideShow?.stop()
        }
    }
}
