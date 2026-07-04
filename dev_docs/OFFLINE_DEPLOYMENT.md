# Offline Viewer Deployment Guide

This guide shows you how to deploy the Field4 offline viewer to various static hosting platforms.

## Quick Start

The offline viewer consists of these files:

```
offline.html          # Main viewer page
edn-parser.js         # EDN format parser
offline-viewer.js     # Viewer logic
examples-gallery.html # Optional: example gallery
iosevka.css          # Font styles (from main Field4)
woff2/               # Iosevka font files
min/vs/              # Monaco editor (from main Field4)
outputareas.css      # Styles (from main Field4)
metadata_store/      # Optional example documents
```

## Deployment Options

### Option 1: GitHub Pages

1. Build the offline viewer bundle:
   ```bash
   ./build-offline-docs.sh
   ```
2. Copy the generated bundle:
   ```bash
   rm -rf docs
   cp -r dist/offline-viewer docs
   ```
3. Enable GitHub Pages in repository settings (source: `docs/` folder)
4. Access at: `https://yourusername.github.io/yourrepo/offline.html?file=metadata_store/example.edn`

### Option 2: Netlify

1. Create a `public/` folder with all required files
2. Add a `netlify.toml`:
   ```toml
   [build]
     publish = "public"
   
   [[redirects]]
     from = "/viewer"
     to = "/offline.html"
     status = 200
   ```
3. Deploy: `netlify deploy --prod --dir=public`

### Option 3: Vercel

1. Create a `public/` folder with all required files
2. Add `vercel.json`:
   ```json
   {
     "routes": [
       { "src": "/viewer", "dest": "/offline.html" }
     ]
   }
   ```
3. Deploy: `vercel --prod`

### Option 4: Any Static Host

Simply upload all files to any web server or CDN. The viewer works with:
- Amazon S3 + CloudFront
- Firebase Hosting
- Cloudflare Pages
- Google Cloud Storage
- Azure Static Web Apps
- Any traditional web hosting

## File Structure

Recommended directory structure:

```
public/
├── offline.html
├── edn-parser.js
├── offline-viewer.js
├── examples-gallery.html
├── iosevka.css
├── woff2/
├── outputareas.css
├── min/
│   └── vs/
│       ├── loader.js
│       └── ...
└── docs/
    ├── example1.edn
    ├── example2.edn
    └── ...
```

## Creating Beautiful Documentation Sites

### Example: Documentation Hub

Create an `index.html` that links to all your examples:

```html
<!DOCTYPE html>
<html>
<head>
    <title>My Field4 Projects</title>
    <link rel="stylesheet" href="iosevka.css">
    <style>
        /* Use the styles from examples-gallery.html */
    </style>
</head>
<body>
    <h1>My Field4 Projects</h1>
    <div class="gallery">
        <a href="offline.html?file=docs/project1.edn">Project 1</a>
        <a href="offline.html?file=docs/project2.edn">Project 2</a>
    </div>
</body>
</html>
```

### Example: Course Materials

For educational content, organize by topic:

```
public/
├── offline.html
├── basics/
│   ├── hello-world.edn
│   └── variables.edn
├── graphics/
│   ├── simple-shapes.edn
│   └── animations.edn
└── advanced/
    ├── shaders.edn
    └── optimization.edn
```

Link to them:
```
offline.html?file=basics/hello-world.edn
offline.html?file=graphics/animations.edn
```

## Custom Branding

You can customize the offline viewer:

### Change Colors

Edit the CSS variables in `offline.html`:

```css
:root {
    --field-cyan-rgb: 72, 201, 255;  /* Change to your brand color */
    --field-cyan: rgb(var(--field-cyan-rgb));
}
```

### Custom Banner

Modify the offline banner in `offline.html`:

```html
<div class="offline-banner">
    🎨 Your Project Name — Interactive Documentation
</div>
```

### Custom Help Text

Edit the help overlay in `offline-viewer.js` function `setupHelpOverlay()`.

## CORS Considerations

If loading `.edn` files from a different domain, ensure CORS headers are set:

```
Access-Control-Allow-Origin: *
```

For S3:
```json
{
  "CORSRules": [{
    "AllowedOrigins": ["*"],
    "AllowedMethods": ["GET"],
    "AllowedHeaders": ["*"]
  }]
}
```

## Performance Tips

1. **Compress Monaco**: The `min/vs/` directory can be gzipped
2. **CDN**: Use a CDN for fonts and Monaco editor
3. **Lazy Loading**: Load Monaco only when a box is selected
4. **Cache Headers**: Set long cache times for static assets

## Security Notes

- The viewer runs entirely client-side
- No code execution - only display
- Safe to host on public sites
- `.edn` files are just data

## Testing Locally

Simple Python server:
```bash
python3 -m http.server 8000
# Visit: http://localhost:8000/offline.html?file=example.edn
```

Node.js:
```bash
npx http-server -p 8000
```

## Troubleshooting

**Monaco editor not loading:**
- Check that `min/vs/loader.js` exists
- Check browser console for 404 errors
- Ensure paths are relative to `offline.html`

**EDN file not loading:**
- Check CORS if loading from different domain
- Verify file path is correct
- Check browser network tab for errors

**Fonts not loading:**
- Ensure `woff2/` exists beside `iosevka.css`
- Check `iosevka.css` paths
- May need to adjust font paths for your hosting

## Advanced: Build Script

Use the repository build script:

```bash
./build-offline-docs.sh
```

Then deploy `dist/offline-viewer/` to your hosting provider.

## Examples

See these live examples:
- [GitHub Pages example](https://example.com) (placeholder)
- [Netlify example](https://example.com) (placeholder)

## Getting Help

If you encounter issues:
1. Check the browser console for errors
2. Verify all files are accessible
3. Test with the sample `.edn` files first
4. Open an issue on the Field4 repository
