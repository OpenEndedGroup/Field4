/**
 * Comment blocks — prose panels for the long `//` and block comments that head up a box.
 *
 * Three pieces, in dependency order:
 *
 *   detectBlocks()   a contiguous run of `//` lines, or a multi-line C-style block, is one block
 *   classifyRuns()   inside a block, which lines are hand-aligned tables and which are prose
 *   decorate()       whole-line decorations paint the block as an inset panel
 *
 * plus a folding-range provider of kind Comment, so `editor.foldAllBlockComments` collapses every
 * block in one keystroke and the whole header folds down to its first line.
 *
 * WHY THE CLASSIFIER. Field's box headers are not markdown. Some runs are prose paragraphs that
 * would read better reflowed; others are columns set by hand with the space bar --
 *
 *     //   W S or up down       forward, back        A D        strafe
 *
 * -- and *any* reflow destroys those permanently. So every line is classified before anything is
 * done to it, and the classifier is deliberately biased: a line with an internal multi-space gap is
 * `aligned` even on its own, because keeping monospace is the cheap mistake and reflowing is the
 * expensive one. Nothing here rewrites text -- that bias exists for the rendered-markdown view zone
 * this is groundwork for, which does.
 *
 * MONACO NOTES, all verified against the bundled editor.main.js rather than assumed:
 *
 *  - whole-line decorations render as `<div class="cdr YOURCLASS" style="left:0;width:100%;">` with
 *    left/width set INLINE, so changing either needs `!important`. They live in `.view-overlays`
 *    inside `.lines-content`, which scrolls with the text, so `width:100%` is the longest line in
 *    the whole FILE -- not the viewport, and not the block. Hence the per-block .field-cb-w-N rule:
 *    without it a card never ends anywhere near its own text.
 *  - the panel carries no border, on any side. A line offset from the text reads as a drop shadow
 *    rather than an edge, and in pure black especially so. The wash does the grouping and the radius
 *    shapes the card by clipping it. That also disposes of the reason the left edge was delicate:
 *    the panel is flush at column 1, where an unindented block-comment opener lives, and a border
 *    there landed on its glyphs. (`linesDecorationsClassName` -> `.cldr` in `.margin-view-overlays`
 *    is the escape hatch if a vertical edge is ever wanted; that lane cannot collide at any indent
 *    and does not scroll sideways, and it inline-styles left and width just like `.cdr`.)
 *  - a whole-line `className` styles the overlay div, never the glyphs. Text styling needs a second
 *    decoration carrying `inlineClassName`, hence two decorations per line.
 *  - `monaco.editor.tokenize(text, languageId)` is public, and using it rather than a regex is what
 *    keeps `val s = "/*"` from opening a block. It re-tokenizes the whole model, so refreshes are
 *    debounced, and the regex stays as the fallback for very large models and for the window before
 *    a lazily-loaded Monarch grammar has arrived.
 *  - but a genuinely EMPTY line tokenizes to an empty token array even mid-block-comment, so tokens
 *    cannot say whether it is interior. Only a span scan can, which is why commentKinds() consults
 *    both and lets each answer what it actually knows.
 */

