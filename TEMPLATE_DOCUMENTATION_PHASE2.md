# Template Documentation - Phase 2 Implementation Plan

## Overview
Phase 1 has successfully implemented the proof of concept for displaying documentation in the Monaco QuickPick template selection menu. Phase 2 will add rich, meaningful documentation about each template.

## Phase 1 Completion ✅
- [x] Added `description` and `detail` fields to template menu items
- [x] Refactored `globalPickMenu_custom` to use `createQuickPick()` for proper documentation display
- [x] Demonstrated with varied lorem ipsum text

## Phase 2 Requirements
Add rich documentation metadata to templates including:
1. **Last Modified Date**: Show when the template was last updated
2. **Box Names**: List of boxes contained in the template
3. **Important Properties**: Summary of non-zero-length important properties
4. **Condensed Display**: Present information in a readable, condensed format

## Implementation Approach

### 1. Extract Template Metadata on Server Side

**File**: `src/jvmMain/kotlin/afield/server/templating.kt`

Add a new function to extract metadata from template files:

```kotlin
@Serializable
data class TemplateMetadata(
    val name: String,
    val lastModified: Long,
    val boxCount: Int,
    val boxNames: List<String>,
    val properties: Map<String, Int> // property name -> count of boxes with it
)

private fun extractTemplateMetadata(templatePath: String, templateName: String): TemplateMetadata? {
    try {
        val file = File(templatePath)
        val lastModified = file.lastModified()
        
        // Load the EDN file to extract box information
        val ednContent = file.readText()
        val boxes = SavingContext().loadFromEdnText(ednContent, templatePath) { it }
        
        val boxNames = boxes.map { it.name ?: it.uid }.take(5) // Limit to first 5
        val boxCount = boxes.size
        
        // Count important properties (non-empty strings)
        val importantProps = listOf("code", "vertex", "fragment", "compute", "description", "metadata")
        val propCounts = importantProps.associateWith { propName ->
            boxes.count { box ->
                val prop = box.properties[propName]
                prop is String && prop.trim().isNotEmpty()
            }
        }.filter { it.value > 0 }
        
        return TemplateMetadata(
            name = templateName,
            lastModified = lastModified,
            boxCount = boxCount,
            boxNames = boxNames,
            properties = propCounts
        )
    } catch (e: Exception) {
        lprintln("warning,template", "Failed to extract metadata for $templateName: ${e.message}")
        return null
    }
}
```

### 2. Update Template List Response

Modify the `request-templates` handler to include metadata:

```kotlin
handlers["request-templates"] = mutableSetOf({ message, reply ->
    val exec = Json.decodeFromJsonElement<Directory>(message.payload!!)
    
    val templates2 = SavingContext().listTemplates(app + "/templates/")
    val templates = SavingContext().listTemplates(home)
    val remoteTemplates = remoteTemplatesCache.values.flatten()
    
    val allTemplateNames = templates + templates2 + remoteTemplates
    
    // Extract metadata for each template
    val templateMetadata = allTemplateNames.mapNotNull { templateName ->
        // Try to find the template file
        val paths = listOf(
            "$home/$templateName/metadata.edn",
            "$home/$templateName.edn",
            "$app/templates/$templateName/metadata.edn",
            "$app/templates/$templateName.edn"
        )
        
        val templateFile = paths.firstOrNull { File(it).exists() }
        if (templateFile != null) {
            extractTemplateMetadata(templateFile, templateName)
        } else {
            // For remote templates, we might not have metadata
            TemplateMetadata(
                name = templateName,
                lastModified = 0,
                boxCount = 0,
                boxNames = emptyList(),
                properties = emptyMap()
            )
        }
    }
    
    reply(Message("list").payload(
        Directory(
            command = "list", 
            value = allTemplateNames,
            metadata = Json.encodeToJsonElement(templateMetadata)
        )
    ))
    
    Handled.yes
})
```

### 3. Update Directory Data Class

**File**: `src/commonMain/kotlin/rpc.kt`

Add metadata field to Directory:

```kotlin
@Serializable
data class Directory(
    val command: String = "", 
    val value: List<String> = listOf(),
    val metadata: JsonElement? = null,
    val mouseX: Double? = null,
    val mouseY: Double? = null
)
```

### 4. Format Documentation on Client Side

**File**: `src/jsMain/kotlin/afield/commands.kt`

Update `insertFromWorkspace()` to use the metadata:

