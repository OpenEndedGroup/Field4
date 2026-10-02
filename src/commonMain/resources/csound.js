define("bithero/csound", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
    "use strict";

    // Csound, as a Csound box (afield.csound.CsoundExecution) holds it: a .csd -- <CsOptions>,
    // <CsInstruments>, <CsScore> -- or a bare orchestra.
    //
    // The orchestra colours opcodes, the instr/endin/opcode/endop structure, variables by their rate
    // prefix (i k a S f w, with g for globals), p-fields, strings, comments and the preprocessor's
    // #define / $MACRO. The score is simpler: statement letters, numbers, and the carry and ramp
    // symbols (. + ^+ ^- < > ~ !).
    //
    // The opcode list is not typed here. It is csound-vocabulary.json, generated from libcsound's
    // own csoundNewOpcodeList by `./gradlew generateCsoundVocabulary`; the grammar is installed once
    // without it (every word is then coloured by its rate prefix alone) and again when it arrives.
    // Completion is the server's (CsoundExecution.complete), from the same file.
    //
    // No `@word` inside a rule regex: Monarch reads it as an attribute reference and throws while
    // compiling, which leaves the whole editor without Monaco (see bootstrapMonaco.js).
    var monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
    var languageId = "csound";

    monaco.languages.register({
        id: languageId,
        extensions: [".csd", ".orc", ".sco"],
        aliases: ["Csound", "csound"]
    });

    monaco.languages.setLanguageConfiguration(languageId, {
        comments: { lineComment: ";", blockComment: ["/*", "*/"] },
        brackets: [["(", ")"], ["[", "]"], ["{{", "}}"]],
        autoClosingPairs: [
            { open: "(", close: ")" },
            { open: "[", close: "]" },
            { open: "\"", close: "\"", notIn: ["string", "comment"] }
        ],
        surroundingPairs: [{ open: "(", close: ")" }, { open: "\"", close: "\"" }],
        indentationRules: {
            increaseIndentPattern: /^\s*(instr|opcode|if\b.*\bthen|else|elseif\b.*\bthen|while\b.*\bdo|until\b.*\bdo)\b.*$/,
            decreaseIndentPattern: /^\s*(endin|endop|endif|else|elseif|od)\b.*$/
        }
    });

    var keywords = [
        "instr", "endin", "opcode", "endop", "if", "then", "ithen", "kthen", "elseif", "else", "endif",
        "while", "until", "do", "od", "goto", "igoto", "kgoto", "cggoto", "cigoto", "ckgoto",
        "struct", "void"
    ];
    var header = ["sr", "kr", "ksmps", "nchnls", "nchnls_i", "0dbfs", "A4"];

    var grammar = function (opcodes) {
        return {
            tokenPostfix: ".csound",
            keywords: keywords,
            header: header,
            opcodes: opcodes,
            tokenizer: {
                root: [
                    [/<\/?CsoundSynthesizer>/, "tag"],
                    [/<CsOptions>/, { token: "tag", next: "options" }],
                    [/<CsScore[^>]*>/, { token: "tag", next: "score" }],
                    [/<\/?Cs\w+>/, "tag"],
                    { include: "orchestra" }
                ],
                options: [
                    [/<\/CsOptions>/, { token: "tag", next: "root" }],
                    [/;.*$/, "comment"],
                    [/-[-+]?[\w=.:\/-]+/, "attribute.name"],
                    [/[^<;-]+/, ""]
                ],
                orchestra: [
                    { include: "common" },
                    [/\bp\d+\b/, "variable.parameter"],
                    [/\bg?[ikaSfw][A-Za-z_]\w*(\[\])*/, {
                        cases: {
                            "@keywords": "keyword",
                            "@opcodes": "type",
                            "@default": "variable"
                        }
                    }],
                    [/\b[A-Za-z_]\w*/, {
                        cases: {
                            "@keywords": "keyword",
                            "@header": "type",
                            "@opcodes": "type",
                            "@default": "identifier"
                        }
                    }],
                    [/0dbfs/, "type"],
                    [/[=+\-*\/%^<>!&|?:]+/, "operator"],
                    [/[(),\[\]]/, "delimiter"]
                ],
                score: [
                    [/<\/CsScore>/, { token: "tag", next: "root" }],
                    { include: "common" },
                    [/^\s*[ifteasbvqrmnxyd](?=[\s\d".])/, "keyword"],
                    [/\^[+-]/, "operator"],
                    [/[.+<>~!]/, "operator"],
                    [/[\[\]{}]/, "delimiter"],
                    [/\b[A-Za-z_]\w*/, "identifier"]
                ],
                common: [
                    [/\s+/, "white"],
                    [/;.*$/, "comment"],
                    [/\/\/.*$/, "comment"],
                    [/\/\*/, { token: "comment", next: "blockComment" }],
                    [/^\s*#\s*(define|undef|include|includestr|ifdef|ifndef|else|end)\b/, "keyword.directive"],
                    [/\$\w+\.?/, "variable.predefined"],
                    [/\{\{/, { token: "string", next: "rawString" }],
                    [/"/, { token: "string.quote", next: "string" }],
                    [/-?\d*\.\d+([eE][+-]?\d+)?/, "number.float"],
                    [/-?\d+([eE][+-]?\d+)?/, "number"]
                ],
                blockComment: [
                    [/\*\//, { token: "comment", next: "@pop" }],
                    [/[^*]+/, "comment"],
                    [/\*/, "comment"]
                ],
                rawString: [
                    [/\}\}/, { token: "string", next: "@pop" }],
                    [/[^}]+/, "string"],
                    [/\}/, "string"]
                ],
                string: [
                    [/[^\\"]+/, "string"],
                    [/\\./, "string.escape"],
                    [/"/, { token: "string.quote", next: "@pop" }]
                ]
            }
        };
    };

    monaco.languages.setMonarchTokensProvider(languageId, grammar([]));

    var installed = { opcodes: 0 };
    window.csoundGrammarState = function () { return installed; };
    if (typeof fetch === "function") {
        fetch("/csound-vocabulary.json")
            .then(function (r) { return r && r.ok ? r.json() : null; })
            .then(function (data) {
                if (!data || !data.opcodes) return;
                var names = data.opcodes.map(function (o) { return String(o.name); });
                monaco.languages.setMonarchTokensProvider(languageId, grammar(names));
                installed.opcodes = names.length;
                installed.csoundVersion = data.csoundVersion;
            })
            .catch(function (e) {
                installed.error = String(e && e.message || e);
                console.warn("Csound vocabulary could not be loaded", e);
            });
    }
});
