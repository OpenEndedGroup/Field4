#!/bin/bash
# Generate API documentation using Dokka

set -e

echo "Generating API documentation with Dokka..."
echo ""

# Generate HTML documentation
echo "Step 1: Generating HTML documentation..."
./gradlew dokkaGenerateHtml

echo ""
echo "✓ HTML documentation generated in build/dokka/html/"
echo ""
echo "Next steps:"
echo "  1. Review the generated documentation in public/docs/api/"
echo "  2. Build the MkDocs site: cd public && mkdocs build"
echo "  3. Preview the site: cd public && mkdocs serve"
echo ""
echo "To generate all supported Dokka publications:"
echo "  ./gradlew dokkaGenerate"
