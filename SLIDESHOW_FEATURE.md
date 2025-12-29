# Slideshow Feature Documentation

## Overview

The slideshow functionality provides a way to create guided tours through Field documents. This is ideal for:
- Onboarding new users
- Creating interactive tutorials
- Documenting complex workflows
- Teaching Field concepts step-by-step

## Features

1. **Kotlin DSL for Slideshow Authoring**: Clean, type-safe API for creating slideshows
2. **Box Selection & Navigation**: Automatically select boxes and scroll to specific lines
3. **Rich HTML Notes**: Display formatted content with links, images, and styling
4. **Interactive Navigation**: Click links to move between steps
5. **Lifecycle Callbacks**: Execute code when entering or leaving steps
6. **Choose-Your-Own-Adventure**: Support for non-linear navigation paths

## Quick Start

### Basic Example

```kotlin
// Create a simple slideshow
val show = slideShow {
    step("Introduction") {
        note("""
            <h2>Welcome!</h2>
            <p>This is your first step.</p>
            <p><a href="#slideshow:next">Continue →</a></p>
        """)
        selectBox(myBox)
    }
    
    step("Details") {
        note("<p>Now let's look at line 10...</p>")
        selectBox(myBox, line = 10, highlight = 10)
    }
}

// Start the slideshow
kotlinx.coroutines.GlobalScope.launch {
    show.start()
}
```

### Full Example

See `src/jvmMain/kotlin/afield/slideshow_example.kt` for comprehensive examples including:
- Multi-step tours
- Interactive navigation
- Lifecycle callbacks
- Control helpers

## DSL Reference

### Creating a Slideshow

```kotlin
fun Box.slideShow(init: SlideShowBuilder.() -> Unit): SlideShowInstance
```

Creates and returns a slideshow instance attached to the box.

### Defining Steps

```kotlin
fun step(title: String, init: SlideStepBuilder.() -> Unit)
```

Defines a step in the slideshow. Available within `SlideStepBuilder`:

#### `note(html: String, persistent: Boolean = false)`

Sets the HTML content to display. If `persistent = true`, the note includes a close button and won't auto-hide.

```kotlin
note("""
    <h3>Step Title</h3>
    <p>Description...</p>
    <a href="#slideshow:next">Next</a>
""")
```

#### `selectBox(box: Box, property: String = "code", line: Int? = null, highlight: Int? = null)`

Selects a box and optionally scrolls/highlights a line.

```kotlin
selectBox(myBox, property = "code", line = 15, highlight = 15)
```

#### `selectBoxByName(name: String, property: String = "code", line: Int? = null, highlight: Int? = null)`

Selects a box by searching for it by name (recursively through all children). This is useful when you want to reference boxes by name rather than keeping explicit references.

```kotlin
selectBoxByName("My Code Box", property = "code", line = 15, highlight = 15)
```

**Note**: If the box is nested inside other boxes, the slideshow will automatically open all parent disclosure areas and scroll to make the box visible.

#### `onEnter(action: suspend () -> Unit)`

Code to execute when entering this step.

```kotlin
onEnter {
    lprintln("User entered this step")
    myBox.someProperty = "new value"
}
```

#### `onExit(action: suspend () -> Unit)`

Code to execute when leaving this step.

```kotlin
onExit {
    // Cleanup or validation
}
```

### Controlling Slideshow

```kotlin
val show: SlideShowInstance = box.slideShow { ... }

// Navigation
show.start()           // Start from beginning
show.next()            // Go to next step
show.previous()        // Go to previous step
show.goToStep(index)   // Jump to specific step
show.stop()            // Stop and hide notes

// State
show.currentStep       // Current step or null
show.currentStepIndex  // Current index
show.hasNext()         // Check if there's a next step
show.hasPrevious()     // Check if there's a previous step
```

### Helper Functions

#### Quick Selection

```kotlin
box.selectAndEdit(property = "code", line = 10, highlight = 10)
```

#### Show Note

```kotlin
box.showNote("<p>A quick note</p>", persistent = false)
```

#### Hide Notes

```kotlin
hideNote()
```

## HTML Note Format

Notes support full HTML with inline styles. Use links for navigation:

```html
<!-- Navigation links -->
<a href="#slideshow:next">Next →</a>
<a href="#slideshow:previous">← Previous</a>
<a href="#slideshow:start">Restart</a>
<a href="#slideshow:stop">Exit Tour</a>

<!-- Jump to specific step (0-indexed) -->
<a href="#slideshow:5">Go to step 5</a>

<!-- Formatting -->
<h2>Title</h2>
<h3>Subtitle</h3>
<p>Paragraph with <strong>bold</strong> and <em>italic</em></p>
<ul>
  <li>List item 1</li>
  <li>List item 2</li>
</ul>
<code>inline code</code>
<kbd>Cmd+Enter</kbd>
```

## Styling

The slideshow overlay is styled with:
- Semi-transparent dark background with blur effect
- Fixed position in top-right corner
- Responsive max-width
- Smooth animations
- Custom scrollbar

Customize by editing `src/jsMain/resources/slideshow.css`.

## Architecture

### Message Types (in `src/commonMain/kotlin/rpc.kt`)

1. **SelectAndEditBox**: Select box, change property, scroll to line
2. **ShowSlideNote**: Display HTML note
3. **HideSlideNote**: Hide note overlay
4. **SlideShowCommand**: Control slideshow from client (next, previous, etc.)

### Server-Side (JVM)

- **SlideShow.kt**: DSL implementation and slideshow logic
- **Server.kt**: Message handler for `slideshow-command`
- **slideshow_example.kt**: Example implementations

### Client-Side (JS)

- **Main.kt**: Message handlers for selection, notes, and commands
- **slideshow.css**: Overlay styling
- **index.html**: CSS import

## Creating Templates

To create a template with a guided tour:

1. Create boxes with your content
2. Add a "Guide" box with the slideshow code
3. Reference boxes by storing them in properties
4. Use `installSlideShowControls()` to add menu actions

Example:

```kotlin
// In a setup box
val guide = this
val step1Box = children.first { it.name == "Step 1" }
val step2Box = children.first { it.name == "Step 2" }

// Create the slideshow
guide.slideShow {
    step("Introduction") {
        note("Welcome! <a href='#slideshow:next'>Start →</a>")
        selectBox(guide)
    }
    step("First Concept") {
        note("Learn about X... <a href='#slideshow:next'>Next →</a>")
        selectBox(step1Box, line = 1)
    }
    // ... more steps
}

// Add menu actions for control
guide.installSlideShowControls()
```

## Best Practices

1. **Keep Steps Focused**: Each step should cover one concept
2. **Use Clear Navigation**: Always provide next/previous links
3. **Test the Flow**: Run through the tour as a new user would
4. **Provide Context**: Explain what users are looking at
5. **Use Highlighting**: Draw attention to specific lines of code
6. **Add Callbacks**: Use onEnter/onExit for dynamic content
7. **Consider Branching**: Create different paths for different user goals

## Troubleshooting

### Notes Don't Appear
- Check browser console for errors
- Verify slideshow.css is loaded
- Ensure HTML is well-formed

### Box Selection Doesn't Work
- Verify box UIDs are correct
- Check that boxes exist before selection
- Ensure boxes are visible

### Line Scrolling Issues
- Line numbers are 1-indexed
- Monaco editor must be loaded
- Property must contain code

## Future Enhancements

Potential additions:
- Progress indicator (step X of Y)
- Keyboard shortcuts for navigation
- Bookmarking/resume functionality
- Export tours as standalone HTML
- Analytics/tracking for tutorial completion
- Multiple tours per document
- Tour templates/presets