const CommentBlocks = (() => {
    const STATE = new WeakMap();

    /** C-style-comment languages that show up as box properties. */
    const LANGUAGES = ["kotlin", "glsl", "msl", "slang"];

    // A `//` pair reads as two remarks, which is exactly why formatter.kt's MINIMUM_RUN leaves it
    // alone; painting a panel on it would argue with that. A `/* */` is already three lines at its
    // smallest, so the delimiters are the author saying it is one thing.
    const MIN_BLOCK_LINES = { line: 3, block: 2 };
    const MIN_FOLD_LINES = 3;       // but only offer a fold at three
    const MIN_ALIGNED_FIELDS = 2;   // "a  b" is a candidate table row, "a b" is not
    const INDENT_VERBATIM_COLUMNS = 2;  // indented this far past the block margin means verbatim
    const STOP_TOLERANCE = 2;       // hand-set columns drift; treat stops this close as the same
    const CHIP_TITLE_CHARS = 60;      // the most a folded block's borrowed title may use
    const CHIP_TITLE_MIN_CHARS = 16;  // below this a title is a stub; drop it and keep the count
    const CHIP_GAP_COLUMNS = 3;       // the separator padding a chip spends on itself
    const CHIP_FOLD_MARKER_COLUMNS = 3;  // room for Monaco's own collapsed indicator
    const TAB_COLUMNS = 4;          // a tab counts this wide when measuring indentation
    // The panel hugs its own block's longest line, so each comment is a card sized to its content.
    const PANEL_PAD_COLUMNS = 3;    // breathing room between the last glyph and the right edge
    const MIN_PANEL_COLUMNS = 32;   // a two-word comment still gets a panel worth looking at
    const MAX_PANEL_COLUMNS = 170;  // one runaway line should not drag the edge off the screen
    const WIDTH_BUCKET_COLUMNS = 2; // round widths to this, to bound the injected rule count
    // Above this, skip tokenize and take the regex: a full re-tokenize per debounced keystroke
    // burst is real main-thread time, and a mis-detected panel on a 1000-line box is cosmetic.
    const TOKENIZE_LINE_LIMIT = 1200;
    const REFRESH_DEBOUNCE_MS = 140;
    const STORAGE_KEY = "afield.commentBlocks.visible";

    /** Glyphs a separator rule is allowed to be made of. */
    const RULE_CHARS = new Set([..."-=_~*.·—–─━┄┅┈┉╌╍#"]);

    // -- storage ----------------------------------------------------------------

    function storedVisible() {
        try {
            const raw = window.localStorage?.getItem(STORAGE_KEY);
            return raw == null ? true : raw === "true";
        } catch (_) {
            return true;
        }
    }

    function storeVisible(value) {
        try {
            window.localStorage?.setItem(STORAGE_KEY, value ? "true" : "false");
        } catch (_) {
        }
    }

    // -- detection --------------------------------------------------------------

    /**
     * Per-line comment kind: `"line"` for `//`, `"block"` for anything inside a C-style block,
     * `"none"` otherwise.
     *
     * @param {string[]} lines
     * @param {Array<Array<{offset:number,type:string}>>|null} tokensPerLine
     * @returns {Array<"none"|"line"|"block">}
     */
    function commentKinds(lines, tokensPerLine) {
        const scan = scanCommentKinds(lines);
        if (!tokensPerLine) return scan;
        const tokens = tokenCommentKinds(lines, tokensPerLine);

        // Each source knows something the other cannot. Tokens are authoritative for a line with
        // content -- they are what keeps `val s = "/*"` from opening a block. But a genuinely empty
        // line tokenizes to an EMPTY TOKEN ARRAY even in the middle of a block comment, so the
        // tokens simply do not say; only the span does. Restricting the scan's answer to "block"
        // matters: a blank line inside a block comment is interior, while a blank line between two
        // `//` runs is a real separator and must stay one.
        return lines.map((text, index) => {
            if (String(text).trim()) return tokens[index];
            return scan[index] === "block" ? "block" : "none";
        });
    }

    function tokenCommentKinds(lines, tokensPerLine) {
        return lines.map((text, index) => {
            const tokens = tokensPerLine[index];
            if (!tokens) return "none";

            // The first token with any content decides the line. Monaco emits a leading whitespace
            // token typed for the language, so skip anything blank -- but remember whether the
            // tokens were comment-typed, which is the whole answer for a blank line.
            let sawComment = false;
            for (let i = 0; i < tokens.length; i += 1) {
                const start = tokens[i].offset;
                const end = i + 1 < tokens.length ? tokens[i + 1].offset : text.length;
                const slice = text.slice(start, end);
                const isComment = String(tokens[i].type || "").includes("comment");
                if (isComment) sawComment = true;
                if (!slice.trim()) continue;
                if (!isComment) return "none";
                return slice.trimStart().startsWith("//") ? "line" : "block";
            }
            // Every token was whitespace. A blank line still typed as a comment is INSIDE a block
            // comment, so it continues the block -- otherwise every paragraph gap ends one panel
            // and starts another, and a header reads as a stack of cards. A blank line BETWEEN two
            // comments is not comment-typed, so it still separates them.
            return sawComment ? "block" : "none";
        });
    }

    /**
     * The no-tokens fallback. It has to track block-comment spans by hand: Field's block-comment
     * style puts no leading `*` on interior lines, so those lines are indistinguishable from code
     * one line at a time -- only the span knows. Requiring `/*` at the *start* of a line to open a
     * span is what keeps a quoted marker from opening a phantom block; a multi-line string whose
     * line begins with `/*` would still fool it, which is the price of not having tokens.
     */
    function scanCommentKinds(lines) {
        const kinds = [];
        let inBlock = false;
        for (const raw of lines) {
            const text = String(raw);
            if (inBlock) {
                kinds.push("block");
                if (text.includes("*/")) inBlock = false;
                continue;
            }
            const trimmed = text.trimStart();
            if (trimmed.startsWith("//")) {
                kinds.push("line");
            } else if (trimmed.startsWith("/*")) {
                kinds.push("block");
                if (!trimmed.slice(2).includes("*/")) inBlock = true;
            } else {
                kinds.push("none");
            }
        }
        return kinds;
    }

    function leadingIndent(text) {
        const match = /^[ \t]*/.exec(text);
        return match ? match[0].length : 0;
    }

    /** Leading whitespace as columns, counting a tab as TAB_COLUMNS. */
    function leadingSpaces(body) {
        const match = /^[ \t]*/.exec(body);
        if (!match) return 0;
        let columns = 0;
        for (const ch of match[0]) columns += ch === "\t" ? TAB_COLUMNS : 1;
        return columns;
    }

    /**
     * Strip a line's comment markers, leaving the body.
     *
     * A `//` line loses its own indent and the marker. A block-comment line KEEPS its leading
     * whitespace: inside a block comment the interior indentation is content -- an indented code
     * sample, a hanging column -- and throwing it away made an indented line look flush, so it
     * classified as prose while its neighbours classified as verbatim. dedent() takes the block's
     * common margin off afterwards, which preserves what is relative.
     */
    function commentBody(text, kind) {
        let body = String(text).replace(/\s+$/, "");
        if (kind === "line") {
            return body.replace(/^[ \t]*/, "").replace(/^\/\/ ?/, "");
        }
        body = body.replace(/^([ \t]*)\/\*+ ?/, "$1");
        body = body.replace(/\*\/\s*$/, "");
        // A leading `*` is the older per-line style; harmless to accept, and Field no longer emits it.
        body = body.replace(/^([ \t]*)\* ?/, "$1");
        return body.replace(/\s+$/, "");
    }

    /** Take the block's common left margin off every line, keeping relative indentation. */
    function dedent(bodies) {
        let margin = Infinity;
        for (const body of bodies) {
            if (body.trim()) margin = Math.min(margin, leadingSpaces(body));
        }
        if (!Number.isFinite(margin) || margin === 0) return bodies;
        return bodies.map((body) => {
            if (!body.trim()) return "";
            let dropped = 0;
            let i = 0;
            while (i < body.length && dropped < margin) {
                const ch = body[i];
                if (ch !== " " && ch !== "\t") break;
                dropped += ch === "\t" ? TAB_COLUMNS : 1;
                i += 1;
            }
            return body.slice(i);
        });
    }

    /**
     * Group lines into comment blocks.
     *
     * `//` runs group while the indent holds steady, so a commented-out block at a different depth
     * does not get swallowed into the header above it. Block comments group on their own.
     *
     * @param {string[]} lines
     * @param {{tokensPerLine?: Array<Array<{offset:number,type:string}>>|null}} [options]
     * @returns {Array<{startLine:number,endLine:number,kind:string,indent:number,bodies:string[],runs:Array}>}
     */
    function detectBlocks(lines, options = {}) {
        const kinds = commentKinds(lines, options.tokensPerLine || null);
        const blocks = [];
        let index = 0;

        while (index < lines.length) {
            const kind = kinds[index];
            if (kind === "none") {
                index += 1;
                continue;
            }

            const indent = leadingIndent(lines[index]);
            let end = index;
            while (end + 1 < lines.length
                && kinds[end + 1] === kind
                && (kind === "block" || leadingIndent(lines[end + 1]) === indent)) {
                end += 1;
            }

            if (end - index + 1 >= (MIN_BLOCK_LINES[kind] || 2)) {
                const raw = [];
                let columns = 0;
                for (let i = index; i <= end; i += 1) {
                    raw.push(commentBody(lines[i], kind));
                    // The panel is drawn over the rendered line, so its width is measured on the
                    // source text -- indent and markers included -- not on the dedented body.
                    columns = Math.max(columns, lines[i].replace(/\s+$/, "").length);
                }
                const bodies = dedent(raw);
                blocks.push({
                    startLine: index + 1,
                    endLine: end + 1,
                    kind,
                    indent,
                    columns,
                    bodies,
                    runs: classifyRuns(bodies)
                });
            }
            index = end + 1;
        }
        return blocks;
    }

    // -- the run classifier -----------------------------------------------------

    /**
     * Column stops of a body: the start column of every field after the first, where fields are
     * held apart by two or more spaces. `"  R    the plates"` has one stop, at the `t`.
     */
    function columnStops(body) {
        const stops = [];
        const gap = / {2,}/g;
        let match;
        while ((match = gap.exec(body)) !== null) {
            const stop = match.index + match[0].length;
            if (match.index > 0 && stop < body.length) stops.push(stop);
        }
        return stops;
    }

    function isRuleLine(body) {
        const glyphs = body.replace(/\s/g, "");
        if (glyphs.length < 4) return false;
        let ruleish = 0;
        for (const ch of glyphs) if (RULE_CHARS.has(ch)) ruleish += 1;
        return ruleish / glyphs.length >= 0.8;
    }

    function lineKind(body, inFence, baseIndent) {
        if (inFence) return "code";
        if (!body.trim()) return "blank";
        if (isRuleLine(body)) return "rule";
        if (columnStops(body).length >= MIN_ALIGNED_FIELDS - 1) return "aligned";
        // Indented past the block's own left margin. Markdown reads that as a code block, and it is
        // also how a shell command or a path gets written into a header:
        //
        //     //   cd ~/temp/planesubdivgame && ./psub_demo --size 320 240
        //
        // Reflowing one of those breaks it, so an indent alone is enough to mean verbatim. This
        // does catch hanging-indent prose continuations too -- deliberately, since holding a line
        // monospace costs nothing and reflowing it cannot be undone.
        return leadingSpaces(body) - baseIndent >= INDENT_VERBATIM_COLUMNS ? "aligned" : "prose";
    }

    /**
     * Classify a block's bodies into contiguous runs.
     *
     * Kinds: `prose` (reflowable sentences), `aligned` (verbatim -- hand-set columns, or indented
     * past the block margin), `code` (inside a ``` fence), `rule` (a separator), `blank`.
     *
     * A run also carries `sharedStops`: the column stops every line in an aligned run agrees on.
     * Non-empty means these lines really are one table rather than merely verbatim, which is the
     * signal a renderer wants before emitting a single `<pre>` for the group.
     *
     * @param {string[]} bodies
     * @returns {Array<{start:number,end:number,kind:string,sharedStops:number[]}>}
     */
    function classifyRuns(bodies) {
        // The block's own left margin, so a uniformly indented comment is not read as all-verbatim.
        let baseIndent = Infinity;
        for (const body of bodies) {
            if (body.trim()) baseIndent = Math.min(baseIndent, leadingSpaces(body));
        }
        if (!Number.isFinite(baseIndent)) baseIndent = 0;

        const kinds = [];
        let inFence = false;
        for (const body of bodies) {
            const fenced = /^\s*```/.test(body);
            if (fenced) {
                kinds.push("code");
                inFence = !inFence;
                continue;
            }
            kinds.push(lineKind(body, inFence, baseIndent));
        }

        const runs = [];
        let index = 0;
        while (index < kinds.length) {
            const kind = kinds[index];
            let end = index;
            let shared = kind === "aligned" ? columnStops(bodies[index]) : [];

            while (end + 1 < kinds.length && kinds[end + 1] === kind) {
                if (kind === "aligned") {
                    const next = columnStops(bodies[end + 1]);
                    if (shared.length && next.length) {
                        // Two table rows that agree on no column are two different tables. Agreement
                        // is fuzzy on purpose: columns set by hand drift a character here and there
                        // ("0" against "1 .. 9" in one key table on the reference box), and an exact
                        // match splits one table into a row each.
                        const intersection = shared.filter(
                            (stop) => next.some((other) => Math.abs(other - stop) <= STOP_TOLERANCE)
                        );
                        if (!intersection.length) break;
                        shared = intersection;
                    } else if (shared.length || next.length) {
                        // A table row and a merely-indented line: verbatim either way, but not the
                        // same run, or a renderer would set the continuation inside the table.
                        break;
                    }
                }
                end += 1;
            }

            runs.push({ start: index, end, kind, sharedStops: kind === "aligned" ? shared : [] });
            index = end + 1;
        }
        return runs;
    }

    /** The run kind covering a block-relative line offset. */
    function runKindAt(block, offset) {
        for (const run of block.runs) {
            if (offset >= run.start && offset <= run.end) return run.kind;
        }
        return "prose";
    }

    // -- styles -----------------------------------------------------------------

    function ensureStyles() {
        if (document.getElementById("afield-comment-blocks-styles")) return;
        const style = document.createElement("style");
        style.id = "afield-comment-blocks-styles";
        style.textContent = `
.monaco-editor .view-overlays .cdr.field-cb {
    /* The palette's --field-panel* are black washes, which on a dark editor just make a darker
       hole. A panel wants to read as a lift, so these are white at low alpha off the same palette
       RGB, and a theme that changes --field-white-rgb still carries them. */
    --field-cb-wash: rgba(var(--field-white-rgb, 255, 255, 255), 0.035);
    --field-cb-wash-firm: rgba(var(--field-white-rgb, 255, 255, 255), 0.065);
    /* For the separator rule drawn INSIDE a comment -- not a panel edge. Kept faint and white:
       a dark line on the wash reads as a bevel, the same reason the panel has no border. */
    --field-cb-hairline: rgba(var(--field-white-rgb, 255, 255, 255), 0.12);

    /* No borders anywhere on the panel. A line offset from the text -- black especially -- reads as
       a drop shadow rather than an edge, so the wash alone does the grouping and the radius shapes
       the card by clipping that wash.

       left/width are inline on the decoration div, so both need !important to change. The panel
       starts flush at the content origin, which is also why an inset would be wrong even with a
       border: an unindented block-comment opener sits in column 1. The width comes from a per-block
       .field-cb-w-N rule, because the inline width:100% is the longest line in the whole FILE --
       so without it the card would never end anywhere near its own text. */
    left: 0 !important;
    background: var(--field-cb-wash);
    pointer-events: none;
}
.monaco-editor .view-overlays .cdr.field-cb-first {
    border-top-right-radius: 7px;
}
.monaco-editor .view-overlays .cdr.field-cb-last {
    border-bottom-right-radius: 7px;
}
.monaco-editor .view-overlays .cdr.field-cb-aligned,
.monaco-editor .view-overlays .cdr.field-cb-code {
    /* Columns read as a table: firmer ground than the prose around them. */
    background: var(--field-cb-wash-firm);
}
.monaco-editor .view-overlays .cdr.field-cb-rule {
    position: relative;
}
.monaco-editor .view-overlays .cdr.field-cb-rule::after {
    content: "";
    position: absolute;
    left: 10px;
    right: 10px;
    top: 50%;
    height: 1px;
    background: var(--field-cb-hairline);
}
.monaco-editor .field-cb-text {
    /* Italic only. A proportional family here would desync the caret -- Monaco measures in
       character advances -- and Iosevka's italic keeps the same advance. */
    font-style: italic;
    color: var(--field-fg-dim, rgba(255, 255, 255, 0.53));
}
.monaco-editor .field-cb-text-aligned {
    font-style: normal;
    color: var(--field-fg-muted, rgba(255, 255, 255, 0.66));
}
.monaco-editor .field-cb-text-rule {
    opacity: 0.3;
}
.monaco-editor .field-cb-text-code {
    font-style: normal;
    color: var(--field-fg-soft, rgba(255, 255, 255, 0.74));
}
.monaco-editor .field-cb-margin {
    /* Carries the wash through the line-number gutter so the panel reads as one band. */
    background: rgba(var(--field-white-rgb, 255, 255, 255), 0.035);
}
.monaco-editor .field-cb-chip {
    color: var(--field-fg-dim, rgba(255, 255, 255, 0.53));
    font-style: italic;
}
`;
        document.head.appendChild(style);
    }

    // -- per-block width --------------------------------------------------------

    const widthRules = new Set();

    /**
     * The panel's width, as a class.
     *
     * A decoration can only carry class names, so a per-block width has to arrive as one -- the same
     * trick lineHeatmap uses for its levels, except the values here are open-ended, so the rules are
     * injected on demand and cached. Buckets of WIDTH_BUCKET_COLUMNS keep that set small while a
     * block is being typed into.
     *
     * `ch` rather than px: the overlay div carries the editor's own font inline, so one `ch` is one
     * character advance, and the panel keeps its alignment across a font-size change with no
     * recompute. That holds because the editor font is monospace, which Monaco already requires.
     */
    function widthClass(columns) {
        const wanted = Math.max(MIN_PANEL_COLUMNS, Math.min(MAX_PANEL_COLUMNS, columns + PANEL_PAD_COLUMNS));
        const bucket = Math.ceil(wanted / WIDTH_BUCKET_COLUMNS) * WIDTH_BUCKET_COLUMNS;
        const name = `field-cb-w-${bucket}`;
        if (!widthRules.has(bucket)) {
            widthRules.add(bucket);
            let sheet = document.getElementById("afield-comment-blocks-widths");
            if (!sheet) {
                sheet = document.createElement("style");
                sheet.id = "afield-comment-blocks-widths";
                document.head.appendChild(sheet);
            }
            sheet.appendChild(document.createTextNode(
                `.monaco-editor .view-overlays .cdr.${name} { width: ${bucket}ch !important; }\n`
            ));
        }
        return name;
    }

    // -- decorations ------------------------------------------------------------

    function stateFor(editor) {
        let state = STATE.get(editor);
        if (!state) {
            state = {
                decorations: editor.createDecorationsCollection
                    ? editor.createDecorationsCollection([])
                    : { set: () => {} },
                chips: editor.createDecorationsCollection
                    ? editor.createDecorationsCollection([])
                    : { set: () => {} },
                blocks: [],
                visible: storedVisible(),
                refreshTimer: null,
                tokenListener: null
            };
            STATE.set(editor, state);
        }
        return state;
    }

    function supported(model) {
        return !!model && LANGUAGES.includes(model.getLanguageId());
    }

    function tokensFor(model, monaco) {
        if (!monaco?.editor?.tokenize) return null;
        if (model.getLineCount() > TOKENIZE_LINE_LIMIT) return null;
        let tokens;
        try {
            tokens = monaco.editor.tokenize(model.getValue(), model.getLanguageId());
        } catch (error) {
            console.debug("[comment-blocks] tokenize failed, falling back to regex", error);
            return null;
        }
        // A Monarch grammar loads lazily, and until it does Monaco answers with the null tokenizer:
        // one token per line, `type: ""`. Taking that at face value classifies every line as
        // not-a-comment, so at editor-creation time -- which is exactly when install() runs -- no
        // block is ever found and nothing paints until the first keystroke. Reject it and let the
        // regex answer instead; followTokens() re-runs this once the real grammar arrives.
        for (const line of tokens) {
            for (const token of line) if (token.type) return tokens;
        }
        return null;
    }

    function blocksFor(model, monaco) {
        const lines = [];
        const count = model.getLineCount();
        for (let line = 1; line <= count; line += 1) lines.push(model.getLineContent(line));
        return detectBlocks(lines, { tokensPerLine: tokensFor(model, monaco) });
    }

    function hoverFor(block) {
        // Monaco renders IMarkdownString itself, so the block's own text -- headings, lists, links --
        // arrives rendered for free. Aligned runs are fenced so their columns survive the reflow.
        const chunks = [];
        for (const run of block.runs) {
            const slice = block.bodies.slice(run.start, run.end + 1);
            if (run.kind === "code") {
                // Already carries its own ``` fences; wrapping it would nest them and the inner
                // fence would close the outer one.
                chunks.push(slice.join("\n"));
            } else if (run.kind === "aligned") {
                // Four backticks, so a stray ``` inside the columns cannot close the fence early.
                chunks.push("````text\n" + slice.join("\n") + "\n````");
            } else if (run.kind === "rule") {
                chunks.push("---");
            } else if (run.kind !== "blank") {
                chunks.push(slice.join("\n"));
            }
        }
        return { value: chunks.join("\n\n"), isTrusted: false };
    }

    function buildDecorations(monaco, blocks) {
        const decorations = [];
        for (const block of blocks) {
            for (let line = block.startLine; line <= block.endLine; line += 1) {
                const kind = runKindAt(block, line - block.startLine);
                const classes = ["field-cb", `field-cb-${kind}`, widthClass(block.columns)];
                if (line === block.startLine) classes.push("field-cb-first");
                if (line === block.endLine) classes.push("field-cb-last");

                decorations.push({
                    range: new monaco.Range(line, 1, line, 1),
                    options: {
                        isWholeLine: true,
                        className: classes.join(" "),
                        marginClassName: "field-cb-margin",
                        stickiness: 1,
                        hoverMessage: line === block.startLine ? hoverFor(block) : undefined
                    }
                });

                // A second decoration for the glyphs: a whole-line className only reaches the
                // overlay div behind them.
                const text = ["field-cb-text", `field-cb-text-${kind}`].join(" ");
                decorations.push({
                    range: new monaco.Range(line, 1, line, Number.MAX_SAFE_INTEGER),
                    options: { inlineClassName: text, stickiness: 1 }
                });
            }
        }
        return decorations;
    }

    /**
     * Is the line after `startLine` hidden? For a folded region Monaco maps every hidden model line
     * onto the fold's own view line, so their tops coincide -- which is a public-API way to ask.
     */
    function isFolded(editor, startLine) {
        if (typeof editor.getTopForLineNumber !== "function") return false;
        try {
            return editor.getTopForLineNumber(startLine) === editor.getTopForLineNumber(startLine + 1);
        } catch (_) {
            return false;
        }
    }

    /**
     * The block's title: its first line with something to say. Folding keeps only the first line
     * visible, and on a Field header that first line is usually the separator rule -- so a folded
     * header would read as a dash and a line count. Hunting down one line for the real opener is
     * what makes the collapsed state legible.
     */
    function blockTitle(block, maxChars) {
        if (maxChars < CHIP_TITLE_MIN_CHARS) return "";
        for (let i = 1; i < block.bodies.length; i += 1) {
            const kind = runKindAt(block, i);
            if (kind === "blank" || kind === "rule") continue;
            const text = block.bodies[i].trim();
            if (!text) continue;
            return text.length > maxChars ? text.slice(0, maxChars - 1) + "…" : text;
        }
        return "";
    }

    /** How many columns fit on one visual row, honouring word wrap when it is on. */
    function visibleColumns(editor) {
        const layout = editor.getLayoutInfo && editor.getLayoutInfo();
        if (!layout) return 0;
        const columns = layout.wrappingColumn > 0 ? layout.wrappingColumn : layout.viewportColumn;
        return Number.isFinite(columns) && columns > 0 ? columns : 0;
    }

    function buildChips(monaco, editor, blocks) {
        const model = editor.getModel && editor.getModel();
        if (!model) return [];
        const columns = visibleColumns(editor);
        const chips = [];
        for (const block of blocks) {
            const lines = block.endLine - block.startLine + 1;
            if (lines < MIN_FOLD_LINES) continue;
            if (!isFolded(editor, block.startLine)) continue;

            const count = `${lines - 1} more lines`;
            // Injected text is laid out after the line's own glyphs and it WRAPS rather than clips
            // (wordWrap is on -- BootstrapMonacoInit.kt), so a chip wider than what is left of the
            // row turns a folded block into two visual rows, which is worse than having no title.
            // A Field header opens on a full-width separator rule and leaves nothing to spend, so
            // the budget decides rather than a fixed width. Only borrow a title when the visible
            // line is not already carrying the text.
            //
            // The budget subtracts the *measured* separators rather than counting them, because
            // arithmetic over them drifts: the first version forgot the middot and overran by
            // exactly one column, which is all it takes to wrap.
            const firstKind = runKindAt(block, 0);
            const borrows = firstKind === "rule" || firstKind === "blank";
            const own = model.getLineContent(block.startLine).replace(/\s+$/, "").length;
            const lead = " ".repeat(CHIP_GAP_COLUMNS);
            const joiner = `${lead}·${lead}`;
            const spare = columns
                ? columns - own - lead.length - joiner.length - count.length - CHIP_FOLD_MARKER_COLUMNS
                : CHIP_TITLE_CHARS;
            const title = borrows ? blockTitle(block, Math.min(CHIP_TITLE_CHARS, spare)) : "";

            chips.push({
                range: new monaco.Range(block.startLine, 1, block.startLine, Number.MAX_SAFE_INTEGER),
                options: {
                    after: {
                        content: title ? `${lead}${title}${joiner}${count}` : `${lead}${count}`,
                        inlineClassName: "field-cb-chip"
                    },
                    stickiness: 1
                }
            });
        }
        return chips;
    }

    function refreshChips(editor, monaco = window.globalMonaco) {
        const state = stateFor(editor);
        if (!monaco || !state.visible) {
            state.chips.set([]);
            return;
        }
        state.chips.set(buildChips(monaco, editor, state.blocks));
    }

    function refresh(editor, monaco = window.globalMonaco) {
        if (!editor || !monaco) return;
        ensureStyles();
        const state = stateFor(editor);
        const model = editor.getModel && editor.getModel();

        if (!state.visible || !supported(model)) {
            state.blocks = [];
            state.decorations.set([]);
            state.chips.set([]);
            return;
        }

        state.blocks = blocksFor(model, monaco);
        state.decorations.set(buildDecorations(monaco, state.blocks));
        refreshChips(editor, monaco);
    }

    function scheduleRefresh(editor, monaco = window.globalMonaco) {
        if (!editor) return;
        const state = stateFor(editor);
        if (state.refreshTimer != null) clearTimeout(state.refreshTimer);
        state.refreshTimer = setTimeout(() => {
            state.refreshTimer = null;
            refresh(editor, monaco);
        }, REFRESH_DEBOUNCE_MS);
    }

    // -- folding ----------------------------------------------------------------

    let foldingRegistered = false;

    /**
     * Register one folding-range provider per language. `foldingStrategy` already defaults to
     * "auto", so nothing else has to be turned on, and kind Comment is what makes the built-in
     * `editor.foldAllBlockComments` command apply to these.
     */
    function registerFolding(monaco) {
        if (foldingRegistered || !monaco?.languages?.registerFoldingRangeProvider) return;
        foldingRegistered = true;
        const commentKind = monaco.languages.FoldingRangeKind?.Comment;
        for (const language of LANGUAGES) {
            monaco.languages.registerFoldingRangeProvider(language, {
                provideFoldingRanges(model) {
                    if (!supported(model)) return [];
                    return blocksFor(model, monaco)
                        .filter((block) => block.endLine - block.startLine + 1 >= MIN_FOLD_LINES)
                        .map((block) => ({
                            start: block.startLine,
                            end: block.endLine,
                            kind: commentKind
                        }));
                }
            });
        }
    }

    // -- actions ----------------------------------------------------------------

    function runBuiltin(editor, id) {
        const action = editor.getAction && editor.getAction(id);
        if (action) action.run();
        else console.debug("[comment-blocks] no such editor action", id);
    }

    function setVisible(editor, value, monaco = window.globalMonaco) {
        const state = stateFor(editor);
        state.visible = !!value;
        storeVisible(state.visible);
        refresh(editor, monaco);
    }

    function toggleVisible(editor, monaco = window.globalMonaco) {
        setVisible(editor, !stateFor(editor).visible, monaco);
    }

    function installActions(editor, monaco) {
        if (!editor || editor._afieldCommentBlocksActionsInstalled || typeof editor.addAction !== "function") return;
        editor._afieldCommentBlocksActionsInstalled = true;
        editor.addAction({
            id: "afield.foldAllCommentBlocks",
            label: "Fold All Comment Blocks",
            run: () => runBuiltin(editor, "editor.foldAllBlockComments")
        });
        editor.addAction({
            id: "afield.unfoldAllCommentBlocks",
            label: "Unfold All Comment Blocks",
            run: () => runBuiltin(editor, "editor.unfoldAll")
        });
        editor.addAction({
            id: "afield.toggleCommentBlockPanels",
            label: "Toggle Comment Block Panels",
            run: () => toggleVisible(editor, monaco)
        });
    }

    // -- install ----------------------------------------------------------------

    /**
     * Re-detect when the model's tokens change, which is how the lazily-loaded Monarch grammar
     * announces itself. Without this the regex fallback in tokensFor() would be the last word for
     * the life of the editor.
     */
    function followTokens(editor, monaco) {
        const state = stateFor(editor);
        state.tokenListener?.dispose?.();
        state.tokenListener = null;
        const model = editor.getModel && editor.getModel();
        if (model?.onDidChangeTokens) {
            state.tokenListener = model.onDidChangeTokens(() => scheduleRefresh(editor, monaco));
        }
    }

    function install(editor, monaco = window.globalMonaco) {
        if (!editor || editor._afieldCommentBlocksInstalled) return;
        editor._afieldCommentBlocksInstalled = true;
        ensureStyles();
        stateFor(editor);
        registerFolding(monaco);
        installActions(editor, monaco);

        if (editor.onDidChangeModelContent) editor.onDidChangeModelContent(() => scheduleRefresh(editor, monaco));
        if (editor.onDidChangeModel) {
            editor.onDidChangeModel(() => {
                followTokens(editor, monaco);
                scheduleRefresh(editor, monaco);
            });
        }
        if (editor.onDidChangeModelLanguage) editor.onDidChangeModelLanguage(() => scheduleRefresh(editor, monaco));
        // Folding changes what is hidden, which is the only thing the summary chips depend on.
        // Deferred by a tick: the event fires while the fold is still being applied, and isFolded()
        // reads getTopForLineNumber, which has not been re-laid-out yet -- so an immediate refresh
        // decides every block is unfolded and emits no chip at all, with nothing scheduled to try
        // again until the next keystroke.
        if (editor.onDidChangeHiddenAreas) {
            editor.onDidChangeHiddenAreas(() => setTimeout(() => refreshChips(editor, monaco), 0));
        }

        followTokens(editor, monaco);
        setTimeout(() => refresh(editor, monaco), 0);
    }

    return {
        install,
        refresh,
        scheduleRefresh,
        setVisible,
        toggleVisible,
        // Pure, and exported on purpose: the rendered-markdown view zone is built on top of these.
        detectBlocks,
        classifyRuns,
        columnStops,
        runKindAt
    };
})();

window.CommentBlocks = CommentBlocks;
if (window.globalEditor) {
    setTimeout(() => CommentBlocks.install(window.globalEditor, window.globalMonaco), 0);
}
