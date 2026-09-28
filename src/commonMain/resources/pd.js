define("bithero/pd", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
    "use strict";

    // Pure Data patch text, as a Pd box (afield.pd.PdExecution) holds it: `#N`/`#X` statements,
    // message lines that are sent one at a time, and `//` comments. No `@word` inside a rule
    // regex -- Monarch reads that as an attribute reference and throws while compiling.
    var monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
    var languageId = "pd";

    monaco.languages.register({
        id: languageId,
        extensions: [".pd"],
        aliases: ["Pd", "Pure Data", "pd"]
    });

    monaco.languages.setLanguageConfiguration(languageId, {
        comments: { lineComment: "//" },
        brackets: [],
        autoClosingPairs: [],
        surroundingPairs: []
    });

    var statements = [
        "obj", "msg", "floatatom", "symbolatom", "listbox", "text", "connect", "restore",
        "canvas", "array", "coords", "declare", "f", "X", "N", "A"
    ];

    monaco.languages.setMonarchTokensProvider(languageId, {
        tokenPostfix: ".pd",
        statements: statements,
        tokenizer: {
            root: [
                [/\/\/.*$/, "comment"],
                [/#[NXA]\b/, "keyword"],
                [/\\?\$\d+/, "variable.name"],
                [/-?\d+(\.\d+)?(e[+-]?\d+)?\b/, "number"],
                [/[A-Za-z_][\w~.+\-*\/]*/, {
                    cases: {
                        "@statements": "keyword.control",
                        "@default": "identifier"
                    }
                }],
                [/\\?[;,]/, "delimiter"],
                [/\s+/, "white"]
            ]
        }
    });
});
