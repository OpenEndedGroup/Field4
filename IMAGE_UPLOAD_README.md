# Image Upload and Window Capture Implementation

This implementation adds comprehensive image support to Field4, including uploading images to AI chat and capturing Vulkan window content to the clipboard.

## Quick Links

- **[IMAGE_UPLOAD_FEATURE.md](IMAGE_UPLOAD_FEATURE.md)** - Complete feature documentation, usage guide, and architecture
- **[VULKAN_CAPTURE_EXAMPLE.md](VULKAN_CAPTURE_EXAMPLE.md)** - Vulkan framebuffer capture integration examples

## What's Implemented

### 1. AI Chat Image Upload (COMPLETE)

**Frontend** (`src/jsChatMain/kotlin/Main.kt`):
- ✅ Paste images from clipboard (Ctrl+V)
- ✅ Upload images via file picker (📎 button)
- ✅ Preview thumbnails with remove buttons
- ✅ Send multiple images with text messages

**Backend** (`src/jvmMain/kotlin/afield/ai/core/install/Ai4Installer.kt`):
- ✅ Receive and process image messages
- ✅ Chat handlers (`hello_chat`, `chat_input`)
- ✅ Markdown formatting for responses
- ✅ Integration hooks for OpenAI vision API (documented, ready to wire up)

**RPC** (`src/commonMain/kotlin/rpc.kt`):
- ✅ Extended `Chat_input` with `images: List<String>` field
- ✅ Base64 encoding for efficient JSON serialization

### 2. Window Clipboard Capture (COMPLETE)

**Window API** (`src/jvmMain/kotlin/afield/four/util/Window.kt`):
- ✅ `copyFramebufferToClipboard()` - Copy rendered content to system clipboard
- ✅ `getFramebufferAsBase64()` - Export as base64 PNG for network transfer
- ✅ `byteBufferToBufferedImage()` - Convert raw RGBA pixels to Java image
- ✅ `ImageSelection` class - Clipboard transfer implementation

## Usage Examples

### Chat: Paste an Image

```javascript
// In browser, user copies an image then:
// 1. Clicks in chat textarea
// 2. Presses Ctrl+V
// 3. Image appears as thumbnail
// 4. Types message and presses Enter
// Image is sent as base64 in Chat_input.images field
```

### Chat: Upload an Image

```javascript
// 1. Click the 📎 button
// 2. Select image file(s)
// 3. Images appear as thumbnails
// 4. Type message and press Enter
```

### Window: Copy to Clipboard

```kotlin
// In your Vulkan rendering code:
val window = lastWindowToOpen
val pixels = readVulkanImagePixels()  // Your implementation
window?.copyFramebufferToClipboard(pixels, width, height)
MemoryUtil.memFree(pixels)
```

### Window: Export as Base64

```kotlin
val base64png = window?.getFramebufferAsBase64(pixels, width, height)
// Send over network or save to file
```

## Integration Guide

### Step 1: Test Chat Image Upload (Ready Now)

1. Start Field4: `./gradlew jvmRun`
2. Open chat: `http://localhost:8080/chat`
3. Paste or upload an image
4. Send a message
5. Check server logs to verify image data received

### Step 2: Enable OpenAI Vision (Requires API Integration)

In `Ai4Installer.kt`, modify the `chat_input` handler:

```kotlin
// Replace the placeholder with:
if (input.images.isNotEmpty()) {
    // Use vision model
    val visionBridge = OpenAiIdeCodexBridge(model = "gpt-4-vision-preview", log = uiLog)
    
    // Format message with images
    val content = buildList {
        add(mapOf("type" to "text", "text" to input.value))
        input.images.forEach { base64 ->
            add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to base64)
            ))
        }
    }
    
    // Send to OpenAI with vision content
    // (You'll need to modify OpenAiIdeCodexBridge to support multi-part content)
}
```

See detailed instructions in IMAGE_UPLOAD_FEATURE.md.

### Step 3: Wire Up Vulkan Capture (Requires Vulkan Code)

See VULKAN_CAPTURE_EXAMPLE.md for complete implementation.

Basic steps:
1. Create staging buffer in host-visible memory
2. Transition swapchain image to TRANSFER_SRC layout
3. Copy image to staging buffer
4. Map buffer memory and read pixels
5. Call `window.copyFramebufferToClipboard(pixels, w, h)`

## File Structure

