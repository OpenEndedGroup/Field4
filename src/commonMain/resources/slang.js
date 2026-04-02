define("bithero/slang", ["require"], function (require) {
    const monaco = require("vs/editor/editor.api");
    const languageId = "slang";

    const keywords = [
        "if", "else", "for", "while", "do", "switch", "case", "default",
        "break", "continue", "return", "discard",
        "struct", "class", "interface", "namespace", "typedef", "using",
        "public", "private", "internal", "static", "extern", "inline",
        "const", "volatile", "precise", "uniform", "groupshared",
        "in", "out", "inout", "nointerpolation", "noperspective",
        "row_major", "column_major", "import", "module", "extension"
    ];

    const types = [
        "void", "bool", "bool1", "bool2", "bool3", "bool4",
        "int", "int1", "int2", "int3", "int4",
        "uint", "uint1", "uint2", "uint3", "uint4",
        "float", "float1", "float2", "float3", "float4",
        "double", "double1", "double2", "double3", "double4",
        "half", "half1", "half2", "half3", "half4",
        "float2x2", "float2x3", "float2x4", "float3x2", "float3x3", "float3x4", "float4x2", "float4x3", "float4x4",
        "half2x2", "half2x3", "half2x4", "half3x2", "half3x3", "half3x4", "half4x2", "half4x3", "half4x4",
        "double2x2", "double2x3", "double2x4", "double3x2", "double3x3", "double3x4", "double4x2", "double4x3", "double4x4",
        "Texture1D", "Texture2D", "Texture3D", "TextureCube", "Texture2DArray", "TextureCubeArray",
        "RWTexture1D", "RWTexture2D", "RWTexture3D", "RWTexture2DArray",
        "SamplerState", "SamplerComparisonState",
        "StructuredBuffer", "RWStructuredBuffer", "ByteAddressBuffer", "RWByteAddressBuffer",
        "ConstantBuffer", "RaytracingAccelerationStructure"
    ];

    const builtins = [
        "SV_Position", "SV_Target", "SV_Target0", "SV_Target1", "SV_Target2", "SV_Target3",
        "SV_VertexID", "SV_InstanceID", "SV_PrimitiveID",
        "SV_DispatchThreadID", "SV_GroupThreadID", "SV_GroupID", "SV_GroupIndex",
        "SV_DomainLocation", "SV_OutputControlPointID", "SV_Coverage"
    ];

    monaco.languages.register({
        id: languageId,
        extensions: [".slang"],
        aliases: ["Slang", "slang"]
    });

    monaco.languages.setLanguageConfiguration(languageId, {
        comments: { lineComment: "//", blockComment: ["/*", "*/"] },
        brackets: [["{", "}"], ["[", "]"], ["(", ")"]],
        autoClosingPairs: [
            { open: "{", close: "}" },
            { open: "[", close: "]" },
            { open: "(", close: ")" },
            { open: "\"", close: "\"", notIn: ["string"] },
            { open: "'", close: "'", notIn: ["string", "comment"] }
        ],
        surroundingPairs: [
            { open: "{", close: "}" },
            { open: "[", close: "]" },
            { open: "(", close: ")" },
            { open: "\"", close: "\"" },
            { open: "'", close: "'" }
        ]
    });

    monaco.languages.setMonarchTokensProvider(languageId, {
        tokenPostfix: ".slang",
        defaultToken: "",
        keywords,
        types,
        builtins,
        operators: [
            "=", ">", "<", "!", "~", "?", ":", "==", "<=", ">=", "!=", "&&", "||",
            "++", "--", "+", "-", "*", "/", "&", "|", "^", "%", "<<", ">>",
            "+=", "-=", "*=", "/=", "&=", "|=", "^=", "%=", "<<=", ">>="
        ],
        symbols: /[=><!~?:&|+\-*\/\^%]+/,
        escapes: /\\(?:[abfnrtv\\'"0]|x[0-9A-Fa-f]{2}|u[0-9A-Fa-f]{4})/,
        tokenizer: {
            root: [
                [/[ \t\r\n]+/, ""],
                [/\[\[[^\]]*\]\]/, "annotation"],
                [/\[[A-Za-z_][^\]]*\]/, "annotation"],
                [/\/\/.*$/, "comment"],
                [/\/\*/, "comment", "@comment"],
                [/#\s*[A-Za-z_][\w]*/, "keyword"],
                [/[{}()\[\]]/, "@brackets"],
                [/@symbols/, {
                    cases: {
                        "@operators": "operator",
                        "@default": ""
                    }
                }],
                [/\b(?:true|false|null)\b/, "keyword"],
                [/\b[A-Za-z_]\w*\b/, {
                    cases: {
                        "@keywords": "keyword",
                        "@types": "type",
                        "@builtins": "keyword.predefined",
                        "@default": "identifier"
                    }
                }],
                [/\d*\.\d+([eE][\-+]?\d+)?[fFhH]?/, "number.float"],
                [/0[xX][0-9a-fA-F]+[uUlL]*/, "number.hex"],
                [/\d+[uUlL]*/, "number"],
                [/"([^"\\]|\\.)*$/, "string.invalid"],
                [/"/, "string", "@string"],
                [/'[^\\']'/, "string"],
                [/'/, "string.invalid"]
            ],
            comment: [
                [/[^/*]+/, "comment"],
                [/\*\//, "comment", "@pop"],
                [/[\/*]/, "comment"]
            ],
            string: [
                [/[^\\"]+/, "string"],
                [/@escapes/, "string.escape"],
                [/\\./, "string.escape.invalid"],
                [/"/, "string", "@pop"]
            ]
        }
    });

    return {};
});
