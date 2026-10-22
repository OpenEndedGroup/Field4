/**
 * EDN (Extensible Data Notation) reader for the Field·4 offline viewer.
 *
 * It reads what Field's own writer (`afield/document/persistence/edn.kt`, driven by
 * `saving.kt`) emits, and is as permissive as that reader:
 *
 * - maps `{k v}`, vectors `[..]`, lists `(..)`, sets `#{..}` (sets and lists become arrays)
 * - strings with `\" \\ \/ \n \r \t \b \f \uXXXX` escapes
 * - numbers (`12`, `-1.5`, `1.0E-5`, `+3`, `7N`, `2.5M`) and the tokens a JVM double prints
 *   as: `NaN`, `Infinity`, `-Infinity`
 * - `true`, `false`, `nil`; keywords `:kw`; any other token is a symbol (kept as its text)
 * - characters `\a`, `\newline`, `\space`, `\tab`, `\uXXXX` (kept as a one-character string)
 * - `;` comments and the `#_` discard form
 *
 * Map keys are strings: a keyword key `:boxes` and a string key `"boxes"` both become `boxes`
 * (Field writes string keys; older and hand-written documents use keywords).
 *
 * Field's tagged values:
 *
 * - `#afield/rect [x y w h]` -> the vector
 * - `#afield/box "uid"`      -> the uid string
 * - `#afield/file-inline {"path" p "inline" text}` -> `text` (the sidecar's contents, inlined)
 * - `#afield/file "p"`       -> an `AfieldFileRef` (`{ afieldFile: p }`): a sidecar too large to
 *   inline (over 256 KB, or an AI chat history), whose text lives only next to `metadata.edn`.
 *   `resolveSidecars(data, baseUrl, fetchText)` fetches those.
 * - `#inst "..."`, `#uuid "..."` -> the string
 * - any other tag -> `{ tag, value }`
 *
 * Every map that held a `#afield/file-inline` / `#afield/file` value records where it came from in
 * a non-enumerable `__sidecars` property, `{ propertyName: { path, inline } }`, so the viewer can
 * list every sidecar property (code, notes, shaders, YAML metadata ...) without a hardcoded list.
 *
 * Tests: `tools/test_offline_edn_parser.mjs` (run by `tools/run_python_tests.py` through
 * `tools/test_offline_viewer_js.py`).
 */

class AfieldFileRef {
    constructor(path) {
        this.afieldFile = path;
    }

    toString() {
        return `#afield/file "${this.afieldFile}"`;
    }
}

const EDN_DISCARD = Symbol('edn-discard');
const EDN_NUMBER = /^[+-]?\d+(\.\d+)?([eE][+-]?\d+)?[NM]?$/;
const EDN_CHAR_NAMES = { newline: '\n', space: ' ', tab: '\t', return: '\r', backspace: '\b', formfeed: '\f' };

class EdnParser {
    constructor(text) {
        this.text = text;
        this.pos = 0;
        this.lastFilePath = null;
    }

    parse() {
        const value = this.parseRealValue();
        this.skipWhitespaceAndComments();
        return value;
    }

    error(message) {
        const before = this.text.slice(0, this.pos);
        const line = before.split('\n').length;
        const column = this.pos - before.lastIndexOf('\n');
        return new Error(`${message} (line ${line}, column ${column})`);
    }

    isWhitespace(char) {
        return char === ' ' || char === '\t' || char === '\n' || char === '\r' || char === ',' || char === '\f';
    }

    isTerminating(char) {
        return char === undefined || this.isWhitespace(char) ||
            char === '(' || char === ')' || char === '[' || char === ']' || char === '{' || char === '}' ||
            char === '"' || char === ';' || char === '#';
    }

    skipWhitespaceAndComments() {
        while (this.pos < this.text.length) {
            const char = this.text[this.pos];
            if (this.isWhitespace(char)) {
                this.pos++;
            } else if (char === ';') {
                while (this.pos < this.text.length && this.text[this.pos] !== '\n') this.pos++;
            } else {
                break;
            }
        }
    }

    peek(offset = 0) {
        return this.text[this.pos + offset];
    }

    advance() {
        return this.text[this.pos++];
    }

