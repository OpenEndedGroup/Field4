# Image Upload Feature for AI4 Chat

## Overview

This feature adds comprehensive image support to the Field4 AI chat interface, including:
- Paste images from clipboard directly into chat
- Upload images via file picker
- Save rendered Vulkan window content to temporary PNG files
- Send images to AI for analysis (infrastructure ready)

## Usage

### Chat Interface (Browser)

#### Pasting Images
1. Copy an image to your system clipboard (from any application)
2. Click in the chat text area
3. Press Ctrl+V (Cmd+V on Mac) to paste
4. The image will appear as a preview thumbnail below the input
5. Press Enter to send the message with the attached image(s)

#### Uploading Images
1. Click the 📎 (paperclip) button next to the chat input
2. Select one or more image files from your file system
3. Selected images will appear as preview thumbnails
4. Press Enter to send the message with the attached image(s)

#### Managing Images
- Each preview thumbnail has an × button in the top-right corner
- Click × to remove an image before sending
- All images are cleared automatically after sending

### Window Screenshot Save (Vulkan Graphics)

The `Window` class now supports saving rendered content to temporary PNG files:

```kotlin
// Get the current window
val window = lastWindowToOpen

// Option 1: Save current framebuffer to a temp PNG file (requires ByteBuffer from Vulkan)
val imageData: ByteBuffer = ... // Get pixels from your Vulkan rendering
val tempFile = window?.saveFramebufferToTempFile(imageData, width, height)
println("Screenshot saved to: ${tempFile.absolutePath}")

// Option 2: Get framebuffer as base64 PNG string for network transfer
val base64Image = window?.getFramebufferAsBase64(imageData, width, height)
// Send this string over WebSocket or HTTP
```

Example integration with Vulkan rendering:

```kotlin
// In your render loop, after presenting the frame:
fun captureFramebuffer(): ByteBuffer {
    // Allocate buffer for RGBA pixels
    val imageBuffer = MemoryUtil.memAlloc(width * height * 4)
    
    // Read from Vulkan image (you'll need to implement the Vulkan-specific parts)
    // This typically involves:
    // 1. Creating a staging buffer
    // 2. Copying the swapchain image to the staging buffer
    // 3. Mapping the staging buffer memory
    // 4. Copying the data to imageBuffer
    // 5. Unmapping and cleanup
    
    return imageBuffer
}

// To save screenshot to temp file:
val pixels = captureFramebuffer()
val screenshotFile = window?.saveFramebufferToTempFile(pixels, window.width, window.height)
println("Screenshot saved: ${screenshotFile?.absolutePath}")
MemoryUtil.memFree(pixels)
```

## Technical Details

### Frontend (Kotlin/JS)

**File:** `src/jsChatMain/kotlin/Main.kt`

The chat interface includes:
- `attachedImages: MutableList<String>` - Stores base64-encoded images
- `readImageFile(file: File)` - Converts file to base64
- `displayImagePreview(imageData: String)` - Shows thumbnail with remove button
- `sendChatMessage(text: String)` - Bundles text and images into RPC message

Image handling:
- Paste events capture `clipboardData.items` of type `image/*`
- File input accepts `accept="image/*"` and supports `multiple`
- Images are converted to base64 data URLs using `FileReader.readAsDataURL()`
- Preview thumbnails are limited to 100×100px with remove buttons

### RPC Messages (Shared)

**File:** `src/commonMain/kotlin/rpc.kt`

```kotlin
@Serializable
data class Chat_input(
    val value: String,        // Text message
    val phase: Int,           // 0=request, 1=intermediate, 20=final
    val images: List<String> = emptyList()  // Base64-encoded images
)
```

Images are encoded as complete data URLs: `data:image/png;base64,iVBORw0KG...`

### Backend (Kotlin/JVM)

**File:** `src/jvmMain/kotlin/afield/ai/core/install/Ai4Installer.kt`

Two handlers are registered:

1. **`hello_chat`** - Initializes chat connection
   - Logs connection info
   - Can initialize per-session state

2. **`chat_input`** - Processes chat messages
   - Receives text and images
   - Currently acknowledges images in text (for testing)
   - Sends intermediate "Thinking..." response (phase=1)
   - Calls `chatHelpers.chatGeneral()` with OpenAI
   - Formats response with markdown
   - Sends final response (phase=20)

**Markdown Formatting:**
- Code blocks: ` ```code``` ` → `<pre><code>code</code></pre>`
- Inline code: `` `code` `` → `<code>code</code>`
- Bold: `**text**` → `<strong>text</strong>`
- Italic: `*text*` → `<em>text</em>`
- Line breaks: `\n` → `<br/>`

