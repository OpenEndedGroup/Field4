package afield

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Shared Chat Examples
 * 
 * This file demonstrates the new shared chat functionality that allows
 * chat discussions to be shared across multiple box instances.
 */

/**
 * Example 1: Per-slide chat in a slideshow (existing functionality)
 * 
 * Each slide in this slideshow has its own chat that persists across sessions.
 */
fun Box.slideshowWithPerSlideChat() {
    val show = slideShow {
        slideshowId("demo-slideshow")
        
        step("Introduction") {
            markdownNote("""
                ## Welcome!
                This slide has its own chat discussion.
                
                [[next]]
            """.trimIndent())
            
            // Chat specific to this slide in this slideshow
            withChat()
            selectBox(this@slideshowWithPerSlideChat)
        }
        
        step("Second Slide") {
            markdownNote("""
                ## Another Slide
                This slide has a different chat.
                
                [[back]] [[next]]
            """.trimIndent())
            
            // Different chat for this slide
            withChat()
            selectBox(this@slideshowWithPerSlideChat)
        }
    }
    
    GlobalScope.launch { show.start() }
}

/**
 * Example 2: Shared chat across template instances
 * 
 * When you create multiple boxes from the same template, they can all
 * share the same chat discussion using withChat(id = "templateId").
 */
fun Box.slideshowWithSharedTemplateChat() {
    val show = slideShow {
        step("Collaborative Learning") {
            markdownNote("""
                ## Shared Template Discussion
                
                This chat is shared across **all instances** of this template!
                
                When you clone this template, all clones will see the same
                discussion, making it perfect for:
                - Shared learning materials
                - Collaborative projects
                - Multi-user experiences
                
                [[next]]
            """.trimIndent())
            
            // Chat shared across all instances from the same template
            withChat(id = "templateId")
            selectBox(this@slideshowWithSharedTemplateChat)
        }
        
        step("How It Works") {
            markdownNote("""
                ## Behind the Scenes
                
                - When a box is created from a template, it gets a `templateId` property
                - Using `withChat(id = "templateId")` uses this ID for the chat
                - All boxes from the same template share the same templateId
                - Therefore, they all connect to the same chat room
                
                [[back]]
            """.trimIndent())
            
            withChat(id = "templateId")
            selectBox(this@slideshowWithSharedTemplateChat)
        }
    }
    
    GlobalScope.launch { show.start() }
}

/**
 * Example 3: Custom shared chat ID
 * 
 * You can also specify any custom string as a chat ID to create
 * arbitrary shared discussion spaces.
 */
fun Box.slideshowWithCustomSharedChat() {
    val show = slideShow {
        step("Custom Shared Space") {
            markdownNote("""
                ## Custom Chat ID
                
                You can create a shared chat with any custom ID:
                ```kotlin
                withChat(id = "myProject-mainDiscussion")
                ```
                
                This allows you to:
                - Share chat across unrelated boxes
                - Create project-wide discussion spaces
                - Organize discussions by topic
                
                [[next]]
            """.trimIndent())
            
            // Custom chat ID shared across multiple boxes
            withChat(id = "myProject-mainDiscussion")
            selectBox(this@slideshowWithCustomSharedChat)
        }
    }
    
    GlobalScope.launch { show.start() }
}

/**
 * Example 4: Standalone chat in output areas
 * 
 * You can also add chat to any box outside of slideshows.
 */
fun Box.standaloneSharedChatExample() {
    this.code = """
        // Add a shared chat to this box
        withChat(id = "templateId")
        
        // Or with a custom ID
        // withChat(id = "project-general")
        
        // Or box-specific (non-shared)
        // withChat()
    """.trimIndent()
}

/**
 * Example 5: Creating a collaborative template
 * 
 * Here's a complete example of a template that includes shared chat
 * for collaborative use.
 */
fun Box.collaborativeLearningTemplate() {
    this.name = "Collaborative Learning Module"
    
    val show = slideShow {
        slideshowId("collab-learning-v1")
        
        step("Introduction") {
            markdownNote("""
                ## Learning Together
                
                This is a collaborative learning module. All instances of this
                template share the same discussion space.
                
                **Current learners:** Use the chat below to:
                - Ask questions
                - Share insights
                - Help each other
                
                [[next:Start Learning →]]
            """.trimIndent())
            
            withChat(id = "templateId")
            selectBox(this@collaborativeLearningTemplate)
        }
        
        step("Lesson Content") {
            markdownNote("""
                ## The Lesson
                
                [Your lesson content here]
                
                **Discussion:** What questions do you have about this topic?
                
                [[back]] [[next]]
            """.trimIndent())
            
            // Same shared chat across all template instances
            withChat(id = "templateId")
            selectBox(this@collaborativeLearningTemplate)
        }
        
        step("Practice Exercise") {
            markdownNote("""
                ## Try It Yourself
                
                [Exercise instructions here]
                
                **Collaboration:** Share your solutions and approaches in the chat!
                
                [[back]] [[finish]]
            """.trimIndent())
            
            withChat(id = "templateId")
            selectBox(this@collaborativeLearningTemplate)
        }
    }
    
    GlobalScope.launch { show.start() }
}

/**
 * Usage Instructions:
 * 
 * 1. Create a box in Field
 * 2. Copy one of these examples into the box
 * 3. Execute the code (Cmd/Ctrl + Enter)
 * 4. The slideshow will start with chat functionality
 * 
 * For template-shared chat:
 * 1. Save the box as a template (right-click → Save as Template)
 * 2. Create multiple instances of the template
 * 3. All instances will share the same chat when using withChat(id = "templateId")
 */
