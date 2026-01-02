# Interactive Guides with Callbacks, Templates, and Chat

This document describes the interactive features of Field's guide/slideshow system.

## Overview

Field's guide system now supports:
1. **Custom Link Callbacks**: Links that execute Kotlin code when clicked
2. **Template System**: Dynamic content that displays live data from your code
3. **Per-Slide Chat**: Discussion threads for students to ask questions and provide feedback

## Per-Slide Chat System

Each slide in your slideshow automatically includes a discussion section where viewers can:
- Post questions and comments
- Report bugs or issues
- Discuss the content with other students

### Features
- **Persistent**: Messages are stored on the server and visible to all future viewers
- **Real-time**: New messages appear immediately for all connected users
- **Anonymous-friendly**: No authentication required; usernames are optional
- **Collapsible**: Chat UI is hidden by default to avoid cluttering the guide

### Using Chat

Chat is automatically available on all slides. No special code is needed:

```kotlin
slideShow {
    step("Introduction") {
        note("""
            <h2>Welcome!</h2>
            <p>Use the Discussion section below to ask questions.</p>
        """)
    }
}
```

Students simply:
1. Click "▸ Discussion" to expand the chat
2. Type their message
3. Click "Send" to post

### Setting a Username

Add a `username` parameter to the URL:
```
http://localhost:9092/?file=myguide&username=student1
```

Without a username, messages are posted as "anonymous".

### Custom Slideshow IDs

For persistent chat across sessions, set a custom slideshow ID:

```kotlin
slideShow {
    slideshowId("my-tutorial-v1")  // Messages persist for this specific ID
    
    step("Intro") {
        note("<h2>Welcome</h2>")
    }
}
```

**See [GUIDE_CHAT_SYSTEM.md](docs/GUIDE_CHAT_SYSTEM.md) for complete chat documentation, including:**
- Server setup and configuration
- API reference
- Data storage format
- Troubleshooting

## Custom Link Callbacks

### How it Works

You can define links in your guide that trigger custom Kotlin code when clicked. The callback automatically receives a context with:
- **`here`**: The box hosting the slideshow (non-null)
- **`show`**: The current slideshow instance (non-null)

```kotlin
slideShow {
    step("My Step") {
        note("""
            <h3>Click Me</h3>
            <p><a href="#custom:myCallback">Click this link</a></p>
        """)
        
        // Register the callback - receives 'here' (the box) and 'show' (the slideshow)
        linkCallback("myCallback") {
            // Your code here - 'here' and 'show' are automatically available
            lprintln("Link was clicked!")
        }
    }
}
```

### Link Format

Custom callback links use the format: `#custom:callbackName`

- `#custom:` is the prefix that indicates this is a custom callback
- `callbackName` is the name you use when registering with `linkCallback()`

### Example: Interactive Counter

```kotlin
var count = 0
this.properties["count"] = count

slideShow {
    step("Counter") {
        note("""
            <p>Count: {{count}}</p>
            <p><a href="#custom:increment">Increment</a></p>
        """)
        
        linkCallback("increment") {
            count++
            here.properties["count"] = count
            // Refresh to show new value
            show.goToStep(show.currentStepIndex)
        }
    }
}
```

## Template System

### How it Works

Templates allow you to embed live data from your code into guide content using the `{{propertyName}}` syntax:

```kotlin
val version = "1.0.0"
this.properties["version"] = version

slideShow {
    step("Info") {
        note("""
            <p>Version: {{version}}</p>
        """)
    }
}
```

### What Can Be Referenced

Templates can access:
1. **Box Properties**: Anything stored in `this.properties[...]`
2. **Kotlin Variables**: Variables in the current Kotlin scripting context
3. **Template Functions**: From the global `templates` and `knownValues` maps

### Example: Live Status Display

```kotlin
var status = "idle"
var processedItems = 0

this.properties["status"] = status
this.properties["processedItems"] = processedItems

slideShow {
    step("Status") {
        note("""
            <h3>Processing Status</h3>
            <p>Status: {{status}}</p>
            <p>Items: {{processedItems}}</p>
        """)
        
        onEnter {
            // Update status when entering this step
            status = "processing"
            this@myBox.properties["status"] = status
        }
    }
}
```

## Combining Features

The real power comes from combining callbacks and templates for fully interactive experiences:

```kotlin
var choice = "none"
this.properties["choice"] = choice

slideShow {
    step("Make a Choice") {
        note("""
            <h3>Choose One:</h3>
            <p><a href="#custom:optionA">Option A</a></p>
            <p><a href="#custom:optionB">Option B</a></p>
            <p>You chose: {{choice}}</p>
        """)
        
        linkCallback("optionA") {
            choice = "Option A"
            here.properties["choice"] = choice
            show.goToStep(show.currentStepIndex)
        }
        
        linkCallback("optionB") {
            choice = "Option B"
            here.properties["choice"] = choice
            show.goToStep(show.currentStepIndex)
        }
    }
}
```

## Built-in Navigation Links

Standard navigation links are still available:

- `#slideshow:next` - Go to next step
- `#slideshow:previous` - Go to previous step  
- `#slideshow:home` - Go to first step
- `#slideshow:start` - Start slideshow
- `#slideshow:stop` - Stop slideshow

## Examples

See `slideshow_example.kt` for complete working examples:

- `simpleInteractiveExample()` - Minimal example showing core features
- `interactiveGuideExample()` - Comprehensive demonstration
- `exampleSlideShow()` - Traditional slideshow (non-interactive)
- `advancedSlideShowExample()` - Annotations and advanced features

## Use Cases

This system enables many interactive guide scenarios:

1. **Tutorial with Choices**: Let users select their experience level or interests
2. **Configuration Wizards**: Guide users through setup with live preview
3. **Interactive Documentation**: Show computed values based on user input
4. **Live Demonstrations**: Display real-time data as the guide progresses
5. **Branching Narratives**: Create Twine-like interactive stories

## Implementation Details

### Template Processing

Templates are processed just-in-time, right before the HTML is sent to the browser. This means:
- Values are always fresh and up-to-date
- You can modify properties and see changes by refreshing the step
- Templates work with both `note()` and `showNote()` functions

### Callback Execution

Callbacks are executed server-side in a coroutine context:
- They have access to the full Field environment
- They can modify properties, execute code, show notifications, etc.
- They run asynchronously but can use `suspend` functions

### Browser Integration

The browser automatically:
- Intercepts clicks on `#custom:` and `#slideshow:` links
- Sends appropriate commands to the server
- Prevents default link navigation behavior

## Migration Guide

Existing guides continue to work without changes. To add interactivity:

1. **Replace static text with templates**: Change hard-coded values to `{{propertyName}}`
2. **Add properties to box**: Store values in `this.properties[...]`
3. **Define callbacks**: Use `linkCallback()` for any custom actions
4. **Update links**: Change plain links to `#custom:callbackName` format

## Performance Considerations

- Templates are processed once per step display
- Callbacks execute on demand when links are clicked
- No performance impact on guides that don't use these features
- Template processing is lightweight (simple string replacement)

## Future Enhancements

Possible future additions:
- Conditional content (show/hide based on properties)
- Loops in templates (iterate over collections)
- More sophisticated template expressions
- Visual callback triggers (buttons, forms)
- Callback parameters passed from links
