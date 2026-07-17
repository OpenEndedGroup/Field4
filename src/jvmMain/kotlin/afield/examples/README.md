# Field4 Examples

This package contains all example code for Field4, organized by topic area. These examples serve as a one-stop shop for understanding how to use various Field4 features and as reference material for AI agents.

## Package Structure

### Top-Level Examples

- **`slideshow_example.kt`** - Demonstrations of the slideshow DSL
  - Simple slideshow examples
  - Interactive guides with custom link callbacks
  - Template system with live data display
  - Shared chat across template instances

- **`BooleanNetworkExplorer.kt`** - Console showcase for the Boolean-network stdlib
  - Demonstrates the public API from `afield.stdlib.booleannetworks.kt`
  - Prints attractor summaries for the bundled example networks
  - Runs a small Kauffman regime sweep to compare random-network behavior

- **`JoltPhysicsExample.kt`** - 3D rigid-body Jolt JNI examples
  - Stress-test chaos scene generation
  - Pickup/grab loop runtime for interactive control
  - Off-center collision demo and debug wireframe output

- **`playlist_example.kt`** - Examples of playlist functionality
  - Setting playlist order on boxes
  - Controlling frame delays with pauseList
  - Executing boxes in sequence

### Graphics Examples (`afield.examples.graphics`)

- **`FLineGeometryExamples.kt`** - Comprehensive FLine geometry utilities
  - Spiral curvature analysis
  - Shape offsetting
  - Boolean operations (union, intersection, difference)
  - Smoothing and simplification
  - Tangent and normal calculations
  - Shape morphing
  - Pattern generation

### Four Module Examples (`afield.examples.four`)

Graphics and rendering examples using the Four module (Vulkan/OpenGL):

- **`ImGuiRTTExample.kt`** - ImGui render-to-texture functionality
- **`ImGuiStereoQuadExample.kt`** - Stereo rendering with ImGui
- **`ImageReducerExample.kt`** - Image reduction and processing
- **`NewMain6.kt`** - Basic textured quad rendering
- **`NewMain7_dynamicNumberOfVerts.kt`** - Dynamic vertex count handling
- **`NewMain7_errorChecking.kt`** - Error checking patterns
- **`NewMain8_writeToImage.kt`** - Rendering to image files
- **`PlyLoaderTest.kt`** - PLY file loading (ASCII and binary)
- **`VirtualTexturingDemo.kt`** - Virtual texture system with dynamic loading

### Video Examples (`afield.examples.video`)

- **`VideoTextureExample.kt`** - Video playback
  - Loading video files
  - Play/pause control
  - Seeking to specific times
  - YUV texture binding

- **`AudioVideoExample.kt`** - Synchronized audio/video playback
  - Audio decoder integration
  - Clock synchronization
  - Master timing source

### Test Examples (`afield.examples.tests`)

- **`SliderTest.kt`** - Interactive slider demonstrations
  - Simple slider usage
  - Multiple slider coordination
  - Slider callbacks

### Standard Library Examples (`afield.examples.stdlib`)

- **`SmileStdlibSmokeTest.kt`** - Smile library integration
  - Cubic splines
  - Gaussian processes
  - Vector operations
  - Spatial indexing

## Usage

These examples are designed to be copied and adapted for your own use. Most examples include extensive comments explaining their functionality.

To run an example, typically you would:

1. Create a box in Field4
2. Copy the example code into the box
3. Execute the code
4. Observe the results in the browser UI or graphics window

## Additional Documentation

For more documentation examples that are not part of the compiled codebase, see the `dev_docs/` directory at the repository root. These include:
- `viz_demo.kt` - Value visualizer demonstrations
- `SLIDER_EXAMPLES.kt` - Additional slider examples
- `GUIDE_CHAT_QUICKSTART.md` - Guide-chat integration examples

Shared-chat usage is demonstrated in `slideshow_example.kt` in this package.

## For AI Agents

This examples package provides comprehensive reference material for:
- Understanding Field4 APIs and patterns
- Learning how to use various Field4 features
- Finding code snippets for common tasks
- Understanding best practices and conventions

When helping users with Field4 code, refer to these examples as canonical implementations. Also check the `dev_docs/` directory for additional documentation examples.