    readToken() {
        const start = this.pos;
        while (this.pos < this.text.length && !this.isTerminating(this.text[this.pos])) this.pos++;
        if (start === this.pos) throw this.error(`Unexpected character ${JSON.stringify(this.peek())}`);
        return this.text.slice(start, this.pos);
    }

    /** One value; returns EDN_DISCARD for a `#_` form, which collections drop. */
    parseValue() {
        this.skipWhitespaceAndComments();
        if (this.pos >= this.text.length) throw this.error('Unexpected end of input');
        const char = this.peek();
        switch (char) {
            case '{': return this.parseMap();
            case '[': return this.parseSeq('[', ']');
            case '(': return this.parseSeq('(', ')');
            case '"': return this.parseString();
            case ':': return this.parseKeyword();
            case '\\': return this.parseChar();
            case '#': return this.parseDispatch();
            case ')': case ']': case '}':
                throw this.error(`Unmatched ${char}`);
            default: return this.parseToken();
        }
    }

    /** The next value that is not a `#_` discard. */
    parseRealValue() {
        let value;
        do { value = this.parseValue(); } while (value === EDN_DISCARD);
        return value;
    }

    parseSeq(open, close) {
        const out = [];
        this.advance();
        for (;;) {
            this.skipWhitespaceAndComments();
            if (this.pos >= this.text.length) throw this.error(`Unterminated ${open}`);
            if (this.peek() === close) { this.advance(); return out; }
            const value = this.parseValue();
            if (value !== EDN_DISCARD) out.push(value);
        }
    }

    parseMap() {
        const map = {};
        let sidecars = null;
        this.advance();
        for (;;) {
            this.skipWhitespaceAndComments();
            if (this.pos >= this.text.length) throw this.error('Unterminated map');
            if (this.peek() === '}') { this.advance(); break; }
            const key = this.parseValue();
            if (key === EDN_DISCARD) continue;
            this.skipWhitespaceAndComments();
            if (this.peek() === '}') throw this.error('Map with an odd number of forms');
            const fileTag = this.peekFileTag();
            const value = this.parseRealValue();
            const keyStr = typeof key === 'string' && key.startsWith(':') ? key.substring(1)
                : (typeof key === 'string' ? key : JSON.stringify(key));
            map[keyStr] = value;
            if (fileTag && this.lastFilePath) {
                sidecars = sidecars || {};
                sidecars[keyStr] = { path: this.lastFilePath, inline: fileTag === 'afield/file-inline' };
            }
        }
        if (sidecars) Object.defineProperty(map, '__sidecars', { value: sidecars, enumerable: false });
        return map;
    }

    /** Whether the next form is a `#afield/file...` tag, so the enclosing map can record its path. */
    peekFileTag() {
        this.lastFilePath = null;
        if (this.text.startsWith('#afield/file-inline', this.pos)) return 'afield/file-inline';
        if (this.text.startsWith('#afield/file', this.pos) && this.isTerminating(this.text[this.pos + 12])) return 'afield/file';
        return null;
    }

    parseDispatch() {
        const next = this.peek(1);
        if (next === '{') {
            this.advance();
            return this.parseSeq('{', '}');
        }
        if (next === '_') {
            this.pos += 2;
            this.parseRealValue();
            return EDN_DISCARD;
        }
        this.advance();
        this.skipWhitespaceAndComments();
        const tag = this.readToken();
        const value = this.parseRealValue();
        return this.applyTag(tag, value);
    }

    applyTag(tag, value) {
        switch (tag) {
            case 'afield/file-inline':
                if (value && typeof value === 'object' && !Array.isArray(value)) {
                    if (typeof value.path === 'string') this.lastFilePath = value.path;
                    if (typeof value.inline === 'string') return value.inline;
                    if (typeof value.path === 'string') return new AfieldFileRef(value.path);
                }
                return value;
            case 'afield/file':
                if (typeof value === 'string') {
                    this.lastFilePath = value;
                    return new AfieldFileRef(value);
                }
                return { tag, value };
            case 'afield/rect':
            case 'afield/box':
            case 'inst':
            case 'uuid':
                return value;
            default:
                return { tag, value };
        }
    }

    parseKeyword() {
        this.advance();
        return ':' + this.readToken();
    }

