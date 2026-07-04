# Field4 Offline Viewer

The offline viewer allows you to view Field4 documents (`.edn` metadata files) in a web browser without running the Field4 server. This is perfect for documentation, sharing examples, and viewing Field projects on systems where Field4 is not installed.

## Features

- **No Backend Required**: Pure client-side rendering of Field documents
- **Read-Only Mode**: View boxes and their code without execution
- **Monaco Editor Integration**: Syntax-highlighted code viewing
- **Property Switching**: Switch between different code properties (code, glsl, python, etc.)
- **Beautiful Documentation**: Clean, professional presentation of Field projects
- **One-Click Local Opening**: Direct link to open the document in Field4 desktop app

## Usage

### Local Files

To view a local Field document:

```bash
# Start a simple HTTP server in your project directory
python3 -m http.server 8000

# Open the viewer in your browser
http://localhost:8000/offline.html?file=metadata.edn
```

### Remote Files

You can also view Field documents from any URL:

```
http://your-server.com/offline.html?file=http://example.com/path/to/metadata.edn
```

### Publishing Documentation

1. Build a static bundle:
   ```bash
   ./build-offline-docs.sh
   ```

2. Copy `dist/offline-viewer/` to your web server. It contains:
   - `offline.html`
   - `edn-parser.js`
   - `offline-viewer.js`
   - `examples-gallery.html`
   - `iosevka.css` and `woff2/` (fonts)
   - `min/vs/` directory (Monaco editor)
   - `metadata_store/` example documents

3. Create an HTML page linking to your Field documents:

```html
<a href="offline.html?file=examples/hello-world.edn">
  View Hello World Example
</a>
```

## How It Works

1. **EDN Parser**: A lightweight JavaScript parser reads Field's EDN metadata format
2. **Box Rendering**: Boxes are rendered in their correct positions on a pannable canvas
3. **Monaco Editor**: Provides syntax highlighting for all supported languages
4. **Read-Only**: No WebSocket connection, no code execution - pure documentation

## Limitations

- Read-only: Cannot edit or execute code
- No graphics: Cannot display visual output from boxes
- No real-time updates: Shows a snapshot of the document
- Basic EDN support: Only supports the EDN features used by Field4 metadata

## Converting Existing Documents

All Field4 documents saved as `metadata.edn` files are automatically compatible with the offline viewer. No conversion needed!

## Integration with Field4

When viewing a document in offline mode, click the "🚀 Open in Field4" button to open the same document in the desktop application (if Field4 is running locally).

## Browser Compatibility

- Modern Chrome, Firefox, Safari, Edge
- Requires JavaScript enabled
- Monaco editor requires ES2015+ support

## Development

The offline viewer is built with:
- Vanilla JavaScript (no frameworks)
- Monaco Editor (from VS Code)
- Custom EDN parser
- Iosevka Web font

Source files:
- `src/commonMain/resources/offline.html` - Main HTML page
- `src/commonMain/resources/edn-parser.js` - EDN format parser
- `src/commonMain/resources/offline-viewer.js` - Viewer logic
