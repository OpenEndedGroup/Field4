package afield

import afield.logger.lprintln
import afield.stdlib.Delegation
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.util.UUID

/**
 * SlideShow - A DSL for creating guided tours through Field documents
 *
 * This system supports creating interactive guides with:
 * - Step-by-step navigation
 * - Custom link callbacks that execute Kotlin code
 * - Template system for displaying live data
 * - Annotations and visual highlighting
 *
 * Example usage:
 * ```kotlin
 * // Set up properties that templates can reference
 * var counter = 0
 * this.properties["counter"] = counter
 * 
 * slideShow {
 *     step("Introduction") {
 *         note("""
 *             <h2>Welcome!</h2>
 *             <p>Counter value: {{counter}}</p>
 *             <p><a href="#custom:increment">Click to increment</a></p>
 *             <p><a href="#slideshow:next">Continue →</a></p>
 *         """)
 *         
 *         // Register a callback for the custom link
 *         linkCallback("increment") {
 *             counter++
 *             here.properties["counter"] = counter
 *             // Refresh to show new value
 *             show.goToStep(show.currentStepIndex)
 *         }
 *         
 *         selectBox(myBox)
 *     }
 * }
 * ```
 * 
 * **Custom Links:**
 * Use `#custom:callbackName` in href attributes, then register with `linkCallback("callbackName") { ... }`
 *
 * **Templates:**
 * Use `{{propertyName}}` to reference box properties or Kotlin variables in the guide's scope
 *
 * **Navigation Links:**
 * - `#slideshow:next` - Go to next step
 * - `#slideshow:previous` - Go to previous step
 * - `#slideshow:home` - Go to first step
 * - `#slideshow:stop` - Stop the slideshow
 */

// Extension property to store slideshow on a box
var Box.slideShow by Delegation<SlideShowInstance?>()

/**
 * Creates and launches a new slideshow
 */
fun Box.slideShow(init: SlideShowBuilder.() -> Unit): SlideShowInstance {
    val builder = SlideShowBuilder(this)
    builder.init()
    val instance = builder.build()
    this.slideShow = instance
    return instance
}

/**
 * Builder for creating slideshows
 */
class SlideShowBuilder(val hostBox: Box) {
    private val steps = mutableListOf<SlideStep>()
    private var slideshowId: String? = null

    /**
     * Defines a step in the slideshow
     */
    fun step(title: String, init: SlideStepBuilder.() -> Unit) {
        val builder = SlideStepBuilder(title, hostBox)
        builder.init()
        steps.add(builder.build())
    }

    /**
     * Set a custom slideshow ID (optional, will generate one if not set)
     */
    fun slideshowId(id: String) {
        this.slideshowId = id
    }

    fun build(): SlideShowInstance {
        return SlideShowInstance(hostBox, steps, slideshowId)
    }
}

/**
 * Builder for individual slide steps
 */
class SlideStepBuilder(val title: String, val hostBox: Box) {
    private var noteHtml: String? = null
    private var noteAnchorBox: Box? = null
    private var targetBox: Box? = null
    private var targetProperty: String = "code"
    private var targetLine: Int? = null
    private var highlightLine: Int? = null
    private var persistent: Boolean = false
    private var animated: Boolean = false
    private var withChat: Boolean = false
    private var customChatId: String? = null
    private val annotations = mutableListOf<SlideAnnotation>()
    private var onEnter: (suspend () -> Unit)? = null
    private var onExit: (suspend () -> Unit)? = null
    private val linkCallbacks = mutableMapOf<String, suspend LinkCallbackContext.() -> Unit>()

    /**
     * Sets the HTML note to display for this step
     */
    fun note(html: String, persistent: Boolean = false, anchorBox: Box? = null, animated: Boolean = false) {
        this.noteHtml = html
        this.persistent = persistent
        this.noteAnchorBox = anchorBox
        this.animated = animated
    }

    /**
    * Anchors the note to a specific box (defaults to the slideshow host box)
    */
    fun anchorNoteTo(box: Box) {
        this.noteAnchorBox = box
    }

    /**
     * Selects a box and optionally scrolls to a specific line
     */
    fun selectBox(box: Box, property: String = "code", line: Int? = null, highlight: Int? = null) {
        this.targetBox = box
        this.targetProperty = property
        this.targetLine = line
        this.highlightLine = highlight
    }

    /**
     * Selects a box by name (searches through children recursively) and optionally scrolls to a specific line
     */
    fun selectBoxByName(name: String, property: String = "code", line: Int? = null, highlight: Int? = null) {
        // Search for box with matching name in all children recursively
        fun findBoxByName(box: Box, targetName: String): Box? {
            if (box.name == targetName) return box
            for (child in box.children) {
                val found = findBoxByName(child, targetName)
                if (found != null) return found
            }
            return null
        }

        val found = findBoxByName(hostBox.root, name)
        if (found != null) {
            this.targetBox = found
            this.targetProperty = property
            this.targetLine = line
            this.highlightLine = highlight
        } else {
            lprintln("warning,slideshow", "Warning: Could not find box with name '$name'")
        }
    }

