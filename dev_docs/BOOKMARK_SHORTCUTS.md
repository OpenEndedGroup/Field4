# Bookmark System - Keyboard Shortcuts

## Quick Reference

### Save Bookmarks
| Shortcut | Action |
|----------|--------|
| Shift + Ctrl/Cmd + F1 | Save Bookmark 1 |
| Shift + Ctrl/Cmd + F2 | Save Bookmark 2 |
| Shift + Ctrl/Cmd + F3 | Save Bookmark 3 |
| Shift + Ctrl/Cmd + F4 | Save Bookmark 4 |
| Shift + Ctrl/Cmd + F5 | Save Bookmark 5 |
| Shift + Ctrl/Cmd + F6 | Save Bookmark 6 |
| Shift + Ctrl/Cmd + F7 | Save Bookmark 7 |
| Shift + Ctrl/Cmd + F8 | Save Bookmark 8 |
| Shift + Ctrl/Cmd + F9 | Save Bookmark 9 |

### Restore Bookmarks
| Shortcut | Action |
|----------|--------|
| Shift + F1 | Restore Bookmark 1 |
| Shift + F2 | Restore Bookmark 2 |
| Shift + F3 | Restore Bookmark 3 |
| Shift + F4 | Restore Bookmark 4 |
| Shift + F5 | Restore Bookmark 5 |
| Shift + F6 | Restore Bookmark 6 |
| Shift + F7 | Restore Bookmark 7 |
| Shift + F8 | Restore Bookmark 8 |
| Shift + F9 | Restore Bookmark 9 |

## What Gets Saved

Each bookmark captures:
- 📍 **Scroll Position** - Canvas pan/zoom location
- 📦 **Selected Box** - Which box is selected
- ✏️ **Edited Property** - Which property is being edited (e.g., "code")
- 📝 **Cursor Position** - Line and column in Monaco editor
- 🗂️ **Nesting State** - Open/closed state of all nested boxes
- 🕐 **Timestamp** - When the bookmark was created

## Usage Examples

### Workflow 1: Code Navigation
```
1. Working on main function
2. Press Shift+Ctrl+F1 → Saves Bookmark 1
3. Navigate to helper function
4. Press Shift+Ctrl+F2 → Saves Bookmark 2
5. Press Shift+F1 → Jump back to main function
6. Press Shift+F2 → Jump to helper function
```

### Workflow 2: Multiple Views
```
1. Overview of document (zoomed out)
2. Press Shift+Ctrl+F1 → Save overview
3. Zoom into specific section
4. Press Shift+Ctrl+F2 → Save detailed view
5. Toggle between views with Shift+F1 and Shift+F2
```

### Workflow 3: Debugging
```
1. At error location
2. Press Shift+Ctrl+F1 → Save error location
3. Navigate to fix location
4. Press Shift+Ctrl+F2 → Save fix location
5. Toggle between error and fix with Shift+F1/F2
```

## Command Palette

All bookmark actions are also available in the command palette:
- "Save Bookmark 1-9"
- "Restore Bookmark 1-9"

Access with Ctrl+Shift+P (Cmd+Shift+P on Mac)

## Notifications

The system shows notifications when:
- ✅ "Saved Bookmark N" - Bookmark saved successfully
- ✅ "Restored Bookmark N" - Bookmark restored successfully
- ⚠️ "Bookmark N not set" - Trying to restore unset bookmark
- ❌ "Error..." - Something went wrong

## Persistence

Bookmarks are automatically persisted:
- Saved to `metadata.edn` when document is saved (Ctrl+S)
- Loaded automatically when document opens
- Survive application restarts
- Version controlled with your document

## Tips

1. **Use Sequential Numbers**: Start with Bookmark 1, then 2, 3... for natural workflow
2. **Save Often**: Bookmarks are cheap - save liberally
3. **Name by Purpose**: Think of bookmarks as "Main", "Helper", "Test", etc.
4. **Persist Manually**: Press Ctrl+S after setting bookmarks to ensure they're saved
5. **Check Status**: Look for notification messages to confirm saves/restores

## Platform Differences

### macOS
- Use **Cmd** instead of Ctrl
- Example: Shift + Cmd + F1 (save), Shift + F1 (restore)

### Windows/Linux
- Use **Ctrl**
- Example: Shift + Ctrl + F1 (save), Shift + F1 (restore)

## Limitations

- Maximum 9 bookmarks per document
- Bookmarks are document-specific (not shared across documents)
- Restoring a bookmark clears current selection

## Advanced

### Bookmark Data Structure

Bookmarks are stored as JSON in the root box's `bookmarks` property:

```json
{
  "version": 1,
  "bookmarks": [
    {
      "id": "bookmark_1",
      "name": "Bookmark 1",
      "timestamp": 1703872800000,
      "scrollPosition": { "x": 100, "y": 200 },
      "selectedBox": "box_id_123",
      "editedProperty": "code",
      "cursorLine": 42,
      "cursorColumn": 10,
      "nestingState": {
        "box_1": { "open": true },
        "box_2": { "open": false }
      }
    }
  ]
}
```

### Programmatic Access

Access bookmarks from console:
```javascript
// Load all bookmarks
const bookmarks = loadBookmarks();
console.log(bookmarks);

// Save bookmark manually
saveBookmarkSlot(1);

// Restore bookmark manually
restoreBookmarkSlot(1);
```

## Troubleshooting

**Q: Bookmarks not persisting?**
A: Make sure to save the document (Ctrl+S) after setting bookmarks

**Q: Shortcut not working?**
A: Check if another application is capturing the F-key shortcuts

**Q: Bookmark restores wrong location?**
A: The bookmark may be from a different document state - try resaving

**Q: How do I delete a bookmark?**
A: Just save over it with new state, or it will be overwritten

**Q: Can I export bookmarks?**
A: Yes, they're in metadata.edn and can be copied with the document

## See Also

- [BOOKMARK_README.md](BOOKMARK_README.md) - Overview and architecture
- [BOOKMARK_IMPLEMENTATION.md](BOOKMARK_IMPLEMENTATION.md) - Technical details
- [BOOKMARK_QUICKSTART.md](BOOKMARK_QUICKSTART.md) - 5-minute tutorial
