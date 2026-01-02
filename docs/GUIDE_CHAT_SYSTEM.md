# Guide Chat System - Documentation

## Overview

The guide chat system adds per-slide discussion functionality to Field4's interactive guides/slideshows. Students can post messages, ask questions, and report bugs on individual slides.

## Features

- **Per-Slide Chat**: Each slide in a slideshow has its own chat thread
- **Real-time Updates**: Messages appear immediately for all viewers
- **Persistent Storage**: Messages are stored on the server and loaded when accessing slides
- **Anonymous Support**: No authentication required, usernames optional
- **Collapsible UI**: Chat is hidden in a disclosure widget to avoid cluttering the guide

## Architecture

### Components

1. **Flask Chat Server** (`server/guide_chat_server.py`)
   - WebSocket and HTTP endpoints for real-time messaging
   - Stores messages as JSON files
   - Supports multiple simultaneous slideshows

2. **Field4 Server** (`src/jvmMain/kotlin/afield/Server.kt`)
   - HTTP client to communicate with Flask server
   - Handlers for loading and posting messages
   - Broadcasts messages to all connected clients

3. **Frontend UI** (`src/jsMain/kotlin/afield/Main.kt`)
   - Chat UI integrated into slide notes
   - Collapsible disclosure section
   - Real-time message updates

4. **SlideShow System** (`src/jvmMain/kotlin/afield/SlideShow.kt`)
   - Generates unique slideshow IDs
   - Tracks current slide number
   - Sends slideshow metadata with notes

## Setup

### 1. Install Python Dependencies

```bash
cd server
pip install -r requirements.txt
```

### 2. Start the Chat Server

```bash
cd server
python3 guide_chat_server.py
```

The server starts on port 5002 by default. Configure with environment variables:
- `CHAT_SERVER_PORT`: Port number (default: 5002)
- `CHAT_SERVER_DEBUG`: Debug mode (default: true)

### 3. Configure Field4 Server

Set the chat server URL via environment variable (optional):
```bash
export CHAT_SERVER_URL=http://localhost:5002
```

Default is `http://localhost:5002` if not set.

### 4. Run Field4

```bash
./gradlew jvmRun
```

## Usage

### Creating a Slideshow with Chat

Chat is **opt-in per slide**. Use `withChat()` to enable discussion on specific slides:

```kotlin
slideShow {
    step("Introduction") {
        note("""
            <h2>Welcome!</h2>
            <p>This slide has chat enabled.</p>
            <p><a href="#slideshow:next">Next →</a></p>
        """)
        withChat()  // Enable chat for this slide
    }
    
    step("Explanation") {
        note("""
            <h2>Key Concept</h2>
            <p>This slide has no chat (no withChat() call).</p>
        """)
        // No withChat() - this slide won't have a Discussion section
    }
    
    step("Questions") {
        note("""
            <h2>Q&A</h2>
            <p>Ask questions in the Discussion below!</p>
        """)
        withChat()  // Enable chat here
    }
}
```

### Accessing Chat

1. Navigate to a slide in the guide
2. On slides with `withChat()`, look for "▸ Discussion" below the slide content
3. Click to expand the chat UI
4. Type messages in the text area
5. Click "Send" to post

**Note**: Only slides where you called `withChat()` will show the Discussion section.

### Setting Username

Add a `username` query parameter to the URL:
```
http://localhost:9092/?file=myguide&username=student1
```

If no username is provided, messages are posted as "anonymous".

## Data Storage

Messages are stored in the `chat_data` directory:
```
chat_data/
  slideshow_box123_1704182400000/
    slide_0.json
    slide_1.json
    slide_2.json
```

Each JSON file contains an array of messages:
```json
[
  {
    "username": "student1",
    "message": "Can you explain this step?",
    "timestamp": "2024-01-02T12:34:56.789Z"
  },
  {
    "username": "instructor",
    "message": "Sure! This step does...",
    "timestamp": "2024-01-02T12:35:23.456Z"
  }
]
```

## API Reference

### Flask Server Endpoints

#### GET /health
Health check endpoint.

**Response:**
```json
{
  "status": "ok",
  "service": "guide-chat-server"
}
```

#### GET /chat/<slideshow_id>/<slide_number>
Retrieve all messages for a slide.

**Response:**
```json
[
  {
    "username": "student1",
    "message": "Message text",
    "timestamp": "2024-01-02T12:34:56.789Z"
  }
]
```