    /**
     * Code to execute when entering this step
     */
    fun onEnter(action: suspend () -> Unit) {
        this.onEnter = action
    }

    /**
     * Code to execute when leaving this step
     */
    fun onExit(action: suspend () -> Unit) {
        this.onExit = action
    }

    /**
     * Register a callback for a custom link in the note HTML.
     * Use in markdown as: <a href="#custom:linkName">Click me</a>
     * or in markdown link syntax: [Click me](#custom:linkName)
     * 
     * The callback receives a LinkCallbackContext with:
     * - here: the box hosting the slideshow
     * - show: the current slideshow instance (non-null)
     */
    fun linkCallback(name: String, action: suspend LinkCallbackContext.() -> Unit) {
        linkCallbacks[name] = action
    }

    /**
     * Enable chat/discussion for this slide.
     * When enabled, a collapsible "Discussion" section appears below the slide content
     * where users can post messages and questions.
     * 
     * @param id Optional custom chat ID. If not provided, uses slideshow-specific ID.
     *           Use "templateId" to share chat across all instances from the same template,
     *           or provide any custom string to create a shared chat space.
     */
    fun withChat(id: String? = null) {
        this.withChat = true
        this.customChatId = id
    }

    /**
     * Add an annotation line from the guide text to a box
     */
    fun annotate(
        id: String,
        targetBox: Box,
        color : String = "rgba(255, 180, 100, 0.8)",
        strokeWidth: Double = 2.0,
        dashed: Boolean = true
    ) {
        annotations.add(
            SlideAnnotation(
                id = id,
                targetUid = targetBox.id,
                color = color,
                strokeWidth = strokeWidth,
                dashed = dashed
            )
        )
    }

    /**
     * Add an annotation line from the guide text to a specific line in a box's editor
     */
    fun annotateToLine(
        id: String,
        targetBox: Box,
        property: String = "code",
        line: Int,
        color: String = "rgba(128, 255, 164, 0.7)",
        strokeWidth: Double = 2.0,
        dashed: Boolean = false
    ) {
        annotations.add(
            SlideAnnotation(
                id = id,
                targetUid = targetBox.id,
                targetProperty = property,
                targetLine = line,
                color = color,
                strokeWidth = strokeWidth,
                dashed = dashed
            )
        )
    }

    fun build(): SlideStep {
        return SlideStep(
            title = title,
            noteHtml = noteHtml,
            noteAnchorBox = noteAnchorBox ?: hostBox,
            targetBox = targetBox,
            targetProperty = targetProperty,
            targetLine = targetLine,
            highlightLine = highlightLine,
            persistent = persistent,
            animated = animated,
            withChat = withChat,
            customChatId = customChatId,
            annotations = annotations.toList(),
            onEnter = onEnter,
            onExit = onExit,
            linkCallbacks = linkCallbacks.toMap()
        )
    }
}

/**
 * Context provided to link callbacks
 */
class LinkCallbackContext(
    val here: Box,
    val show: SlideShowInstance
)

/**
 * Represents a single step in a slideshow
 */
data class SlideStep(
    val title: String,
    val noteHtml: String?,
    val noteAnchorBox: Box?,
    val targetBox: Box?,
    val targetProperty: String,
    val targetLine: Int?,
    val highlightLine: Int?,
    val persistent: Boolean,
    val animated: Boolean,
    val withChat: Boolean,
    val customChatId: String?,
    val annotations: List<SlideAnnotation>,
    val onEnter: (suspend () -> Unit)?,
    val onExit: (suspend () -> Unit)?,
    val linkCallbacks: Map<String, suspend LinkCallbackContext.() -> Unit> = emptyMap()
)

/**
 * Markdown renderer for slideshow notes with nav shortcuts.
 */
object SlideMarkdown {
    private val flavour = CommonMarkFlavourDescriptor()
    private val parser = MarkdownParser(flavour)
    private val navPattern = Regex("""\[\[\s*(next|previous|prev|back|home|start|stop|finish)(?:\s*:\s*([^\]]+))?]]""", RegexOption.IGNORE_CASE)

    fun render(markdown: String): String {
        val mdWithNav = injectNavShortcuts(markdown)
        val parsedTree = parser.buildMarkdownTreeFromString(mdWithNav)
        return HtmlGenerator(mdWithNav, parsedTree, flavour).generateHtml()
    }

