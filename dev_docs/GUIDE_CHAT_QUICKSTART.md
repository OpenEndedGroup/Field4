# Guide Chat System - Quick Start

Get the Field4 guide chat system running.

> The canonical public writeup is `public/docs/guide_chat.md`. This dev_doc is the
> hands-on runbook plus the architecture/worked-example detail.

## Prerequisites

- Python 3.8 or higher
- Field4 running (Java 24 toolchain)
- Modern web browser (Chrome, Firefox, Safari, Edge)

## Step 1: Install Python Dependencies

```bash
cd server
pip install -r requirements.txt
```

This installs:
- Flask (web framework)
- flask-cors (CORS support)
- flask-socketio (WebSocket support)
- python-socketio (Socket.IO client/server)

## Step 2: Start the Chat Server

```bash
cd server
python3 guide_chat_server.py
```

You should see:
```
Guide Chat Server starting...
Chat data directory: /path/to/Field4/chat_data
Available at: http://localhost:5002
  - WebSocket: ws://localhost:5002/ws
  - HTTP GET: http://localhost:5002/chat/<slideshow_id>/<slide_number>
  - HTTP POST: http://localhost:5002/chat/<slideshow_id>/<slide_number>
  - Health check: http://localhost:5002/health
  - Debug mode: enabled
```

**Keep this terminal running!** The chat server must stay active for chat to work.

## Step 3: Start Field4

In a new terminal:

```bash
./gradlew runJvm
```

`runJvm` is the fully-configured launch task (mainClass `afield.Server`, JDWP, AOT
wiring). It is one of several ways to start Field4; `README.md` documents
`./gradlew runDesktop` (backend + Electron) and the `PORT` override as the canonical
dev-server path. Do not use `./gradlew jvmRun` — that is an unconfigured
Kotlin-Multiplatform carrier task with no mainClass.

## Step 4: Create a Guide with Chat

1. Create a new box in Field4
2. Paste this simple example:

```kotlin
slideShow {
    step("Welcome") {
        note("""
            <h2>Hello!</h2>
            <p>Try the Discussion section below!</p>
            <p><a href="#slideshow:next">Next →</a></p>
        """)
        withChat()  // Enable chat for this slide
    }

    step("Second Slide") {
        note("""
            <h2>Another Slide</h2>
            <p>Each slide can optionally have chat.</p>
        """)
        withChat()  // Enable chat here too
    }

    step("No Chat") {
        note("""
            <h2>This slide has no chat</h2>
            <p>Chat is opt-in per slide.</p>
        """)
        // No withChat() call, so no Discussion section appears
    }
}

this.slideShow?.start()
```

3. Execute the box (Shift+Enter or click the run button)

## Step 5: Test the Chat

1. Look for "▸ Discussion" below the slide content (only on slides with `withChat()`)
2. Click it to expand the chat
3. Type a message: "Hello from Field4!"
4. Press Enter/Return to post (the input is a text area with placeholder
   "Add a comment + return" — there is no Send button; Shift+Enter inserts a newline)
5. Your message should appear immediately
6. Navigate to the third slide - notice there's no Discussion section (no `withChat()` call)

## Step 6: Test Multi-User Chat

1. Copy your browser URL (e.g., `http://localhost:9092/?file=myfile`)
2. Open a new tab or window
3. Paste the URL but add `&username=student2`
4. The same guide opens for "student2"
5. Post messages from both tabs
6. Watch them sync in real-time!

## Troubleshooting

### Chat Server Won't Start

**Problem:** `Address already in use` error

**Solution:** Change the port:
```bash
CHAT_SERVER_PORT=5003 python3 guide_chat_server.py
```

Then tell Field4 to use the new port:
```bash
CHAT_SERVER_URL=http://localhost:5003 ./gradlew runJvm
```

### No Chat UI Appears

**Check:**
1. Is the chat server running? Check the terminal
2. Is it on port 5002? Check the startup message
3. Browser console errors? Press F12 and check console
4. Server console errors? Check Field4 terminal output

### Messages Not Saving

**Check:**
1. Can the server write to `chat_data/`?
   ```bash
   ls -la chat_data/
   ```
2. Check server logs for permission errors

### Messages Not Appearing for Other Users

**Check:**
1. Both users on the same slide?
2. Same slideshow ID? (should be automatic)
3. Network issues? Check browser console

## Customization

### Set Custom Slideshow IDs

For persistent chat across sessions:

```kotlin
slideShow {
    slideshowId("my-lesson-v1")  // Custom ID
    step("...") {
        note("...")
        withChat()  // Remember to enable chat per slide
    }
}
```

Without a custom ID, a new ID is generated each time, losing old messages.

### Configure Usernames

Add username to URL:
```
http://localhost:9092/?file=myguide&username=alice
```

Or let users post as "anonymous" (default).

