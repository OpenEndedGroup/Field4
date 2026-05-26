define("bithero/nano", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
    "use strict";

    var monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
    var languageId = "nano";

    monaco.languages.register({
        id: languageId,
        extensions: [".nano", ".nasm"],
        aliases: ["Nano", "nano", "NanoASM"]
    });

    monaco.languages.setLanguageConfiguration(languageId, {
        comments: {
            lineComment: ";"
        },
        brackets: [],
        autoClosingPairs: [],
        surroundingPairs: []
    });

    var mnemonics = [
        "nop", "hlt", "ret",
        "inc", "dec", "not", "neg", "clr", "out", "rng",
        "mov", "add", "sub", "mul", "and", "or", "xor", "cmp", "ldr", "str", "div",
        "ldi", "lda", "sta", "addi", "ldrr", "strr", "shl", "shr",
        "jmp", "jz", "jnz", "jgt", "jlt", "jge", "jle", "jc", "jnc",
        "call", "jmpa", "calla",
        ".data"
    ];

    var registers = ["r0", "r1", "r2", "r3"];

    monaco.languages.setMonarchTokensProvider(languageId, {
        tokenPostfix: ".nano",
        ignoreCase: true,
        mnemonics: mnemonics,
        registers: registers,
        tokenizer: {
            root: [
                // Comments
                [/;.*$/, "comment"],

                // Labels (identifier followed by colon)
                [/[A-Za-z_]\w*(?=\s*:)/, "type.identifier"],
                [/:/, "delimiter"],

                // Directives
                [/\.[a-zA-Z]+/, "keyword.control"],

                // Numbers: hex, binary, decimal
                [/0x[0-9a-fA-F]+/, "number.hex"],
                [/0b[01]+/, "number.binary"],
                [/-?[0-9]+/, "number"],

                // Registers
                [/\br[0-3]\b/, "variable.name"],

                // Mnemonics and identifiers
                [/[A-Za-z_]\w*/, {
                    cases: {
                        "@mnemonics": "keyword",
                        "@default": "identifier"
                    }
                }],

                // Commas and whitespace
                [/,/, "delimiter"],
                [/\s+/, "white"]
            ]
        }
    });
});
