# Testing Guide for Document History Feature

Canonical testing map: `dev_docs/testing/TESTING_STRATEGY.md`. This file is a
feature-specific manual checklist; the automated history panel smoke path lives
in the browser UI regression suite.

## Prerequisites
- Field4 server running (`./gradlew jvmRun`)
- A document with at least a few saved versions

## Creating Test Data

### Step 1: Create Initial Document
1. Open Field4 in browser: `http://localhost:9092/?file=test_history`
2. Create a few boxes (e.g., 3-4 boxes)
3. Save the document (Ctrl+S or menu)
   - This creates the first `metadata.edn`

### Step 2: Make Changes to Create Backups
1. Modify one of the boxes (change its code, name, or position)
2. Save again
   - This creates `backups/metadata_backup_00000.edn`
   - And updates `metadata.edn`

3. Add a new box
4. Save
   - Creates `backups/metadata_backup_00001.edn`

5. Delete a box (or tombstone it)
6. Save
   - Creates `backups/metadata_backup_00002.edn`

7. Repeat 2-3 more times with various changes

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
   - Status shows "Connected" (green)
   - Timeline displays with all versions
   - Versions numbered from 0 (oldest) to N (current)
   - Current version highlighted in green at bottom
   - Each version shows box count and timestamp

**Verification**:
- [ ] All versions appear
- [ ] Version numbers are sequential
- [ ] Timestamps are correct
- [ ] Box counts match expectations
- [ ] Current version is highlighted

### Test 2: Version Selection
**Goal**: Verify version selection and detail display

1. Click on version #1 (second oldest)
2. **Expected**:
   - Info panel appears on right
   - Shows correct version number
   - Shows timestamp
   - Shows box count
   - Shows filename
   - Restore button is enabled (not disabled)

3. Click on current version (last one)
4. **Expected**:
   - Restore button is disabled
   - Button text changes to "Current Version"

**Verification**:
- [ ] Info panel appears on click
- [ ] All details are correct
- [ ] Current version disables restore button
- [ ] Non-current versions enable restore button

### Test 3: Version Restoration
**Goal**: Verify restoring a previous version works correctly

**Setup**: Note the current state (which boxes exist)

1. Click on version #2 (middle version)
2. Click "Restore This Version"
3. Confirm in the dialog
4. **Expected**:
   - Green success notification appears
   - "Version restored successfully! Refresh..."
   - Timeline reloads
   - New version appears at bottom (the restored state)

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
3. **Expected**:
   ```clojure
   {:transitions [{:fromIndex 5
                   :toIndex 2
                   :timestamp <some-timestamp>}]}
   ```

4. Restore another version (e.g., #4)
5. Check `versions.edn` again
6. **Expected**:
   ```clojure
   {:transitions [{:fromIndex 5
                   :toIndex 2
                   :timestamp <timestamp1>}
                  {:fromIndex 2
                   :toIndex 4
                   :timestamp <timestamp2>}]}
   ```

**Verification**:
- [ ] versions.edn is created
- [ ] Contains transitions array
- [ ] Each transition has fromIndex, toIndex, timestamp
- [ ] Multiple restores add multiple transitions

### Test 5: Transition Visualization
**Goal**: Verify transition arrows appear on timeline

1. After performing multiple restorations (Test 4)
2. Refresh history page: `http://localhost:9092/history?file=test_history`
3. **Expected**:
   - Curved dashed lines between non-sequential versions
   - Arrows pointing to the "to" version
   - Lines appear in semi-transparent white

**Verification**:
- [ ] Transition lines are visible
- [ ] Lines connect correct versions
- [ ] Arrows point in correct direction
- [ ] Multiple transitions all display

### Test 6: Empty History
**Goal**: Verify behavior with no backups

1. Create new document: `http://localhost:9092/?file=fresh_document`
2. Don't save anything
3. Open: `http://localhost:9092/history?file=fresh_document`
4. **Expected**:
   - "No version history available yet." message
   - No timeline displayed

**Verification**:
- [ ] Empty state message appears
- [ ] No errors in console
- [ ] Page doesn't crash

### Test 7: Hover Interactions
**Goal**: Verify tooltips and hover states

1. Open history page with multiple versions
2. Hover over a version node
3. **Expected**:
   - Circle grows slightly
   - Stroke gets thicker
   - Tooltip appears showing exact timestamp
   - Tooltip follows mouse

4. Move mouse away
5. **Expected**:
   - Circle returns to normal
   - Tooltip disappears

**Verification**:
- [ ] Hover effects work
- [ ] Tooltip appears with correct timestamp
- [ ] Tooltip follows cursor
- [ ] Effects disappear on mouse leave

### Test 8: Error Handling
**Goal**: Verify error scenarios are handled gracefully

1. Try to restore with invalid version (manually edit JavaScript console):
   ```javascript
   send({name: 'restore-history-version', payload: {versionIndex: 999}})
   ```
2. **Expected**:
   - Red error notification
   - "Failed to restore version: Invalid version index"

3. Disconnect server while viewing history
4. **Expected**:
   - Status changes to "Disconnected"
   - Reconnects automatically after 2 seconds

**Verification**:
- [ ] Invalid restore shows error notification
- [ ] Error doesn't crash page
- [ ] Connection loss is detected
- [ ] Auto-reconnect works

### Test 9: Keyboard/Accessibility
**Goal**: Verify keyboard navigation and accessibility

1. Tab through the page
2. **Expected**:
   - Can focus on version nodes
   - Can focus on restore button
   - Enter key works on focused elements

**Verification**:
- [ ] Tab navigation works
- [ ] Focus indicators are visible
- [ ] Enter activates focused elements

### Test 10: Properties Inspection
**Goal**: Verify restored boxes have correct markers

1. Restore version #2
2. In main document, inspect a restored box's properties
3. **Expected**:
   - Box has `_restored_from_version: 2` property
   - Tombstoned boxes have `_tombstone: true`
   - Un-tombstoned boxes have `_tombstone: false` (or property removed)

**Verification**:
- [ ] _restored_from_version property is set
- [ ] Tombstoned boxes marked correctly
- [ ] Un-tombstoned boxes marked correctly

## Known Limitations
1. Must refresh main document page to see restored changes
2. No undo for restoration (but new backup is created)
3. Large number of versions (>100) may be slow
4. No diff view between versions yet

## Troubleshooting

### Timeline doesn't appear
- Check browser console for errors
- Verify `?file=` parameter matches document name
- Ensure document has at least one save

### Restore doesn't work
- Check server console for errors
- Verify backup files exist in `backups/` directory
- Ensure backup files are valid EDN format

### Transitions don't show
- Check that `versions.edn` exists
- Verify transitions array is populated
- Look for JavaScript errors in console

## Success Criteria
All test cases should pass with:
- [ ] No errors in browser console
- [ ] No errors in server logs
- [ ] All UI elements work as expected
- [ ] Data persists correctly
- [ ] Restoration works as documented

## Report Issues
If any tests fail, note:
1. Which test failed
2. Expected vs actual behavior
3. Browser console errors
4. Server console errors
5. Contents of problematic files (if relevant)
