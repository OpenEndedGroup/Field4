// Metal Shading Language for Field's `msl` box property — the shader a visionOS Live Metal head
// compiles. Both entry points (`field_vertex` and `field_fragment`) live in one property, because
// Metal compiles them from one translation unit; that is why this is not the GLSL `fragment`/`vertex`
// pair.
//
// Highlighting is a Monarch grammar in the same shape as slang.js. Completion is deliberately local:
// it knows the Field ABI (the three structs the head prepends, and the `u_NAME` uniform defines it
// generates), so it can answer without a round trip. There are no lexical diagnostics — the real
// diagnostics come from the device's own Metal compiler and arrive back through
// `visionmetal/status`, already carrying the author's line numbers.
define("bithero/msl", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
    const monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
    const languageId = "msl";

    const keywords = [
        "if", "else", "for", "while", "do", "switch", "case", "default",
        "break", "continue", "return", "discard_fragment",
        "struct", "class", "union", "enum", "namespace", "typedef", "using", "template", "typename",
        "public", "private", "protected", "static", "extern", "inline", "constexpr", "const", "volatile",
        "auto", "sizeof", "nullptr", "operator", "this", "new", "delete",
        // address spaces — the qualifier is load-bearing in MSL, not decoration
        "device", "constant", "thread", "threadgroup", "threadgroup_imageblock", "ray_data", "object_data",
        // function qualifiers
        "vertex", "fragment", "kernel", "visible", "intersection", "mesh", "object",
        "as_type"
    ];

    const types = [
        "void", "bool", "char", "short", "int", "long", "uchar", "ushort", "uint", "ulong",
        "half", "float", "size_t", "ptrdiff_t",
        "bool2", "bool3", "bool4", "char2", "char3", "char4", "uchar2", "uchar3", "uchar4",
        "short2", "short3", "short4", "ushort2", "ushort3", "ushort4",
        "int2", "int3", "int4", "uint2", "uint3", "uint4",
        "long2", "long3", "long4", "ulong2", "ulong3", "ulong4",
        "half2", "half3", "half4", "float2", "float3", "float4",
        "half2x2", "half2x3", "half2x4", "half3x2", "half3x3", "half3x4", "half4x2", "half4x3", "half4x4",
        "float2x2", "float2x3", "float2x4", "float3x2", "float3x3", "float3x4",
        "float4x2", "float4x3", "float4x4",
        "packed_float2", "packed_float3", "packed_float4", "packed_half2", "packed_half3", "packed_half4",
        "texture1d", "texture2d", "texture3d", "texturecube", "texture2d_array", "texturecube_array",
        "texture2d_ms", "depth2d", "depthcube", "depth2d_array", "depthcube_array",
        "sampler", "atomic_int", "atomic_uint", "atomic_bool",
        "array", "vec", "matrix", "metal",
        // the ABI structs the head prepends to every Field shader
        "FieldVertex", "FieldFrame", "FieldVarying"
    ];

    const builtins = [
        "fieldTime", "fieldDeltaTime", "fieldPredictedDisplayTime", "fieldFrameIndex", "fieldUniform",
        "field_vertex", "field_fragment",
        "abs", "acos", "asin", "atan", "atan2", "ceil", "clamp", "cos", "cosh", "cross", "degrees",
        "determinant", "distance", "dot", "exp", "exp2", "faceforward", "floor", "fma", "fmax", "fmin",
        "fmod", "fract", "inverse", "length", "length_squared", "log", "log2", "max", "min", "mix",
        "modf", "normalize", "pow", "powr", "radians", "reflect", "refract", "rint", "round", "rsqrt",
        "saturate", "sign", "sin", "sincos", "sinh", "smoothstep", "sqrt", "step", "tan", "tanh", "trunc",
        "dfdx", "dfdy", "fwidth", "is_null_texture", "sample", "read", "write", "gather",
        "threadgroup_barrier", "simd_sum", "popcount", "select", "unpack_unorm4x8_to_float"
    ];

    monaco.languages.register({
        id: languageId,
        extensions: [".metal", ".msl"],
        aliases: ["Metal", "MSL", "msl", "metal"],
        mimetypes: ["text/x-metal"]
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
        tokenPostfix: ".msl",
        defaultToken: "",
        keywords,
        types,
        builtins,
        operators: [
            "=", ">", "<", "!", "~", "?", ":", "==", "<=", ">=", "!=", "&&", "||",
            "++", "--", "+", "-", "*", "/", "&", "|", "^", "%", "<<", ">>",
            "+=", "-=", "*=", "/=", "&=", "|=", "^=", "%=", "<<=", ">>=", "::", "->"
        ],
        symbols: /[=><!~?:&|+\-*\/\^%]+/,
        escapes: /\\(?:[abfnrtv\\'"0]|x[0-9A-Fa-f]{2}|u[0-9A-Fa-f]{4})/,
        tokenizer: {
            root: [
                [/[ \t\r\n]+/, ""],
                // [[position]], [[buffer(1)]], [[vertex_id]] — MSL's attribute syntax
                [/\[\[[^\]]*\]\]/, "annotation"],
                [/\/\/.*$/, "comment"],
                [/\/\*/, "comment", "@comment"],
                // The head generates `#define u_NAME frame.user[i]` and a `#line` marker; both are
                // preprocessor lines the author also writes by hand.
                [/#\s*[A-Za-z_][\w]*/, "keyword"],
                [/\bu_[A-Za-z_]\w*\b/, "variable.predefined"],
                [/[{}()\[\]]/, "@brackets"],
                [/@symbols/, {
                    cases: {
                        "@operators": "operator",
                        "@default": ""
                    }
                }],
                [/\b(?:true|false|nullptr|NAN|INFINITY)\b/, "keyword"],
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

    // ── The Field ABI, mirrored from LiveMetalShaderABI.preamble in LiveMetalRenderer.swift ──

    const abiMembers = {
        FieldVertex: [
            ["position", "float4", "Object-space position; .w is 1."],
            ["normal", "float4", "Surface normal; .w is 0."],
            ["color", "float4", "Per-vertex RGBA from the geometry builder's `color(...)`."],
            ["uv", "float4", ".xy is the texture coordinate; .zw are the builder's two free channels."]
        ],
        FieldFrame: [
            ["viewProjection", "float4x4[2]", "One per eye. Index it with the [[amplification_id]]."],
            ["modelMatrix", "float4x4", "World transform for the program."],
            ["timing", "float4", "x=time, y=deltaTime, z=predictedDisplayTime, w=frameIndex."],
            ["user", "float4[16]", "The declared uniform slots. Prefer the generated u_NAME defines."]
        ],
        FieldVarying: [
            ["position", "float4", "Clip-space position — [[position]]."],
            ["worldPosition", "float3", "World-space position, for lighting."],
            ["normal", "float3", "Interpolated normal."],
            ["color", "float4", "Interpolated vertex colour."],
            ["uv", "float2", "Interpolated texture coordinate."]
        ]
    };

    const abiHelpers = [
        ["fieldTime", "float fieldTime(constant FieldFrame &frame)", "Seconds since this program started."],
        ["fieldDeltaTime", "float fieldDeltaTime(constant FieldFrame &frame)", "Seconds since the previous frame."],
        ["fieldPredictedDisplayTime", "float fieldPredictedDisplayTime(constant FieldFrame &frame)",
            "Predicted presentation time of this frame."],
        ["fieldFrameIndex", "float fieldFrameIndex(constant FieldFrame &frame)", "Monotonic compositor frame index."],
        ["fieldUniform", "float4 fieldUniform(constant FieldFrame &frame, uint index)",
            "Uniform slot by index. Prefer the generated `u_NAME` defines."]
    ];

    const entryPointSnippets = [
        {
            label: "field_vertex",
            detail: "vertex entry point",
            documentation: "The vertex function every Field shader must define. Vertices arrive at buffer 0, the frame at buffer 1.",
            insertText: [
                "vertex FieldVarying field_vertex(",
                "    uint vertexID [[vertex_id]],",
                "    ushort viewID [[amplification_id]],",
                "    const device FieldVertex *vertices [[buffer(0)]],",
                "    constant FieldFrame &frame [[buffer(1)]])",
                "{",
                "    FieldVertex v = vertices[vertexID];",
                "    float4 world = frame.modelMatrix * v.position;",
                "    FieldVarying out;",
                "    out.position = frame.viewProjection[min(uint(viewID), 1u)] * world;",
                "    out.worldPosition = world.xyz;",
                "    out.normal = normalize((frame.modelMatrix * float4(v.normal.xyz, 0.0)).xyz);",
                "    out.color = v.color;",
                "    out.uv = v.uv.xy;",
                "    return out;",
                "}",
                "$0"
            ].join("\n")
        },
        {
            label: "field_fragment",
            detail: "fragment entry point",
            documentation: "The fragment function every Field shader must define.",
            insertText: [
                "fragment half4 field_fragment(",
                "    FieldVarying in [[stage_in]],",
                "    constant FieldFrame &frame [[buffer(1)]])",
                "{",
                "    float3 light = normalize(float3(-0.4, 0.8, 0.5));",
                "    float diffuse = 0.2 + 0.8 * max(dot(normalize(in.normal), light), 0.0);",
                "    return half4(half3(in.color.rgb * diffuse), half(in.color.a));",
                "}",
                "$0"
            ].join("\n")
        }
    ];

    const attributeSnippets = [
        ["[[position]]", "Clip-space position output of the vertex stage."],
        ["[[stage_in]]", "Interpolated per-fragment input."],
        ["[[vertex_id]]", "Index of the vertex being shaded."],
        ["[[instance_id]]", "Index of the instance being shaded."],
        ["[[amplification_id]]", "Which eye — index viewProjection with this on visionOS."],
        ["[[buffer(0)]]", "Vertices are bound at buffer 0."],
        ["[[buffer(1)]]", "FieldFrame is bound at buffer 1."],
        ["[[point_size]]", "Point size output."],
        ["[[flat]]", "Do not interpolate this varying."]
    ];

    // The declared uniform slot order lives on the Kotlin side of the same box — in the
    // `// {{ visionmetal uniforms=… }}` marker. Read it from the box the editor has selected so
    // `u_` completes the names that will actually exist, in the right slots.
    const declaredUniformNames = () => {
        try {
            const selected = window.currentSelectedCode;
            if (!selected || !selected.length) return [];
            const code = selected[0] && selected[0].code;
            if (typeof code !== "string") return [];
            const marker = code.match(/^\s*\/\/\s*\{\{\s*(?:visionmetal|metal|vision-metal)\b(.*?)\}\}/mi);
            if (marker) {
                const option = marker[1].match(/\buniforms\s*=\s*([^\s}]+)/i);
                if (option) {
                    return option[1].split(",").map((name) => name.trim()).filter((name) => name.length > 0);
                }
            }
            // No explicit declaration: fall back to the same inference the server does, so the two
            // agree about what the shader will see.
            const inferred = [];
            const writes = /\buniform\s*\(\s*"((?:\\.|[^"\\])*)"/g;
            let hit;
            while ((hit = writes.exec(code)) !== null) {
                if (inferred.indexOf(hit[1]) < 0) inferred.push(hit[1]);
            }
            return inferred;
        } catch (ignored) {
            return [];
        }
    };

    /** The struct type of `identifier`, if the source declares it as one of the ABI structs. */
    const abiTypeOf = (text, identifier) => {
        const escaped = identifier.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
        for (const type of Object.keys(abiMembers)) {
            const declaration = new RegExp("\\b" + type + "\\b[\\s&*]*" + escaped + "\\b");
            if (declaration.test(text)) return type;
        }
        // `FieldVarying out; out.position = …` is the overwhelmingly common shape, and so is naming
        // the fragment input `in`.
        if (identifier === "in") return "FieldVarying";
        return null;
    };

    monaco.languages.registerCompletionItemProvider(languageId, {
        triggerCharacters: [".", "_", "["],
        provideCompletionItems(model, position) {
            const kinds = monaco.languages.CompletionItemKind;
            const line = model.getValueInRange({
                startLineNumber: position.lineNumber,
                startColumn: 1,
                endLineNumber: position.lineNumber,
                endColumn: position.column
            });

            // Member access: `frame.`, `v.`, `in.` — answer with that struct's real members only.
            const memberAccess = line.match(/([A-Za-z_]\w*)\s*\.\s*(\w*)$/);
            if (memberAccess) {
                const type = abiTypeOf(model.getValue(), memberAccess[1]);
                if (type) {
                    const typed = memberAccess[2] || "";
                    const range = new monaco.Range(
                        position.lineNumber, position.column - typed.length,
                        position.lineNumber, position.column
                    );
                    return {
                        suggestions: abiMembers[type]
                            .filter(([name]) => name.indexOf(typed) === 0)
                            .map(([name, memberType, doc], index) => ({
                                label: name,
                                kind: kinds.Field,
                                insertText: name,
                                range,
                                detail: memberType,
                                sortText: "0" + String(index).padStart(3, "0"),
                                documentation: { value: "`" + memberType + " " + type + "." + name + "`\n\n" + doc }
                            }))
                    };
                }
            }

            const word = model.getWordUntilPosition(position);
            const range = new monaco.Range(
                position.lineNumber, word.startColumn, position.lineNumber, word.endColumn
            );
            const suggestions = [];

            // Declared uniforms first — they are the names this particular box actually has.
            declaredUniformNames().forEach((name, slot) => {
                suggestions.push({
                    label: "u_" + name,
                    kind: kinds.Variable,
                    insertText: "u_" + name,
                    range,
                    detail: "float4 — uniform slot " + slot,
                    sortText: "000" + String(slot).padStart(3, "0"),
                    documentation: {
                        value: "`#define u_" + name + " frame.user[" + slot + "]`\n\n" +
                            "Declared by this box's `// {{ visionmetal uniforms=… }}` marker and written from " +
                            "Kotlin with `uniform(\"" + name + "\", …)`."
                    }
                });
            });

            entryPointSnippets.forEach((snippet, index) => {
                suggestions.push({
                    label: snippet.label,
                    kind: kinds.Snippet,
                    insertText: snippet.insertText,
                    insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
                    range,
                    detail: snippet.detail,
                    sortText: "001" + index,
                    documentation: { value: snippet.documentation }
                });
            });

            abiHelpers.forEach(([name, signature, doc], index) => {
                suggestions.push({
                    label: name,
                    kind: kinds.Function,
                    insertText: name + "(frame)",
                    range,
                    detail: signature,
                    sortText: "002" + index,
                    documentation: { value: "`" + signature + "`\n\n" + doc }
                });
            });

            Object.keys(abiMembers).forEach((type, index) => {
                suggestions.push({
                    label: type,
                    kind: kinds.Struct,
                    insertText: type,
                    range,
                    detail: "Field ABI struct",
                    sortText: "003" + index,
                    documentation: {
                        value: "```msl\nstruct " + type + " {\n" +
                            abiMembers[type].map(([name, memberType]) => "    " + memberType + " " + name + ";").join("\n") +
                            "\n};\n```"
                    }
                });
            });

            attributeSnippets.forEach(([attribute, doc], index) => {
                suggestions.push({
                    label: attribute,
                    kind: kinds.Property,
                    insertText: attribute,
                    range,
                    detail: "Metal attribute",
                    sortText: "004" + String(index).padStart(2, "0"),
                    documentation: { value: doc }
                });
            });

            types.forEach((type) => {
                suggestions.push({
                    label: type, kind: kinds.TypeParameter, insertText: type, range,
                    detail: "MSL type", sortText: "005" + type
                });
            });
            keywords.forEach((keyword) => {
                suggestions.push({
                    label: keyword, kind: kinds.Keyword, insertText: keyword, range,
                    detail: "MSL keyword", sortText: "006" + keyword
                });
            });
            builtins.forEach((builtin) => {
                if (abiHelpers.some(([name]) => name === builtin)) return;
                suggestions.push({
                    label: builtin, kind: kinds.Function, insertText: builtin, range,
                    detail: "MSL builtin", sortText: "007" + builtin
                });
            });

            return { suggestions };
        }
    });

    return {
        languageId,
        abiMembers,
        abiTypeOf,
        declaredUniformNames
    };
});