```kotlin
fun insertFromWorkspace() {
    val mouseX = lastMouse.first
    val mouseY = lastMouse.second
    
    sendWithReply(Message("request-templates").payload(Directory(command = "list")), "templates") { message, payload ->
        val directory = Json.decodeFromJsonElement<Directory>(message.payload!!)
        
        // Parse metadata if available
        val metadataList = try {
            directory.metadata?.let { 
                Json.decodeFromJsonElement<List<TemplateMetadata>>(it)
            } ?: emptyList()
        } catch (e: Exception) {
            console.log("Failed to parse template metadata", e)
            emptyList()
        }
        
        val metadataMap = metadataList.associateBy { it.name }
        val options = eval("[]")
        
        directory.value.forEach { templateName ->
            val metadata = metadataMap[templateName]
            
            // Format the description and detail
            val description = if (metadata != null && metadata.boxCount > 0) {
                "${metadata.boxCount} box${if (metadata.boxCount != 1) "es" else ""}"
            } else {
                "Template"
            }
            
            val detail = if (metadata != null) {
                buildString {
                    // Last modified
                    if (metadata.lastModified > 0) {
                        val date = Date(metadata.lastModified.toDouble())
                        append("Modified: ${date.toLocaleDateString()}\n")
                    }
                    
                    // Box names
                    if (metadata.boxNames.isNotEmpty()) {
                        append("Boxes: ${metadata.boxNames.joinToString(", ")}")
                        if (metadata.boxCount > metadata.boxNames.size) {
                            append(" and ${metadata.boxCount - metadata.boxNames.size} more")
                        }
                        append("\n")
                    }
                    
                    // Properties summary
                    if (metadata.properties.isNotEmpty()) {
                        val propSummary = metadata.properties.entries
                            .sortedByDescending { it.value }
                            .joinToString(", ") { "${it.key} (${it.value})" }
                        append("Properties: $propSummary")
                    }
                }
            } else {
                "Template information not available"
            }
            
            val e = """
            ({
                id:'insert-$templateName', 
                label:'$templateName', 
                description: '$description',
                detail: '$detail',
                canvasKeybinding: (event) => false, 
                run: () => {
                    import("./afield.mjs").then( (afield) => {
                        afield.send("insert-template", {
                            command:"insert",
                            value:["$templateName"],
                            mouseX: $mouseX,
                            mouseY: $mouseY
                        })
                    })
                }
            })
            """.trimIndent()
            options.push(eval(e))
        }
        
        if (directory.value.size > 0) {
            showPickMenu_custom(options)
        }
        
        canvasGestures.rebuildConnectionGroup()
        Handled.yes_remove
    }
}
```

### 5. Add TemplateMetadata to Common

**File**: `src/commonMain/kotlin/rpc.kt`

```kotlin
@Serializable
data class TemplateMetadata(
    val name: String,
    val lastModified: Long,
    val boxCount: Int,
    val boxNames: List<String>,
    val properties: Map<String, Int>
)
```

## Expected User Experience

When opening the "Insert from workspace" menu:

1. Each template will show:
   - **Label**: Template name
   - **Description**: Number of boxes (e.g., "3 boxes")
   - **Detail Panel** (shown when item is highlighted):
     ```
     Modified: 1/15/2025
     Boxes: GraphicsBox, ShaderBox, ControlBox
     Properties: code (3), vertex (1), fragment (1)
     ```

2. Users can:
   - Quickly see which templates contain what
   - Understand when templates were last modified
   - Search by box names and properties (thanks to `matchOnDetail`)
   - Make informed decisions before inserting

## Performance Considerations

1. **Caching**: Extract metadata once when templates are listed, cache results
2. **Lazy Loading**: For large template collections, consider loading metadata on-demand
3. **Remote Templates**: May need separate metadata endpoint for remote template servers
4. **Error Handling**: Gracefully handle missing or corrupted template files

## Testing Strategy

1. Create test templates with various configurations:
   - Simple template (1 box, minimal properties)
   - Complex template (many boxes, many properties)
   - Template with long box names
   - Recently modified template
   - Old template

2. Verify:
   - Metadata displays correctly
   - Search works with metadata
   - Performance remains acceptable with many templates
   - Graceful degradation when metadata unavailable

## Future Enhancements

- Template previews (thumbnail images)
- Template tags/categories
- Usage statistics (most used templates)
- Template descriptions (user-editable)
- Version history
