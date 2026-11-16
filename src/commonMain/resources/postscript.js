define("bithero/postscript", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
    "use strict";

    // PostScript as a PostScript box (afield.lang.postscript.PostScriptExecution) holds it: `%` comments,
    // `/literal` names, `(strings)`, numbers, `{ }` procedures and `[ ]` arrays, and the operators the
    // interpreter defines. No `@word` inside a rule regex -- Monarch reads that as an attribute
    // reference and throws while compiling.
    var monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
    var languageId = "postscript";

    monaco.languages.register({
        id: languageId,
        extensions: [".ps", ".eps"],
        aliases: ["PostScript", "postscript"]
    });

    monaco.languages.setLanguageConfiguration(languageId, {
        comments: { lineComment: "%" },
        brackets: [["{", "}"], ["[", "]"], ["(", ")"]],
        autoClosingPairs: [
            { open: "{", close: "}" }, { open: "[", close: "]" }, { open: "(", close: ")" }
        ],
        surroundingPairs: [
            { open: "{", close: "}" }, { open: "[", close: "]" }, { open: "(", close: ")" }
        ]
    });

    var control = ["if", "ifelse", "for", "repeat", "loop", "exit", "forall", "exec", "def", "begin", "end", "bind"];
    var graphics = [
        "newpath", "moveto", "rmoveto", "lineto", "rlineto", "curveto", "rcurveto", "arc", "arcn", "closepath",
        "stroke", "fill", "eofill", "gsave", "grestore", "translate", "rotate", "scale", "setlinewidth",
        "setgray", "setrgbcolor", "sethsbcolor", "showpage", "currentpoint", "initmatrix"
    ];

    monaco.languages.setMonarchTokensProvider(languageId, {
        tokenPostfix: ".ps",
        control: control,
        graphics: graphics,
        tokenizer: {
            root: [
                [/%.*$/, "comment"],
                [/\(/, { token: "string", next: "string" }],
                [/\/[^\s\[\]{}()\/%<>]+/, "variable.name"],
                [/[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?(?![^\s\[\]{}()\/%<>])/, "number"],
                [/[{}\[\]]/, "delimiter.bracket"],
                [/[^\s\[\]{}()\/%<>]+/, {
                    cases: {
                        "@control": "keyword.control",
                        "@graphics": "keyword",
                        "@default": "identifier"
                    }
                }],
                [/\s+/, "white"]
            ],
            string: [
                [/\\./, "string.escape"],
                [/\(/, { token: "string", next: "@push" }],
                [/\)/, { token: "string", next: "@pop" }],
                [/[^\\()]+/, "string"]
            ]
        }
    });
});
