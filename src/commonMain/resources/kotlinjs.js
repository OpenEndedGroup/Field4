// Monaco language for "Kotlin in the head" (KotlinJsExecution) boxes.
//
// We register a DISTINCT language id ("kotlinjs") rather than reusing "kotlin" so that Field's
// kotlin-only editor surfaces (debug-probe / symbol-context providers gated on "kotlin", the
// JVM-Kotlin completion/diagnostics) do NOT attach to head boxes — a head box's `external val
// console` etc. would be flagged as errors by the JVM-Kotlin path. For highlighting we reuse
// Monaco's own official Kotlin grammar (conf + Monarch tokens), so it looks exactly like Kotlin.
//
// Loaded via "bithero/kotlinjs" in bootstrapMonaco.js. The box's code_language is "kotlinjs"
// (see afield.remotejs.kotlinJsTopologyNeedsUpdating), which the editor uses as the model language.
define("bithero/kotlinjs", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
    "use strict";

    var monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
    var languageId = "kotlinjs";

    monaco.languages.register({
        id: languageId,
        aliases: ["KotlinJS", "Kotlin (head)"],
        extensions: [".kotlinjs"]
    });

    // Reuse Monaco's official Kotlin grammar so highlighting is identical to Kotlin.
    require(["vs/basic-languages/kotlin/kotlin"], function (kotlin) {
        var mod = kotlin && kotlin.default ? kotlin.default : kotlin;
        if (mod && mod.conf) monaco.languages.setLanguageConfiguration(languageId, mod.conf);
        if (mod && mod.language) monaco.languages.setMonarchTokensProvider(languageId, mod.language);
    });
});