### Vision API Integration (TODO)

The backend includes detailed comments on integrating OpenAI's vision API:

```kotlin
// To add vision support:
// 1. Check if input.images is not empty
// 2. Use OpenAI's chat completion with vision model (e.g., gpt-4-vision-preview)
// 3. Format the message content as an array with text and image_url parts
// 4. Each image_url should have the format: 
//    { "type": "image_url", "image_url": { "url": "data:image/png;base64,..." } }
// 5. The images are already base64-encoded from the frontend

// Example structure:
val content = listOf(
    mapOf("type" to "text", "text" to input.value),
    *input.images.map { base64 ->
        mapOf("type" to "image_url", "image_url" to mapOf("url" to base64))
    }.toTypedArray()
)
```

You'll need to modify the `OpenAiIdeCodexBridge` to support vision models and multi-part content messages.

### Window Extensions (Vulkan/LWJGL)

**File:** `src/jvmMain/kotlin/afield/four/util/Window.kt`

Added methods:

1. **`saveFramebufferToTempFile(imageData: ByteBuffer?, width: Int, height: Int): File`**
   - Converts ByteBuffer RGBA pixels to PNG format
   - Saves to a temporary file with timestamp in filename
   - Returns File object pointing to the saved screenshot
   - Throws exception if imageData is null or buffer size is invalid

2. **`getFramebufferAsBase64(imageData: ByteBuffer?, width: Int, height: Int): String?`**
   - Converts pixels to PNG format
   - Returns Base64-encoded string
   - Suitable for network transfer or embedding in HTML
   - Returns null on error

3. **`bufferToPngBytes(buffer: ByteBuffer, width: Int, height: Int): ByteArray`**
   - Helper to convert raw RGBA bytes to PNG format
   - Handles vertical flip for OpenGL/Vulkan coordinate system
   - Uses custom PNG encoder to avoid AWT dependencies

4. **`encodePng(pixels: IntArray, width: Int, height: Int): ByteArray`**
   - Pure Kotlin PNG encoder
   - Creates valid PNG files without external dependencies
   - Handles IHDR, IDAT, and IEND chunks with proper CRC calculation

**Dependencies:**
- Standard Java NIO for file operations
- Standard Java ZIP for DEFLATE compression
- No AWT or javax.imageio dependencies

## Testing Checklist

- [ ] **Frontend - Paste Image**
  1. Copy an image from another application
  2. Open chat interface at http://localhost:8080/chat
  3. Click in chat input and paste (Ctrl+V)
  4. Verify image preview appears
  5. Type a message and press Enter
  6. Verify image is sent (check browser console)

- [ ] **Frontend - Upload Image**
  1. Open chat interface
  2. Click the 📎 button
  3. Select one or more image files
  4. Verify previews appear
  5. Type a message and press Enter
  6. Verify images are sent

- [ ] **Frontend - Remove Image**
  1. Add one or more images via paste or upload
  2. Click the × button on a preview
  3. Verify image is removed from preview
  4. Send message and verify only remaining images are sent

- [ ] **Backend - Chat Handler**
  1. Send a chat message with images
  2. Check server logs for "Chat connection from..." message
  3. Verify intermediate "Thinking..." response appears
  4. Verify final response is formatted with HTML
  5. Check that image count is logged correctly

- [ ] **Window - Save Screenshot**
  1. Render something in a Vulkan window
  2. Call `val file = window.saveFramebufferToTempFile(pixels, w, h)`
  3. Check that file exists and is a valid PNG
  4. Open the file in an image viewer
  5. Verify the rendered content appears correctly

- [ ] **Window - Base64 Export**
  1. Render something in a Vulkan window
  2. Call `val b64 = window.getFramebufferAsBase64(pixels, w, h)`
  3. Verify the string starts with base64 characters
  4. Decode and verify it's a valid PNG

## Known Limitations

1. **Vision API Integration**: The backend currently only acknowledges images but doesn't send them to OpenAI. This requires:
   - Using a vision-capable model (gpt-4-vision-preview, gpt-4o, etc.)
   - Modifying the OpenAI bridge to support multi-part content messages
   - Implementing proper error handling for large images

2. **Image Size Limits**: 
   - Base64 encoding increases size by ~33%
   - Large images may exceed WebSocket message limits
   - Consider adding client-side image resizing before upload
   - OpenAI has specific size/resolution limits for vision inputs

3. **Vulkan Framebuffer Reading**:
   - The Window methods accept ByteBuffer but don't directly read from Vulkan
   - You need to implement Vulkan-specific code to:
     - Create staging buffers
     - Copy swapchain images
     - Map memory and read pixels
   - This is deliberately separated for flexibility

