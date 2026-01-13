# Quick Start: Field4 Offline Viewer

Get started with the offline documentation viewer in 5 minutes!

## For Users (Viewing Documentation)

### Online Viewing

If the documentation is already hosted, just visit:
```
https://example.com/offline.html?file=path/to/document.edn
```

### Local Viewing

1. Download the documentation files
2. Start a local web server:
   ```bash
   cd path/to/files
   python3 -m http.server 8000
   ```
3. Open in browser:
   ```
   http://localhost:8000/offline.html?file=example.edn
   ```

### Navigation

- **Click on boxes** to view their code
- **Drag with middle mouse** or **Shift+drag** to pan
- **Mouse wheel** to scroll
- **Arrow keys** to navigate
- **Escape** to close editor
- **?** for help

## For Developers (Creating Documentation)

### Quick Setup

1. Create your Field4 document in Field4 desktop app
2. Save it (creates a `metadata.edn` file)
3. Copy the offline viewer files to your project:
   ```bash
   # Run the build script
   ./build-offline-docs.sh
   
   # Copy your metadata files
   cp my-project/metadata.edn dist/offline-viewer/
   ```
4. Test locally:
   ```bash
   cd dist/offline-viewer
   python3 -m http.server 8000
   ```
5. Open: `http://localhost:8000/offline.html?file=metadata.edn`

### Publishing to GitHub Pages

1. Copy viewer files to `docs/` in your repository:
   ```bash
   mkdir -p docs
   cp dist/offline-viewer/* docs/
   ```

2. Enable GitHub Pages in repository settings:
   - Go to Settings → Pages
   - Source: Deploy from branch
   - Branch: main, folder: /docs

3. Access at: `https://yourusername.github.io/yourrepo/offline.html?file=metadata.edn`

### Creating an Example Gallery

1. Copy `examples-gallery.html` to your `docs/` folder
2. Edit it to add your examples:
   ```html
   <a href="offline.html?file=my-example.edn" class="card">
       <div class="card-title">My Example</div>
       <div class="card-description">Description here</div>
   </a>
   ```
3. Access at: `https://yourusername.github.io/yourrepo/examples-gallery.html`

## For Content Creators (Writing Tutorials)

### Embedding in Webpages

Add this to your HTML:

```html
<iframe 
    src="offline.html?file=example.edn" 
    width="100%" 
    height="600px"
    style="border: none; border-radius: 8px;">
</iframe>
```

### Embedding in Markdown (with iframe support)

```markdown
# My Tutorial

Here's a live example:

<iframe src="offline.html?file=tutorial.edn" width="100%" height="600"></iframe>

You can interact with the boxes and view the code!
```

### Multiple Examples

Create a page with tabs or links:

```html
<div class="examples">
    <button onclick="loadExample('basic.edn')">Basic</button>
    <button onclick="loadExample('advanced.edn')">Advanced</button>
</div>

<iframe id="viewer" src="offline.html?file=basic.edn"></iframe>

<script>
function loadExample(file) {
    document.getElementById('viewer').src = 'offline.html?file=' + file;
}
</script>
```

## Common Use Cases

### Use Case 1: API Documentation

Create a Field document showing API usage examples, host it on GitHub Pages:

```
docs/
├── offline.html
├── api-basics.edn
├── api-advanced.edn
└── examples-gallery.html
```

Link from your README:
```markdown
[View Interactive Examples](https://yourorg.github.io/project/examples-gallery.html)
```

### Use Case 2: Course Materials

Create a course with multiple lessons:

```
course/
├── offline.html
├── lesson-1-intro.edn
├── lesson-2-basics.edn
├── lesson-3-advanced.edn
└── index.html (gallery)
```

Students can click through lessons and see working code.

### Use Case 3: Project Portfolio

Showcase your Field4 projects:

```
portfolio/
├── offline.html
├── project-generative-art.edn
├── project-data-viz.edn
├── project-music.edn
└── index.html (gallery with descriptions)
```

Host on your personal website or GitHub Pages.

## Tips and Tricks

### Tip 1: Descriptive File Names
```
good: shader-raymarching-example.edn
bad:  example1.edn
```

### Tip 2: Organize by Topic
```
docs/
├── graphics/
│   ├── shaders.edn
│   └── shapes.edn
├── data/
│   ├── plotting.edn
│   └── analysis.edn
```

### Tip 3: Add Metadata to EDN Files
```clojure
{:version 2
 :description "Demonstrates shader programming"
 :tags ["graphics" "shaders" "glsl"]
 :boxes [...]}
```

### Tip 4: Create Index Pages
Don't just link to individual files - create beautiful gallery pages that showcase all your examples.

### Tip 5: Mobile Friendly
The viewer is responsive! Test on mobile:
- Pan with touch gestures
- Select boxes with tap
- Pinch to zoom (browser default)

## Troubleshooting

**"Failed to load document"**
- Check the file path is correct
- Ensure file is in the same origin (or CORS is enabled)
- Try opening the file directly to see if it exists

**Monaco editor not showing**
- Check that `min/vs/` directory is present
- Look for 404 errors in browser console
- Verify paths are relative to `offline.html`

**Boxes not rendering**
- Open browser console for errors
- Verify EDN file is valid (try parsing manually)
- Check that `frame` property exists: `[x, y, width, height]`

**Can't pan/zoom**
- Check that JavaScript is enabled
- Try middle mouse button or Shift+drag
- Use arrow keys as fallback

## Next Steps

- Read full documentation: [OFFLINE_VIEWER.md](OFFLINE_VIEWER.md)
- Deployment guide: [OFFLINE_DEPLOYMENT.md](OFFLINE_DEPLOYMENT.md)
- See embed example: `embed-example.html`
- Build script: `build-offline-docs.sh`

## Support

Having issues? Check:
1. Browser console for errors
2. Network tab for failed requests
3. Verify all files are present
4. Test with simple example first

## License

The offline viewer is part of Field4 and uses the same license.

---

**Ready to create beautiful documentation?** 🚀

Start with: `./build-offline-docs.sh` and `python3 -m http.server 8000`
