define("bithero/forth", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
    "use strict";

    var monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
    var languageId = "forth";

    monaco.languages.register({
        id: languageId,
        extensions: [".forth", ".fth", ".4th"],
        aliases: ["Forth", "forth"]
    });

    monaco.languages.setLanguageConfiguration(languageId, {
        comments: {
            lineComment: "\\",
            blockComment: ["(", ")"]
        },
        brackets: [
            ["{", "}"],
            ["[", "]"],
            ["(", ")"]
        ],
        autoClosingPairs: [
            { open: "{", close: "}" },
            { open: "[", close: "]" },
            { open: "(", close: ")" },
            { open: "\"", close: "\"", notIn: ["comment"] }
        ],
        surroundingPairs: [
            { open: "{", close: "}" },
            { open: "[", close: "]" },
            { open: "(", close: ")" },
            { open: "\"", close: "\"" }
        ]
    });

    var keywords = [
        ":", ";", "if", "else", "then", "begin", "until", "while", "repeat", "do", "loop", "+loop", "leave", "i", "j",
        "variable", "constant", "dup", "drop", "swap", "over", "rot", "-rot", "nip", "tuck", "2dup", "2drop", "2swap",
        "depth", "clear", "?dup", "pick", "roll",
        "+", "-", "*", "/", "mod", "/mod", "1+", "1-", "2+", "2-", "2*", "2/", "negate", "abs", "max", "min", "**",
        "sqrt", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "exp", "ln", "log", "floor", "ceil", "round",
        "pi", "e",
        "=", "<>", "<", ">", "<=", ">=", "0=", "0<", "0>",
        "and", "or", "xor", "invert", "not", "true", "false",
        "!", "@", "+!",
        ".", ".s", "cr", "space", "spaces", "emit", "type", "print", "println",
        "display", "display-channel", "frame",
        "noop", "between", "clamp", "lerp", "random", "random-range", "int", "float", "str", "str+",
        "class-for", "class-matches", "class-help", "@?", "@?!", "new", "newInstance",
        "vec2", "vec2@x", "vec2@y", "vec2+", "vec2-", "vec2*", "vec2len", "vec2norm", "vec2dot",
        "vec3", "vec3@x", "vec3@y", "vec3@z", "vec3+", "vec3-", "vec3*", "vec3len", "vec3norm", "vec3dot", "vec3cross",
        "vec4", "vec4@x", "vec4@y", "vec4@z", "vec4@w", "vec4+", "vec4*", "vec4len",
        "fline", "moveto", "moveto3", "lineto", "lineto3", "cubicto", "cubicto3",
        "fline-close", "fline-filled", "fline-stroked", "fline-color", "fline-thickness",
        "fline-moveto-v2", "fline-lineto-v2", "fline-moveto-v3", "fline-lineto-v3", "fline-node-color"
    ];

    monaco.languages.setMonarchTokensProvider(languageId, {
        tokenPostfix: ".forth",
        ignoreCase: true,
        keywords: keywords,
        tokenizer: {
            root: [
                [/\\.*$/, "comment"],
                [/\(/, "comment", "@parenComment"],

                [/s\"/, "string.quote", "@string"],
                [/\.\"/, "string.quote", "@string"],

                [/[{}\[\]]/, "@brackets"],
                [/:|;/, "delimiter"],

                [/[+\-]?(?:0x[0-9a-fA-F]+|\d+(?:\.\d+)?)/, "number"],

                [/[^\s]+/, {
                    cases: {
                        "@keywords": "keyword",
                        "@default": "identifier"
                    }
                }]
            ],

            parenComment: [
                [/[^\)]+/, "comment"],
                [/\)/, "comment", "@pop"]
            ],

            string: [
                [/[^"\\]+/, "string"],
                [/\\./, "string.escape"],
                [/"/, "string.quote", "@pop"]
            ]
        }
    });
});
