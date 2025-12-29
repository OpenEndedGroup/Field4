package afield

import afield.stdlib.Delegation
import kotlinx.coroutines.delay

/**
 * SlideShow - A DSL for creating guided tours through Field documents
 * 
 * Example usage:
 * ```
 * slideShow {
 *     step("Introduction") {
 *         note("""
 *             <h2>Welcome to Field!</h2>
 *             <p>This is a guided tour of the basics.</p>
 *             <p><a href="#" onclick="nextStep()">Continue →</a></p>
 *         """)
 *         selectBox(myBox)
 *     }
 *     
 *     step("Code Editing") {
 *         note("Now let's look at some code...")
 *         selectBox(codeBox, property = "code", line = 5)
 *     }
 * }
 * ```
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
    
    /**
     * Defines a step in the slideshow
     */
    fun step(title: String, init: SlideStepBuilder.() -> Unit) {
        val builder = SlideStepBuilder(title, hostBox)
        builder.init()
        steps.add(builder.build())
    }
    
    fun build(): SlideShowInstance {
        return SlideShowInstance(hostBox, steps)
    }
}

/**
 * Builder for individual slide steps
 */
class SlideStepBuilder(val title: String, val hostBox: Box) {
    private var noteHtml: String? = null
    private var targetBox: Box? = null
    private var targetProperty: String = "code"
    private var targetLine: Int? = null
    private var highlightLine: Int? = null
    private var persistent: Boolean = false
    private var onEnter: (suspend () -> Unit)? = null
    private var onExit: (suspend () -> Unit)? = null
    
    /**
     * Sets the HTML note to display for this step
     */
    fun note(html: String, persistent: Boolean = false) {
        this.noteHtml = html
        this.persistent = persistent
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
    
    fun build(): SlideStep {
        return SlideStep(
            title = title,
            noteHtml = noteHtml,
            targetBox = targetBox,
            targetProperty = targetProperty,
            targetLine = targetLine,
            highlightLine = highlightLine,
            persistent = persistent,
            onEnter = onEnter,
            onExit = onExit
        )
    }
}

/**
 * Represents a single step in a slideshow
 */
data class SlideStep(
    val title: String,
    val noteHtml: String?,
    val targetBox: Box?,
    val targetProperty: String,
    val targetLine: Int?,
    val highlightLine: Int?,
    val persistent: Boolean,
    val onEnter: (suspend () -> Unit)?,
    val onExit: (suspend () -> Unit)?
)

/**
 * A running instance of a slideshow
 */
class SlideShowInstance(
    val hostBox: Box,
    val steps: List<SlideStep>
) {
    var currentStepIndex = -1
        private set
    
    val currentStep: SlideStep?
        get() = if (currentStepIndex >= 0 && currentStepIndex < steps.size) steps[currentStepIndex] else null
    
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
            _send(Message("show-slide-note").payload(
                ShowSlideNote(
                    html = step.noteHtml,
                    uid = step.targetBox?.id ?: "",
                    persistent = step.persistent
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

/**
 * Show a slide note
 */
fun Box.showNote(html: String, persistent: Boolean = false) {
    _send(Message("show-slide-note").payload(
        ShowSlideNote(
            html = html,
            uid = this.id,
            persistent = persistent
        )
    ))
}

/**
 * Hide slide notes
 */
fun hideNote() {
    _send(Message("hide-slide-note").payload(HideSlideNote()))
}
