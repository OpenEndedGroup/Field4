#!/bin/bash
# Generate API documentation using Dokka

set -e

echo "Generating API documentation with Dokka..."
echo ""

# Generate GFM (markdown) documentation for MkDocs
echo "Step 1: Generating markdown documentation..."
./gradlew dokkaGfm

echo ""
echo "✓ Markdown documentation generated in public/docs/api/"
echo ""
echo "Next steps:"
echo "  1. Review the generated documentation in public/docs/api/"
echo "  2. Build the MkDocs site: cd public && mkdocs build"
echo "  3. Preview the site: cd public && mkdocs serve"
echo ""
echo "To generate HTML documentation instead:"
echo "  ./gradlew dokkaHtml"
echo "  (Output will be in build/dokka/html/)"