    private fun injectNavShortcuts(markdown: String): String {
        return navPattern.replace(markdown) { m ->
            val keyword = m.groupValues[1].lowercase()
            val target = when (keyword) {
                "next", "start" -> "next"
                "previous", "prev", "back" -> "previous"
                "home" -> "home"
                "stop", "finish" -> "stop"
                else -> keyword
            }
            val defaultKey = when (keyword) {
                "start" -> "start"
                "finish" -> "finish"
                else -> target
            }
            val label = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: defaultLabel(defaultKey)
            "[${label}](#slideshow:$target)"
        }
    }

    private fun defaultLabel(target: String): String = when (target) {
        "start" -> "Start →"
        "next" -> "Next →"
        "previous" -> "← Back"
        "home" -> "Home"
        "stop", "finish" -> "Finish"
        else -> target
    }
}

/**
 * A running instance of a slideshow
 */
class SlideShowInstance(
    val hostBox: Box,
    val steps: List<SlideStep>,
    customId: String? = null
) {
    // Generate unique slideshow ID (use custom ID if provided, otherwise generate based on host box)
    val slideshowId: String = customId ?: "slideshow_${hostBox.id}_${System.currentTimeMillis()}"
    
    var currentStepIndex = -1
        private set

    val currentStep: SlideStep?
        get() = if (currentStepIndex >= 0 && currentStepIndex < steps.size) steps[currentStepIndex] else null

    /**
     * Process templates in HTML, replacing {{propertyName}} with box properties/variables
     */
    private fun processTemplates(html: String): String {
        return replaceTemplate(html) { placeholder, _ ->
            // Try to get from box properties first
            val property = hostBox.properties[placeholder]
            if (property != null) {
                return@replaceTemplate property.toString()
            }
            
            // Try to get from Kotlin scripting context variables
            try {
                val variable = hostBox.ki.context.vars[placeholder]?.value
                if (variable != null) {
                    return@replaceTemplate variable.toString()
                }
            } catch (e: Exception) {
                // Ignore if context not available
            }
            
            // Try templates map and knownValues (from replacements.kt)
            knownValues[placeholder] ?: templates[placeholder]?.invoke(placeholder, html) ?: ""
        }
    }

    /**
     * Goes to a specific step
     */
    suspend fun goToStep(index: Int) {
        if (index < 0 || index >= steps.size) {
            lprintln("SlideShow: Step $index out of bounds (0..${steps.size - 1})")
            return
        }

        // Exit current step
        currentStep?.onExit?.invoke()

        currentStepIndex = index
        val step = steps[index]

        // Execute onEnter
        step.onEnter?.invoke()

        // Show note if present
        if (step.noteHtml != null) {
            // Process templates in the HTML before sending to browser
            val processedHtml = processTemplates(step.noteHtml)
            
            // Determine chat ID to use
            val chatId = when {
                !step.withChat -> "" // No chat
                step.customChatId == "templateId" -> hostBox.templateId ?: slideshowId // Use template ID if available
                step.customChatId != null -> step.customChatId // Use custom ID
                else -> slideshowId // Default to slideshow ID
            }
            
            _send(Message("show-slide-note").payload(
                ShowSlideNote(
                    html = processedHtml,
                    uid = step.noteAnchorBox?.id ?: hostBox.id,
                    persistent = step.persistent,
                    animated = step.animated,
                    annotations = step.annotations,
                    slideshowId = if (step.withChat) chatId else "",
                    slideNumber = if (step.withChat) index else -1
                )
            ))
        } else {
            _send(Message("hide-slide-note").payload(HideSlideNote()))
        }

        // Select and scroll to box if specified
        if (step.targetBox != null) {
            _send(Message("select-and-edit-box").payload(
                SelectAndEditBox(
                    uid = step.targetBox.id,
                    property = step.targetProperty,
                    scrollToLine = step.targetLine,
                    highlightLine = step.highlightLine
                )
            ))
        }
    }

    /**
     * Execute a custom link callback by name
     */
    suspend fun executeCallback(callbackName: String): Boolean {
        val callback = currentStep?.linkCallbacks?.get(callbackName)
        if (callback != null) {
            val context = LinkCallbackContext(hostBox, this)
            callback.invoke(context)
            return true
        }
        return false
    }

    /**
     * Goes to the next step
     */
    suspend fun next() {
        goToStep(currentStepIndex + 1)
    }

    /**
     * Goes to the previous step
     */
    suspend fun previous() {
        if (currentStepIndex > 0) {
            goToStep(currentStepIndex - 1)
        }
    }

    /**
     * Jump back to the first step
     */
    suspend fun home() {
        goToStep(0)
    }

    /**
     * Starts the slideshow from the beginning
     */
    suspend fun start() {
        goToStep(0)
    }

    /**
     * Stops the slideshow and hides notes
     */
    suspend fun stop() {
        currentStep?.onExit?.invoke()
        currentStepIndex = -1
        _send(Message("hide-slide-note").payload(HideSlideNote()))
    }

    /**
     * Returns true if there is a next step
     */
    fun hasNext(): Boolean = currentStepIndex < steps.size - 1

    /**
     * Returns true if there is a previous step
     */
    fun hasPrevious(): Boolean = currentStepIndex > 0
}

