# Testing Guide for Document History Feature

Canonical testing map: `dev_docs/testing/TESTING_STRATEGY.md`. This file is a
feature-specific manual checklist; the automated history panel smoke path lives
in the browser UI regression suite (`history-panel`).

For exact current UI terminology and behavior (button labels, status text,
timeline layout) defer to `public/docs/document_history.md` — this checklist
avoids restating UI strings that drift with every iteration.

## Prerequisites
- Field4 server running (`./gradlew jvmRun`)
- A document with at least a few saved versions

## Creating Test Data

### Step 1: Create Initial Document
1. Open Field4 in browser: `http://localhost:9092/?file=test_history`
2. Create a few boxes (e.g., 3-4 boxes)
3. Save the document (Ctrl+S or menu) — creates the first `metadata.edn`

### Step 2: Make Changes to Create Backups
1. Modify one of the boxes (change its code, name, or position)
2. Save again — creates `backups/metadata_backup_00000.edn` and updates `metadata.edn`
3. Add a new box, save — creates `backups/metadata_backup_00001.edn`
4. Delete a box (or tombstone it), save — creates `backups/metadata_backup_00002.edn`
5. Repeat 2-3 more times with various changes

You should now have:
```
test_history/
├── metadata.edn (current version)
├── backups/
│   ├── metadata_backup_00000.edn
│   ├── metadata_backup_00001.edn
│   ├── metadata_backup_00002.edn
│   ├── metadata_backup_00003.edn
│   └── metadata_backup_00004.edn
```

## Test Cases

### Test 1: View Timeline
**Goal**: Verify the timeline displays correctly

1. Open: `http://localhost:9092/history?file=test_history`
2. **Expected**:
   - Status reads "Ready" (it passes through "Connecting" then "Loading history")
   - The horizontal rail displays all versions, positioned left-to-right
   - Versions numbered from 0 (oldest) to N (current)
   - The current version's dot is rendered in a distinct dark style
   - Each version shows box count and timestamp

**Verification**:
- [ ] All versions appear
- [ ] Version numbers are sequential
- [ ] Timestamps are correct
- [ ] Box counts match expectations
- [ ] Current version is visually distinct

### Test 2: Version Selection
**Goal**: Verify version selection and detail display

1. Click on version #1 (second oldest)
2. **Expected**:
   - The side (`aside`) summary panel updates with version number, timestamp,
     box count, and filename
   - The "Apply — full restore" button is enabled

3. Click on the current version (last one)
4. **Expected**:
   - The "Apply — full restore" button is disabled (its label does not change)

**Verification**:
- [ ] Summary panel updates on click
- [ ] All details are correct
- [ ] Current version disables the restore button
- [ ] Non-current versions enable the restore button

### Test 3: Version Restoration
**Goal**: Verify restoring a previous version works correctly

**Setup**: Note the current state (which boxes exist)

1. Click on version #2 (middle version)
2. Click "Apply — full restore"
3. Confirm in the dialog (`Restore the whole document to version #2?`)
4. **Expected**:
   - Success notification "Version restored" appears
   - Timeline reloads with a new (restored) current version

5. Go back to main document: `http://localhost:9092/?file=test_history`
6. **Expected**:
   - Boxes match version #2's state
   - Boxes not in version #2 are hidden (tombstoned)
   - Boxes in version #2 are visible

**Verification**:
- [ ] Success notification appears
- [ ] Timeline shows new version
- [ ] Main document reflects restored state
- [ ] Tombstoned boxes are hidden
- [ ] Un-tombstoned boxes are visible
- [ ] Box properties match version #2

### Test 4: Transition Tracking
**Goal**: Verify transitions are recorded in versions.edn

1. After restoring version #2 (from Test 3)
2. Check file: `test_history/versions.edn`
3. **Expected** (`:names` is always written, even when empty):
   ```clojure
   {:transitions [{:fromIndex 5 :toIndex 2 :timestamp <some-timestamp>}]
    :names {}}
   ```
