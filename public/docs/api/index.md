# API Documentation

This directory contains auto-generated API documentation for Field4's public APIs.

## Packages

The API documentation covers the following packages:

- **afield.four**: Core graphics and rendering system
- **afield.stdlib**: Standard library extensions and utilities

## Generating Documentation

To generate the latest API documentation from source code, run:

```diffstatic {data-lang=bash}
./gradlew dokkaGenerateHtml
```

This will generate HTML files in `build/dokka/html/` that can be browsed directly in a browser.

## Browsing HTML Documentation

To generate all supported Dokka publications:

```diffstatic {data-lang=bash}
./gradlew dokkaGenerate
```

The HTML documentation will be available in `build/dokka/html/`.

> Note: Dokka Gradle plugin v2 only supports HTML and Javadoc outputs. Markdown/GFM is no longer supported.
