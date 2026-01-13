#!/bin/bash

# Build script for Field4 offline documentation viewer
# This script packages all necessary files for static hosting

set -e

echo "Building Field4 offline documentation viewer..."

# Create dist directory
DIST_DIR="dist/offline-viewer"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

echo "Copying HTML and JavaScript files..."
cp src/commonMain/resources/offline.html "$DIST_DIR/"
cp src/commonMain/resources/edn-parser.js "$DIST_DIR/"
cp src/commonMain/resources/offline-viewer.js "$DIST_DIR/"
cp src/commonMain/resources/examples-gallery.html "$DIST_DIR/"

echo "Copying CSS files..."
if [ -f "src/jsMain/resources/outputareas.css" ]; then
    cp src/jsMain/resources/outputareas.css "$DIST_DIR/"
elif [ -f "src/commonMain/resources/outputareas.css" ]; then
    cp src/commonMain/resources/outputareas.css "$DIST_DIR/"
else
    echo "Warning: outputareas.css not found in jsMain or commonMain resources"
fi

if [ -f "src/jsMain/resources/iosevka.css" ]; then
    cp src/jsMain/resources/iosevka.css "$DIST_DIR/"
elif [ -f "src/commonMain/resources/iosevka.css" ]; then
    cp src/commonMain/resources/iosevka.css "$DIST_DIR/"
else
    echo "Warning: iosevka.css not found in jsMain or commonMain resources"
fi

echo "Copying font files..."
FONTS_COPIED=0
for font_dir in "src/jsMain/resources" "src/commonMain/resources"; do
    if ls "$font_dir"/iosevka-*.woff2 1> /dev/null 2>&1; then
        cp "$font_dir"/iosevka-*.woff2 "$DIST_DIR/"
        FONTS_COPIED=1
        break
    fi
done
if [ $FONTS_COPIED -eq 0 ]; then
    echo "Warning: No font files (iosevka-*.woff2) found in resources directories"
fi

echo "Copying Monaco editor..."
if [ -d "src/jsMain/resources/monaco/min" ]; then
    cp -r src/jsMain/resources/monaco/min "$DIST_DIR/"
elif [ -d "src/commonMain/resources/monaco/min" ]; then
    cp -r src/commonMain/resources/monaco/min "$DIST_DIR/"
else
    echo "Warning: Monaco editor not found. Editor will not work without it."
fi

echo "Copying example metadata files..."
if [ -d "metadata_store" ]; then
    cp -r metadata_store "$DIST_DIR/"
else
    echo "Warning: metadata_store directory not found"
fi

echo "Creating README..."
cat > "$DIST_DIR/README.md" << 'EOF'
# Field4 Offline Viewer

This directory contains the Field4 offline documentation viewer.

## Quick Start

1. Upload all files to a web server or static hosting
2. Access via: `offline.html?file=metadata_store/example.edn`
3. Or open `examples-gallery.html` to browse examples

## Files

- `offline.html` - Main viewer page
- `edn-parser.js` - EDN parser
- `offline-viewer.js` - Viewer logic  
- `examples-gallery.html` - Example gallery
- `min/vs/` - Monaco editor
- `metadata_store/` - Example Field documents

## Deployment

### Local Testing
```bash
python3 -m http.server 8000
# Visit: http://localhost:8000/offline.html?file=metadata_store/simple_hello/metadata.edn
```

### Static Hosts

Works with:
- GitHub Pages
- Netlify
- Vercel
- Amazon S3
- Any static host

See the full deployment guide in the repository documentation.

## Requirements

- Modern web browser with JavaScript enabled
- HTTP server (for local testing)
- No backend required

## More Info

See `docs/OFFLINE_VIEWER.md` and `docs/OFFLINE_DEPLOYMENT.md` in the repository.
EOF

echo "Creating .htaccess for Apache servers..."
cat > "$DIST_DIR/.htaccess" << 'EOF'
# Field4 Offline Viewer Apache Configuration

# Enable compression
<IfModule mod_deflate.c>
    AddOutputFilterByType DEFLATE text/html text/plain text/xml text/css text/javascript application/javascript application/json
</IfModule>

# Set caching headers
<IfModule mod_expires.c>
    ExpiresActive On
    ExpiresByType text/html "access plus 1 hour"
    ExpiresByType text/css "access plus 1 week"
    ExpiresByType text/javascript "access plus 1 week"
    ExpiresByType application/javascript "access plus 1 week"
    ExpiresByType font/woff2 "access plus 1 month"
</IfModule>

# CORS headers (if needed)
<IfModule mod_headers.c>
    Header set Access-Control-Allow-Origin "*"
</IfModule>
EOF

echo "Creating deployment manifest..."
cat > "$DIST_DIR/manifest.json" << EOF
{
  "name": "Field4 Offline Viewer",
  "version": "1.0.0",
  "description": "Static offline documentation viewer for Field4 projects",
  "files": [
    "offline.html",
    "edn-parser.js",
    "offline-viewer.js",
    "examples-gallery.html"
  ],
  "dependencies": {
    "monaco-editor": "bundled in min/vs/",
    "iosevka-font": "bundled"
  },
  "requirements": {
    "browser": "Modern browser with ES2015+ support",
    "javascript": "required",
    "server": "Any static HTTP server"
  }
}
EOF

# Calculate size
DIST_SIZE=$(du -sh "$DIST_DIR" | cut -f1)

echo ""
echo "✅ Build complete!"
echo "📦 Output directory: $DIST_DIR"
echo "💾 Total size: $DIST_SIZE"
echo ""
echo "Next steps:"
echo "1. Test locally: cd $DIST_DIR && python3 -m http.server 8000"
echo "2. Deploy to your static host"
echo "3. Access: http://your-domain.com/offline.html?file=metadata_store/simple_hello/metadata.edn"
echo ""
echo "See docs/OFFLINE_DEPLOYMENT.md for detailed deployment instructions"
