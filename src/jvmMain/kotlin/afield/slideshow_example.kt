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
    val homeLink = """<a href="#slideshow:home" style="float:right;text-decoration:none;color:inherit;opacity:0.85;">&#8962;</a>"""
    this.autorun = true

    // Create the slideshow
    val show = slideShow {
        step("Welcome to Field") {
            note("""
                $homeLink
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
                $homeLink
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
                $homeLink
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
                $homeLink
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
                $homeLink
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
            note("""
                <h2>Slideshow with Images</h2>
                <p>This example demonstrates how to include images in your slideshows.</p>
                <p>Images are served from your project directory (workspace).</p>
                <br>
                <p><a href="#slideshow:next">Continue →</a></p>
            """)
            selectBox(this@slideShowWithImagesExample)
        }
        
        step("Image Example") {
            note("""
                <h3>Including Images</h3>
                <p>Place images in your project directory and reference them with <code>/project/</code>:</p>
                <img src="/project/example.png" alt="Example image" 
                     style="max-width: 100%; border: 2px solid #444; border-radius: 4px; margin: 10px 0;">
                <p><em>Note: If the image doesn't appear, make sure "example.png" exists in your project directory.</em></p>
                <br>
                <p><a href="#slideshow:previous">← Back</a> | <a href="#slideshow:next">Next →</a></p>
            """)
            selectBox(this@slideShowWithImagesExample)
        }
        
        step("Subdirectories") {
            note("""
                <h3>Organizing Images</h3>
                <p>You can organize images in subdirectories:</p>
                <ul>
                  <li><code>/project/images/diagram.png</code></li>
                  <li><code>/project/screenshots/ui.jpg</code></li>
                  <li><code>/project/docs/architecture.svg</code></li>
                </ul>
                <p>Always use forward slashes, even on Windows.</p>
                <br>
                <p><a href="#slideshow:previous">← Back</a> | <a href="#slideshow:stop">Finish</a></p>
            """)
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

/**
 * Advanced slideshow example with annotations and animations
 */
fun Box.advancedSlideShowExample() {
    val hostBox = this
    val codeBox = this.children.firstOrNull() ?: this
    val secondBox = this.children.elementAtOrNull(1) ?: codeBox
    
    val show = slideShow {
        step("Introduction with Animation") {
            note("""
                <h2>Welcome!</h2>
                <p>This guide demonstrates advanced slideshow features.</p>
                <p>Notice how this text animates in letter by letter!</p>
                <p><a href="#slideshow:next">Continue →</a></p>
            """.trimIndent(), animated = true)
            
            selectBox(hostBox)
        }
        
        step("Drawing Attention with Annotations") {
            note("""
                <h3>Pointing Things Out</h3>
                <p>You can draw lines from the guide to boxes!</p>
                <p><span id="annotation-target">Look at the line connecting this guide to the box below.</span></p>
                <br>
                <p><a href="#slideshow:previous">← Back</a> | <a href="#slideshow:next">Next →</a></p>
            """.trimIndent())
            
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
            note("""
                <h3>Code-Level Annotations</h3>
                <p>Lines can point to specific code locations!</p>
                <p>The annotation below targets line 5 in the editor.</p>
                <br>
                <p><a href="#slideshow:previous">← Back</a> | <a href="#slideshow:next">Next →</a></p>
            """.trimIndent(), animated = true)
            
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
            note("""
                <h3>Complex Guides</h3>
                <p>You can have multiple annotation lines at once.</p>
                <p>This helps explain relationships between different parts of your document.</p>
                <br>
                <p><a href="#slideshow:previous">← Back</a> | <a href="#slideshow:stop">Finish</a></p>
            """.trimIndent(), persistent = true)
            
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