4. **Format Support**:
   - Frontend accepts any image/* MIME type
   - Backend assumes images are already in a format OpenAI accepts
   - May need server-side image conversion/validation

## Future Enhancements

1. **Client-side Image Processing**
   - Add image resizing to reduce upload size
   - Add image compression controls
   - Support image rotation/cropping

2. **Advanced Vision Features**
   - Support for video frames
   - OCR text extraction
   - Image annotation and markup

3. **Vulkan Integration**
   - Add convenience methods to capture Vulkan swapchain images
   - Support multiple image formats (RGBA, RGB, etc.)
   - Optimize memory usage and copying

4. **UI Improvements**
   - Drag-and-drop image upload
   - Image thumbnails in chat history
   - Zoom/preview for larger images
   - Progress indicators for large uploads

## Example: Complete Flow

```kotlin
// 1. User pastes image in browser
// Frontend: Main.kt
// - Clipboard event captured
// - File converted to base64
// - Preview shown with remove button

// 2. User presses Enter
// Frontend: sendChatMessage()
val message = Chat_input(
    value = "What do you see in this image?",
    phase = 0,
    images = listOf("data:image/png;base64,iVBORw0KGgo...")
)
sendWithReply(Message("chat_input").payload(message), "input") { ... }

// 3. Backend receives message
// Backend: Ai4Installer.kt
handlers["chat_input"] = { message, reply ->
    val input = Json.decodeFromJsonElement<Chat_input>(message.payload!!)
    // input.images contains the base64 strings
    
    // Send to AI (when vision is integrated)
    val result = chatWithVision(input.value, input.images)
    
    // Send formatted response
    reply(Chat_input(value = result, phase = 20))
}

// 4. Response displayed in browser
// Frontend: Message handler updates innerHTML
```

## Architecture Diagram

```
┌─────────────┐                  ┌──────────────┐                 ┌─────────────┐
│   Browser   │                  │    Server    │                 │   OpenAI    │
│  (Kotlin/JS)│                  │ (Kotlin/JVM) │                 │     API     │
└──────┬──────┘                  └──────┬───────┘                 └──────┬──────┘
       │                                │                                 │
       │  1. Paste/Upload Image         │                                 │
       ├─> readImageFile()              │                                 │
       │   (converts to base64)         │                                 │
       │                                │                                 │
       │  2. Send via WebSocket         │                                 │
       │   Message("chat_input")        │                                 │
       ├───────────────────────────────>│                                 │
       │   Chat_input(text, images=[])  │                                 │
       │                                │                                 │
       │  3. Intermediate Response      │                                 │
       │<───────────────────────────────┤                                 │
       │   phase=1, "Thinking..."       │                                 │
       │                                │                                 │
       │                                │  4. Chat Request                │
       │                                │  (with images when integrated)  │
       │                                ├────────────────────────────────>│
       │                                │                                 │
       │                                │  5. AI Response                 │
       │                                │<────────────────────────────────┤
       │                                │                                 │
       │  6. Final Response             │                                 │
       │<───────────────────────────────┤                                 │
       │   phase=20, formatted HTML     │                                 │
       │                                │                                 │

┌─────────────┐
│   Vulkan    │
│   Window    │
└──────┬──────┘
       │
       │  saveFramebufferToTempFile()
       ├─> bufferToPngBytes()
       │   (converts pixels to PNG format)
       │
       │  encodePng() (custom PNG encoder)
       └─> Temporary PNG File
```

## Files Modified

1. `src/commonMain/kotlin/rpc.kt` - Added `images` field to `Chat_input`
2. `src/jsChatMain/kotlin/Main.kt` - Full image paste/upload UI implementation
3. `src/jvmMain/kotlin/afield/ai/core/install/Ai4Installer.kt` - Chat message handlers
4. `src/jvmMain/kotlin/afield/four/util/Window.kt` - Temp file save and export methods

## Dependencies

No new external dependencies were added. The implementation uses:
- Existing kotlinx-html and kotlinx-css libraries
- Standard Web APIs (FileReader, Clipboard for paste)
- Java NIO (already available in JVM)
- Java ZIP for DEFLATE compression (already available)
- LWJGL (already in project for Vulkan/GLFW)
- No AWT dependencies

## Conclusion

This feature provides a complete foundation for image support in the AI chat interface. The frontend and infrastructure are production-ready. The main remaining work is integrating with OpenAI's vision API in the backend, which is well-documented and straightforward to implement when needed.
