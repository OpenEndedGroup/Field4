define("bithero/fnrewrite", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
        const monaco = monacoModule || require("vs/editor/editor.api")
        const root = window
        const languageId = "fnrewrite"
        const markerOwner = "fnrewrite-diagnostics"
        const embeddedHeaderRegex = /^(?:[ \t]*(?:\r?\n|$))*[ \t]*#fnrewrite\b[^\n]*(?:\r?\n|$)/i
        const rewriteWords = new Set([
                "any", "const", "number", "param", "opaque", "drop", "prune", "zero",
                "op", "replace", "name", "domain", "all", "default", "value", "it",
                "role", "edge", "parameter", "parent", "under", "tag", "depth", "index",
        ])
        const opWords = new Set(["sin", "saw", "pulseTrain", "fbm", "valueNoise", "hannWindow", "delay", "mirror", "repeat"])

        if (monaco && monaco.languages && !root.__fnRewriteLanguageRegistered) {
                root.__fnRewriteLanguageRegistered = true
                monaco.languages.register({ id: languageId, aliases: ["FnRewrite", "fnrewrite"] })
                monaco.languages.setMonarchTokensProvider(languageId, {
                        tokenPostfix: ".fnrewrite",
                        tokenizer: {
                                root: [
                                        [/\/\/.*$/, "comment"],
                                        [/#.*$/, "comment"],
                                        [/(?:@\{|\$\{)/, { token: "delimiter.bracket", next: "@kotlin" }],
                                        [/=>/, "operator"],
                                        [/(?:\*=|\/=|\+=|-=|=)/, "operator"],
                                        [/\*\*|\*|\//, "operator"],
                                        [/\[|\]/, "delimiter.square"],
                                        [/[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/, "number"],
                                        [/[A-Za-z_][\w-]*/, {
                                                cases: {
                                                        "@rewriteWords": "keyword",
                                                        "@opWords": "type.identifier",
                                                        "@default": "identifier",
                                                }
                                        }],
                                ],
                                kotlin: [
                                        [/\}/, { token: "delimiter.bracket", next: "@pop" }],
                                        [/\/\/.*$/, "comment"],
                                        [/".*?"/, "string"],
                                        [/[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/, "number"],
                                        [/[A-Za-z_][\w]*/, "identifier"],
                                        [/./, "source"],
                                ],
                        },
                        rewriteWords: Array.from(rewriteWords),
                        opWords: Array.from(opWords),
                })
        }

        const rangeFromOffsets = (monacoApi, model, start, end) => {
                const a = model.getPositionAt(Math.max(0, start))
                const b = model.getPositionAt(Math.max(start, end))
                return new monacoApi.Range(a.lineNumber, a.column, b.lineNumber, b.column)
        }

        const findEmbeddedRegions = (model) => {
                if (!model || model.getLanguageId() !== "kotlin") return []
                const source = model.getValue()
                const regions = []
                let search = 0
                while (search < source.length) {
                        const open = source.indexOf('"""', search)
                        if (open < 0) break
                        const contentStart = open + 3
                        const close = source.indexOf('"""', contentStart)
                        if (close < 0) break
                        const rawContent = source.slice(contentStart, close)
                        const header = embeddedHeaderRegex.exec(rawContent)
                        if (header) {
                                const bodyStart = contentStart + header[0].length
                                const headerStart = contentStart + header[0].search(/#fnrewrite\b/i)
                                const headerEnd = headerStart + "#fnrewrite".length
                                regions.push({
                                        open,
                                        close,
                                        contentStart,
                                        contentEnd: close,
                                        bodyStart,
                                        bodyEnd: close,
                                        headerStart,
                                        headerEnd,
                                        source: source.slice(bodyStart, close),
                                        bodyPosition: model.getPositionAt(bodyStart),
                                })
                        }
                        search = close + 3
                }
                return regions
        }

        const tokenize = (source, baseOffset) => {
                const tokens = []
                const push = (kind, start, end) => {
                        if (end > start) tokens.push({ kind, start: baseOffset + start, end: baseOffset + end })
                }
                let i = 0
                while (i < source.length) {
                        const ch = source[i]
                        if (/\s/.test(ch)) {
                                i++
                                continue
                        }
                        if (ch === "#" || (ch === "/" && source[i + 1] === "/")) {
                                const start = i
                                while (i < source.length && source[i] !== "\n") i++
                                push("comment", start, i)
                                continue
                        }
                        if ((ch === "@" || ch === "$") && source[i + 1] === "{") {
                                const start = i
                                i += 2
                                let depth = 1
                                let inString = false
                                let escaped = false
                                while (i < source.length && depth > 0) {
                                        const c = source[i]
                                        if (inString) {
                                                if (escaped) escaped = false
                                                else if (c === "\\") escaped = true
                                                else if (c === "\"") inString = false
                                        } else if (c === "\"") inString = true
                                        else if (c === "{") depth++
                                        else if (c === "}") depth--
                                        i++
                                }
                                push("kotlin", start, i)
                                continue
                        }
                        if (source.slice(i, i + 2) === "=>") {
                                push("operator", i, i + 2)
                                i += 2
                                continue
                        }
                        const two = source.slice(i, i + 2)
                        if (two === "**") {
                                push("operator", i, i + 2)
                                i += 2
                                continue
                        }
                        if (["*=", "/=", "+=", "-="].includes(two)) {
                                push("operator", i, i + 2)
                                i += 2
                                continue
                        }
                        if (ch === "=" || ch === "|" || ch === "," || ch === "." || ch === "&" ||
                            ch === "/" || ch === "*" || ch === "[" || ch === "]" ||
                            ch === ">" || ch === "<" || ch === "!") {
                                push("operator", i, i + 1)
                                i++
                                continue
                        }
                        const number = /^[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(source.slice(i))
                        if (number) {
                                push("number", i, i + number[0].length)
                                i += number[0].length
                                continue
                        }
                        const word = /^[A-Za-z_][\w-]*/.exec(source.slice(i))
                        if (word) {
                                const text = word[0]
                                push(rewriteWords.has(text) ? "keyword" : (opWords.has(text) ? "op" : "identifier"), i, i + text.length)
                                i += text.length
                                continue
                        }
                        i++
                }
                return tokens
        }

        const tokenClass = (kind) => {
                if (kind === "comment") return "afield-stacksl-token-comment"
                if (kind === "number") return "afield-stacksl-token-number"
                if (kind === "operator") return "afield-stacksl-token-operator"
                if (kind === "keyword") return "afield-stacksl-token-keyword"
                if (kind === "op") return "afield-stacksl-token-creative"
                if (kind === "kotlin") return "afield-stacksl-token-variable"
                return "afield-stacksl-token-identifier"
        }

        const updateEmbeddedHighlight = (editor, context) => {
                const model = context.model
                const monacoApi = context.monaco || monaco
                if (!model || !monacoApi) return
                const decorations = []
                for (const region of findEmbeddedRegions(model)) {
                        decorations.push({
                                range: rangeFromOffsets(monacoApi, model, region.headerStart, region.headerEnd),
                                options: { inlineClassName: "afield-stacksl-token-directive", hoverMessage: { value: "Embedded Fn rewrite rules" } },
                        })
                        for (const token of tokenize(region.source, region.bodyStart)) {
                                decorations.push({
                                        range: rangeFromOffsets(monacoApi, model, token.start, token.end),
                                        options: { inlineClassName: tokenClass(token.kind) },
                                })
                        }
                }
                editor._fnRewriteDecorations = editor.deltaDecorations(editor._fnRewriteDecorations || [], decorations)
        }

        const validateKotlinBraces = (source) => {
                const warnings = []
                let i = 0
                while (i < source.length) {
                        if ((source[i] === "@" || source[i] === "$") && source[i + 1] === "{") {
                                const opener = source[i] + "{"
                                const start = i
                                i += 2
                                let depth = 1
                                let inString = false
                                let escaped = false
                                while (i < source.length && depth > 0) {
                                        const c = source[i]
                                        if (inString) {
                                                if (escaped) escaped = false
                                                else if (c === "\\") escaped = true
                                                else if (c === "\"") inString = false
                                        } else if (c === "\"") inString = true
                                        else if (c === "{") depth++
                                        else if (c === "}") depth--
                                        i++
                                }
                                if (depth > 0) warnings.push({ start, end: source.length, message: `Unclosed Kotlin expression in ${opener}...}.` })
                                continue
                        }
                        i++
                }
                return warnings
        }

        const updateDiagnostics = (editor, context) => {
                const model = context.model
                const monacoApi = context.monaco || monaco
                if (!model || !monacoApi || !monacoApi.editor) return
                const markers = []
                for (const region of findEmbeddedRegions(model)) {
                        const lines = region.source.split(/\r?\n/)
                        let offset = region.bodyStart
                        for (const line of lines) {
                                const trimmed = line.trim()
                                if (trimmed && !trimmed.startsWith("#") && !trimmed.startsWith("//") && !trimmed.includes("=>")) {
                                        const start = offset + line.indexOf(trimmed)
                                        const end = start + trimmed.length
                                        const range = rangeFromOffsets(monacoApi, model, start, end)
                                        markers.push({
                                                severity: monacoApi.MarkerSeverity.Warning,
                                                message: "Expected selector => action.",
                                                startLineNumber: range.startLineNumber,
                                                startColumn: range.startColumn,
                                                endLineNumber: range.endLineNumber,
                                                endColumn: range.endColumn,
                                        })
                                }
                                offset += line.length + 1
                        }
                        for (const warning of validateKotlinBraces(region.source)) {
                                const range = rangeFromOffsets(monacoApi, model, region.bodyStart + warning.start, region.bodyStart + warning.end)
                                markers.push({
                                        severity: monacoApi.MarkerSeverity.Warning,
                                        message: warning.message,
                                        startLineNumber: range.startLineNumber,
                                        startColumn: range.startColumn,
                                        endLineNumber: range.endLineNumber,
                                        endColumn: range.endColumn,
                                })
                        }
                }
                monacoApi.editor.setModelMarkers(model, markerOwner, markers)
        }

        const buildContextPayload = (editor, resolveContext) => {
                const model = editor && editor.getModel && editor.getModel()
                if (!model || model.getLanguageId() !== "kotlin") return null
                const position = resolveContext && resolveContext.position
                if (!position) return null
                const offset = model.getOffsetAt(position)
                const region = findEmbeddedRegions(model).find((candidate) => offset >= candidate.bodyStart && offset <= candidate.bodyEnd)
                if (!region) return null
                const html = '<div class="afield-stacksl-context"><strong>FnRewrite</strong> ' +
                        '<span class="afield-stacksl-muted">path selector =&gt; action; use <code>*</code>, <code>**</code>, predicates, and Kotlin inside <code>@{...}</code></span></div>'
                return { key: `fnrewrite-${region.bodyStart}-${region.bodyEnd}`, html, language: languageId, embedded: true }
        }

        const provideCompletionItems = (editor, model, position, context) => {
                if (!model || model.getLanguageId() !== "kotlin") return null
                const offset = model.getOffsetAt(position)
                const region = findEmbeddedRegions(model).find((candidate) => offset >= candidate.bodyStart && offset <= candidate.bodyEnd)
                if (!region) return null
                const monacoApi = context.monaco || monaco
                const kind = monacoApi.languages.CompletionItemKind.Snippet
                const range = model.getWordUntilPosition(position)
                const replaceRange = new monacoApi.Range(position.lineNumber, range.startColumn, position.lineNumber, range.endColumn)
                const snippets = [
                        ["Path saw under sin", "sin/*/saw => op=sin"],
                        ["Deep constants under sin", "sin/**/[tag=number] => *= @{value * 1.1}"],
                        ["Deep amplitude params", "**/[role=amplitude] => *= 0.5"],
                        ["Positive constants", "**/[op=const][value>0.2] => *= @{value.pow(0.8)}"],
                        ["Scale frequency", "sin.frequency => *= @{value * 2.0}"],
                        ["Quiet amplitudes", "role=amplitude => *= @{if (parentOp == \"fbm\") 0.5 else 1.0}"],
                        ["Replace saw", "saw => op=sin"],
                        ["Drop constants", "const => drop"],
                        ["Rename", "any => name=mutated"],
                        ["Domain all", "any => domain=all"],
                ]
                return {
                        suggestions: snippets.map(([label, insertText]) => ({
                                label,
                                kind,
                                insertText,
                                range: replaceRange,
                                detail: "Fn rewrite rule",
                        }))
                }
        }

        const install = (editor, monacoApi) => {
                const schedule = () => setTimeout(() => {
                        const context = { editor, model: editor.getModel && editor.getModel(), monaco: monacoApi || monaco }
                        updateEmbeddedHighlight(editor, context)
                        updateDiagnostics(editor, context)
                }, 0)
                if (editor.onDidChangeModelContent) editor.onDidChangeModelContent(schedule)
                if (editor.onDidChangeModel) editor.onDidChangeModel(schedule)
                schedule()
        }

        const extension = {
                id: languageId,
                label: "FnRewrite",
                languageIds: ["kotlin", languageId],
                markerOwner,
                findEmbeddedRegions,
                install,
                updateEmbeddedHighlight,
                updateDiagnostics,
                buildContextPayload,
                provideCompletionItems,
        }

        if (root.FieldLanguageExtensions && typeof root.FieldLanguageExtensions.register === "function") {
                root.FieldLanguageExtensions.register(extension)
        } else {
                root.__fieldLanguageExtensionQueue = root.__fieldLanguageExtensionQueue || []
                root.__fieldLanguageExtensionQueue.push(extension)
        }
        root.__fnRewriteExtensionProof = { registered: true, extension, findEmbeddedRegions, tokenize }
        return extension
})
