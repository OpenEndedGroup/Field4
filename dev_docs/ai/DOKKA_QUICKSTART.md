# Quick Start: API Documentation with Dokka

This guide will help you quickly generate and view API documentation for Field4.

## Prerequisites

- Java 24 (configured in the project)
- Gradle (via the provided gradlew wrapper)
- Python with MkDocs (for building the documentation site)

## Generating Documentation

### Option 1: Using the Helper Script (Recommended)

```bash
./generate-docs.sh
```

This will:
1. Generate HTML documentation using Dokka
2. Place the files in `build/dokka/html/`
3. Show you next steps

### Option 2: Using Gradle Directly

Generate HTML documentation:
```bash
./gradlew generateApiDocs
```

Or just run the Dokka task directly:
```bash
./gradlew dokkaGenerateHtml
```

Generate all supported Dokka publications:
```bash
./gradlew dokkaGenerate
# Output: build/dokka/html/index.html
```

## Viewing the Documentation

### View HTML Files Directly

The generated HTML files are in `build/dokka/html/`. Open `build/dokka/html/index.html` in a browser.

> Note: Dokka Gradle plugin v2 only supports HTML and Javadoc outputs. Markdown/GFM is no longer supported.

### Build and Preview the MkDocs Site

```bash
cd public
mkdocs build    # Generates site in public/field4/
mkdocs serve    # Starts local server at http://localhost:8000
```

Then open http://localhost:8000 and navigate to "API Documentation" in the menu.

## What Gets Documented

The Dokka configuration documents these packages:

- `afield.four.*` - Core graphics and rendering system
- `afield.stdlib.*` - Standard library extensions and utilities

All other internal packages are suppressed from the documentation.

## Troubleshooting

### Gradle fails to download dependencies

If you're behind a firewall or have network issues, Gradle may fail to download dependencies. Make sure you can access:
- https://repo.maven.apache.org/maven2/
- https://plugins.gradle.org/

### Documentation seems out of date

Clean and regenerate:
```bash
./gradlew clean
./gradlew generateApiDocs
```

### No documentation appears

Make sure the source code has KDoc comments. Example:

```kotlin
/**
 * Represents a drawing primitive for lines and shapes.
 * 
 * @property nodes The list of nodes in this FLine
 */
class FLine {
    /**
     * Adds a line to the specified point.
     * @param x The x coordinate
     * @param y The y coordinate
     */
    fun lineTo(x: Double, y: Double) { ... }
}
```

## Learn More

See `dev_docs/API_DOCUMENTATION.md` for complete documentation on:
- Adding KDoc comments to code
- Customizing the Dokka configuration
- CI/CD integration
- Advanced usage

## Directory Structure

```
Field4/
├── build.gradle.kts              # Dokka configuration
├── generate-docs.sh              # Helper script
├── dev_docs/
│   └── API_DOCUMENTATION.md      # Full documentation
├── public/
│   ├── mkdocs.yml                # MkDocs config (includes API docs)
│   └── docs/
│       └── api/
│           ├── .gitignore        # Ignores generated files
│           ├── index.md          # API documentation overview
│           └── [generated]/      # Previously generated markdown files (legacy)
└── build/
    └── dokka/
        └── html/                 # Generated HTML docs (after running dokkaGenerateHtml)
```
