# AI-Powered Documentation & Code Search

## Overview

Field4 now includes an AI-powered documentation and code search feature that helps developers discover and understand Field4's APIs, code patterns, and documentation. This feature is deeply integrated with the filestore mirror, providing context-aware search results from the entire Field4 codebase.

## Features

### 🔍 Intelligent Code Search
- **Natural Language Queries**: Ask questions in plain English about Field4 functionality
- **Context-Aware Results**: Leverages the KotlinSourceIndex vector store for relevant code examples
- **KDoc Integration**: Includes inline documentation and KDoc comments in search results
- **Multi-Language Support**: Searches across Kotlin, GLSL, and other Field4 code

### 📚 Documentation Discovery
- **API References**: Find function signatures, parameters, and return types
- **Usage Examples**: Get practical code examples showing how to use features
- **Best Practices**: Discover patterns and conventions used in Field4
- **File Locations**: See where specific implementations live in the codebase

### 💬 Interactive AI Assistant
- **Conversational Interface**: Ask follow-up questions to refine your search
- **Code Generation**: Request example code tailored to your specific use case
- **Explanation Mode**: Get detailed explanations of complex concepts
- **Streaming Responses**: See results appear in real-time as the AI generates them

### 🎨 Beautiful Formatting
- **Markdown Rendering**: Clean formatting with headings, lists, and emphasis
- **Syntax Highlighting**: Code blocks properly highlighted by language
- **Copy Buttons**: One-click copying of code examples
- **Responsive Design**: Clean, readable interface matching Field4's style

## Usage

### Opening the Search Interface

There are two ways to open the documentation search:

1. **Keyboard Shortcut**: Press `Ctrl+Shift+D` (or `Cmd+Shift+D` on Mac)
2. **Command Palette**: 
   - Open command palette (Ctrl+Shift+P)
   - Search for "Search Documentation & Code"
   - Press Enter

The search interface appears in the documentation browser (the same view used when pressing `Ctrl+/` or `Cmd+/`), replacing any existing documentation content. This provides a seamless, integrated experience within Field4's main window.

### Example Queries

Here are some example queries to get you started:

#### API Discovery
- "How do I create a box programmatically?"
- "What are the available drawing functions?"
- "Show me how to use the animation system"
- "How do I connect boxes together?"

#### Code Examples
- "Show me examples of GLSL shaders in Field4"
- "Give me an example of using Python integration"
- "How do I create custom properties on a box?"
- "Show me how to handle mouse events"

#### Implementation Details
- "Where is the WebSocket handling implemented?"
- "How does the Monaco editor integration work?"
- "Explain the Box class hierarchy"
- "What's the difference between vertex and fragment shaders?"

#### Best Practices
- "What's the best way to structure a Field4 project?"
- "How should I organize my shader code?"
- "What are common patterns for box interactions?"

## Technical Details

### Architecture

The documentation search feature consists of three main components:

#### 1. Backend: DocsSearchBridge
- **Location**: `src/jvmMain/kotlin/afield/ai/core/DocsSearchBridge.kt`
- **Purpose**: Manages AI conversations using OpenAI's API
- **Key Features**:
  - Vector store integration for codebase context
  - Streaming response support
  - Conversation state management
  - Specialized system instructions for code search

#### 2. Backend: DocsSearchInstaller
- **Location**: `src/jvmMain/kotlin/afield/ai/core/DocsSearchInstaller.kt`
- **Purpose**: Message handling and response formatting
- **Key Features**:
  - WebSocket message processing
  - Markdown-to-HTML conversion
  - Code block formatting with copy buttons
  - HTML escaping for security

#### 3. Frontend: Search Interface
- **Location**: `src/commonMain/resources/docs-search.html`
- **Purpose**: User interface for search queries
- **Key Features**:
  - Real-time streaming display
  - Example queries for quick start
  - Responsive design
  - WebSocket integration
  - Displayed in documentation browser iframe

### Integration with Documentation Browser

The search interface integrates seamlessly with Field4's existing documentation browser:

- Uses `switchToDocsIframe("/docs-search.html")` to display search in the same iframe used for documentation (Ctrl+/)
- Provides consistent navigation experience with other browser views (history, documentation)
- Allows easy switching between search and other documentation views
- No separate window management required

### Message Protocol

The feature uses WebSocket messages for communication:

```kotlin
@Serializable
data class DocsSearch_input(
    val value: String,  // The search query or response text
    val phase: Int      // 0=query, 1=streaming, 20=final
)
```

**Message Flow**:
1. User enters query → `phase: 0` sent to server
2. Server processes → `phase: 1` messages stream back
3. Final response → `phase: 20` marks completion

### Vector Store Integration

The search leverages Field4's KotlinSourceIndex:

```kotlin
KotlinSourceIndex.latestIndex?.vectorStoreId?.let { vsId ->
    // Include vector store in search context
    vectorStoreIds.add(vsId)
}
```

This provides the AI with:
- Kotlin source files from `src/jvmMain/kotlin`, `src/personal/kotlin`, and `src/generatedLinalg/kotlin`
- KDoc comments and inline documentation
- Code structure and patterns
- File paths and locations

### AI Model Configuration

By default, the feature uses the flagship model (`gpt-5.5`) with a separate fallback model (`gpt-5.4`) when the preferred model is unavailable. Both are resolved through environment overrides in the constructor:

