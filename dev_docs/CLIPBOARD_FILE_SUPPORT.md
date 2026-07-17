# Clipboard File Support for metadata.edn

## Overview

This feature extends the clipboard functionality to support exporting copied boxes as `metadata.edn` files that can be pasted directly into Finder (macOS) or File Explorer (Windows).

## Implementation

The implementation uses the modern Clipboard API with `ClipboardItem` to write multiple MIME types simultaneously:

1. **text/plain** - For in-app paste operations (backwards compatible)
2. **application/edn** - For pasting as a file to the filesystem

### Key Changes

#### `copySelectedBoxesToClipboard()` in `commands.kt`

- Now creates a `ClipboardItem` with both text and file representations
- Uses `new File()` constructor to create a proper file with `metadata.edn` filename
- Includes fallback to text-only for browsers without full Clipboard API support
- Gracefully degrades if any part of the API is unavailable

#### `pasteBoxesFromClipboard()` in `commands.kt`

- Enhanced to use `clipboard.read()` to access all clipboard items
- Checks for `application/edn` MIME type first (file)
- Falls back to `text/plain` for text-based paste
- Maintains compatibility with old `clipboard.readText()` API
- Extracted common paste logic into `processPasteText()` helper

## Browser Compatibility

The Clipboard API's `ClipboardItem` file support varies by browser (Chromium-based browsers are the most capable; Safari and Firefox have partial or no file-clipboard support). The implementation includes fallbacks for browsers with limited support, so plain copy/paste of boxes continues to work even when file clipboard isn't available. Verify current support against MDN / browser release notes rather than this doc.

## Testing Instructions

### Manual Testing - Copy as File

1. **Start the Field4 application**
   ```bash
   ./gradlew runJvm
   ```
   (or `./field.sh`, which wraps `runJvm`, or `./gradlew runDesktop`). The
   `runJvm` task carries the required native-library JVM args; the
   auto-generated `jvmRun` task does not and is not what you want here.

2. **In the browser:**
   - Create or select one or more boxes
   - Copy them using Cmd+C (Mac) or Ctrl+C (Windows/Linux)
   - Open Finder/Explorer
   - Use Cmd+V (Mac) or Ctrl+V (Windows) to paste
   - A file named `metadata.edn` should appear

3. **Verify the file contents:**
   - Open the `metadata.edn` file in a text editor
   - It should contain EDN-formatted box data with structure like:
     ```clojure
     {:version 2
      :boxes [{:id "box-id-123"
               :properties {...}}]}
     ```

### Manual Testing - Paste Back

1. **Copy boxes** (as above)
2. **Paste back into Field4:**
   - Use Cmd+V (Mac) or Ctrl+V (Windows/Linux) in the Field4 window
   - Boxes should be pasted with a +10,+10 offset
   - All properties should be preserved

### Manual Testing - Import from File

The existing file drop functionality also works:

1. **Drag a `metadata.edn` file from Finder/Explorer**
2. **Drop it onto the Field4 window**
3. **Boxes should be imported** with new UUIDs

## Technical Details

### MIME Types

- **application/edn** - Custom MIME type for EDN files
- **text/plain** - Standard text format for backwards compatibility

### Data Format

The EDN format matches the existing `exportEdnPackage()` function in `saving.kt`:

```clojure
{:version 2
 :boxes [{:id "uuid-string"
          :properties {:name "Box Name"
                      :frame #afield/rect [x y w h]
                      :code #afield/file-inline {:path "uuid_code_.py" 
                                                  :inline "code content"}}}]}
```

### Security Considerations

- Uses browser's built-in Clipboard API security model
- Requires HTTPS or localhost for clipboard access
- User consent may be required on first use (browser-dependent)
- File writing requires explicit user action (copy command)

## Fallback Strategy

The implementation includes multiple fallback layers:

1. **Try modern Clipboard API with ClipboardItem** (Chrome, Edge, Safari)
2. **Catch exceptions and fallback to text-only** (Firefox, older browsers)
3. **On paste, try read() first, then readText()** (maximum compatibility)

This ensures the feature enhances the experience on supporting browsers while maintaining full functionality on all browsers.

## Related Files

- `src/jsMain/kotlin/afield/commands.kt` - Clipboard operations
- `src/jvmMain/kotlin/afield/saving.kt` - EDN serialization
- `src/jvmMain/kotlin/afield/Server.kt` - Copy/paste message handlers
- `src/jvmMain/kotlin/afield/server/importing.kt` - File import handler
- `src/jsMain/kotlin/afield/Main.kt` - Drop handler registration