#### POST /chat/<slideshow_id>/<slide_number>
Post a new message to a slide.

**Request:**
```json
{
  "username": "student1",
  "message": "Message text"
}
```

**Response:**
```json
{
  "username": "student1",
  "message": "Message text",
  "timestamp": "2024-01-02T12:34:56.789Z"
}
```

### WebSocket Events

#### Subscribe to Slide
```json
{
  "slideshow_id": "slideshow_box123_1704182400000",
  "slide_number": 0
}
```

#### Post Message
```json
{
  "slideshow_id": "slideshow_box123_1704182400000",
  "slide_number": 0,
  "username": "student1",
  "message": "Message text"
}
```

#### Receive Message
```json
{
  "action": "message",
  "slideshow_id": "slideshow_box123_1704182400000",
  "slide_number": 0,
  "username": "student1",
  "message": "Message text",
  "timestamp": "2024-01-02T12:34:56.789Z"
}
```

## RPC Messages (Field4 Internal)

### load-chat-messages
Client requests messages for a slide.

**Payload:**
```kotlin
LoadChatMessages(
    slideshowId: String,
    slideNumber: Int
)
```

### chat-messages-loaded
Server sends messages to client.

**Payload:**
```kotlin
ChatMessagesLoaded(
    slideshowId: String,
    slideNumber: Int,
    messages: List<ChatMessage>
)
```

### post-chat-message
Client posts a new message.

**Payload:**
```kotlin
PostChatMessage(
    slideshowId: String,
    slideNumber: Int,
    username: String,
    message: String
)
```

### chat-message-posted
Server broadcasts new message to all clients.

**Payload:**
```kotlin
ChatMessagePosted(
    slideshowId: String,
    slideNumber: Int,
    message: ChatMessage
)
```

## Future Enhancements

### Per-Box Chat
The system can be extended to support chat on individual boxes (not just slides):
- Track originating UIDs in template system
- Collect all instances/copies of a templated box into same chat stream
- Similar to AI4 chat but persisted on server

### Authentication
Currently no authentication is required. Future versions could add:
- User login system
- GitHub/Google OAuth integration
- Instructor moderation controls

### Advanced Features
- Message reactions/upvotes
- Threading/replies
- File attachments
- @mentions
- Search across all messages

## Troubleshooting

### Chat not appearing
1. Check Flask server is running on port 5002
2. Check `CHAT_SERVER_URL` environment variable
3. Look for errors in Field4 server console
4. Check browser developer console for JavaScript errors

### Messages not saving
1. Check write permissions on `chat_data` directory
2. Check Flask server logs for errors
3. Verify slideshow ID is being generated correctly

### Port conflicts
If port 5002 is in use:
```bash
CHAT_SERVER_PORT=5003 python3 server/guide_chat_server.py
```

And update Field4:
```bash
CHAT_SERVER_URL=http://localhost:5003 ./gradlew jvmRun
```

## Example: Complete Slideshow with Chat

```kotlin
// Create a slideshow - chat is opt-in per slide
slideShow {
    // Optional: Set custom slideshow ID for persistence
    slideshowId("my-tutorial-v1")
    
    step("Welcome") {
        note("""
            <h2>Getting Started</h2>
            <p>Welcome to this tutorial!</p>
            <p>Use the Discussion section below to ask questions.</p>
            <p><a href="#slideshow:next">Next →</a></p>
        """)
        withChat()  // Enable chat for this slide
    }
    
    step("Concept") {
        note("""
            <h2>Key Concept</h2>
            <p>This is an important concept to understand.</p>
            <p><a href="#slideshow:next">Next →</a> | <a href="#slideshow:previous">← Back</a></p>
        """)
        withChat()  // Enable chat here too
        
        selectBoxByName("example-box")
    }
    
    step("Practice") {
        note("""
            <h2>Try It Yourself</h2>
            <p>Now practice what you learned.</p>
            <p>Post your results in the discussion!</p>
        """)
        withChat()  // Enable chat
    }
    
    step("Summary") {
        note("""
            <h2>Summary</h2>
            <p>Great work! Review the key points.</p>
        """)
        // No withChat() - this summary slide won't have chat
    }
}
```

Students can now:
1. Navigate through the slides
2. On slides with `withChat()`, expand the "Discussion" section
3. Post questions like "What does line 5 do?"
4. Get answers from instructors or other students
5. Messages persist for future viewers on those slides
