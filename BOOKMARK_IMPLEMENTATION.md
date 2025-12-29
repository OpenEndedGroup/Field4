# Bookmark System Implementation

## Overview
This implementation adds support for a bookmark system that can persist arbitrary key/value data with a document. The system uses the root box as a global document store, which saving.kt already understands.

## Implementation Details

### Backend Changes (Boxes.kt)
1. Added `"bookmarks"` to `uiResyncProperties` - ensures bookmark changes are synced to frontend
2. Added `"bookmarks"` to `ioPersistentProperties` - ensures bookmarks are saved to disk

### How It Works
- **Storage**: Bookmarks are stored as a property on the root box (acting as a global document store)
- **Persistence**: The existing `saving.kt` system automatically persists any property in `ioPersistentProperties`
- **Synchronization**: The existing `doResync()` function handles syncing bookmark changes to all connected sessions
- **Message Protocol**: Uses existing `Set_string` message type to sync bookmark data between frontend and backend

### Usage Example

#### Frontend (JavaScript/TypeScript)
```javascript
// Define bookmark structure
const bookmarkData = {
    version: 1,
    bookmarks: [
        {
            name: "Current Work",
            scrollPosition: { x: 0, y: 100 },
            selectedBox: "box_id_123",
            editedProperty: "code",
            cursorLine: 42,
            nestingState: {
                "box_id_1": { open: true },
                "box_id_2": { open: false }
            }
        }
    ]
};

// Save bookmarks to root box
const bookmarkJson = JSON.stringify(bookmarkData);
send(Message("set-string").payload(
    Set_string("root", "bookmarks", bookmarkJson)
));

// Bookmarks will be automatically persisted when the document is saved
```

#### Restoring Bookmarks
```javascript
// Bookmarks are automatically loaded from disk when document is opened
// The frontend receives them via the set-string message handler

handlers.put("set-string") { message, _ ->
    val set = Json.decodeFromJsonElement<Set_string>(message.payload!!)
    
    if (set.uid == "root" && set.property == "bookmarks") {
        // Parse and restore bookmark state
        val bookmarkData = JSON.parse(set.value);
        restoreBookmarkState(bookmarkData);
    }
    
    // ... other handlers
}
```

## Bookmark Data Structure Recommendation

```typescript
interface BookmarkData {
    version: number;
    bookmarks: Bookmark[];
}

interface Bookmark {
    id: string;
    name: string;
    timestamp: number;
    scrollPosition: { x: number; y: number };
    selectedBox?: string;
    editedProperty?: string;
    cursorLine?: number;
    cursorColumn?: number;
    nestingState?: Record<string, { open: boolean }>;
}
```

## Frontend Implementation Notes

The frontend will need to:

1. **Capture State**: Implement functions to capture current UI state:
   - `captureScrollPosition()` - get current canvas scroll/pan
   - `captureSelectedBox()` - get currently selected box
   - `captureEditedProperty()` - get currently edited property
   - `captureCursorPosition()` - get cursor line/column in Monaco editor
   - `captureNestingState()` - get open/closed state of nested boxes

2. **Restore State**: Implement functions to restore UI state:
   - `restoreScrollPosition(pos)` - restore canvas pan/zoom
   - `restoreSelectedBox(boxId)` - select the bookmarked box
   - `restoreEditedProperty(boxId, property)` - open editor for property
   - `restoreCursorPosition(line, column)` - set Monaco cursor position
   - `restoreNestingState(state)` - restore open/closed state

3. **UI Integration**: Add bookmark management UI:
   - Button to create new bookmark
   - List of existing bookmarks
   - Button to restore a bookmark
   - Button to delete a bookmark

## Testing

To test the bookmark system:

1. Start Field4 application
2. Open a document
3. In browser console, save a bookmark:
   ```javascript
   const bookmark = { name: "test", scrollPosition: { x: 10, y: 20 } };
   send(Message("set-string").payload(Set_string("root", "bookmarks", JSON.stringify(bookmark))));
   ```
4. Save the document (the system will automatically persist bookmarks)
5. Close and reopen the document
6. In browser console, check if bookmark was loaded:
   ```javascript
   // The root box should have the bookmarks property
   // You can verify by checking the set-string messages in network tab
   ```

## Benefits of This Approach

1. **Minimal Changes**: Only required adding one property to two lists
2. **Reuses Existing Infrastructure**: Uses existing property system and saving.kt
3. **Type Flexible**: Can store any JSON-serializable data structure
4. **Session Consistent**: Bookmarks sync across multiple browser sessions
5. **Git Friendly**: Bookmarks are saved in EDN format which is readable and diffable
6. **Extensible**: Easy to add new fields to bookmark data structure

## Future Enhancements

- Add UI for bookmark creation/management
- Support multiple bookmarks per document
- Add keyboard shortcuts for bookmarks
- Export/import bookmarks between documents
- Bookmark search/filter functionality
