# GitHub Copilot Instructions for Field4

## Repository Overview

Field4 is a sophisticated live coding environment that combines high-performance graphics, rich scripting support, and deep AI integration. It's designed for rapid prototyping and improvisation with code, enabling developers to create visual and computational experiments in real-time.

### Core Purpose
- **Live Coding Environment**: Real-time code execution and visualization
- **Multi-Language Support**: Kotlin (primary), Python, Remote JS, Clojure, and Vulkan GLSL
- **High-Performance Graphics**: Vulkan-based graphics system with LWJGL for real-time rendering
- **AI Integration**: Deep integration with AI tools for code completion and generation
- **Visual Programming**: Box-based visual programming interface with Monaco editor as the main code editor

## Technology Stack

### Backend (JVM)
- **Primary Language**: Kotlin 2.2.10 with JVM target 24
- **Graphics**: LWJGL 3.4.0-SNAPSHOT (Vulkan, OpenGL, GLFW)
- **Web Server**: Ktor 3.3.0 (WebSockets, HTTP)
- **Scripting**: Kotlin scripting runtime with custom modifications
- **Python Integration**: Jep (Java Embedded Python) for Python execution
- **Clojure Integration**: Native Clojure runtime
- **AI/ML**: OpenAI client, Playwright for browser automation

### Frontend (Browser)
- **Language**: Kotlin/JS compiled to ES2015, plus raw ESM JavaScript
- **Editor**: Monaco Editor (from VS Code) - the main code editor for the environment
- **UI**: Kotlinx-html-js, interact.js for drag/drop
- **Communication**: WebSockets for real-time server communication
- **Graphics**: Canvas and SVG for UI rendering

### Build System
- **Gradle**: Kotlin Multiplatform project
- **Targets**: JVM (main application), JS (browser frontend), jsChat (chat interface)
- **Java Toolchain**: Java 24

## Architecture

### Box-Based System
The core abstraction is a **Box** - a computational unit that can:
- Contain code (Kotlin, Python, Clojure, JavaScript)
- Have properties and metadata
- Connect to other boxes to form execution graphs
- Execute code and produce output
- Be visualized in the browser

### Key Components

