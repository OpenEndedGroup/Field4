# Documentation Iframe Mode

Field now supports a documentation iframe mode that allows you to display documentation pages alongside your work, with full bidirectional communication between Field and the documentation page.

## Overview

The documentation iframe mode replaces the Monaco editor with an iframe of the same size and position. Documentation pages loaded in this iframe can control Field through a JavaScript API, enabling:

- Interactive tutorials
- Live code examples
- Custom UI for specific workflows
- Context-sensitive documentation

## Switching Modes

### From Field's Command Menu

1. Press `F1` or `Cmd/Ctrl+P` to open the command menu
2. Type "Switch to Documentation Iframe"
3. Enter a URL (can be local or remote)

### From Code

```javascript
// Switch to iframe mode with a URL
window.switchToDocsIframe('http://localhost:9092/sample-docs.html');

// Switch back to Monaco editor
window.switchToMonaco();
```

## Creating Documentation Pages

### Basic Structure

```html
<!DOCTYPE html>
<html>
<head>
    <title>My Field Documentation</title>
</head>
<body>
    <h1>Documentation</h1>
    <button onclick="FieldAPI.switchToEditor()">Back to Editor</button>
    
    <script>
        // Check if API is available
        if (window.FieldAPI) {
            console.log('Field API is ready!');
        }
    </script>
</body>
</html>
```

### Field API Reference

The `FieldAPI` object is automatically injected into pages loaded in the documentation iframe. It provides the following methods:

#### Box Control

```javascript
// Execute code in a specific box
FieldAPI.executeBox(uid, code);

// Launch boxes (start their execution loops)
FieldAPI.beginBoxes(['box-uid-1', 'box-uid-2']);

// Stop running boxes
FieldAPI.endBoxes(['box-uid-1']);
```

#### Selection & Navigation

```javascript
// Select specific boxes
FieldAPI.selectBoxes(['box-uid-1'], false); // Replace selection
FieldAPI.selectBoxes(['box-uid-2'], true);  // Add to selection

// Navigate to a box (scroll and optionally select)
FieldAPI.navigateToBox('box-uid-1', true);

// Get current selection
FieldAPI.getSelection((selection) => {
    console.log('Selected boxes:', selection.boxes);
    console.log('Current property:', selection.property);
    
    // Each box has: uid, name, code, frame {x, y, w, h}
});
```

#### Editor Control

```javascript
// Switch to editing a different property
FieldAPI.switchToProperty('code');      // Main Kotlin code
FieldAPI.switchToProperty('vertex');    // Vertex shader
FieldAPI.switchToProperty('fragment');  // Fragment shader
FieldAPI.switchToProperty('compute');   // Compute shader

// Return to Monaco editor from docs iframe
FieldAPI.switchToEditor();
```

#### Box Creation

```javascript
// Create a new box
FieldAPI.createBox({
    name: 'New Box',
    code: 'println("Hello!")',
    frame: { x: 100, y: 100, w: 200, h: 150 }
});
```

#### Commands

```javascript
// Execute a Field command
FieldAPI.runCommand('commandName', { arg1: 'value' });
```

## Example: Interactive Tutorial

```html
<!DOCTYPE html>
<html>
<head>
    <title>Field Tutorial</title>
    <style>
        body { 
            font-family: monospace; 
            background: #1e1e1e; 
            color: #e6e6e6; 
            padding: 20px; 
        }
        button { 
            background: rgba(72, 201, 255, 0.2); 
            border: 1px solid rgb(72, 201, 255);
            color: rgb(72, 201, 255);
            padding: 10px 20px;
            cursor: pointer;
            font-family: inherit;
        }
    </style>
</head>
<body>
    <h1>Interactive Tutorial: Hello World</h1>
    
    <p>This tutorial will guide you through creating your first Field box.</p>
    
    <h2>Step 1: Create a Box</h2>
    <button onclick="step1()">Create Hello World Box</button>
    
    <h2>Step 2: Execute It</h2>
    <button onclick="step2()">Run the Code</button>
    
    <h2>Step 3: Switch to Fragment Shader</h2>
    <button onclick="step3()">Edit Fragment Shader</button>
    
    <button onclick="FieldAPI.switchToEditor()">Finish Tutorial</button>
    
    <script>
        let createdBoxUid = null;
        
        function step1() {
            FieldAPI.createBox({
                name: 'Hello World',
                code: 'println("Hello from Field!")'
            });
            
            // Get the newly created box
            setTimeout(() => {
                FieldAPI.getSelection((sel) => {
                    if (sel.boxes.length > 0) {
                        createdBoxUid = sel.boxes[0].uid;
                    }
                });
            }, 500);
        }
        
        function step2() {
            if (!createdBoxUid) {
                alert('Please complete step 1 first!');
                return;
            }
            FieldAPI.executeBox(createdBoxUid);
        }
        
        function step3() {
            FieldAPI.switchToProperty('fragment');
        }
    </script>
</body>
</html>
```

## Local Development

Documentation pages can be:
1. Served from Field's built-in server (place in `src/commonMain/resources/`)
2. Served from any external server
3. Loaded from `file://` URLs (with appropriate sandbox settings)

### Example URLs

```javascript
// Local resource served by Field
window.switchToDocsIframe('http://localhost:9092/sample-docs.html');

// External documentation
window.switchToDocsIframe('https://your-docs-site.com/field-guide.html');

// Local file (may have CORS restrictions)
window.switchToDocsIframe('file:///path/to/docs.html');
```

## Security

The iframe uses the following sandbox attributes:
- `allow-scripts`: JavaScript can run in the iframe
- `allow-same-origin`: Same-origin content can access parent
- `allow-forms`: Forms can be submitted

The API uses `postMessage` for communication, which is safe for cross-origin content. However, be cautious about loading untrusted documentation pages as they can control Field.

## Sample Documentation

See `src/commonMain/resources/sample-docs.html` for a complete example demonstrating all API features.

## Tips

1. **Always provide a back button**: Include a way to return to the editor
2. **Check API availability**: Always verify `window.FieldAPI` exists before using it
3. **Use callbacks for selection**: `getSelection()` is async, use the callback pattern
4. **Handle errors gracefully**: API calls may fail if boxes don't exist
5. **Style for Field's dark theme**: Documentation looks best with dark backgrounds

## Troubleshooting

**API not available**: Ensure the page is loaded in Field's iframe mode, not a regular browser

**Can't control boxes**: Make sure you have the correct UIDs - use `getSelection()` to inspect

**CORS errors**: If loading external content, ensure the server sends appropriate CORS headers

**Iframe not showing**: Check browser console for errors, verify the URL is accessible