```kotlin
class DocsSearchBridge(
    model: String = Ai4ModelDefaults.envOrDefault("AFIELD_DOCS_SEARCH_MODEL", Ai4ModelDefaults.FLAGSHIP_MODEL),
    // ...
) {
    private val fallbackModel = Ai4ModelDefaults.envOrDefault(
        "AFIELD_DOCS_SEARCH_FALLBACK_MODEL", Ai4ModelDefaults.FRONTIER_FALLBACK_MODEL
    )
}
```

Set `AFIELD_DOCS_SEARCH_MODEL` / `AFIELD_DOCS_SEARCH_FALLBACK_MODEL` to override either model.

## Configuration

### Requirements

1. **OpenAI API Key**: Set `OPENAI_API_KEY` environment variable
2. **KotlinSourceIndex**: Filestore must be indexed (runs automatically)
3. **Vector Store**: Created during KotlinSourceIndex sync

### Optional Configuration

- **Model Selection**: Change AI model in `DocsSearchBridge` constructor
- **System Instructions**: Customize prompts in `DocsSearchBridge.systemInstructions`
- **Documentation Browser**: Search appears in the documentation iframe (same as Ctrl+/)

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+Shift+D` (Windows/Linux) | Open documentation search |
| `Cmd+Shift+D` (Mac) | Open documentation search |
| `Enter` | Send query (in search input) |
| `Shift+Enter` | New line (in search input) |

## Tips & Best Practices

### Writing Effective Queries

1. **Be Specific**: "How do I animate a box's position?" vs "animation"
2. **Ask for Examples**: Include "show me" or "example" in your query
3. **Specify Context**: Mention what you're trying to accomplish
4. **Follow Up**: Ask clarifying questions if the first result isn't perfect

### Understanding Results

- **Code Blocks**: Formatted with language tags (kotlin, glsl, etc.)
- **File Paths**: Click to navigate (if supported by browser)
- **Copy Buttons**: Copy code without selection artifacts
- **Headings**: Organize responses into logical sections

### Performance Tips

- First query may be slower (initializing AI bridge)
- Subsequent queries in same session are faster (conversation context)
- Toggling between Monaco and the docs view does *not* reset the conversation: `setDocsIframeContent` only reassigns `iframe.src` when the URL changes or the iframe hasn't loaded yet, and `searchSessionId` (in `docs-search.html`) is generated once when the iframe's script first runs. To start a fresh conversation, reload the page/iframe or restart the server.
- Complex queries can take a while (anecdotally on the order of tens of seconds); there is no fixed timeout or SLA in the code.

## Troubleshooting

### Search Interface Doesn't Appear
- Check JavaScript console for errors
- Verify Field4 server is running
- Try refreshing main Field4 window
- Ensure documentation browser is functioning (test with Ctrl+/)

### No Results or Errors
- Verify `OPENAI_API_KEY` is set correctly
- Check KotlinSourceIndex is initialized
- Look for errors in Field4 server logs
- Ensure vector store is available

### Slow Responses
- First query initializes AI bridge (one-time delay)
- Complex queries take longer to process
- Check network connectivity
- Verify OpenAI API is accessible

### Formatting Issues
- Code blocks should have copy buttons
- If not rendering, check browser console
- Try switching back to Monaco editor and reopening search
- Ensure using modern browser (Chrome/Edge recommended)

### Switching Views
- Press Ctrl+/ (Cmd+/) to toggle to default documentation
- Use "Switch to Monaco Editor" action to return to code editing
- Search interface replaces current browser content (this is expected behavior)

## Development

### Adding New Example Queries

Edit the welcome section in `docs-search.html`. Example items are declared with a `data-example-query` attribute and wired up via `querySelectorAll('[data-example-query]')` + `addEventListener` (there is no `askExample()` function):

```html
<ul>
    <li data-example-query="Your new example">• Your new example</li>
</ul>
```

### Customizing Styling

CSS variables are defined in `docs-search.html`:

```css
:root {
    --field-cyan: rgb(72, 201, 255);
    --field-bg: #1e1e1e;
    --field-text: #e6e6e6;
    /* ... more variables ... */
}
```

Note: The search interface appears within the documentation browser iframe, so styling is self-contained within the HTML file.

### Extending Markdown Support

The markdown formatter is in `DocsSearchInstaller.kt`:

```kotlin
private fun formatMarkdownResponse(markdown: String): String {
    // Add new markdown patterns here
}
```

## Integration with Other Features

### Documentation Browser
- Seamlessly integrates with existing documentation browser (Ctrl+/)
- Same iframe used for built documentation and history views
- Consistent navigation experience
- No additional window management

### Filestore Mirror
- Automatically uses the indexed codebase
- No additional configuration needed
- The index is (re)built once at server startup (`syncKotlinSourceIndex` runs `@RunsAt(early)` and calls `startBackgroundSync()` once). There is no file watcher or periodic resync, so a server restart is required to pick up new or changed source files.

### AI Completion
- Complements AI4 code completion
- Different focus (search vs generation)
- Shares underlying infrastructure

## Future Enhancements

Potential improvements for future versions:

- **GitHub Integration**: Search across issues and PRs
- **Documentation Indexing**: Include public docs content in vector store
- **Code Navigation**: Click to open files in Field4
- **Search History**: Recently searched queries
- **Bookmarks**: Save useful search results
- **Export**: Download conversation as markdown
- **Custom Filters**: Filter by file type, date, author
- **Multi-Model**: Compare results from different AI models

## Credits

This feature leverages:
- OpenAI API for AI capabilities
- KotlinSourceIndex for codebase indexing
- Field4's existing WebSocket infrastructure
- Monaco editor styling patterns

## Feedback

Have suggestions or found issues? Please report them through the Field4 issue tracker.
