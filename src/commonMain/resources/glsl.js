define("bithero/glsl", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
    const monaco = monacoModule || require("vs/editor/editor.api");
    const languageId = "glsl";

    const directives = [
        "#", "#define", "#undef", "#if", "#ifdef", "#ifndef", "#else", "#elif", "#endif",
        "#error", "#pragma", "#extension", "#version", "#line"
    ];

    const macros = ["__LINE__", "__FILE__", "__VERSION__"];

    const storage = [
        "in", "out", "inout", "uniform", "buffer", "shared", "layout", "attribute", "varying",
        "precision", "highp", "mediump", "lowp", "const", "centroid", "sample", "patch",
        "smooth", "flat", "noperspective", "coherent", "volatile", "restrict", "readonly",
        "writeonly"
    ];

    const keywords = [
        "if", "else", "for", "while", "do", "switch", "case", "default", "break",
        "continue", "return", "discard", "struct", "subroutine", "true", "false"
    ];

    const types = [
        "void", "bool", "int", "uint", "float", "double",
        "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "bvec2", "bvec3", "bvec4",
        "uvec2", "uvec3", "uvec4", "dvec2", "dvec3", "dvec4",
        "mat2", "mat3", "mat4", "mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3",
        "mat3x4", "mat4x2", "mat4x3", "mat4x4",
        "dmat2", "dmat3", "dmat4", "dmat2x2", "dmat2x3", "dmat2x4", "dmat3x2",
        "dmat3x3", "dmat3x4", "dmat4x2", "dmat4x3", "dmat4x4",
        "sampler1D", "texture1D", "image1D", "sampler1DShadow", "sampler1DArray",
        "texture1DArray", "image1DArray", "sampler1DArrayShadow",
        "sampler2D", "texture2D", "image2D", "sampler2DShadow", "sampler2DArray",
        "texture2DArray", "image2DArray", "sampler2DArrayShadow", "sampler2DMS",
        "texture2DMS", "image2DMS", "sampler2DMSArray", "texture2DMSArray",
        "image2DMSArray", "sampler2DRect", "texture2DRect", "image2DRect",
        "sampler2DRectShadow", "sampler3D", "texture3D", "image3D",
        "samplerCube", "textureCube", "imageCube", "samplerCubeShadow", "samplerCubeArray",
        "textureCubeArray", "imageCubeArray", "samplerCubeArrayShadow",
        "samplerBuffer", "textureBuffer", "imageBuffer", "subpassInput", "subpassInputMS",
        "isampler1D", "itexture1D", "iimage1D", "isampler1DArray", "itexture1DArray",
        "iimage1DArray", "isampler2D", "itexture2D", "iimage2D", "isampler2DArray",
        "itexture2DArray", "iimage2DArray", "isampler2DMS", "itexture2DMS",
        "iimage2DMS", "isampler2DMSArray", "itexture2DMSArray", "iimage2DMSArray",
        "isampler2DRect", "itexture2DRect", "iimage2DRect", "isampler3D", "itexture3D",
        "iimage3D", "isamplerCube", "itextureCube", "iimageCube", "isamplerCubeArray",
        "itextureCubeArray", "iimageCubeArray", "isamplerBuffer", "itextureBuffer",
        "iimageBuffer", "isubpassInput", "isubpassInputMS",
        "usampler1D", "utexture1D", "uimage1D", "usampler1DArray", "utexture1DArray",
        "uimage1DArray", "usampler2D", "utexture2D", "uimage2D", "usampler2DArray",
        "utexture2DArray", "uimage2DArray", "usampler2DMS", "utexture2DMS",
        "uimage2DMS", "usampler2DMSArray", "utexture2DMSArray", "uimage2DMSArray",
        "usampler2DRect", "utexture2DRect", "uimage2DRect", "usampler3D", "utexture3D",
        "uimage3D", "usamplerCube", "utextureCube", "uimageCube", "usamplerCubeArray",
        "utextureCubeArray", "uimageCubeArray", "usamplerBuffer", "utextureBuffer",
        "uimageBuffer", "atomic_uint", "usubpassInput", "usubpassInputMS",
        "sampler", "samplerShadow", "accelerationStructureEXT"
    ];

    const builtinVars = [
        "gl_VertexID", "gl_InstanceID", "gl_VertexIndex", "gl_InstanceIndex", "gl_DrawID",
        "gl_BaseVertex", "gl_BaseInstance", "gl_Position", "gl_PointSize", "gl_ClipDistance",
        "gl_CullDistance", "gl_FragCoord", "gl_FrontFacing", "gl_PointCoord", "gl_FragDepth",
        "gl_GlobalInvocationID", "gl_LocalInvocationID", "gl_WorkGroupID", "gl_WorkGroupSize",
        "gl_LocalInvocationIndex", "gl_PrimitiveID", "gl_LaunchIDEXT", "gl_LaunchSizeEXT",
        "gl_WorldRayOriginEXT", "gl_WorldRayDirectionEXT", "gl_ObjectRayOriginEXT",
        "gl_ObjectRayDirectionEXT", "gl_RayTminEXT", "gl_RayTmaxEXT", "gl_InstanceCustomIndexEXT",
        "gl_GeometryIndexEXT", "gl_HitKindEXT"
    ];

    const constants = [
        "gl_MaxVertexAttribs", "gl_MaxVertexUniformVectors", "gl_MaxVertexUniformComponents",
        "gl_MaxVertexOutputComponents"
    ];

    const templateWords = [
        "if", "else", "elif", "elseif", "endif", "for", "endfor", "while", "endwhile",
        "include", "copyPC"
    ];

    monaco.languages.register({
        id: languageId,
        extensions: [".frag", ".vert", ".glsl", ".comp", ".tesc", ".tese", ".geom", ".mesh", ".task", ".rgen", ".rchit", ".rahit", ".rmiss", ".rint", ".rcall"],
        aliases: ["GLSL", "glsl"]
    });

    monaco.languages.setLanguageConfiguration(languageId, {
        comments: { lineComment: "//", blockComment: ["/*", "*/"] },
        brackets: [["{", "}"], ["[", "]"], ["(", ")"]],
        autoClosingPairs: [
            { open: "{", close: "}" },
            { open: "[", close: "]" },
            { open: "(", close: ")" },
            { open: "\"", close: "\"", notIn: ["string", "comment"] },
            { open: "'", close: "'", notIn: ["string", "comment"] }
        ],
        surroundingPairs: [
            { open: "{", close: "}" },
            { open: "[", close: "]" },
            { open: "(", close: ")" },
            { open: "\"", close: "\"" },
            { open: "'", close: "'" }
        ],
        folding: {
            markers: {
                start: /^\s*(?:#\s*(?:if|ifdef|ifndef|region)\b|\{%\s*(?:if|for|while)\b)/,
                end: /^\s*(?:#\s*(?:endif|endregion)\b|\{%\s*end(?:if|for|while)\s*%})/
            }
        }
    });

    monaco.languages.setMonarchTokensProvider(languageId, {
        tokenPostfix: ".glsl",
        defaultToken: "",
        directives,
        macros,
        storage,
        keywords,
        types,
        builtinVars,
        constants,
        templateWords,
        operators: [
            "*", "+", "-", "/", "~", "!", "%", "<<", ">>", "<", ">", "<=", ">=", "==", "!=",
            "&", "^", "|", "&&", "^^", "||", "=", "+=", "-=", "*=", "/=", "%=", "<<=",
            ">>=", "&=", "^=", "|=", "?", ":", "++", "--"
        ],
        intsuffix: "[uU]?",
        floatsuffix: "([fF]|(fl|FL))?",
        symbols: /[=><!~?:&|+\-*\/\^%]+/,
        escapes: /\\(?:[abfnrtv\\"']|x[0-9A-Fa-f]{2}|u[0-9A-Fa-f]{4})/,
        tokenizer: {
            root: [
                [/\{\{/, { token: "delimiter.template", next: "@fieldExpression" }],
                [/\{%/, { token: "delimiter.template", next: "@fieldTag" }],
                [/\/\/.*$/, "comment.line"],
                [/\/\*/, "comment.block", "@comment"],
                [/#\s*[A-Za-z_]*/, {
                    cases: {
                        "@directives": "keyword.control.preprocessor",
                        "@default": "meta.preprocessor"
                    }
                }],
                [/GL_ES/, "meta.preprocessor"],
                [/__[A-Z_]+__/, {
                    cases: {
                        "@macros": "meta.preprocessor",
                        "@default": "identifier"
                    }
                }],
                [/[{}()\[\]]/, "@brackets"],
                [/@symbols/, {
                    cases: {
                        "@operators": "operator",
                        "@default": ""
                    }
                }],
                [/\b(?:true|false)\b/, "constant"],
                [/[A-Za-z_][A-Za-z0-9_]*(?=\s*\()/, "entity.name.function"],
                [/[A-Za-z_][A-Za-z0-9_]*/, {
                    cases: {
                        "@storage": "storage.type",
                        "@keywords": "keyword",
                        "@types": "entity.name.type",
                        "@builtinVars": "variable.predefined",
                        "@constants": "constant",
                        "@default": "variable.name"
                    }
                }],
                [/\d*\d+[eE]([\-+]?\d+)?(@floatsuffix)/, "number.float"],
                [/\d*\.\d+([eE][\-+]?\d+)?(@floatsuffix)/, "number.float"],
                [/0[xX][0-9a-fA-F]+(@intsuffix)/, "number.hex"],
                [/0[0-7]+(@intsuffix)/, "number.octal"],
                [/\d+(@intsuffix)/, "number"],
                [/"([^"\\]|\\.)*$/, "string.invalid"],
                [/"/, "string", "@string"],
                [/'[^\\']'/, "string"],
                [/'/, "string.invalid"],
                [/[;,.]/, "delimiter"]
            ],
            comment: [
                [/[^\/*]+/, "comment.block"],
                [/\*\//, "comment.block", "@pop"],
                [/[\/*]/, "comment.block"]
            ],
            string: [
                [/[^\\"]+/, "string"],
                [/@escapes/, "string.escape"],
                [/\\./, "string.escape.invalid"],
                [/"/, "string", "@pop"]
            ],
            fieldExpression: [
                [/\}\}/, { token: "delimiter.template", next: "@pop" }],
                [/"([^"\\]|\\.)*"/, "string"],
                [/'([^'\\]|\\.)*'/, "string"],
                [/\d*\.\d+([eE][\-+]?\d+)?/, "number.float"],
                [/\d+/, "number"],
                [/[A-Za-z_][A-Za-z0-9_.]*/, "variable.parameter"],
                [/[{}()\[\]]/, "@brackets"],
                [/[=><!~?:&|+\-*\/\^%,.]+/, "operator"],
                [/\s+/, ""],
                [/./, "variable.parameter"]
            ],
            fieldTag: [
                [/%\}/, { token: "delimiter.template", next: "@pop" }],
                [/"([^"\\]|\\.)*"/, "string"],
                [/'([^'\\]|\\.)*'/, "string"],
                [/\b[A-Za-z_][A-Za-z0-9_]*\b/, {
                    cases: {
                        "@templateWords": "keyword.control.template",
                        "@default": "variable.parameter"
                    }
                }],
                [/[{}()\[\]]/, "@brackets"],
                [/[=><!~?:&|+\-*\/\^%,.]+/, "operator"],
                [/\s+/, ""],
                [/./, "variable.parameter"]
            ]
        }
    });

    function indentUnit(options) {
        const tabSize = Math.max(1, Number(options && options.tabSize) || 4);
        return options && options.insertSpaces === false ? "\t" : " ".repeat(tabSize);
    }

    function compactOutsideQuotes(value) {
        let result = "";
        let quote = null;
        let escaped = false;
        let pendingSpace = false;

        for (let i = 0; i < value.length; i++) {
            const ch = value[i];
            if (quote) {
                result += ch;
                if (escaped) {
                    escaped = false;
                } else if (ch === "\\") {
                    escaped = true;
                } else if (ch === quote) {
                    quote = null;
                }
                continue;
            }

            if (ch === "\"" || ch === "'") {
                if (pendingSpace && result.length > 0) result += " ";
                pendingSpace = false;
                quote = ch;
                result += ch;
                continue;
            }

            if (/\s/.test(ch)) {
                pendingSpace = true;
                continue;
            }

            if (pendingSpace && result.length > 0) result += " ";
            pendingSpace = false;
            result += ch;
        }

        return result.trim();
    }

    function normalizeTemplateSpans(line) {
        return line
            .replace(/\{\{\s*([^]*?)\s*\}\}/g, function (_, expression) {
                return "{{ " + expression.trim() + " }}";
            })
            .replace(/\{%\s*([^]*?)\s*%\}/g, function (_, tag) {
                return "{% " + compactOutsideQuotes(tag) + " %}";
            });
    }

    function templateTagKind(trimmed) {
        const match = /^\{%\s*([A-Za-z_][A-Za-z0-9_]*)/.exec(trimmed);
        if (!match) return null;

        const word = match[1].toLowerCase();
        if (word === "if" || word === "for" || word === "while") return "open";
        if (word === "else" || word === "elif" || word === "elseif") return "middle";
        if (word.indexOf("end") === 0) return "close";
        return null;
    }

    function skipString(line, index, quote) {
        let i = index + 1;
        while (i < line.length) {
            if (line[i] === "\\") {
                i += 2;
                continue;
            }
            if (line[i] === quote) return i + 1;
            i++;
        }
        return i;
    }

    function analyzeGlslLine(line, state) {
        let opens = 0;
        let closes = 0;
        let firstCode = "";
        let blockComment = !!state.blockComment;

        for (let i = 0; i < line.length;) {
            if (state.templateClose) {
                const end = line.indexOf(state.templateClose, i);
                if (end < 0) {
                    i = line.length;
                } else {
                    i = end + state.templateClose.length;
                    state.templateClose = null;
                }
                continue;
            }

            const ch = line[i];
            const next = line[i + 1];

            if (blockComment) {
                if (ch === "*" && next === "/") {
                    blockComment = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }

            if (ch === "/" && next === "*") {
                blockComment = true;
                i += 2;
                continue;
            }

            if (ch === "/" && next === "/") break;

            if (ch === "{" && (next === "{" || next === "%")) {
                const close = next === "{" ? "}}" : "%}";
                const end = line.indexOf(close, i + 2);
                if (end < 0) {
                    state.templateClose = close;
                    i = line.length;
                } else {
                    i = end + close.length;
                }
                continue;
            }

            if (ch === "\"" || ch === "'") {
                i = skipString(line, i, ch);
                continue;
            }

            if (!firstCode && !/\s/.test(ch)) firstCode = ch;
            if (ch === "{") opens++;
            if (ch === "}") closes++;
            i++;
        }

        state.blockComment = blockComment;
        return {
            delta: opens - closes,
            startsWithClosingBrace: firstCode === "}"
        };
    }

    function formatFieldGLSL(source, options) {
        if (!source) return "";

        const unit = indentUnit(options || {});
        const normalized = String(source).replace(/\r\n?/g, "\n");
        const hadTrailingNewline = normalized.endsWith("\n");
        const inputLines = normalized.split("\n");
        if (hadTrailingNewline) inputLines.pop();

        const output = [];
        const state = { blockComment: false };
        let depth = 0;

        for (const rawLine of inputLines) {
            const withoutTrailing = rawLine.replace(/[ \t]+$/g, "");
            const trimmed = withoutTrailing.trim();

            if (trimmed.length === 0) {
                output.push("");
                continue;
            }

            if (!state.blockComment && trimmed[0] === "#") {
                output.push(normalizeTemplateSpans(trimmed));
                continue;
            }

            const tagKind = templateTagKind(trimmed);
            const analysis = analyzeGlslLine(withoutTrailing, state);

            let lineDepth = depth;
            if (tagKind === "middle" || tagKind === "close") lineDepth--;
            if (analysis.startsWithClosingBrace) lineDepth--;
            lineDepth = Math.max(0, lineDepth);

            output.push(unit.repeat(lineDepth) + normalizeTemplateSpans(trimmed));

            depth = Math.max(0, depth + analysis.delta);
            if (tagKind === "open") depth++;
            if (tagKind === "close") depth = Math.max(0, depth - 1);
        }

        let formatted = output.join("\n");
        if (hadTrailingNewline) formatted += "\n";
        return formatted;
    }

    monaco.languages.registerDocumentFormattingEditProvider(languageId, {
        displayName: "Field GLSL",
        provideDocumentFormattingEdits: function (model, options) {
            return [{
                range: model.getFullModelRange(),
                text: formatFieldGLSL(model.getValue(), options || {})
            }];
        }
    });

    if (typeof window !== "undefined") {
        window.afieldFormatGLSL = formatFieldGLSL;
    }

    return {
        formatFieldGLSL: formatFieldGLSL
    };
});
