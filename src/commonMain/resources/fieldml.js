define("bithero/fieldml", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
    "use strict";

    var monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
    var languageId = "fieldml";

    monaco.languages.register({
        id: languageId,
        extensions: [".fml", ".fieldml"],
        aliases: ["FieldML", "fieldml"]
    });

    monaco.languages.setLanguageConfiguration(languageId, {
        comments: {
            lineComment: "//",
            blockComment: ["(*", "*)"]
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
            { open: "\"", close: "\"", notIn: ["string", "comment"] }
        ],
        surroundingPairs: [
            { open: "{", close: "}" },
            { open: "[", close: "]" },
            { open: "(", close: ")" },
            { open: "\"", close: "\"" }
        ]
    });

    var keywords = [
        "let", "in", "fun", "if", "then", "else", "type", "match", "with", "true", "false"
    ];

    var typeKeywords = [
        "Int", "Float", "Bool", "String", "Unit", "Vec2", "Vec3", "Vec4", "FLine", "Host"
    ];

    monaco.languages.setMonarchTokensProvider(languageId, {
        tokenPostfix: ".fieldml",
        keywords: keywords,
        typeKeywords: typeKeywords,
        tokenizer: {
            root: [
                [/\/\/.*$/, "comment"],
                [/\(\*/, "comment", "@blockComment"],

                [/"([^"\\]|\\.)*$/, "string.invalid"],
                [/"/, "string.quote", "@string"],

                [/\|>|->|==|!=|<=|>=|&&|\|\||\+\+|::|[=+\-*\/<>]/, "operator"],
                [/[{}\[\]()]/, "@brackets"],
                [/[;,.:|]/, "delimiter"],

                [/[0-9]+\.[0-9]+/, "number.float"],
                [/[0-9]+/, "number"],

                [/[A-Z][A-Za-z0-9_']*/, {
                    cases: {
                        "@typeKeywords": "type",
                        "@default": "type.identifier"
                    }
                }],
                [/[a-z_][A-Za-z0-9_']*/, {
                    cases: {
                        "@keywords": "keyword",
                        "@default": "identifier"
                    }
                }]
            ],

            blockComment: [
                [/[^\(*]+/, "comment"],
                [/\(\*/, "comment", "@push"],
                [/\*\)/, "comment", "@pop"],
                [/[\(*]/, "comment"]
            ],

            string: [
                [/[^"\\]+/, "string"],
                [/\\./, "string.escape"],
                [/"/, "string.quote", "@pop"]
            ]
        }
    });
});