    parseChar() {
        this.advance();
        // The next character, then any token characters (`\newline`, `é`).
        let token = this.advance() || '';
        while (this.pos < this.text.length && !this.isTerminating(this.text[this.pos])) token += this.advance();
        if (token.length === 1) return token;
        if (EDN_CHAR_NAMES[token]) return EDN_CHAR_NAMES[token];
        if (/^u[0-9a-fA-F]{4}$/.test(token)) return String.fromCharCode(parseInt(token.slice(1), 16));
        throw this.error(`Unknown character literal \\${token}`);
    }

    parseString() {
        this.advance();
        let out = '';
        let runStart = this.pos;
        while (this.pos < this.text.length) {
            const char = this.text[this.pos];
            if (char === '"') {
                out += this.text.slice(runStart, this.pos);
                this.pos++;
                return out;
            }
            if (char === '\\') {
                out += this.text.slice(runStart, this.pos);
                this.pos++;
                const esc = this.advance();
                switch (esc) {
                    case 'n': out += '\n'; break;
                    case 't': out += '\t'; break;
                    case 'r': out += '\r'; break;
                    case 'b': out += '\b'; break;
                    case 'f': out += '\f'; break;
                    case 'u': {
                        const hex = this.text.substr(this.pos, 4);
                        if (!/^[0-9a-fA-F]{4}$/.test(hex)) throw this.error('Invalid unicode escape');
                        out += String.fromCharCode(parseInt(hex, 16));
                        this.pos += 4;
                        break;
                    }
                    default: out += esc; // \" \\ \/, and anything else leniently
                }
                runStart = this.pos;
            } else {
                this.pos++;
            }
        }
        throw this.error('Unterminated string');
    }

    parseToken() {
        const token = this.readToken();
        switch (token) {
            case 'nil': return null;
            case 'true': return true;
            case 'false': return false;
            case 'NaN': return NaN;
            case 'Infinity': case '+Infinity': return Infinity;
            case '-Infinity': return -Infinity;
        }
        if (EDN_NUMBER.test(token)) return parseFloat(token.replace(/[NM]$/, ''));
        return token; // a symbol
    }
}

function parseEdn(text) {
    return new EdnParser(text).parse();
}

/**
 * Every sidecar-backed property, as `{ box, key, path, ref }`. With `includeInline`, the
 * `#afield/file-inline` properties too; otherwise only the `#afield/file` references whose text
 * is not in metadata.edn at all.
 */
function findFileRefs(data, includeInline = false) {
    const refs = [];
    for (const box of (data && data.boxes) || []) {
        const props = box.properties || {};
        const sidecars = props.__sidecars || {};
        for (const key of Object.keys(props)) {
            if (props[key] instanceof AfieldFileRef) {
                refs.push({ box, key, path: props[key].afieldFile, ref: true });
            } else if (includeInline && sidecars[key] && sidecars[key].inline) {
                refs.push({ box, key, path: sidecars[key].path, ref: false });
            }
        }
    }
    return refs;
}

/**
 * Reads sidecar files relative to `baseUrl` (the metadata.edn's URL), the way Field loads a
 * document: the loose file wins, and the inline copy is the fallback for a file that went missing.
 * `#afield/file` references are always fetched (their text is nowhere else); inline ones only
 * with `{ includeInline: true }`. A sidecar that cannot be fetched keeps its inline text, or stays
 * an `AfieldFileRef`. Resolves to `{ resolved, refreshed, missing }`: references read, inline
 * copies the loose file replaced with different text, and references that could not be read.
 */
async function resolveSidecars(data, baseUrl, fetchText, options = {}) {
    const result = { resolved: 0, refreshed: 0, missing: 0 };
    const here = typeof location !== 'undefined' ? location.href : 'file:///';
    await Promise.all(findFileRefs(data, !!options.includeInline).map(async ({ box, key, path, ref }) => {
        let text = null;
        try {
            text = await fetchText(new URL(path, new URL(baseUrl, here)).href);
        } catch (_) {
            text = null;
        }
        if (typeof text !== 'string') {
            if (ref) result.missing++;
            return;
        }
        if (ref) {
            result.resolved++;
        } else if (box.properties[key] !== text) {
            result.refreshed++;
        }
        box.properties[key] = text;
    }));
    return result;
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { parseEdn, EdnParser, AfieldFileRef, findFileRefs, resolveSidecars };
}
