# Using Images in Slideshows

## Overview

The Field4 slideshow system now supports serving images from your project directory (workspace). This allows you to create rich, visual slideshows with diagrams, screenshots, and other media.

## How It Works

Images and other files stored in your project directory (`$home` / workspace) are served via the `/project/` route. This provides a secure, dedicated endpoint for accessing project files.

## Quick Start

### 1. Add Images to Your Project

Place images in your project directory (workspace folder):

```
~/Documents/AFieldWorkspace/
  └── myproject/
      ├── metadata.edn
      ├── diagram.png
      └── images/
          ├── screenshot.jpg
          └── icon.svg
```

### 2. Reference Images in Slideshow Notes

Use the `/project/` prefix in your HTML:

```kotlin
slideShow {
    step("Visual Guide") {
        note("""
            <h3>Architecture Overview</h3>
            <img src="/project/diagram.png" 
                 alt="System architecture" 
                 style="max-width: 100%; border-radius: 4px;">
            <p>The diagram shows our key components.</p>
            <a href="#slideshow:next">Next →</a>
        """)
    }
}
```

## Image Path Examples

| File Location | URL Path |
|---------------|----------|
| `diagram.png` (in project root) | `/project/diagram.png` |
| `images/photo.jpg` | `/project/images/photo.jpg` |
| `docs/screenshots/ui.png` | `/project/docs/screenshots/ui.png` |

**Important:** Always use forward slashes (`/`) in paths, even on Windows.

## Best Practices

### 1. Image Sizing

Use CSS to ensure images fit well in the slideshow overlay:

```html
<img src="/project/large-image.png" style="max-width: 100%; height: auto;">
```

### 2. Alt Text

Always provide descriptive alt text for accessibility:

```html
<img src="/project/chart.png" alt="Performance comparison chart">
```

### 3. Organization

Keep images organized in subdirectories:

```
project/
├── images/          # General images
├── screenshots/     # UI screenshots
├── diagrams/        # Technical diagrams
└── assets/          # Icons and small graphics
```

### 4. File Formats

Recommended formats:
- **PNG**: Screenshots, diagrams with transparency
- **JPG**: Photos, complex images
- **SVG**: Icons, logos, scalable graphics
- **GIF**: Simple animations (use sparingly)

## Security

The `/project/` route includes security checks to prevent directory traversal attacks:
- Files must exist within the project directory
- Canonical path validation prevents `../` attacks
- Only files (not directories) are served

## Example Slideshow with Images

```kotlin
fun Box.visualTutorial() {
    val show = slideShow {
        step("Introduction") {
            note("""
                <h2>Welcome to the Visual Guide</h2>
                <img src="/project/welcome.png" 
                     alt="Welcome banner"
                     style="max-width: 100%; margin: 15px 0;">
                <p>This tour includes visual aids to help you learn.</p>
                <a href="#slideshow:next">Start →</a>
            """)
        }
        
        step("Architecture") {
            note("""
                <h3>System Architecture</h3>
                <img src="/project/diagrams/architecture.svg" 
                     alt="Architecture diagram"
                     style="max-width: 100%; background: white; padding: 10px; border-radius: 4px;">
                <p>Our system consists of three main components.</p>
                <a href="#slideshow:next">Continue →</a>
            """)
        }
        
        step("Screenshot") {
            note("""
                <h3>User Interface</h3>
                <img src="/project/screenshots/ui.jpg" 
                     alt="Application screenshot"
                     style="max-width: 100%; border: 2px solid #666; box-shadow: 0 2px 8px rgba(0,0,0,0.3);">
                <p>This is what the interface looks like.</p>
                <a href="#slideshow:previous">← Back</a> | 
                <a href="#slideshow:stop">Finish</a>
            """)
        }
    }
    
    GlobalScope.launch { show.start() }
}
```

## Troubleshooting

### Image Not Displaying

1. **Check file path**: Verify the file exists in your project directory
2. **Check console**: Look for 404 errors in browser console
3. **Check permissions**: Ensure the file is readable
4. **Check extension**: Use common formats (png, jpg, svg, gif)

### Image Too Large

Use CSS to constrain size:

```html
<img src="/project/huge-image.png" 
     style="max-width: 100%; max-height: 400px; object-fit: contain;">
```

### Server Logs

Enable debug logging to see file access attempts:

```
[debug,server] routing /project for http://localhost:8080/project/myimage.png
```

If files aren't found, check:
- File name spelling and case (case-sensitive on Linux/macOS)
- File location relative to project directory
- File permissions

## Advanced: Multiple Image Formats

For high-resolution displays, consider using `srcset`:

```html
<img src="/project/image.png"
     srcset="/project/image@2x.png 2x, /project/image@3x.png 3x"
     alt="High-res image"
     style="max-width: 100%;">
```

## Related Documentation

- **[SLIDESHOW_FEATURE.md](SLIDESHOW_FEATURE.md)**: Complete slideshow feature reference
- **[SLIDESHOW_QUICKSTART.md](SLIDESHOW_QUICKSTART.md)**: Getting started guide
- **[slideshow_example.kt](src/jvmMain/kotlin/afield/examples/slideshow_example.kt)**: Code examples

## Technical Details

The `/project/` route is implemented in `Server.kt`:
- Serves files from the `$home` directory (workspace)
- Includes security validation to prevent path traversal
- Supports all file types (images, PDFs, etc.)
- Uses Ktor's `respondFile()` with automatic MIME type detection

Example request flow:
```
Browser: GET /project/images/diagram.png
  ↓
Server: Resolve to $home/images/diagram.png
  ↓
Server: Validate path is within $home
  ↓
Server: Serve file with appropriate Content-Type
  ↓
Browser: Display image in slideshow note
```
