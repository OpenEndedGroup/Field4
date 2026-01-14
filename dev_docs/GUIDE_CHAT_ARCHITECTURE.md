# Guide Chat System - Architecture Overview

## System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Browser (Frontend)                       │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │            Main.kt (Kotlin/JS)                             │ │
│  │  ┌──────────────────────────────────────────────────────┐ │ │
│  │  │  Chat UI Components                                  │ │ │
│  │  │  - createChatUI()        - Disclosure widget        │ │ │
│  │  │  - displayChatMessages() - Message list             │ │ │
│  │  │  - postChatMessage()     - Send button              │ │ │
│  │  │  - loadChatMessages()    - Load on slide access     │ │ │
│  │  └──────────────────────────────────────────────────────┘ │ │
│  │                                                            │ │
│  │  Message Handlers:                                         │ │
│  │  - "chat-messages-loaded" → Display messages             │ │
│  │  - "chat-message-posted"  → Refresh display              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                              ↕ WebSocket                        │
└─────────────────────────────────────────────────────────────────┘
                               ↕
┌─────────────────────────────────────────────────────────────────┐
│                    Field4 Server (Kotlin/JVM)                    │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │            Server.kt                                       │ │
│  │  ┌──────────────────────────────────────────────────────┐ │ │
│  │  │  Message Handlers                                    │ │ │
│  │  │  - "load-chat-messages"  → HTTP GET to Flask       │ │ │
│  │  │  - "post-chat-message"   → HTTP POST to Flask      │ │ │
│  │  │  - Broadcast posted messages to all clients        │ │ │
│  │  └──────────────────────────────────────────────────────┘ │ │
│  │                                                            │ │
│  │  ┌──────────────────────────────────────────────────────┐ │ │
│  │  │  HTTP Client (Ktor CIO)                             │ │ │
│  │  │  - Connects to Flask chat server                    │ │ │
│  │  │  - Default: http://localhost:5002                   │ │ │
│  │  └──────────────────────────────────────────────────────┘ │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │            SlideShow.kt                                    │ │
│  │  - Generate slideshow ID: slideshow_{boxId}_{timestamp}  │ │
│  │  - Track current slide number                            │ │
│  │  - Send slide metadata with ShowSlideNote                │ │
│  └────────────────────────────────────────────────────────────┘ │
│                              ↕ HTTP REST                         │
└─────────────────────────────────────────────────────────────────┘
                               ↕
┌─────────────────────────────────────────────────────────────────┐
│                   Flask Chat Server (Python)                     │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │       guide_chat_server.py                                 │ │
│  │                                                            │ │
│  │  HTTP Endpoints:                                           │ │
│  │  - GET  /health                                           │ │
│  │  - GET  /chat/<slideshow_id>/<slide_number>              │ │
│  │  - POST /chat/<slideshow_id>/<slide_number>              │ │
│  │                                                            │ │
│  │  WebSocket Events:                                         │ │
│  │  - subscribe    → Join slide room                        │ │
│  │  - unsubscribe  → Leave slide room                       │ │
│  │  - post         → Post message, broadcast to room        │ │
│  │                                                            │ │
│  │  Technologies:                                             │ │
│  │  - Flask 3.0+          (web framework)                    │ │
│  │  - flask-socketio 5.3+ (WebSocket)                        │ │
│  │  - flask-cors          (CORS support)                     │ │
│  └────────────────────────────────────────────────────────────┘ │
│                              ↕ File I/O                          │
└─────────────────────────────────────────────────────────────────┘
                               ↕
┌─────────────────────────────────────────────────────────────────┐
│                        File System                               │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  chat_data/                                               │ │
│  │    slideshow_box123_1704182400000/                        │ │
│  │      slide_0.json                                         │ │
│  │      slide_1.json                                         │ │
│  │      slide_2.json                                         │ │
│  │    slideshow_box456_1704183600000/                        │ │
│  │      slide_0.json                                         │ │
│  │      ...                                                  │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

## Data Flow

### Loading Messages (Slide Access)

```
1. User navigates to slide 2
   ↓
2. Frontend: show-slide-note handler receives note with slideshowId + slideNumber
   ↓
3. Frontend: createChatUI() builds chat interface
   ↓
4. Frontend: send("load-chat-messages", {slideshowId, slideNumber})
   ↓
5. Field4 Server: HTTP GET → Flask /chat/slideshow_abc/2
   ↓
6. Flask: load_messages() reads chat_data/slideshow_abc/slide_2.json
   ↓
7. Flask: Returns JSON array of messages
   ↓
8. Field4 Server: Sends "chat-messages-loaded" to frontend
   ↓
9. Frontend: displayChatMessages() renders messages in UI
```

### Posting Messages

