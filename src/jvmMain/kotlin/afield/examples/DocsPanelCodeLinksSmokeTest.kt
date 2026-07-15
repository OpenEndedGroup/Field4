package afield.examples

import afield.KotlinCompletionSupport

/*
FIELD_TEST
id: examples.docs-panel-code-links.smoke
runner: jvm-main
entrypoint: afield.examples.DocsPanelCodeLinksSmokeTest
tags: [smoke, completion, docs, code-links]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "DocsPanelCodeLinksSmokeTest ok"
*/

/**
 * Exercises the documentation-panel code-link backend against the real Kotlin
 * source index (~/.afield/filestore/kotlin_source_index.json): KDoc `[Symbol]`
 * linkification and source-card resolution. No GPU/UI — pure index + source
 * reads — so it runs in a fresh JVM.
 */
object DocsPanelCodeLinksSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val failures = mutableListOf<String>()
        fun check(label: String, condition: Boolean) {
            if (condition) println("  ok: $label") else failures += label
        }

        // 1. Linkify a real KDoc body: [Vec2] resolves (project typealias), [Float]
        //    (classpath-only) stays literal — no dead links.
        val kdoc = "Creates a mutable 2D [Vec2] from numeric coordinates, converting each component to [Float]."
        val linked = KotlinCompletionSupport.linkifyKDocSymbols(kdoc)
        println("linkify -> $linked")
        check("[Vec2] became a field-source link", linked.contains("[Vec2](field-source:"))
        check("field-source href carries a path", linked.contains("path="))
        check("[Float] stayed literal (no dead link)", !linked.contains("[Float]("))

        // 2. Fenced code blocks are never linkified.
        val fenced = "```kotlin\nval x: [Vec2] = z\n```"
        check("code fences untouched", KotlinCompletionSupport.linkifyKDocSymbols(fenced) == fenced)

        // 3. Resolve the Vec2 typealias card by symbol name.
        val vec2 = KotlinCompletionSupport.resolveSourceCard("Vec2", null, -1)
        println("Vec2 card -> found=${vec2.found} ${vec2.sourcePath}:${vec2.sourceLine}")
        check("Vec2 resolved", vec2.found)
        check("Vec2 points at linalg.kt", vec2.sourcePath.endsWith("linalg.kt"))
        check("Vec2 title", vec2.title == "Vec2")
        check("Vec2 card has a signature block", vec2.documentation.contains("```kotlin"))
        check("Vec2 card mentions typealias", vec2.documentation.contains("typealias Vec2"))

        // 4. Resolve the vec() function card and confirm its own doc is recursively
        //    linkified (the [Vec2] inside vec's KDoc becomes a link).
        val vec = KotlinCompletionSupport.resolveSourceCard("vec", null, -1)
        println("vec card -> found=${vec.found} ${vec.sourcePath}:${vec.sourceLine}")
        check("vec resolved", vec.found)
        check("vec card shows a fun signature", vec.documentation.contains("fun vec"))
        check("vec card recursively linkifies [Vec2]", vec.documentation.contains("field-source:"))

        // 5. Resolve by path+line (the source-line link path).
        val byLoc = KotlinCompletionSupport.resolveSourceCard(null, vec2.sourcePath, vec2.sourceLine)
        check("resolve by path+line works", byLoc.found && byLoc.title == "Vec2")

        // 6. Unknown symbol resolves to not-found (so the client shows no card).
        val missing = KotlinCompletionSupport.resolveSourceCard("NotARealSymbolXYZ", null, -1)
        check("unknown symbol -> not found", !missing.found)

        if (failures.isEmpty()) {
            println("DocsPanelCodeLinksSmokeTest ok")
        } else {
            System.err.println("DocsPanelCodeLinksSmokeTest FAILED:")
            failures.forEach { System.err.println("  - $it") }
            kotlin.system.exitProcess(1)
        }
    }
}
