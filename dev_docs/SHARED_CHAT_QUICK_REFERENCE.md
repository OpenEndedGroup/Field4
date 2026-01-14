# Shared Chat Implementation - Quick Reference

## What Was Implemented

Added support for persistent shared chat that can be used across multiple box instances, particularly for boxes created from the same template.

## Key Changes

### 1. Template ID Tracking
**File**: `src/jvmMain/kotlin/afield/Boxes.kt`
- Added `Box.templateId` property
- Tracks which template a box was created from
- Automatically persisted in metadata

### 2. Template System
**File**: `src/jvmMain/kotlin/afield/server/templating.kt`
- Sets `templateId` when inserting templates
- Works for all template sources

### 3. Chat API
**File**: `src/jvmMain/kotlin/afield/SlideShow.kt`
- `withChat(id: String? = null)` - Optional ID parameter
- `id = "templateId"` - Share across template instances
- `id = "custom"` - Custom shared chat
- `id = null` - Instance-specific (default)

## Quick Usage

### Share chat across template instances:
```kotlin
withChat(id = "templateId")
```

### Custom shared chat:
```kotlin
withChat(id = "project-discussion")
```

### Instance-specific (default):
```kotlin
withChat()
```

## How It Works

1. **Template Loading**: When a template is inserted, all boxes get `templateId = templateName`
2. **Chat Resolution**: `"templateId"` string is replaced with actual template ID
3. **Persistence**: Chat server stores by ID, so discussions persist

## Testing

1. Create slideshow with `withChat(id = "templateId")`
2. Save as template
3. Insert template multiple times
4. Verify all instances share same chat

## Files Modified

- `src/jvmMain/kotlin/afield/Boxes.kt`
- `src/jvmMain/kotlin/afield/SlideShow.kt`
- `src/jvmMain/kotlin/afield/server/templating.kt`
- `src/jvmMain/kotlin/afield/slideshow_example.kt`

## New Documentation

- `docs/SHARED_CHAT_EXAMPLE.kt` - Usage examples
- `docs/SHARED_CHAT_FEATURE.md` - Technical details
- `docs/SHARED_CHAT_QUICK_REFERENCE.md` - This file

## Benefits

✓ Collaborative learning materials
✓ Shared project discussions  
✓ Backward compatible
✓ No frontend changes needed
✓ No chat server changes needed
✓ Simple API
