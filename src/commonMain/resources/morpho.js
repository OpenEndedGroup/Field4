// Monaco language for MorphoHDL boxes (afield.morpho.MorphoExecution).
//
// MorphoHDL is Python-shaped but it is not Python, and reusing Monaco's Python grammar would be
// actively misleading: `@morpho` is the only decorator there is, `SPLIT`/`CAT`/`LSLICE` are
// language primitives rather than library calls, and the underscore-grouped binary literals that
// truth tables are written in (`0b1001_0110`) are the thing you most want to read correctly.
// Python's grammar highlights none of those as special and would render a LUT as a plain number.
//
// The one Field-specific rule: a `# main …` line is the directive that says which design to build
// and how wide (see MorphoExecution.Directive). Morpho's own tokenizer discards comments entirely,
// so the directive is invisible to the language — which is exactly why it needs to be visible to
// the reader. It is tokenised as a keyword rather than a comment.
//
// Loaded via "bithero/morpho" in bootstrapMonaco.js. The language id has to be the box's
// code_language — "MorphoHDL", set by afield.morpho.morphoTopologyNeedsUpdating — lower-cased,
// because that is the string the editor hands to monaco.editor.setModelLanguage (Main.kt and
// BootstrapMonacoRuntime.kt both call it as `code_language.lowercase()`). An alias will not do:
// setModelLanguage resolves mime types and then ids, never aliases, and silently falls back to
// "plaintext" for an id nobody registered. Registering as "morpho" therefore left every morpho box
// as plaintext — no highlighting, and no fieldSuggest either, since it gates on
// model.getLanguageId(). See dev_docs/small_language_anatomy.md step 5.
define("bithero/morpho", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
    "use strict";

    var monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
    var languageId = "morphohdl";

    monaco.languages.register({
        id: languageId,
        aliases: ["MorphoHDL", "morpho", "Morpho"],
        extensions: [".morpho"]
    });

    monaco.languages.setLanguageConfiguration(languageId, {
        comments: { lineComment: "#" },
        brackets: [["(", ")"], ["[", "]"]],
        autoClosingPairs: [
            { open: "(", close: ")" },
            { open: "[", close: "]" }
        ],
        surroundingPairs: [
            { open: "(", close: ")" },
            { open: "[", close: "]" }
        ],
        indentationRules: {
            increaseIndentPattern: /^\s*(@morpho.*|def\s+\w+\s*\(.*\)\s*:)\s*$/,
            decreaseIndentPattern: /^\s*return\b.*$/
        }
    });

    monaco.languages.setMonarchTokensProvider(languageId, {
        defaultToken: "",
        tokenPostfix: ".morpho",

        keywords: ["def", "return"],

        // Bus algebra. These are the language, not a library: SPLIT is what ends a recursion.
        primitives: ["SPLIT", "CAT", "REPEAT", "LSLICE", "HSLICE", "LUT"],

        constants: ["ZERO", "ONE", "VOID"],

        tokenizer: {
            root: [
                // The Field directive, before the generic comment rule so it wins.
                [/#\s*main\b.*$/, "keyword.directive"],
                [/#.*$/, "comment"],

                // `[@]` rather than `@`: Monarch reads a bare `@name` in a rule's regex as a
                // reference to an attribute on this lexer object and throws at compile time when
                // there isn't one -- and setMonarchTokensProvider compiles eagerly, so the throw
                // escapes the AMD factory, "bithero/morpho" never resolves, and the require in
                // bootstrapMonaco.js that gates initMonaco() hangs forever with no editor at all.
                [/[@]morpho\b/, "keyword.decorator"],
                [/[@]\w+/, "annotation"],

                [/\bfallback\b(?=\s*=)/, "keyword.parameter"],

                // A cell definition: colour the name, not just `def`.
                [/\b(def)(\s+)([a-zA-Z_]\w*)/, ["keyword", "", "entity.name.function"]],

                [
                    /[A-Za-z_]\w*/,
                    {
                        cases: {
                            "@keywords": "keyword",
                            "@primitives": "keyword.operator",
                            "@constants": "constant.language",
                            "@default": "identifier"
                        }
                    }
                ],

                // Truth tables. Underscore grouping is idiomatic here (0b1001_0110) and a grammar
                // that stops at the underscore splits a LUT into two numbers and a name.
                [/0[bB][01_]+/, "number.binary"],
                [/0[xX][0-9a-fA-F_]+/, "number.hex"],
                [/\d[\d_]*/, "number"],

                [/[()\[\]]/, "@brackets"],
                [/[,:=]/, "delimiter"],
                [/\s+/, ""]
            ]
        }
    });
});
