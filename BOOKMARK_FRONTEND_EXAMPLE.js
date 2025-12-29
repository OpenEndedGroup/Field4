/**
 * Bookmark System - Frontend Example Implementation
 * 
 * This file demonstrates how to implement bookmark capture and restoration
 * in the Field4 frontend. Copy these functions into your frontend code
 * (e.g., in Main.kt or a new bookmarks.kt file).
 */

// ============================================================================
// BOOKMARK DATA STRUCTURES
// ============================================================================

/**
 * Represents a single bookmark in the document
 */
interface Bookmark {
    id: string;              // Unique bookmark ID
    name: string;            // User-friendly name
    timestamp: number;       // When bookmark was created
    scrollPosition?: {       // Canvas scroll/pan position
        x: number;
        y: number;
    };
    selectedBox?: string;    // ID of selected box
    editedProperty?: string; // Name of property being edited (e.g., "code")
    cursorLine?: number;     // Cursor line in Monaco editor
    cursorColumn?: number;   // Cursor column in Monaco editor
    nestingState?: {         // Open/closed state of nested boxes
        [boxId: string]: {
            open: boolean;
        };
    };
}

/**
 * Container for all bookmarks in a document
 */
interface BookmarkData {
    version: number;         // Data structure version (for future compatibility)
    bookmarks: Bookmark[];   // Array of bookmarks
}


// ============================================================================
// CAPTURE FUNCTIONS
// ============================================================================

/**
 * Captures the current scroll/pan position of the main canvas
 */
function captureScrollPosition(): { x: number; y: number } {
    // Access the main canvas translation
    // (Adapt to your actual canvas implementation)
    return {
        x: mainTranslation[0],
        y: mainTranslation[1]
    };
}

/**
 * Captures the ID of the currently selected box
 */
function captureSelectedBox(): string | undefined {
    if (selected.size === 1) {
        const selectedBox = Array.from(selected)[0];
        return selectedBox.getAttribute('uid') || undefined;
    }
    return undefined;
}

/**
 * Captures the currently edited property name
 */
function captureEditedProperty(): string | undefined {
    return selectedProperty; // Global variable from Main.kt
}

/**
 * Captures the cursor position in the Monaco editor
 */
function captureCursorPosition(): { line: number; column: number } | undefined {
    if (typeof globalEditor !== 'undefined' && globalEditor) {
        const position = globalEditor.getPosition();
        return {
            line: position.lineNumber,
            column: position.column
        };
    }
    return undefined;
}

/**
 * Captures the open/closed state of all nested boxes
 */
function captureNestingState(): { [boxId: string]: { open: boolean } } {
    const state: { [boxId: string]: { open: boolean } } = {};
    
    // Find all boxes with nested children
    const boxes = document.querySelectorAll('[uid]');
    boxes.forEach((box: HTMLElement) => {
        const nestedArea = box.querySelector('.disclosure-nested');
        if (nestedArea) {
            const uid = box.getAttribute('uid');
            if (uid) {
                // Check if the nested area is visible (open)
                const isOpen = nestedArea.style.display !== 'none';
                state[uid] = { open: isOpen };
            }
        }
    });
    
    return state;
}

/**
 * Captures complete current state as a bookmark
 */
