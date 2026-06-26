define("bithero/languageExtensions", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
        const monaco = monacoModule || require("vs/editor/editor.api")
        const root = window

        const extensions = new Map()
        const installKey = (id) => `_afieldLanguageExtensionInstalled_${id}`

        const callExtension = (extension, method, args) => {
                const fn = extension && extension[method]
                if (typeof fn !== "function") return null
                try {
                        return fn.apply(extension, args)
                } catch (error) {
                        console.warn(`Field language extension '${extension.id}' ${method} failed`, error)
                        return null
                }
        }

        const register = (extension) => {
                if (!extension || !extension.id) {
                        console.warn("Field language extension registration ignored: missing id", extension)
                        return null
                }
                const id = String(extension.id)
                const normalized = {
                        label: id,
                        languageIds: [],
                        markerOwner: `${id}-diagnostics`,
                        ...extension,
                        id,
                }
                extensions.set(id, normalized)
                if (root.globalEditor) {
                        setTimeout(() => {
                                install(root.globalEditor, root.globalMonaco || root.monaco || monaco)
                                update(root.globalEditor)
                        }, 0)
                }
                return normalized
        }

        const all = () => Array.from(extensions.values())
        const get = (id) => extensions.get(String(id))

        const install = (editor, monacoApi = monaco) => {
                if (!editor) return
                for (const extension of all()) {
                        const key = installKey(extension.id)
                        if (editor[key]) continue
                        editor[key] = true
                        callExtension(extension, "install", [editor, monacoApi, registry])
                }
        }

        const update = (editor, options = {}) => {
                if (!editor) return
                const monacoApi = root.globalMonaco || root.monaco || monaco
                const model = editor.getModel && editor.getModel()
                const context = { editor, model, monaco: monacoApi, registry, options }
                for (const extension of all()) {
                        if (typeof extension.update === "function") {
                                callExtension(extension, "update", [editor, context])
                                continue
                        }
                        callExtension(extension, "updateEmbeddedHighlight", [editor, context])
                        callExtension(extension, "updateFlowLens", [editor, context])
                        callExtension(extension, "updateDiagnostics", [editor, context])
                }
        }

        const buildContextPayload = (editor, resolveContext, options = {}) => {
                for (const extension of all()) {
                        const payload = callExtension(extension, "buildContextPayload", [editor, resolveContext, options])
                        if (payload) return payload
                }
                return null
        }

        const completionItems = (editor, model, position, context = {}) => {
                const suggestions = []
                const monacoApi = root.globalMonaco || root.monaco || monaco
                const extensionContext = { editor, model, position, context, monaco: monacoApi, registry }
                for (const extension of all()) {
                        const result = callExtension(extension, "provideCompletionItems", [editor, model, position, extensionContext])
                        if (!result) continue
                        if (Array.isArray(result)) {
                                suggestions.push(...result)
                        } else if (Array.isArray(result.suggestions)) {
                                suggestions.push(...result.suggestions)
                        }
                }
                return suggestions
        }

        const embeddedRegions = (model, filter = null) => {
                const regions = []
                for (const extension of all()) {
                        if (filter && extension.id !== filter && !(extension.languageIds || []).includes(filter)) continue
                        const found = callExtension(extension, "findEmbeddedRegions", [model, { registry, extension }])
                        if (!Array.isArray(found)) continue
                        for (const region of found) {
                                regions.push({
                                        languageExtensionId: extension.id,
                                        languageLabel: extension.label || extension.id,
                                        ...region,
                                })
                        }
                }
                return regions
        }

        const registry = {
                register,
                all,
                get,
                install,
                update,
                updateAll: update,
                buildContextPayload,
                completionItems,
                embeddedRegions,
        }

        root.FieldLanguageExtensions = registry
        root.installLanguageExtensions = install
        root.updateLanguageExtensions = update
        root.buildLanguageContextPayload = buildContextPayload
        root.fieldLanguageCompletionItems = completionItems
        root.findLanguageEmbeddedRegions = embeddedRegions

        const queued = root.__fieldLanguageExtensionQueue || []
        root.__fieldLanguageExtensionQueue = queued
        while (queued.length > 0) register(queued.shift())

        return registry
})