### Shared / Template Chat

`withChat(id = "templateId")` shares one thread across all boxes created from the
same template; any other string is a custom shared-chat ID. See the KDoc on
`Box.withChat` / `SlideStepBuilder.withChat` in
`src/jvmMain/kotlin/afield/SlideShow.kt` for the full semantics, and
`src/jvmMain/kotlin/afield/examples/slideshow_example.kt` for a compiled example.

---

## Architecture

### Components

- **Frontend UI** — `src/jsMain/kotlin/afield/Main.kt`
  Builds the collapsible chat widget (`createChatWidget` / `createChatUI` /
  `mountSharedChat`), renders messages (`displayChatMessages`), and drives the
  `loadChatMessages` / `postChatMessage` RPCs. The message input is a `<textarea>`
  that posts on Enter.
- **Field4 JVM chat bridge** — `src/jvmMain/kotlin/afield/server/chat.kt`
  The `chat()` function (annotated `@RunsAt(installHandlers)`, so `Server.kt`
  discovers and registers it during startup) installs the `load-chat-messages` and
  `post-chat-message` handlers and owns the Ktor CIO `HttpClient` that talks to the
  Flask server. It is not defined in `Server.kt` itself.
- **SlideShow runtime** — `src/jvmMain/kotlin/afield/SlideShow.kt`
  Generates the slideshow ID, tracks the current slide number, resolves the chat ID
  (see below), and ships `slideshowId` + `slideNumber` metadata on `ShowSlideNote`.
- **Flask chat server** — `server/guide_chat_server.py`
  Flask + flask-socketio + flask-cors. HTTP GET/POST plus WebSocket rooms, JSON-file
  persistence.

### Component diagram

```
Browser (Main.kt, Kotlin/JS)
  Chat widget: createChatWidget / displayChatMessages / loadChatMessages / postChatMessage
  Handlers: "chat-messages-loaded" -> render, "chat-message-posted" -> refresh
        │  RPC over the Field transport
        ▼
Field4 JVM server
  server/chat.kt (fun chat(), @RunsAt(installHandlers)):
    "load-chat-messages" -> HTTP GET  Flask
    "post-chat-message"  -> HTTP POST Flask, then broadcast "chat-message-posted"
    Ktor CIO HttpClient -> CHAT_SERVER_URL (default http://localhost:5002)
  SlideShow.kt: slideshow ID + slide number metadata on ShowSlideNote
        │  HTTP REST (+ WebSocket)
        ▼
Flask chat server (server/guide_chat_server.py)
  GET  /health
  GET  /chat/<slideshow_id>/<slide_number>
  POST /chat/<slideshow_id>/<slide_number>
  WebSocket: subscribe / unsubscribe / post
        │  File I/O
        ▼
File system
  chat_data/<slideshow_id>/slide_<n>.json
```

### Data flow: loading messages

1. User navigates to a slide.
2. Frontend receives `show-slide-note` (carries `slideshowId` + `slideNumber`).
3. Frontend builds the chat UI and sends `load-chat-messages`.
4. `server/chat.kt` HTTP GETs Flask `/chat/<slideshow_id>/<slide_number>`.
5. Flask `load_messages()` reads `chat_data/<slideshow_id>/slide_<n>.json`.
6. Flask returns the JSON array.
7. `server/chat.kt` replies `chat-messages-loaded` (empty list on error).
8. Frontend `displayChatMessages()` renders them.

### Data flow: posting messages

1. User types a message and presses Enter.
2. Frontend `postChatMessage()` sends `post-chat-message`.
3. `server/chat.kt` HTTP POSTs Flask `/chat/<slideshow_id>/<slide_number>`.
4. Flask `save_message()` rewrites the slide's JSON file with the new message
   appended, and broadcasts to the WebSocket room.
5. `server/chat.kt` broadcasts `chat-message-posted` to connected clients.
6. Frontend refreshes via `loadChatMessages()` and re-renders.

### Chat ID resolution

`SlideShowInstance.goToStep` in `SlideShow.kt` picks the chat ID:

```kotlin
val chatId = when {
    !step.withChat -> ""                                        // no chat
    step.customChatId == "templateId" -> hostBox.templateId ?: slideshowId
    step.customChatId != null -> step.customChatId              // custom shared ID
    else -> slideshowId                                          // default
}
```

`Box.templateId` (`src/jvmMain/kotlin/afield/Boxes.kt`) is set on template insert by
`server/templating.kt`. The standalone `Box.withChat` helper resolves the ID the same
way but falls back to the box's own id instead of the slideshow id.

### RPC message types

Defined in `src/commonMain/kotlin/rpc.kt`: `LoadChatMessages`, `ChatMessagesLoaded`,
`ChatMessage` (`username`, `message`, `timestamp`), `PostChatMessage`,
`ChatMessagePosted`, and `ShowSlideNote` (extended with `slideshowId` / `slideNumber`
for chat).

