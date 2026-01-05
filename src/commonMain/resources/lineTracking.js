
// this is tested and working. Need to put a revision marking on execute so that lines can be traced back to this line for markings from that revision
// not from the current state of the editor

let revisions = new Map();
let allDeltas = [];
let currentRevision = null;
let paused = false
const DEBUG_LINE_TRACKING = false;
const lineLog = (...args) => {
        if (DEBUG_LINE_TRACKING) console.info(...args);
};

function markRevision(inside, name) {
        inside.revisions = inside.revisions ?? new Map()
        inside.allDeltas = inside.allDeltas ?? []

        inside.currentRevision = {
                name: name,
                versionId: globalEditor.getModel().getVersionId(),
                deltas: []
        };
        inside.revisions.set(name, inside.currentRevision);
}

function pauseLineTracking() {
        paused = true
}

function unpauseLineTracking() {
        paused = false
}

function beginLineTracking(globalEditor) {
        globalEditor.getModel().onDidChangeContent(function(e) {
                lineLog("editor did change content", e, paused)

                if (currentSelectedCode.length!=1) return
                if (paused) return

                revisions = currentSelectedCode[0].revisions = currentSelectedCode[0].revisions ?? new Map()
                allDeltas = currentSelectedCode[0].allDeltas = currentSelectedCode[0].allDeltas ?? []
                currentRevision= currentSelectedCode[0].currentRevision

                if (!currentRevision) return;
                const changeVersion = globalEditor.getModel().getVersionId();
                e.changes.forEach(function(change) {
                        // Determine correct startLine, accounting for Enter at start of line
                        let startLine = change.range.startLineNumber;
                        // If change is Enter at column 1, treat as splitting previous line
                        if (change.text.startsWith('\n') && change.range.startColumn === 1 && startLine > 1) {
                                startLine = startLine - 1;
                        }
                        const removed = change.range.endLineNumber - change.range.startLineNumber;
                        const inserted = change.text.split('\n').length - 1;
                        lineLog("removed "+removed+" inserted "+inserted+" starting at "+change.range.startLineNumber)
                        const delta = {
                                revisionVersion: currentRevision.versionId,  // checkpoint marker
                                deltaVersion: changeVersion,
                                startLine: startLine,
                                removedLines: removed,
                                insertedLines: inserted
                        };
                        currentRevision.deltas.push(delta);
                        allDeltas.push(delta);
                });
        });
}


/**
 * Map a line from a named revision to the current document state.
 * @param {string} revName - The name of the saved revision.
 * @param {number} line - The 1-based line number in that revision.
 * @param {object} [options] - Optional behavior flags.
 * @param {boolean} [options.closest=false] - If true, return the nearest surviving line instead of null when deleted.
 * @returns {number|null} - The current line number or null (or nearest if requested).
 */
function mapLineFromRevision(inside, revName, line, options) {

        revisions = currentSelectedCode[0].revisions ?? new Map()
        allDeltas = currentSelectedCode[0].allDeltas ?? []
        currentRevision= currentSelectedCode[0].currentRevision

        const { closest = false } = options || {};
        const revision = revisions.get(revName);
        if (!revision) return null;

        let mappedLine = line;
        // Apply all deltas whose deltaVersion is after the saved revision version
        allDeltas
            .filter(function(d) { return d.deltaVersion > revision.versionId; })
            .forEach(function(delta) {
                    if (mappedLine === null) return;
                    if (delta.startLine < mappedLine) {
                            // Check if the original line was deleted
                            if (delta.startLine + delta.removedLines >= mappedLine) {
                                    if (closest) {
                                            mappedLine = delta.startLine;
                                    } else {
                                            mappedLine = null;
                                    }
                                    return;
                            }
                            // Shift line by net change
                            mappedLine += (delta.insertedLines - delta.removedLines);
                    }
            });

        return mappedLine;
}