function captureBookmark(name: string): Bookmark {
    const cursor = captureCursorPosition();
    
    return {
        id: `bookmark_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
        name: name,
        timestamp: Date.now(),
        scrollPosition: captureScrollPosition(),
        selectedBox: captureSelectedBox(),
        editedProperty: captureEditedProperty(),
        cursorLine: cursor?.line,
        cursorColumn: cursor?.column,
        nestingState: captureNestingState()
    };
}


// ============================================================================
// RESTORE FUNCTIONS
// ============================================================================

/**
 * Restores the scroll/pan position of the main canvas
 */
function restoreScrollPosition(position: { x: number; y: number }) {
    // Set the main canvas translation
    mainTranslation = [position.x, position.y];
    
    // Update the canvas transform
    mainCanvas.style.transform = `translate(${position.x}px, ${position.y}px)`;
    
    // Trigger any necessary UI updates
    canvasGestures.rebuildConnectionGroup();
}

/**
 * Selects and focuses a specific box
 */
function restoreSelectedBox(boxId: string) {
    // Find the box element
    const box = document.querySelector(`[uid="${boxId}"]`);
    if (box && box instanceof HTMLElement) {
        // Clear current selection
        selected.forEach(selectedBox => {
            deselectBox(selectedBox);
        });
        
        // Select the target box
        selectBox(box);
        
        // Scroll the box into view if needed
        box.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
}

/**
 * Opens the Monaco editor for a specific property of a box
 */
function restoreEditedProperty(boxId: string, property: string) {
    const box = document.querySelector(`[uid="${boxId}"]`);
    if (box && box instanceof HTMLElement) {
        // Select the box first
        restoreSelectedBox(boxId);
        
        // Set the property to edit
        selectedProperty = property;
        
        // Trigger editor open (this may vary based on your implementation)
        showMonaco();
    }
}

/**
 * Restores cursor position in the Monaco editor
 */
function restoreCursorPosition(line: number, column: number) {
    if (typeof globalEditor !== 'undefined' && globalEditor) {
        // Set cursor position
        globalEditor.setPosition({ lineNumber: line, column: column });
        
        // Reveal the line (scroll to make it visible)
        globalEditor.revealLineInCenter(line);
        
        // Focus the editor
        globalEditor.focus();
    }
}

/**
 * Restores the open/closed state of nested boxes
 */
function restoreNestingState(state: { [boxId: string]: { open: boolean } }) {
    Object.entries(state).forEach(([boxId, boxState]) => {
        const box = document.querySelector(`[uid="${boxId}"]`);
        if (box) {
            const nestedArea = box.querySelector('.disclosure-nested');
            if (nestedArea && nestedArea instanceof HTMLElement) {
                if (boxState.open) {
                    nestedArea.style.display = '';
                } else {
                    nestedArea.style.display = 'none';
                }
            }
        }
    });
}

/**
 * Restores complete state from a bookmark
 */
function restoreBookmark(bookmark: Bookmark) {
    // Restore scroll position
    if (bookmark.scrollPosition) {
        restoreScrollPosition(bookmark.scrollPosition);
    }
    
    // Restore nesting state first (before selecting boxes)
    if (bookmark.nestingState) {
        restoreNestingState(bookmark.nestingState);
    }
    
    // Restore selected box and edited property
    if (bookmark.selectedBox) {
        if (bookmark.editedProperty) {
            restoreEditedProperty(bookmark.selectedBox, bookmark.editedProperty);
        } else {
            restoreSelectedBox(bookmark.selectedBox);
        }
    }
    
    // Restore cursor position (after opening editor)
    if (bookmark.cursorLine !== undefined && bookmark.cursorColumn !== undefined) {
        // Delay slightly to ensure editor is ready
        setTimeout(() => {
            restoreCursorPosition(bookmark.cursorLine!, bookmark.cursorColumn!);
        }, 300);
    }
    
    console.log(`✓ Restored bookmark: ${bookmark.name}`);
}


// ============================================================================
// BOOKMARK MANAGEMENT
// ============================================================================

/**
 * Loads bookmarks from the root box property
 */
function loadBookmarks(): BookmarkData {
    const rootBox = document.querySelector('[uid="root"]');
    if (rootBox && (rootBox as any).bookmarks) {
        try {
            return JSON.parse((rootBox as any).bookmarks);
        } catch (e) {
            console.error("Failed to parse bookmarks:", e);
        }
    }
    
    // Return empty bookmarks if none exist
    return { version: 1, bookmarks: [] };
}

/**
 * Saves bookmarks to the root box property
 */
function saveBookmarks(bookmarkData: BookmarkData) {
    const message = {
        name: "set-string",
        uid: `message_${Date.now()}`,
        payload: {
            uid: "root",
            property: "bookmarks",
            value: JSON.stringify(bookmarkData)
        }
    };
    
    send(message);
}

/**
 * Creates a new bookmark with the current state
 */
function createBookmark(name: string) {
    // Load existing bookmarks
    const bookmarkData = loadBookmarks();
    
    // Capture current state
    const bookmark = captureBookmark(name);
    
    // Add to bookmarks list
    bookmarkData.bookmarks.push(bookmark);
    
    // Save back to root box
    saveBookmarks(bookmarkData);
    
    console.log(`✓ Created bookmark: ${name}`);
    notify(`Bookmark "${name}" created`, "bookmarks");
}

/**
 * Restores a bookmark by ID
 */
function goToBookmark(bookmarkId: string) {
    const bookmarkData = loadBookmarks();
    const bookmark = bookmarkData.bookmarks.find(b => b.id === bookmarkId);
    
    if (bookmark) {
        restoreBookmark(bookmark);
    } else {
        console.error(`Bookmark not found: ${bookmarkId}`);
        notify(`Bookmark not found`, "error");
    }
}

/**
 * Deletes a bookmark by ID
 */
function deleteBookmark(bookmarkId: string) {
    const bookmarkData = loadBookmarks();
    const index = bookmarkData.bookmarks.findIndex(b => b.id === bookmarkId);
    
    if (index !== -1) {
        const name = bookmarkData.bookmarks[index].name;
        bookmarkData.bookmarks.splice(index, 1);
        saveBookmarks(bookmarkData);
        
        console.log(`✓ Deleted bookmark: ${name}`);
        notify(`Bookmark "${name}" deleted`, "bookmarks");
    }
}

/**
 * Lists all bookmarks
 */
function listBookmarks(): Bookmark[] {
    const bookmarkData = loadBookmarks();
    return bookmarkData.bookmarks;
}


// ============================================================================
// USAGE EXAMPLES
// ============================================================================

/*
// Create a bookmark
createBookmark("Important section");

// List all bookmarks
const bookmarks = listBookmarks();
console.log("Available bookmarks:", bookmarks);

// Go to a bookmark
goToBookmark("bookmark_1234567890");

// Delete a bookmark
deleteBookmark("bookmark_1234567890");

// Manual bookmark creation with specific state
const customBookmark: Bookmark = {
    id: "my_custom_bookmark",
    name: "Main Function",
    timestamp: Date.now(),
    scrollPosition: { x: 500, y: 300 },
    selectedBox: "box_main",
    editedProperty: "code",
    cursorLine: 42,
    cursorColumn: 10
};

const bookmarkData = loadBookmarks();
bookmarkData.bookmarks.push(customBookmark);
saveBookmarks(bookmarkData);
*/


// ============================================================================
// KEYBOARD SHORTCUTS (Optional)
// ============================================================================

/**
 * Register keyboard shortcuts for bookmarks
 * Add this to your keyboard event handler
 */
function registerBookmarkShortcuts() {
    document.addEventListener('keydown', (event) => {
        // Ctrl+Shift+B: Create bookmark
        if (event.ctrlKey && event.shiftKey && event.key === 'B') {
            event.preventDefault();
            const name = prompt("Bookmark name:");
            if (name) {
                createBookmark(name);
            }
        }
        
        // Ctrl+1-9: Go to bookmark by index
        if (event.ctrlKey && !event.shiftKey && event.key >= '1' && event.key <= '9') {
            event.preventDefault();
            const index = parseInt(event.key) - 1;
            const bookmarks = listBookmarks();
            if (bookmarks[index]) {
                goToBookmark(bookmarks[index].id);
            }
        }
    });
}

// Initialize shortcuts
// registerBookmarkShortcuts();