#### Backend Components (src/jvmMain/kotlin/afield/)
- **Server.kt**: Main Ktor server, WebSocket handling, session management
- **Boxes.kt**: Core Box class and hierarchy management
- **KotlinInterface.kt**: Kotlin scripting integration and execution
- **ai/**: AI integration (OpenAI, code completion, chat)
- **graphics/**: Vulkan graphics system, drawing primitives
- **python/**: Python (Jep) integration
- **clojure/**: Clojure integration
- **remotejs/**: Remote JS execution in browser
- **server/**: Server-side utilities (importing, routing, etc.)

#### Frontend Components (src/jsMain/kotlin/afield/)
- **Main.kt**: Browser entry point, WebSocket client, UI initialization
- **BootstrapMonaco*.kt**: Monaco editor integration and customization
- **commands.kt**: User commands, clipboard operations
- **outputareas.kt**: Output rendering in browser
- **Messages.kt**: Message types for server-client communication

### Communication Pattern
- Browser connects to server via WebSocket
- Messages are JSON-serialized Kotlin objects
- Real-time bidirectional communication for:
  - Code execution requests
  - Box property updates
  - UI state synchronization
  - Output rendering

## Code Style and Conventions

### Kotlin Style
1. **Idiomatic Kotlin**: Use Kotlin idioms and features extensively
   - Extension functions and properties
   - Delegation patterns (`by Delegation<T>()`)
   - Coroutines for async operations
   - DSL-style builders where appropriate

2. **Property Delegation**: Boxes use custom delegation for properties
   ```kotlin
   var Box.customProperty by Delegation<Type>(defaultValue = { ... })
   ```

3. **Annotations for Lifecycle**:
   ```kotlin
   @RunsAt(early)   // Runs during early initialization
   @RunsAt(plugins) // Runs during plugin phase
   ```

4. **Marker Interfaces and Extensions**: Prefer composition over inheritance
   ```kotlin
   interface HasOutput {
       fun output(message: String)
   }
   ```

5. **Naming Conventions**:
   - Use camelCase for functions and properties
   - Use PascalCase for classes
   - Prefix private/internal properties with `_` when necessary
   - Use descriptive names that indicate purpose

### JavaScript/Frontend Style
1. **Kotlin/JS Preference**: Prefer Kotlin over JavaScript when possible, but don't be afraid to drop down into raw ESM JavaScript for frontend-specific needs

2. **External Declarations**: Use `external` for JS interop
   ```kotlin
   external fun monaco(): dynamic
   external val interact: dynamic
   ```

3. **Dynamic Types**: Use `dynamic` sparingly, prefer typed interfaces when possible

4. **Message Passing**: Use strongly-typed message classes for WebSocket communication

### Multi-Language Integration
1. **Isolated Execution**: Each language (Python, Remote JS, Clojure, GLSL) has its own execution context
2. **Bridge Pattern**: Use interface classes (PythonInterface, ClojureInterface, etc.) to bridge to JVM
3. **Remote JS**: JavaScript code is executed in the browser, with results communicated back via WebSocket

## Development Workflow

### Building and Running
```bash
# Build the project
./gradlew build

# Run the main application (opens browser)
./gradlew jvmRun

# Run specific tools
./gradlew runai        # AI tools
./gradlew runfilestore # AI filestore CLI
```

### Project Structure
```
src/
  commonMain/       # Shared code between JVM and JS
  jvmMain/          # Backend (Kotlin/JVM)
    kotlin/afield/  # Main application code
    resources/      # Static resources, extensions
  jsMain/           # Frontend (Kotlin/JS)
    kotlin/afield/  # Browser code
    resources/      # Monaco, CSS, JavaScript libraries
  jsChatMain/       # Separate chat interface
```

### Testing
- No formal test suite currently (live coding environment)
- Manual testing by running the application
- Test changes in the browser UI
- Check console output for errors

## Key Areas and Patterns

### Graphics System (graphics/)
- **Vulkan-based**: Low-level graphics using LWJGL Vulkan bindings
- **FLine**: Custom drawing primitive for lines and shapes
- **StandardDrawing**: High-level drawing interface
- **Skiko Integration**: For 2D rendering and text

### AI Integration (ai/)
- **Ai4CompletionDoc**: Code completion powered by AI
- **OpenAI Integration**: Chat and completion APIs
- **MCP Support**: Model Context Protocol for AI tools
- **KotlinSourceIndex**: Index Kotlin code for AI context

### Scripting Support
- **Kotlin Scripting**: Custom-modified Kotlin scripting runtime
  - Allows fragment reuse without history checks
  - IDE-like completion support
- **Python via Jep**: Embedded Python interpreter
- **Remote JS**: Execute JavaScript in browser from server
- **Clojure**: Native Clojure runtime
- **Vulkan GLSL**: Shader language support for graphics programming

### File Management
- **EDN Format**: Uses Extensible Data Notation for persistence
- **metadata.edn**: Boxes and their properties serialized to EDN
- **Clipboard Support**: Copy boxes as files to filesystem
- **File Watching**: FileWatchManager monitors external file changes

## Common Tasks

### Adding a New Box Property
1. Define delegation in relevant file:
   ```kotlin
   var Box.myProperty by Delegation<Type>(defaultValue = { ... })
   ```
2. Add to persistent properties if needed:
   ```kotlin
   Box.ioPersistentProperties.add("myProperty")
   ```
3. Handle in frontend if UI-relevant

### Adding a New Command
1. Create command in jsMain/kotlin/afield/commands.kt
2. Register in actions system
3. Handle message in Server.kt if server-side processing needed

### Extending Language Support
1. Create interface class (e.g., MyLanguageInterface.kt)
2. Implement execution context and evaluation
3. Register with Box execution system
4. Add UI support for syntax highlighting

## AI and Completion

### Code Completion
- Uses custom Kotlin scripting IDE services
- AI-powered completion via OpenAI
- GLSL-specific completion for Vulkan shaders
- Context-aware suggestions based on box hierarchy
- Monaco editor integration for all supported languages

### AI Features
- Chat interface for AI assistance
- Code generation and refactoring
- Documentation generation
- Error diagnosis and fixes

## Important Notes

### Performance Considerations
1. **Graphics**: Vulkan operations are performance-critical
2. **WebSockets**: Minimize message size for real-time updates
3. **Coroutines**: Use appropriately to avoid blocking UI
4. **Memory**: Be cautious with large box hierarchies

### Browser Compatibility
- Primary target: Modern Chrome/Edge (Chromium)
- Monaco editor requires ES2015+
- Clipboard API features vary by browser
- WebSocket support required

### Security Considerations
1. **Code Execution**: Arbitrary code execution is a feature, not a bug
2. **Local Environment**: Designed for local development, not production deployment
3. **File System Access**: Server has full file system access
4. **Workspace Isolation**: Use designated workspace directory

## Resources

### Custom Libraries
- **libs/kotlin-scripting-*.jar**: Modified Kotlin scripting jars
- **libs/jep-*.jar**: Java Embedded Python
- **libs/nashorn-core-*.jar**: JavaScript parsing

### External Resources
- Monaco editor: Bundled in resources
- LWJGL documentation: https://www.lwjgl.org/
- Kotlin scripting: https://github.com/Kotlin/kotlin-script-examples

## When Making Changes

1. **Preserve the Live Coding Nature**: Changes should maintain real-time responsiveness
2. **Don't Break the REPL**: Ensure code can be iteratively evaluated
3. **Consider Multi-Language Impact**: Changes might affect Kotlin, Python, Remote JS, Clojure, or Vulkan GLSL execution
4. **Test in Browser**: Always verify changes in the actual browser UI
5. **Check WebSocket Messages**: Ensure client-server communication works correctly
6. **Maintain Box Abstraction**: Keep the box-based model consistent
7. **Document AI Integration Changes**: AI features are core to the experience

## Troubleshooting

### Common Issues
- **Vulkan Initialization**: May fail on unsupported systems (graceful degradation expected)
- **Python/Jep**: May not initialize on all systems (warning only)
- **Monaco Loading**: Check browser console for loading errors
- **WebSocket Disconnection**: Automatic reconnection should handle this

### Debug Logging
- Server logs to console
- Browser logs to dev tools console
- Use `lprintln()` for server-side logging
- Use `console.log()` for client-side logging

## Version Information
- Kotlin: 2.2.10
- LWJGL: 3.4.0-SNAPSHOT
- Ktor: 3.3.0
- Java Toolchain: 24
- Target JVM: 24
