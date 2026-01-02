// Example: Interactive Guide with Chat
// This example demonstrates how to create a guide with built-in chat functionality

// Set up properties that templates can reference
var lessonProgress = 0
this.properties["lessonProgress"] = lessonProgress

// Create the slideshow with a custom ID for persistent chat
slideShow {
    // Set a custom slideshow ID so chat messages persist across sessions
    slideshowId("kotlin-intro-lesson-v1")
    
    step("Welcome") {
        note("""
            <h2>Welcome to Kotlin Basics!</h2>
            <p>This is an interactive guide. You can:</p>
            <ul>
                <li>Navigate through slides using the links</li>
                <li>Interact with code examples</li>
                <li><strong>Use the Discussion section below to ask questions!</strong></li>
            </ul>
            <p>The Discussion section is where you can:</p>
            <ul>
                <li>Ask questions about the content</li>
                <li>Report issues or bugs</li>
                <li>Share insights with other learners</li>
            </ul>
            <p><a href="#slideshow:next">Let's get started! →</a></p>
        """, persistent = true)
    }
    
    step("Variables") {
        note("""
            <h2>Kotlin Variables</h2>
            <p>In Kotlin, you can declare variables using <code>val</code> (read-only) or <code>var</code> (mutable):</p>
            <pre>val name = "Alice"  // Immutable
var age = 25        // Mutable</pre>
            <p><strong>Try it:</strong> Look at the code box to the right.</p>
            <p><em>Have questions? Click "▸ Discussion" below to ask!</em></p>
            <p><a href="#slideshow:next">Next →</a> | <a href="#slideshow:previous">← Back</a></p>
        """)
        
        selectBoxByName("variables-example")
    }
    
    step("Functions") {
        note("""
            <h2>Kotlin Functions</h2>
            <p>Functions are declared with the <code>fun</code> keyword:</p>
            <pre>fun greet(name: String): String {
    return "Hello, ${'$'}name!"
}</pre>
            <p><strong>Exercise:</strong> Modify the function to include a greeting time.</p>
            <p><em>Stuck? Post in the Discussion section and get help from others!</em></p>
            <p><a href="#slideshow:next">Next →</a> | <a href="#slideshow:previous">← Back</a></p>
        """)
        
        selectBoxByName("functions-example")
    }
    
    step("Interactive Counter") {
        note("""
            <h2>Interactive Example</h2>
            <p>Progress: {{lessonProgress}}/10 tasks completed</p>
            <p><a href="#custom:incrementProgress">Complete a task</a></p>
            <p>This demonstrates live updates! Click the link above to see the counter change.</p>
            <p><em>Share your progress in the Discussion!</em></p>
            <p><a href="#slideshow:next">Next →</a> | <a href="#slideshow:previous">← Back</a></p>
        """)
        
        linkCallback("incrementProgress") {
            lessonProgress++
            if (lessonProgress > 10) lessonProgress = 0
            here.properties["lessonProgress"] = lessonProgress
            // Refresh to show new value
            show.goToStep(show.currentStepIndex)
        }
    }
    
    step("Summary") {
        note("""
            <h2>Great Work!</h2>
            <p>You've learned the basics of Kotlin:</p>
            <ul>
                <li>✓ Variables (val/var)</li>
                <li>✓ Functions</li>
                <li>✓ Interactive code</li>
            </ul>
            <p><strong>Keep Learning:</strong></p>
            <ul>
                <li>Review any slide using the navigation</li>
                <li>Check the Discussion sections for Q&A</li>
                <li>Share your own tips and tricks!</li>
            </ul>
            <p><a href="#slideshow:home">Return to start</a> | <a href="#slideshow:stop">Finish</a></p>
        """, persistent = true)
    }
}

// Start the slideshow
this.slideShow?.start()

"""
USAGE INSTRUCTIONS:

1. Run this code in a Field box
2. Navigate through the slides
3. Click "▸ Discussion" on any slide to expand the chat
4. Try posting a message: "This is my first message!"
5. Open this guide in another browser tab with a different username:
   ?file=yourfile&username=student2
6. Post messages from both tabs and watch them sync in real-time!

CHAT FEATURES DEMONSTRATED:
- Per-slide discussions (each slide has its own chat thread)
- Persistent messages (reload the page, messages remain)
- Multiple users (open in multiple tabs with different usernames)
- Anonymous support (omit username parameter to post as "anonymous")
- Real-time updates (messages appear immediately)

SERVER SETUP:
Before running, make sure the chat server is running:
  cd server
  pip install -r requirements.txt
  python3 guide_chat_server.py

The server runs on port 5002 by default.
"""
