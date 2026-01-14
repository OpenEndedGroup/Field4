# Guide Chat System - Quick Start

Get the Field4 guide chat system running in 5 minutes!

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
./gradlew jvmRun
```

Field4 will open in your default browser.

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
4. Click "Send"
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
CHAT_SERVER_URL=http://localhost:5003 ./gradlew jvmRun
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
3. Try creating the directory manually:
   ```bash
   mkdir -p chat_data
   chmod 755 chat_data
   ```

### Messages Not Appearing for Other Users

**Check:**
1. Both users on the same slide?
2. Same slideshow ID? (should be automatic)
3. Network issues? Check browser console
4. Server receiving requests? Check server logs

## Next Steps

### Customize Your Guide

See [GUIDE_CHAT_EXAMPLE.kt](GUIDE_CHAT_EXAMPLE.kt) for a complete example with:
- Multiple slides
- Interactive callbacks
- Template variables
- Navigation links

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

### Production Deployment

For classroom use:

1. **Run chat server persistently:**
   ```bash
   nohup python3 guide_chat_server.py > chat_server.log 2>&1 &
   ```

2. **Configure for network access:**
   - Update `CHAT_SERVER_URL` to use actual IP/hostname
   - Consider using systemd service or Docker
   - Add authentication if needed (future enhancement)

3. **Backup chat data:**
   ```bash
   tar -czf chat_backup_$(date +%Y%m%d).tar.gz chat_data/
   ```

## Learning More

- **Full Documentation:** [GUIDE_CHAT_SYSTEM.md](GUIDE_CHAT_SYSTEM.md)
- **Interactive Guides:** [../INTERACTIVE_GUIDES.md](../INTERACTIVE_GUIDES.md)
- **Server API:** [../server/README.md](../server/README.md)

## Tips

1. **Chat is opt-in per slide** - Use `withChat()` to enable discussion on specific slides
2. **Each slide gets its own chat** - Students can discuss specific content
3. **Messages persist** - Future students see past discussions
4. **No authentication** - Low barrier to entry for students
5. **Collapsible UI** - Doesn't clutter the learning experience
6. **Real-time** - Messages appear immediately for all viewers

Happy teaching! 🎓