### Storage format

`chat_data/<slideshow_id>/slide_<n>.json` holds a JSON array of message records:

```json
[
  {
    "username": "alice",
    "message": "Can you explain the syntax on line 5?",
    "timestamp": "2024-01-02T10:15:30.123456+00:00"
  }
]
```

The Flask server sanitizes `slideshow_id` (alphanumerics, `-`, `_`) to prevent
directory traversal.

### Configuration

- Flask server: `CHAT_SERVER_PORT` (default 5002), `CHAT_SERVER_DEBUG` (default true).
- Field4 JVM server: `CHAT_SERVER_URL` (default `http://localhost:5002`).
- Browser: `?file=<filename>`, `?username=<name>` (default `anonymous`).

### Design decisions

- **Two-server split** — Flask handles chat (mature Python WebSocket libraries),
  Field4 handles the app; clean separation, and chat is optional (the slideshow still
  renders if the chat server is down).
- **Per-slide threading** — each slide has its own thread, keeping discussion
  contextual.
- **JSON-file storage** — no database; human-readable, easy to back up.
- **Anonymous by default** — low barrier for classroom use.
- **Collapsible, opt-in UI** — chat only appears where `withChat()` is called.

### Performance and security notes

- **Message load** is one file read plus a JSON parse; parse cost scales with the
  number of messages already stored for that slide (O(n) in message count, not O(1)).
- **Message post** does a full read-modify-rewrite of the slide's JSON file (cost
  scales with existing message count), then broadcasts — not an O(1) append.
- Concurrency capacity and average message size are not benchmarked; treat any figure
  as a rough estimate.
- **Security**: no authentication/authorization, no server-side validation/
  sanitization, no rate limiting. Designed for trusted classroom environments; add
  auth/sanitization/HTTPS before any untrusted deployment.

---

## Full Example

A richer worked guide: custom slideshow ID for persistent chat, per-slide chat, a
`{{lessonProgress}}` template driven by a `linkCallback` counter, and navigation
links. Start the chat server (Steps 1-2) first, then paste this into a Field box.

```kotlin
// Set up properties that templates can reference
var lessonProgress = 0
this.properties["lessonProgress"] = lessonProgress

slideShow {
    // Custom slideshow ID so chat messages persist across sessions
    slideshowId("kotlin-intro-lesson-v1")

    step("Welcome") {
        note("""
            <h2>Welcome to Kotlin Basics!</h2>
            <p>This is an interactive guide. Use the Discussion section below to ask questions.</p>
            <p><a href="#slideshow:next">Let's get started! →</a></p>
        """, persistent = true)
        withChat()
    }

    step("Variables") {
        note("""
            <h2>Kotlin Variables</h2>
            <p>Declare variables with <code>val</code> (read-only) or <code>var</code> (mutable):</p>
            <pre>val name = "Alice"  // Immutable
var age = 25        // Mutable</pre>
            <p><a href="#slideshow:next">Next →</a> | <a href="#slideshow:previous">← Back</a></p>
        """)
        withChat()
        selectBoxByName("variables-example")
    }

    step("Interactive Counter") {
        note("""
            <h2>Interactive Example</h2>
            <p>Progress: {{lessonProgress}}/10 tasks completed</p>
            <p><a href="#custom:incrementProgress">Complete a task</a></p>
            <p>Click the link above to see the counter change live.</p>
            <p><a href="#slideshow:next">Next →</a> | <a href="#slideshow:previous">← Back</a></p>
        """)
        withChat()

        linkCallback("incrementProgress") {
            lessonProgress++
            if (lessonProgress > 10) lessonProgress = 0
            here.properties["lessonProgress"] = lessonProgress
            show.goToStep(show.currentStepIndex)  // refresh to show new value
        }
    }

    step("Summary") {
        note("""
            <h2>Great Work!</h2>
            <ul>
                <li>✓ Variables (val/var)</li>
                <li>✓ Interactive code</li>
            </ul>
            <p><a href="#slideshow:home">Return to start</a> | <a href="#slideshow:stop">Finish</a></p>
        """, persistent = true)
        withChat()
    }
}

this.slideShow?.start()
```

To test multi-user chat, open the same guide in another tab with a different
username (`?file=yourfile&username=student2`) and post from both. Only slides that
call `withChat()` show a Discussion section.

## Learning More

- **Canonical public doc:** [../public/docs/guide_chat.md](../public/docs/guide_chat.md)
- **Interactive Guides (broader SlideShow DSL):** [INTERACTIVE_GUIDES.md](INTERACTIVE_GUIDES.md)
- **Server API:** [../server/README.md](../server/README.md)
- **Compiled examples:** `src/jvmMain/kotlin/afield/examples/slideshow_example.kt`
