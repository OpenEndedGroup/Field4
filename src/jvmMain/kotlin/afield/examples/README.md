# Field4 Examples

This directory contains example code demonstrating various Field4 features.

## Examples

### CompiledSnippetExample.kt
Demonstrates the `CompiledSnippet` helper for compiling code once and evaluating it multiple times. Shows:
- Simple expression evaluation
- Using context variables
- Expression evaluation with changing variables
- Error handling

Run with: `./gradlew jvmRun` (or through your IDE)

### playlist_example.kt
Example usage of the playlist functionality. Demonstrates:
- Setting playlist property on boxes
- Using pauseList property to control frame delays
- Executing boxes in playlist order

### slideshow_example.kt
Comprehensive examples of the slideshow DSL. Includes:
- Custom link callbacks
- Template system for live data display
- Shared chat integration
- Interactive guides

See the [slideshow documentation](../../dev_docs/README_SLIDESHOW.md) for more details.

## Usage

These examples are meant to be:
1. **Run directly** - Many have `main()` functions that can be executed
2. **Copied and modified** - Use as templates for your own code
3. **Referenced** - Learn patterns and best practices

## Package Structure

All examples are in the `afield.examples` package and have access to the main `afield` package classes.
