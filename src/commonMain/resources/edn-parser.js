/**
 * Simple EDN (Extensible Data Notation) parser for Field4 offline viewer
 * Supports the subset of EDN used in Field metadata files:
 * - Maps: {:key value}
 * - Vectors: [1 2 3]
 * - Keywords: :keyword
 * - Strings: "string"
 * - Numbers: 123, 45.6
 * - Booleans: true, false
 * - nil
 */

class EdnParser {
    constructor(text) {
        this.text = text;
        this.pos = 0;
    }

    parse() {
        this.skipWhitespaceAndComments();
        return this.parseValue();
    }

    skipWhitespaceAndComments() {
        while (this.pos < this.text.length) {
            const char = this.text[this.pos];
            if (char === ' ' || char === '\t' || char === '\n' || char === '\r' || char === ',') {
                this.pos++;
            } else if (char === ';') {
                // Skip comment to end of line
                while (this.pos < this.text.length && this.text[this.pos] !== '\n') {
                    this.pos++;
                }
            } else {
                break;
            }
        }
    }

    peek() {
        return this.text[this.pos];
    }

    advance() {
        return this.text[this.pos++];
    }

    parseValue() {
        this.skipWhitespaceAndComments();
        const char = this.peek();

        if (char === '{') {
            return this.parseMap();
        } else if (char === '[') {
            return this.parseVector();
        } else if (char === ':') {
            return this.parseKeyword();
        } else if (char === '"') {
            return this.parseString();
        } else if (char === 't' || char === 'f') {
            return this.parseBoolean();
        } else if (char === 'n') {
            return this.parseNil();
        } else if (char === '-' || (char >= '0' && char <= '9')) {
            return this.parseNumber();
        } else {
            throw new Error(`Unexpected character at position ${this.pos}: ${char}`);
        }
    }

    parseMap() {
        const map = {};
        this.advance(); // skip '{'
        this.skipWhitespaceAndComments();

        while (this.peek() !== '}') {
            const key = this.parseValue();
            this.skipWhitespaceAndComments();
            const value = this.parseValue();
            this.skipWhitespaceAndComments();

            // Convert keyword to string for map key
            const keyStr = typeof key === 'string' && key.startsWith(':') ? key.substring(1) : key;
            map[keyStr] = value;
        }

        this.advance(); // skip '}'
        return map;
    }

    parseVector() {
        const vector = [];
        this.advance(); // skip '['
        this.skipWhitespaceAndComments();

        while (this.peek() !== ']') {
            vector.push(this.parseValue());
            this.skipWhitespaceAndComments();
        }

        this.advance(); // skip ']'
        return vector;
    }

    parseKeyword() {
        this.advance(); // skip ':'
        let keyword = ':';
        while (this.pos < this.text.length) {
            const char = this.peek();
            if (char === ' ' || char === '\t' || char === '\n' || char === '\r' ||
                char === '}' || char === ']' || char === ',' || char === ';') {
                break;
            }
            keyword += this.advance();
        }
        return keyword;
    }

    parseString() {
        this.advance(); // skip opening '"'
        let string = '';
        while (this.pos < this.text.length) {
            const char = this.advance();
            if (char === '"') {
                break;
            } else if (char === '\\') {
                const nextChar = this.advance();
                switch (nextChar) {
                    case 'n': string += '\n'; break;
                    case 't': string += '\t'; break;
                    case 'r': string += '\r'; break;
                    case '\\': string += '\\'; break;
                    case '"': string += '"'; break;
                    default: string += nextChar;
                }
            } else {
                string += char;
            }
        }
        return string;
    }

    parseNumber() {
        let numStr = '';
        while (this.pos < this.text.length) {
            const char = this.peek();
            if ((char >= '0' && char <= '9') || char === '.' || char === '-' || char === 'e' || char === 'E') {
                numStr += this.advance();
            } else {
                break;
            }
        }
        return parseFloat(numStr);
    }

    parseBoolean() {
        if (this.text.substring(this.pos, this.pos + 4) === 'true') {
            this.pos += 4;
            return true;
        } else if (this.text.substring(this.pos, this.pos + 5) === 'false') {
            this.pos += 5;
            return false;
        } else {
            throw new Error(`Expected boolean at position ${this.pos}`);
        }
    }

    parseNil() {
        if (this.text.substring(this.pos, this.pos + 3) === 'nil') {
            this.pos += 3;
            return null;
        } else {
            throw new Error(`Expected nil at position ${this.pos}`);
        }
    }
}

function parseEdn(text) {
    const parser = new EdnParser(text);
    return parser.parse();
}

// Export for use in other scripts
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { parseEdn, EdnParser };
}
