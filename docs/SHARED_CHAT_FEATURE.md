# Shared Chat Feature Documentation

## Overview

The shared chat feature allows creating persistent, shared discussion spaces that can be embedded in Field boxes. These chat spaces can be:

1. **Instance-specific**: Each box has its own chat (default)
2. **Template-shared**: All boxes from the same template share one chat
3. **Custom-shared**: Multiple boxes share a chat via a custom ID

## Key Components

### Backend (JVM)

#### Box Properties
- `Box.templateId`: String property that tracks which template a box was created from
  - Set automatically when inserting templates
  - Persisted in `metadata.edn`
  - Used for template-shared chat

#### SlideShow Integration
- `SlideStepBuilder.withChat(id: String? = null)`: Enable chat on a slide
  - `id = null`: Uses slideshow-specific ID (default)
  - `id = "templateId"`: Uses the box's template ID
  - `id = "custom-id"`: Uses the provided custom ID

- `SlideStep.customChatId`: Stores the custom chat ID if provided

- `SlideShowInstance.goToStep()`: Determines the chat ID to use based on:
  1. If `customChatId == "templateId"`: Use `hostBox.templateId` or fall back to `slideshowId`
  2. If `customChatId != null`: Use the custom ID
  3. Otherwise: Use the default `slideshowId`

#### Standalone Chat Function
- `Box.withChat(id: String? = null)`: Add chat to any box's output area
  - Works the same way as in slideshows
  - Displays in the "chat" output channel

### Frontend (JS)

No changes needed! The existing implementation already:
- Accepts any `slideshowId` string
- Creates chat UI based on the provided ID
- Loads/posts messages using the ID

### Chat Server

The external Flask chat server:
- Stores messages by `{slideshowId}/{slideNumber}`
- Works with any string as `slideshowId`
- Persists across sessions

## Usage Examples

### 1. Per-Slide Chat (Existing)

```kotlin
slideShow {
    step("Slide 1") {
        markdownNote("# Welcome")
        withChat()  // Chat specific to this slide
    }
}
```

### 2. Template-Shared Chat (New)

```kotlin
slideShow {
    step("Lesson 1") {
        markdownNote("# Collaborative Learning")
        withChat(id = "templateId")  // Shared across all template instances
    }
}
```

**When to use:**
- Educational templates where learners should see each other's questions
- Collaborative project templates
- Shared documentation with community discussion

### 3. Custom Shared Chat (New)

```kotlin
slideShow {
    step("Project Overview") {
        markdownNote("# Main Discussion")
        withChat(id = "myProject-general")  // Custom shared space
    }
}
```

**When to use:**
- Cross-box discussions
- Project-wide chat spaces
- Organizing discussions by topic

### 4. Standalone Chat in Output Areas (New)

```kotlin
// In a box's code:
this.withChat(id = "templateId")

// Or with custom ID:
this.withChat(id = "project-team-chat")

// Or box-specific:
this.withChat()
```

## Implementation Details

### Template ID Assignment

When a template is inserted:

```kotlin
// In server/templating.kt
handlers["insert-template"] = mutableSetOf({ message, reply ->
    // ... template loading code ...
    
    // Set templateId on all loaded boxes
    boxes.forEach { box ->
        box.templateId = templateName
    }
    
    boxes
})
```

### Chat ID Resolution

When displaying a slide with chat:

```kotlin
// In SlideShow.kt
val chatId = when {
    !step.withChat -> ""  // No chat
    step.customChatId == "templateId" -> hostBox.templateId ?: slideshowId
    step.customChatId != null -> step.customChatId
    else -> slideshowId  // Default
}
```

### Message Structure

Chat messages are sent via WebSocket:
```kotlin
// Loading messages
LoadChatMessages(
    slideshowId = chatId,  // Can be any string
    slideNumber = slideIndex
)

// Posting messages  
PostChatMessage(
    slideshowId = chatId,
    slideNumber = slideIndex,
    username = username,
    message = message
)
```

## Benefits

1. **Collaborative Learning**: Students using the same template can discuss together
2. **Persistent Discussions**: Conversations survive across sessions
3. **Flexible Organization**: Use custom IDs to organize by project/topic
4. **Backward Compatible**: Existing code continues to work unchanged
5. **No Additional Infrastructure**: Uses existing chat server

## Future Enhancements

Possible future improvements:
- User presence indicators
- Rich text formatting in chat
- File/image sharing in chat
- Moderation tools
- Chat export functionality
- Real-time typing indicators

## Technical Notes

- The `templateId` property is automatically persisted
- Template IDs are set during template insertion
- The chat server URL is configurable via `CHAT_SERVER_URL` environment variable
- Default chat server runs on `http://localhost:5002`
- Chat persistence is handled by the external Flask server

## Migration Guide

### For Existing Slideshows

No changes needed! Your existing slideshows will continue to work:

```kotlin
// This still works exactly as before
withChat()
```

### To Enable Template-Shared Chat

Simply change:
```kotlin
withChat()
```

To:
```kotlin
withChat(id = "templateId")
```

### To Use Custom Shared Chat

```kotlin
withChat(id = "your-custom-id")
```

## Security Considerations

- Chat messages are stored by the external server
- No built-in authentication (relies on username parameter)
- Consider network security for the chat server
- For production use, implement proper authentication
- Messages are not encrypted in transit (use HTTPS in production)