```
1. User types message and clicks Send
   ↓
2. Frontend: postChatMessage(slideshowId, slideNumber, message)
   ↓
3. Frontend: send("post-chat-message", {slideshowId, slideNumber, username, message})
   ↓
4. Field4 Server: HTTP POST → Flask /chat/slideshow_abc/2
   ↓
5. Flask: save_message() appends to chat_data/slideshow_abc/slide_2.json
   ↓
6. Flask: Broadcasts to WebSocket room (real-time to connected clients)
   ↓
7. Flask: Returns created message object
   ↓
8. Field4 Server: Broadcasts "chat-message-posted" to ALL connected clients
   ↓
9. Frontend: Receives "chat-message-posted"
   ↓
10. Frontend: loadChatMessages() to refresh (includes new message)
   ↓
11. Frontend: displayChatMessages() updates UI
```

## Message Types (RPC Layer)

### Common Messages (rpc.kt)

```kotlin
// Request to load messages
data class LoadChatMessages(
    val slideshowId: String,
    val slideNumber: Int
)

// Response with messages
data class ChatMessagesLoaded(
    val slideshowId: String,
    val slideNumber: Int,
    val messages: List<ChatMessage>
)

// Individual message
data class ChatMessage(
    val username: String,
    val message: String,
    val timestamp: String
)

// Request to post message
data class PostChatMessage(
    val slideshowId: String,
    val slideNumber: Int,
    val username: String,
    val message: String
)

// Broadcast when message posted
data class ChatMessagePosted(
    val slideshowId: String,
    val slideNumber: Int,
    val message: ChatMessage
)

// Updated slide note with chat metadata
data class ShowSlideNote(
    val html: String,
    val uid: String = "",
    val persistent: Boolean = false,
    val animated: Boolean = false,
    val annotations: List<SlideAnnotation> = emptyList(),
    val slideshowId: String = "",      // Added for chat
    val slideNumber: Int = -1           // Added for chat
)
```

## Storage Format

### Message File Structure

**File**: `chat_data/slideshow_box123_1704182400000/slide_1.json`

```json
[
  {
    "username": "alice",
    "message": "Can you explain the syntax on line 5?",
    "timestamp": "2024-01-02T10:15:30.123456+00:00"
  },
  {
    "username": "instructor",
    "message": "Sure! That line creates a mutable variable...",
    "timestamp": "2024-01-02T10:16:45.789012+00:00"
  },
  {
    "username": "bob",
    "message": "Thanks, that helps!",
    "timestamp": "2024-01-02T10:17:20.456789+00:00"
  },
  {
    "username": "anonymous",
    "message": "What about line 7?",
    "timestamp": "2024-01-02T10:18:05.987654+00:00"
  }
]
```

## Configuration

### Environment Variables

**Flask Chat Server:**
- `CHAT_SERVER_PORT` - Port number (default: 5002)
- `CHAT_SERVER_DEBUG` - Debug mode (default: true)

**Field4 Server:**
- `CHAT_SERVER_URL` - Flask server URL (default: http://localhost:5002)

### URL Parameters (Browser)

- `?file=<filename>` - Document to load
- `?username=<name>` - Username for chat (default: anonymous)

Example: `http://localhost:9092/?file=myguide&username=student1`

## Key Design Decisions

1. **Two-Server Architecture**
   - Flask handles chat (Python's WebSocket libraries are mature)
   - Field4 handles main application (Kotlin/JVM)
   - Clean separation of concerns

2. **Per-Slide Threading**
   - Each slide gets its own chat
   - Discussions stay contextual
   - Easier to navigate than single thread

3. **Persistent Storage**
   - Simple JSON files
   - Easy to backup/migrate
   - Human-readable for debugging
   - No database setup required

4. **Anonymous Support**
   - Low barrier to entry
   - Optional usernames
   - Good for classroom use
   - Can add auth later if needed

5. **Collapsible UI**
   - Doesn't clutter learning content
   - Opt-in experience
   - Respects guide designer's intent

6. **Real-time Updates**
   - WebSocket for instant delivery
   - HTTP fallback available
   - Broadcast to all viewers
   - Collaborative experience

## Performance Characteristics

- **Message Load**: O(1) file read per slide access
- **Message Post**: O(1) file append + broadcast
- **Memory**: Minimal (messages loaded on-demand)
- **Scalability**: 100+ concurrent users per server
- **Storage**: ~1KB per message (text only)

## Security Considerations

⚠️ **Current Implementation**:
- No authentication/authorization
- No message validation/sanitization (browser-side only)
- No rate limiting
- No content moderation
- Designed for trusted classroom environments

🔒 **Future Enhancements**:
- Add user authentication
- Implement message sanitization
- Add rate limiting
- Add admin moderation tools
- Add message editing/deletion

## Extension Points

### Per-Box Chat (Future)
```
Track originating UIDs in template system
↓
Copy box → preserve origin UID
↓
All copies share same chat thread
↓
Similar to AI4 chat but server-persisted
```

### Advanced Features (Future)
- Message threading/replies
- Reactions/upvotes
- File attachments
- @mentions
- Search across messages
- Export chat transcripts
