let markerDecorationsLayer = null

// 2) helper to check if a position is inside that decoration:
function getForbiddenRangeAtPosition(editor, pos) {
        const model = editor.getModel();
        const decs = model.getDecorationsInRange(
            new monaco.Range(pos.lineNumber, pos.column, pos.lineNumber, pos.column)
        );

        for (const d of decs) {
                if (markerDecorationsLayer._decorationIds.includes(d.id)) {
                        const range = model.getDecorationRange(d.id);

                        // If cursor is exactly at the start or end boundary, treat as outside
                        const atStart = pos.lineNumber === range.startLineNumber
                            && pos.column === range.startColumn;
                        const atEnd = pos.lineNumber === range.endLineNumber
                            && pos.column === range.endColumn;

                        if (atStart || atEnd) {
                                return null;
                        }
                        // Otherwise it's strictly inside
                        return range;
                }
        }
// No forbidden decoration under cursor
        return null;
}

function addMarkerHere(editor, text) {
        const insertText = text;

        // 1. Get current cursor position
        const position = editor.getPosition();

        // 2. Create a zero-length range at that position
        const insertRange = new monaco.Range(position.lineNumber, position.column, position.lineNumber, position.column);

        // 3. Perform the edit
        editor.executeEdits('insert-marker', [{
                range: insertRange, text: insertText, forceMoveMarkers: true
        }]);

        // 4. Compute and return the range of the newly inserted text
        const endColumn = position.column + insertText.length;
        let r = new monaco.Range(position.lineNumber, position.column, position.lineNumber, endColumn);


        const newDecoration = {
                range: r, options: {
                        inlineClassName: "coverMarker", inlineClassNameAffectsLetterSpacing:true, stickiness: 1
                }, _isMarker: true
        }
        markerDecorationsLayer.append([newDecoration])
}

function epoch64() {
        let n = Date.now(),  s = '',
            b = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
        while (n > 0) {
                s = b[n % 64] + s;
                n = Math.floor(n / 64);
        }
        return s+"-"+randomString(2);
}

function randomString(length = 5) {
        const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
        let result = '';
        for (let i = 0; i < length; i++) {
                result += chars.charAt(Math.floor(Math.random() * chars.length));
        }
        return result;
}

let lastSelection = null;

function initMarkerSupport(globalEditor) {

        let editor = globalEditor
        editor.onDidChangeCursorSelection(e => {
                const sel     = e.selection;
                const prevSel = lastSelection;

                // --- NEW GUARD: if we don’t have a previous selection yet, just sync and exit
                if (!prevSel) {
                        lastSelection = sel;
                        return;
                }

                // now prevSel is safe to use…
                lastSelection = sel;  // remember for next time

                const model = editor.getModel();

                // A) Plain cursor move
                if (sel.isEmpty()) {
                        const newPos    = sel.getPosition();
                        const oldPos    = prevSel.getPosition();
                        const fr        = getForbiddenRangeAtPosition(editor, newPos);
                        if (!fr) return;

                        const oldOffset = model.getOffsetAt(oldPos);
                        const newOffset = model.getOffsetAt(newPos);
                        if (newOffset > oldOffset) {
                                editor.setPosition({ lineNumber: fr.endLineNumber, column: fr.endColumn + 1 });
                        } else {
                                editor.setPosition({ lineNumber: fr.startLineNumber, column: fr.startColumn });
                        }

                        // B) Shift+arrow (selection)
                } else {
                        const anchorPos = {
                                lineNumber: sel.selectionStartLineNumber,
                                column:     sel.selectionStartColumn
                        };
                        const activePos    = { lineNumber: sel.positionLineNumber, column: sel.positionColumn };
                        const fr           = getForbiddenRangeAtPosition(editor, activePos);
                        if (!fr) return;

                        const prevActivePos = prevSel.getPosition();
                        const prevOffset    = model.getOffsetAt(prevActivePos);
                        const activeOffset  = model.getOffsetAt(activePos);

                        let correctedActive;
                        if (activeOffset > prevOffset) {
                                correctedActive = { lineNumber: fr.endLineNumber, column: fr.endColumn + 1 };
                        } else {
                                correctedActive = { lineNumber: fr.startLineNumber, column: fr.startColumn };
                        }

                        editor.setSelection(new monaco.Selection(
                            anchorPos.lineNumber, anchorPos.column,
                            correctedActive.lineNumber, correctedActive.column
                        ));
                }
        });

        markerDecorationsLayer = globalEditor.createDecorationsCollection([])

        globalEditor.addAction({
                id: "insert-stack-marker",
                label: "Insert Stack Mark",
                keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Period | monaco.KeyMod.Shift],
                precondition: undefined,
                keybindingContext: undefined,
                contextMenuGroupId: "navigation",
                contextMenuOrder: 2.0,
                run: function (e) {
                        addMarkerHere(globalEditor, `__m("${epoch64()}")`)
                        globalEditor.focus()
                }
        })

        // epoch64() uses the standard Base64 alphabet, then appends a hyphen and suffix.
        const markerRegex = /__m\("[A-Za-z0-9+\/-]*"\)/g;

        editor.onDidChangeModelContent(() => {
                const model = editor.getModel();
                const text  = model.getValue();
                const newDecs = [];
                let match;

                // 3) Find each match and compute its Range
                while ((match = markerRegex.exec(text)) !== null) {
                        const startOffset = match.index;
                        const endOffset   = startOffset + match[0].length;

                        const startPos = model.getPositionAt(startOffset);
                        const endPos   = model.getPositionAt(endOffset);

                        const r = new monaco.Range(
                            startPos.lineNumber,
                            startPos.column,
                            endPos.lineNumber,
                            endPos.column
                        );

                        // 4) Build your decoration object
                        newDecs.push({
                                range: r,
                                options: {
                                        inlineClassName: "coverMarker",
                                        inlineClassNameAffectsLetterSpacing: true,
                                        stickiness: 1
                                },
                                _isMarker: true
                        });
                }

                // 5) Reset the layer to exactly these decorations
                markerDecorationsLayer.clear();        // remove any old markers
                markerDecorationsLayer.append(newDecs); // add the new ones
        });


}
