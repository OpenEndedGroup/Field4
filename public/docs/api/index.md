# API Documentation

This directory contains auto-generated API documentation for Field4's public APIs.

## Packages

The API documentation covers the following packages:

- **afield.four**: Core graphics and rendering system
- **afield.stdlib**: Standard library extensions and utilities

## Generating Documentation

To generate the latest API documentation from source code, run:

```bash
./gradlew dokkaGfm
```

This will generate markdown files in this directory that can be browsed directly or included in the MkDocs site.

## Browsing HTML Documentation

For a richer browsing experience with search and cross-references, you can generate HTML documentation:

```bash
./gradlew dokkaHtml
```

The HTML documentation will be available in `build/dokka/html/`.
