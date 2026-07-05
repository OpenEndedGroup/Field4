# API Documentation Generation

This document explains how to generate and maintain API documentation for Field4.

## Overview

Field4 uses [Dokka](https://kotlinlang.org/docs/dokka-introduction.html) to generate API documentation from KDoc comments in the source code. The documentation is generated in HTML format.

## Documented Packages

The API documentation focuses on the public API packages:

- `afield.four.*` - Core graphics and rendering system
- `afield.stdlib.*` - Standard library extensions and utilities

Other internal packages are suppressed from the documentation.

## Generating Documentation

### HTML Documentation

To generate HTML documentation:

```bash
./gradlew generateApiDocs
```

Or directly run the Dokka task:

```bash
./gradlew dokkaGenerateHtml
```

The HTML output will be available in `build/dokka/html/`. You can open `build/dokka/html/index.html` in a browser to browse the documentation.

> Note: Dokka Gradle plugin v2 only supports HTML and Javadoc outputs. Markdown/GFM is no longer supported.

## Building the Zensical Site

After generating the HTML documentation, you can build the complete Zensical site (the API docs page can link to the HTML output):

```bash
FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

The site will be generated in `public/field4/` and can be deployed to a web server.

To preview the site locally:

```bash
FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --skip-build
cd public
uv run --project .. --group docs zensical serve -f zensical.generated.yml
```

Then open http://localhost:8000 in your browser.

## Configuration

### Dokka Configuration

The Dokka configuration is in `build.gradle.kts`:

```kotlin
dokka {
    // HTML format
    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
        // ... package filters ...
    }
    
    // HTML format only (Dokka Gradle plugin v2)
}
```

### Site Configuration

The public docs navigation is configured with the site build configuration and includes the API documentation under the "API Documentation" section.

## Adding Documentation

To add documentation to your code:

1. Add KDoc comments to public classes, functions, and properties:

```kotlin
/**
 * Represents a drawing primitive for lines and shapes.
 * 
 * FLine provides a fluent API for creating and manipulating
 * vector graphics paths.
 * 
 * @property nodes The list of nodes that make up this FLine
 * @constructor Creates an empty FLine
 */
class FLine {
    /**
     * Adds a line segment to this FLine.
     * 
     * @param x The x coordinate of the endpoint
     * @param y The y coordinate of the endpoint
     * @return This FLine for method chaining
     */
    fun lineTo(x: Double, y: Double): FLine {
        // implementation
    }
}
```

2. Regenerate the documentation:

```bash
./gradlew generateApiDocs
```

3. Review the generated HTML in `build/dokka/html/`

## CI/CD Integration

You can integrate documentation generation into your CI/CD pipeline:

```yaml
# Example GitHub Actions workflow
- name: Generate API Documentation
  run: ./gradlew generateApiDocs

- name: Build Zensical Site
  run: |
    FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean
    
- name: Deploy Documentation
  # ... deploy public/field4/ to web server ...
```

## Troubleshooting

### Documentation not updating

1. Clean the build:
   ```bash
   ./gradlew clean
   ```

2. Regenerate:
   ```bash
   ./gradlew generateApiDocs
   ```

### Package not appearing in documentation

Check that the package matches the filter patterns in `build.gradle.kts`:

```kotlin
perPackageOption {
    matchingRegex.set("""afield\.four(\..*)?""")
    suppress.set(false)
}
```

Add your package pattern if it's not covered.

## References

- [Dokka Documentation](https://kotlinlang.org/docs/dokka-introduction.html)
- [KDoc Syntax](https://kotlinlang.org/docs/kotlin-doc.html)
