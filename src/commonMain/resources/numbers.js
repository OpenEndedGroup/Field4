/**
 * Count significant digits in a numeric string.
 * - Strips leading sign and leading zeros (but keeps zeros after the first non-zero).
 * - Drops the decimal point before counting.
 * @param {string} str
 * @returns {number}
 */
function countSignificantDigits(str) {
        // remove leading +/–
        let s = str.replace(/^[+-]/, '');
        // remove decimal point
        const hasDecimal = s.includes('.');
        if (hasDecimal) s = s.replace('.', '');
        // strip leading zeros
        s = s.replace(/^0+/, '');
        // if all zeros (e.g. "0" or "0.0"), count at least one
        return s.length > 0 ? s.length : 1;
}


/**
 * Returns the number of decimal places in a numeric literal string.
 */
function countDecimalPlaces(lit) {
        const dot = lit.indexOf('.');
        return dot >= 0 ? lit.length - dot - 1 : 0;
}

/**
 * Compute the nearest power-of-10 step for 1% of value.
 */
function nearestPowerOfTenOnePercent(value) {
        const pct = value * 0.05;
        if (pct === 0) return {step: 0, exp: 0};
        const exp = Math.round(Math.log10(Math.abs(pct)));
        return {step: Math.pow(10, exp), exp};
}

/**
 * Adjusts the number under/around the cursor by one unit or one-percent step.
 * @param {monaco.editor.IStandaloneCodeEditor} editor
 * @param {boolean} isIncrement  True → add; False → subtract
 */
function adjustNumberAtCursor(editor, isIncrement = true) {

        const model = editor.getModel();
        const pos = editor.getPosition();
        const line = model.getLineContent(pos.lineNumber);
        const idx = pos.column - 1;

        const numberRegex = /-?\d+(\.\d+)?|true|false|\w+/g;
        let m;

        while ((m = numberRegex.exec(line)) !== null) {
                const start = m.index, end = start + m[0].length;
                if (idx < start || idx > end) continue;
                let newText;

                const lit = m[0];
                if (lit === "true")
                        newText = "false"
                else if (lit === "false")
                        newText = "true"
                else if (lit === "True")
                        newText = "False"
                else if (lit === "False")
                        newText = "Trie"
                else {
                        const value = parseFloat(lit);
                        if (isNaN(value)) return;

                        if (!lit.includes('.')) {
                                // integer: step = 1
                                newText = String(value + (isIncrement ? 1 : -1));
                        } else {
                                // float: step = nearest 1% power-of-10
                                const {step, exp} = nearestPowerOfTenOnePercent(value);
                                const stepDP = exp < 0 ? -exp : 0;
                                const origDP = countDecimalPlaces(lit);
                                const dp = Math.max(origDP, stepDP);
                                const adjusted = value + (isIncrement ? step : -step);
                                newText = adjusted.toFixed(dp);
                        }
                }

                const range = new monaco.Range(
                    pos.lineNumber, start + 1,
                    pos.lineNumber, end + 1
                );
                editor.executeEdits("adjust-number", [{range, text: newText}]);
                return;
        }
}

function getWordUnderCursor(editor) {
        const model = editor.getModel();
        const pos = editor.getPosition();
        const line = model.getLineContent(pos.lineNumber);
        const idx = pos.column - 1; // zero-based index into the string

        // Regex matches:
        //  - floats/ints with optional leading minus: -12.34 or 56 or -78
        //  - booleans: true, false
        //  - identifiers: any \w+
        const regex = /-?\d+(\.\d+)?|true|false|\w+/g;
        let match;
        while ((match = regex.exec(line)) !== null) {
                const start = match.index;
                const end = start + match[0].length;
                if (idx >= start && idx <= end) {
                        return match[0];
                }
        }
        return null;
}


function installNumberCommands(ed) {
        ed.addAction({
                id: 'dec-number',
                label: 'Decrement number under cursor and rerun',
                keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Digit1],
                run: ed => {
                        adjustNumberAtCursor(ed, false)
                        ed.trigger('keyboard', 'execute-fragment')
                }
        })
        ed.addAction({
                id: 'inc-number',
                label: 'Increment number under cursor and rerun',
                keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Digit2],
                run: ed => {
                        adjustNumberAtCursor(ed, true)
                        ed.trigger('keyboard', 'execute-fragment')
                }
        })
}