4. Restore another version (e.g., #4), check `versions.edn` again
5. **Expected**: a second transition appended to `:transitions`

**Verification**:
- [ ] versions.edn is created
- [ ] Contains a `:transitions` array and a `:names` map
- [ ] Each transition has fromIndex, toIndex, timestamp
- [ ] Multiple restores add multiple transitions

### Test 5: Transition Visualization
**Goal**: Verify transition paths appear on the timeline

1. After performing multiple restorations (Test 4)
2. Refresh history page: `http://localhost:9092/history?file=test_history`
3. **Expected**:
   - Thin gray dashed bezier curves connect the non-sequential rail positions
     (no arrowheads)

**Verification**:
- [ ] Transition lines are visible
- [ ] Lines connect the correct versions
- [ ] Multiple transitions all display

### Test 6: Empty History
**Goal**: Verify behavior with no backups

1. Create new document: `http://localhost:9092/?file=fresh_document`
2. Don't save anything
3. Open: `http://localhost:9092/history?file=fresh_document`
4. **Expected**:
   - The live `metadata.edn` (if present) still shows as a single current version;
     confirm the actual empty-state rendering against the current page rather than
     assuming a specific message string

**Verification**:
- [ ] Page renders without crashing
- [ ] No errors in console
- [ ] Behavior with zero backups is sensible

### Test 7: Hover Interactions
**Goal**: Verify tooltips and hover states

1. Open history page with multiple versions
2. Hover over a version node
3. **Expected**: node grows / stroke thickens, and a tooltip with the exact
   timestamp appears and follows the mouse
4. Move mouse away — node returns to normal, tooltip disappears

**Verification**:
- [ ] Hover effects work
- [ ] Tooltip appears with correct timestamp
- [ ] Tooltip follows cursor
- [ ] Effects disappear on mouse leave

### Test 8: Error Handling
**Goal**: Verify error scenarios are handled gracefully

1. Try to restore with an invalid version (via the JavaScript console):
   ```javascript
   send({name: 'restore-history-version', payload: {versionIndex: 999}})
   ```
2. **Expected**:
   - Error notification with the client-visible text "Failed to restore version"
     (the detailed reason, e.g. the invalid index, appears only in server logs)

3. Disconnect the server while viewing history
4. **Expected**:
   - Status changes to "Disconnected"
   - Reconnects automatically after 1.5 seconds

**Verification**:
- [ ] Invalid restore shows an error notification
- [ ] Error doesn't crash the page
- [ ] Connection loss is detected
- [ ] Auto-reconnect works

### Test 9: Keyboard/Accessibility
**Goal**: Verify keyboard navigation and accessibility

1. Tab through the page
2. **Expected**:
   - Can focus version nodes and the restore button
   - Enter activates focused elements

**Verification**:
- [ ] Tab navigation works
- [ ] Focus indicators are visible
- [ ] Enter activates focused elements

### Test 10: Properties Inspection
**Goal**: Verify restored boxes have correct markers

1. Restore version #2
2. In the main document, inspect a restored box's properties
3. **Expected**:
   - Box has `_restored_from_version: 2`
   - Tombstoned boxes have `_tombstone: true`
   - Un-tombstoned boxes have `_tombstone: false` (or the property removed)

**Verification**:
- [ ] `_restored_from_version` is set
- [ ] Tombstoned boxes marked correctly
- [ ] Un-tombstoned boxes marked correctly

## Known Limitations
1. Must refresh the main document page to see restored changes
2. No undo for restoration (but a new backup is created first)
3. Large numbers of versions (>100) may be slow

## Troubleshooting

### Timeline doesn't appear
- Check the browser console for errors
- Verify the `?file=` parameter matches the document name
- Ensure the document has at least one save

### Restore doesn't work
- Check the server console for errors
- Verify backup files exist in `backups/`
- Ensure backup files are valid EDN

### Transitions don't show
- Check that `versions.edn` exists and its `:transitions` array is populated
- Look for JavaScript errors in the console

## Success Criteria
- [ ] No errors in the browser console
- [ ] No errors in server logs
- [ ] All UI elements work as expected
- [ ] Data persists correctly
- [ ] Restoration works as documented

## Report Issues
If any tests fail, note: which test, expected vs actual behavior, browser console
errors, server console errors, and contents of problematic files (if relevant).
