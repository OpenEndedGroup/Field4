#!/usr/bin/env python3
"""
Manual Test Script for Bookmark System

This script demonstrates how to test the bookmark persistence system.
It provides examples of bookmark data structures and verification steps.

Usage:
    1. Start Field4 application
    2. Open browser console
    3. Copy and paste the JavaScript examples below
    4. Follow the verification steps
"""

print("""
========================================
Bookmark System Manual Test
========================================

STEP 1: Start Field4 Application
---------------------------------
Run:
    ./gradlew jvmRun

Wait for the application to start and open in your browser.


STEP 2: Create and Save a Bookmark
----------------------------------
Open the browser console (F12) and run this JavaScript:

```javascript
// Create a bookmark data structure
const bookmarkData = {
    version: 1,
    bookmarks: [
        {
            id: "bookmark_1",
            name: "Test Bookmark",
            timestamp: Date.now(),
            scrollPosition: { x: 100, y: 200 },
            selectedBox: "test_box_123",
            editedProperty: "code",
            cursorLine: 42,
            cursorColumn: 10,
            nestingState: {
                "box_1": { open: true },
                "box_2": { open: false }
            }
        }
    ]
};

// Save bookmark to root box
const message = {
    name: "set-string",
    uid: "message_" + Date.now(),
    payload: {
        uid: "root",
        property: "bookmarks",
        value: JSON.stringify(bookmarkData)
    }
};

// Send the message (using Field4's send function)
send(message);

console.log("✓ Bookmark created and sent to backend");
```


STEP 3: Save the Document
--------------------------
In the Field4 UI:
- Press Ctrl+S (or Cmd+S on Mac) to save the document
- You should see a "saved file" notification

The bookmark data should now be persisted to the metadata.edn file.


STEP 4: Verify Persistence
---------------------------
Check the metadata.edn file in your workspace directory:

```bash
# Default location (adjust path as needed)
cat ~/Documents/AFieldWorkspace/<your-document>/metadata.edn
```

Look for a line containing "bookmarks" with your bookmark data.
It should look something like:

    "bookmarks" "{\\"version\\":1,\\"bookmarks\\":[...]}"


STEP 5: Test Restoration
-------------------------
1. Close the browser tab or restart Field4
2. Reopen the same document
3. Open the browser console
4. Check if bookmarks were loaded:

```javascript
// Look for the root box element
const rootBox = document.querySelector('[uid="root"]');

// Check if bookmarks property exists
if (rootBox && rootBox.bookmarks) {
    console.log("✓ Bookmarks loaded from disk:", 
                JSON.parse(rootBox.bookmarks));
} else {
    console.log("✗ Bookmarks not found on root box");
}
```


STEP 6: Advanced Test - Multiple Bookmarks
-------------------------------------------
```javascript
// Add multiple bookmarks
const multipleBookmarks = {
    version: 1,
    bookmarks: [
        {
            id: "bookmark_1",
            name: "Start of Work",
            timestamp: Date.now(),
            scrollPosition: { x: 0, y: 0 }
        },
        {
            id: "bookmark_2", 
            name: "Main Function",
            timestamp: Date.now() + 1000,
            scrollPosition: { x: 500, y: 300 },
            selectedBox: "main_box",
            cursorLine: 10
        },
        {
            id: "bookmark_3",
            name: "Bug Fix Area",
            timestamp: Date.now() + 2000,
            scrollPosition: { x: 1000, y: 600 },
            selectedBox: "bugfix_box",
            cursorLine: 55
        }
    ]
};

const message = {
    name: "set-string",
    uid: "message_" + Date.now(),
    payload: {
        uid: "root",
        property: "bookmarks",
        value: JSON.stringify(multipleBookmarks)
    }
};

send(message);
console.log("✓ Multiple bookmarks saved");
```

Save the document and verify the data persists across sessions.


EXPECTED RESULTS
----------------
✓ Bookmark data is sent to backend via Set_string message
✓ Bookmark property appears in metadata.edn file
✓ Bookmarks persist across application restarts
✓ Multiple bookmarks can be stored and retrieved
✓ Bookmark data structure is preserved (JSON roundtrip)


TROUBLESHOOTING
---------------
If bookmarks are not persisting:

1. Check browser console for errors
2. Verify the root box exists:
   ```javascript
   document.querySelector('[uid="root"]')
   ```

3. Verify the message was sent:
   ```javascript
   // Enable message logging
   const oldLog = console.log;
   console.log = function() {
       if (typeof arguments[0] === 'string' && arguments[0].includes("set-string")) {
           oldLog.apply(console, arguments);
       }
   };
   ```

4. Check that saving.kt includes "bookmarks" in ioPersistentProperties:
   ```bash
   grep -n "bookmarks" src/jvmMain/kotlin/afield/Boxes.kt
   ```

5. Check the metadata.edn file for the bookmarks entry


VERIFICATION CHECKLIST
---------------------
[ ] Bookmark created in JavaScript console
[ ] Set_string message sent successfully
[ ] Document saved (Ctrl+S)
[ ] metadata.edn contains bookmarks entry
[ ] Application restarted
[ ] Bookmarks loaded on document open
[ ] Multiple bookmarks work correctly
[ ] JSON data structure preserved
[ ] No errors in console
[ ] System works with complex bookmark data


SUCCESS CRITERIA
----------------
The bookmark system is working correctly if:
1. Bookmarks can be set via JavaScript console
2. Bookmarks persist in metadata.edn file
3. Bookmarks load automatically when document opens
4. Multiple bookmarks are supported
5. Complex nested data structures are preserved
6. System integrates seamlessly with existing save/load


Next Steps for Full Implementation:
------------------------------------
- Add UI for creating bookmarks (button in toolbar)
- Add UI for listing bookmarks (sidebar/panel)
- Add UI for restoring bookmarks (click to restore)
- Implement state capture functions (scroll, selection, etc.)
- Implement state restore functions
- Add keyboard shortcuts (e.g., Ctrl+1-9 for bookmarks)
- Add bookmark export/import functionality

========================================
""")