```
Field4/
├── src/
│   ├── commonMain/kotlin/
│   │   └── rpc.kt                          ← Chat_input with images field
│   │
│   ├── jsChatMain/kotlin/
│   │   └── Main.kt                         ← Image paste/upload UI
│   │
│   └── jvmMain/kotlin/afield/
│       ├── ai/core/install/
│       │   └── Ai4Installer.kt             ← Chat handlers
│       │
│       └── four/util/
│           └── Window.kt                    ← Clipboard methods
│
├── IMAGE_UPLOAD_FEATURE.md                 ← Complete documentation
└── VULKAN_CAPTURE_EXAMPLE.md               ← Vulkan integration guide
```

## Testing Checklist

### Frontend Tests (Browser)
- [ ] Open `http://localhost:8080/chat`
- [ ] Paste image with Ctrl+V - verify preview appears
- [ ] Click 📎 and select image - verify preview appears
- [ ] Click × on preview - verify image removed
- [ ] Send message with images - check browser console for success
- [ ] Verify images included in WebSocket message payload

### Backend Tests (Server)
- [ ] Send chat message with images
- [ ] Check server logs for "Chat connection from..." message
- [ ] Verify intermediate "Thinking..." response
- [ ] Verify final formatted response
- [ ] Check that `input.images.size` is logged correctly

### Window Tests (Vulkan)
- [ ] Capture framebuffer pixels from Vulkan
- [ ] Call `window.copyFramebufferToClipboard()`
- [ ] Open Paint/Photoshop and paste (Ctrl+V)
- [ ] Verify rendered content appears correctly
- [ ] Verify image is not upside down (auto-flipped)

## Architecture

```
Browser (Kotlin/JS)              Server (Kotlin/JVM)            OpenAI API
     │                                   │                          │
     │  1. User pastes/uploads image    │                          │
     ├──> FileReader.readAsDataURL()    │                          │
     │    (converts to base64)          │                          │
     │                                   │                          │
     │  2. WebSocket message             │                          │
     │    Chat_input(text, images=[...]) │                          │
     ├──────────────────────────────────>│                          │
     │                                   │                          │
     │  3. Intermediate response         │                          │
     │    phase=1 "Thinking..."          │                          │
     │<──────────────────────────────────┤                          │
     │                                   │                          │
     │                                   │  4. Chat request         │
     │                                   │    (with vision content) │
     │                                   ├─────────────────────────>│
     │                                   │                          │
     │                                   │  5. AI response          │
     │                                   │<─────────────────────────┤
     │                                   │                          │
     │  6. Final response                │                          │
     │    phase=20 (formatted HTML)      │                          │
     │<──────────────────────────────────┤                          │
     │                                   │                          │

Vulkan Window
     │
     │  copyFramebufferToClipboard()
     ├──> byteBufferToBufferedImage()
     │    (converts RGBA to Java image)
     │
     │  ImageSelection (Transferable)
     └──> System Clipboard
```

## Dependencies

No new external dependencies required. Uses:
- **Frontend**: kotlinx-html, kotlinx-css, Web FileReader API
- **Backend**: Kotlin serialization, existing OpenAI client
- **Window**: Java AWT (Toolkit, BufferedImage, ImageIO), LWJGL Vulkan

## Known Limitations

1. **Vision API Integration**: Backend receives images but doesn't send to OpenAI yet
   - Requires vision-capable model (gpt-4-vision-preview, gpt-4o)
   - Requires multi-part content message format
   - Full integration guide provided in IMAGE_UPLOAD_FEATURE.md

2. **Vulkan Framebuffer**: Window methods ready but need Vulkan-specific wiring
   - Need to read pixels from swapchain images
   - Complete implementation guide in VULKAN_CAPTURE_EXAMPLE.md

3. **Image Size**: No client-side compression/resizing yet
   - Large images increase WebSocket message size
   - Consider adding image optimization before upload

## Next Steps

### Immediate (No Code Changes)
1. Test chat interface image paste/upload
2. Verify backend receives images correctly
3. Test Window.copyFramebufferToClipboard() with dummy data

### Short Term (API Integration)
1. Add vision model support to OpenAiIdeCodexBridge
2. Format messages with image_url content
3. Handle vision API responses

### Medium Term (Vulkan Integration)
1. Implement Vulkan image capture in render loop
2. Add keyboard shortcut for screenshots (F12)
3. Add Box-based screenshot management

### Long Term (Enhancements)
1. Client-side image resizing/compression
2. Drag-and-drop image upload
3. Image history in chat
4. Multiple chat threads with separate image contexts

## Support

For questions or issues:
- See detailed documentation in IMAGE_UPLOAD_FEATURE.md
- Check Vulkan examples in VULKAN_CAPTURE_EXAMPLE.md
- Review code comments in modified files
- Test with provided checklists

## License

This implementation follows the Field4 project license.
