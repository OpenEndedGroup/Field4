# KDoc Support in Kotlin Autocomplete - Implementation Summary

## Overview
This implementation adds support for displaying KDoc-style comments in Kotlin autocomplete suggestions. Previously, only `@Doc` annotations were shown in the documentation popup.

## Changes Made

### 1. KDocExtractor.kt (New File)
**Location:** `src/jvmMain/kotlin/afield/ai/core/completion/KDocExtractor.kt`

A utility class that parses Kotlin source files to extract KDoc comments:
- Extracts KDoc comments (`/** ... */`) from source text
- Associates KDocs with their declarations (functions, classes, properties)
- Handles various KDoc formats (single-line, multi-line)
- Provides lookup functions to find KDoc for specific symbols

**Key Functions:**
- `extractKDocs(sourceText: String): List<KDocComment>` - Parses all KDocs from source

### 2. KotlinSourceIndex.kt (Modified)
**Location:** `src/jvmMain/kotlin/afield/ai/core/completion/KotlinSourceIndex.kt`

Enhanced the source indexing to include KDoc information:

**Changes:**
1. Updated `KotlinDefinitionRecord` to include `kdoc: String? = null` field
2. Modified `ParsedDefinition` to include `kdoc` parameter
3. Updated `parseTopLevelDefinitions()` to extract and associate KDocs with definitions
4. Added `getKDocForSymbol(symbolName: String): String?` function to retrieve KDoc from index

**How it works:**
- When indexing Kotlin source files, KDocExtractor is used to find KDocs
- KDocs are matched to their declarations by line number
- The KDoc text is stored in the definition record
- Lookup is performed by symbol name (with fallback to partial matching)

### 3. execution.kt (Modified)
**Location:** `src/jvmMain/kotlin/afield/execution.kt`

Updated completion metadata generation to include KDoc:

**Changes:**
1. Added import: `import afield.ai.KotlinSourceIndex`
2. Modified `completionFor()` function to:
   - Extract symbol name from `DeclarationDescriptor`
   - Look up KDoc using `KotlinSourceIndex.getKDocForSymbol()`
   - Add KDoc to documentation field if `@Doc` annotation is not present

**Priority:**
- `@Doc` annotations take priority (backwards compatibility)
- KDoc is used as fallback when no `@Doc` annotation exists
- Empty string if neither is available

## How It Works (End-to-End)

### At Startup:
1. `KotlinSourceIndex.startBackgroundSync()` runs (via `@RunsAt(early)`)
2. Scans `src/jvmMain/kotlin` and `src/personal/kotlin` for `.kt` files
3. For each file:
   - Extracts KDoc comments using `KDocExtractor`
   - Parses top-level declarations
   - Associates KDocs with declarations by line number
   - Stores in index with symbol name, file path, and KDoc text

### During Autocomplete:
1. User types code and triggers completion
2. Kotlin scripting engine provides completion candidates with `DeclarationDescriptor`
3. `completionFor()` extracts symbol name from descriptor
4. Checks for `@Doc` annotation first (existing behavior)
5. If no `@Doc`, calls `KotlinSourceIndex.getKDocForSymbol(symbolName)`
6. Returns KDoc text if found
7. FieldSuggest displays KDoc in its documentation pane

## Example

### Source Code:
```kotlin
/**
 * returns a list of `Vec3` (i.e. positions) by repeatedly moving along 
 * the line by 'distance' and recording those positions. Note that while 
 * the start of the line will be in this list the end point will probably 
 * not (unless the length of the line just happens to be exactly divisible 
 * by `distance`)
 */
fun sampleByDistance(distance: Float): List<Vec3> {
    // implementation
}
```

### Result:
When user types `sampleBy` and autocomplete suggests `sampleByDistance`, the documentation popup will show:
```
returns a list of `Vec3` (i.e. positions) by repeatedly moving along 
the line by 'distance' and recording those positions. Note that while 
the start of the line will be in this list the end point will probably 
not (unless the length of the line just happens to be exactly divisible 
by `distance`)
```

## Scope and Limitations

### What's Covered:
- ✅ Top-level functions in afield package
- ✅ Top-level classes in afield package
- ✅ Top-level properties (val/var) in afield package
- ✅ Single-line and multi-line KDoc comments
- ✅ KDoc with markdown formatting

### What's Not Covered (By Design):
- ❌ Member functions (methods inside classes) - only top-level
- ❌ Nested classes - only top-level
- ❌ Extension functions on receivers - symbol name extraction may not work
- ❌ External libraries (not in afield source) - only afield package

### Limitations:
- KDoc must immediately precede the declaration (with only blank lines/annotations in between)
- Symbol lookup is by simple name, not fully qualified name
- Partial match fallback handles some cases but not all

## Testing

### Manual Testing:
1. Start Field4 application
2. Open or create a box with Kotlin code
3. Type a symbol that has KDoc in afield sources (e.g., `sampleByDistance`, `moveTo`)
4. Verify KDoc appears in autocomplete documentation

### Test Files Created:
- `test_kdoc_sample.kt` - Sample file with various KDoc formats
- `test_kdoc_extractor.kt` - Manual test script
- `test_kdoc_real.kt` - Test with realistic KDoc
- `test_kdoc_simple.kts` - Standalone verification script

## Backwards Compatibility

- ✅ Existing `@Doc` annotations continue to work
- ✅ `@Doc` takes priority over KDoc
- ✅ Old index files load correctly (new field has default value)
- ✅ No breaking changes to existing APIs
- ✅ Graceful fallback if KDoc not found

## Performance Considerations

- Index built in background thread at startup (low priority)
- KDoc extraction is one-time per source file
- Lookup is O(n) over definitions (typically small for afield package)
- No performance impact during normal operation
- Index persisted to disk for future sessions

## Future Improvements

Potential enhancements (not implemented):
1. Support member function KDocs (requires deeper parsing)
2. Cache KDoc lookups in-memory for better performance
3. Extract parameter documentation (`@param`) separately
4. Support fully qualified name lookups
5. Index extension functions with receiver types
6. Incremental index updates (currently full rebuild)
7. Make KDoc vs @Doc priority configurable

## Files Changed Summary

1. **New:** `KDocExtractor.kt` (~300 lines) - KDoc parsing logic
2. **Modified:** `KotlinSourceIndex.kt` (+25 lines) - Index includes KDoc
3. **Modified:** `execution.kt` (+15 lines) - Completion uses KDoc
4. **Test files:** Various test scripts (not for production)

## Conclusion

This implementation provides a lightweight, minimal-change solution to include KDoc comments in Kotlin autocomplete. It leverages the existing source indexing infrastructure and requires no changes to the frontend or communication protocol. The implementation is backwards compatible and follows the existing patterns in the codebase.