// Convenience extension functions for Box

/**
 * Quick select and edit a box with optional line navigation
 */
fun Box.selectAndEdit(property: String = "code", line: Int? = null, highlight: Int? = null) {
    _send(Message("select-and-edit-box").payload(
        SelectAndEditBox(
            uid = this.id,
            property = property,
            scrollToLine = line,
            highlightLine = highlight
        )
    ))
}

fun SlideStepBuilder.markdownNote(markdown: String, persistent: Boolean = false, anchorBox: Box? = null, animated: Boolean = false) {
    val html = SlideMarkdown.render(markdown)
    note(html, persistent, anchorBox, animated)
}

/**
 * Show a slide note
 */
fun Box.showNote(
    html: String,
    persistent: Boolean = false,
    anchorBox: Box? = this,
    animated: Boolean = false,
    annotations: List<SlideAnnotation> = emptyList()
) {
    // Process templates before displaying
    val processedHtml = replaceTemplate(html) { placeholder, _ ->
        // Try to get from box properties first
        val property = this.properties[placeholder]
        if (property != null) {
            return@replaceTemplate property.toString()
        }
        
        // Try to get from Kotlin scripting context variables
        try {
            val variable = this.ki.context.vars[placeholder]?.value
            if (variable != null) {
                return@replaceTemplate variable.toString()
            }
        } catch (e: Exception) {
            // Ignore if context not available
        }
        
        // Try templates map and knownValues (from replacements.kt)
        knownValues[placeholder] ?: templates[placeholder]?.invoke(placeholder, html) ?: ""
    }
    
    _send(Message("show-slide-note").payload(
        ShowSlideNote(
            html = processedHtml,
            uid = anchorBox?.id ?: this.id,
            persistent = persistent,
            animated = animated,
            annotations = annotations
        )
    ))
}

fun Box.showMarkdownNote(
    markdown: String,
    persistent: Boolean = false,
    anchorBox: Box? = this,
    animated: Boolean = false,
    annotations: List<SlideAnnotation> = emptyList()
) {
    val html = SlideMarkdown.render(markdown)
    showNote(html, persistent, anchorBox, animated, annotations)
}

/**
 * Hide slide notes
 */
fun hideNote() {
    _send(Message("hide-slide-note").payload(HideSlideNote()))
}

/**
 * Add a shared chat interface to this box's output area.
 * 
 * @param id The chat ID to use. Special values:
 *           - "templateId": Share chat across all boxes from the same template
 *           - Any other string: Custom shared chat ID
 *           If not provided, uses the box's own ID (non-shared).
 * 
 * Example usage:
 * ```kotlin
 * // Share chat across all instances from the same template
 * withChat(id = "templateId")
 * 
 * // Create a custom shared chat
 * withChat(id = "myProject-mainDiscussion")
 * 
 * // Box-specific chat (not shared)
 * withChat()
 * ```
 */
fun Box.withChat(id: String? = null) {
    // Determine the chat ID to use
    val chatId = when {
        id == "templateId" -> this.templateId ?: this.id // Use template ID if available
        id != null -> id // Use custom ID
        else -> this.id // Default to box ID
    }

    val containerId = "shared-chat-${UUID.randomUUID()}"
    fun escapeAttr(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
    val escapedChatId = escapeAttr(chatId)

    // Display chat in the "chat" channel using the same UI as slideshow notes.
    this.display("chat", """
        <div class="shared-chat-host" id="$containerId" data-chat-id="$escapedChatId" data-chat-slide="0"></div>
        <script>
        (function attachSharedChat(attempt) {
            const hostId = "$containerId";
            const host = document.getElementById(hostId);
            const mount = (window.afield && window.afield.mountSharedChat) || window.mountSharedChat;
            if (!host) {
                console.warn("Shared chat host missing:", hostId);
                return;
            }
            if (typeof mount !== "function") {
                if (attempt < 5) {
                    setTimeout(() => attachSharedChat(attempt + 1), 50);
                } else {
                    console.warn("Shared chat mount function is not ready");
                }
                return;
            }
            const chatId = host.dataset.chatId || "";
            const slideNumber = Number(host.dataset.chatSlide || "0");
            mount(hostId, chatId, Number.isNaN(slideNumber) ? 0 : slideNumber, false, false);
        })(0);
        </script>
    """.trimIndent())
}
