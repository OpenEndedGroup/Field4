# Template Documentation - Phase 2 Implementation

## Status: ✅ COMPLETED

Phase 1 successfully implemented the proof of concept for displaying documentation in the Monaco QuickPick template selection menu. Phase 2 has now been implemented to add rich, meaningful documentation extracted from template box metadata.

## Phase 1 Completion ✅
- [x] Added `description` and `detail` fields to template menu items
- [x] Refactored `globalPickMenu_custom` to use `createQuickPick()` for proper documentation display
- [x] Demonstrated with varied lorem ipsum text

## Phase 2 Completion ✅
- [x] Extract template metadata from box.metadata property (YAML formatted)
- [x] Order boxes by increasing frame.y coordinate
- [x] Accumulate documentation from doc, docPreamble, and boxPreamble fields
- [x] Create compressed box descriptions showing sidecar properties
- [x] Exclude "code" property, include only vertex, fragment, compute, task, mesh
- [x] Display last modified dates
- [x] Format and display rich documentation on client side
- [x] Coexist with existing documentation system

## Implementation Summary (As Built)

### Metadata Storage Approach

Per user specification, template metadata is stored in the **`metadata` property of boxes** (YAML formatted). This coexists with the existing documentation system that also uses the metadata property.

### Data Extraction

**File**: `src/jvmMain/kotlin/afield/server/templating.kt`

The `extractTemplateMetadata()` function:

1. **Loads boxes** from template EDN files
2. **Sorts by frame.y** (increasing) - boxes ordered by visual position
3. **Extracts from metadata property**:
   - `doc`: Main documentation text
   - `docPreamble`: Documentation preamble
   - `boxPreamble`: Box-specific preamble
4. **Creates compressed box descriptions**:
   - Format: `BoxName/{vertex,fragment}, OtherBox, AnotherBox/{compute}`
   - Excludes "code" property (as specified)
   - Includes only sidecar-able properties: vertex, fragment, compute, task, mesh
5. **Returns TemplateMetadata** with:
   - Template name
   - Last modified timestamp
   - Formatted documentation string

```kotlin
@Serializable
data class TemplateMetadata(
    val name: String,
    val lastModified: Long,
    val boxDocumentation: String
)
```

### RPC Layer Updates

**File**: `src/commonMain/kotlin/rpc.kt`

Added metadata support to Directory class:

```kotlin
@Serializable
class Directory(
    val command: String, 
    val value: List<String> = emptyList<String>(),
    val metadata: JsonElement? = null,  // ← Added
    val mouseX: Double? = null,
    val mouseY: Double? = null
)

@Serializable
data class TemplateMetadata(
    val name: String,
    val lastModified: Long,
    val boxDocumentation: String
)
```

### Server Handler Update

The `request-templates` handler now:
- Extracts metadata for each template
- Tries multiple file paths (standalone .edn and directories)
- Sends metadata array in Directory.metadata field

### Client-Side Display

**File**: `src/jsMain/kotlin/afield/commands.kt`

The `insertFromWorkspace()` function:
- Parses metadata from server response
- Formats display with:
  - Last modified date (when available)
  - Box documentation from metadata property
  - Compressed box list with properties
- Handles missing metadata gracefully ("No documentation available")

## Documentation Format

Templates with metadata display in QuickPick detail panel:

```
Modified: 1/1/2026

[Documentation text from doc/docPreamble/boxPreamble fields]

Boxes: GraphicsBox/{vertex,fragment}, ControlBox, DataBox/{compute}
```

## Usage Guide

### Adding Documentation to Templates

To add documentation to a template, edit the `metadata` property of boxes in YAML format:

```yaml
doc: |
  This is the main documentation for this box.
  It can span multiple lines.

docPreamble: "Introduction text for this section"

boxPreamble: "Specific preamble for this box"
```

### Metadata Fields

- **doc**: Main documentation text (appears in detail panel)
- **docPreamble**: Documentation preamble (accumulated from all boxes)
- **boxPreamble**: Box-specific preamble (accumulated from all boxes)

### Box Ordering

Boxes are automatically ordered by their `frame.y` coordinate (increasing), so documentation appears in visual order from top to bottom.

### Sidecar Properties Display

The compressed box description automatically includes which shader properties each box has:
- vertex
- fragment  
- compute
- task
- mesh

Example: `ShaderBox/{vertex,fragment}` means ShaderBox has vertex and fragment properties.

Note: The "code" property is intentionally excluded from this display as specified.
