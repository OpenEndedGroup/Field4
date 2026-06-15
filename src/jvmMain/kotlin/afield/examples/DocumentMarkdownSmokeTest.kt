package afield.examples

import afield.Box
import afield.Rect
import afield.SavingContext
import java.nio.file.Files

/*
FIELD_TEST
id: examples.document-markdown.smoke
runner: jvm-main
entrypoint: afield.examples.DocumentMarkdownSmokeTest
tags: [smoke, persistence, markdown, llm]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "DocumentMarkdownSmokeTest ok"
*/
object DocumentMarkdownSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Files.createTempDirectory("afield-document-markdown").toFile()

        val root = Box("root").apply {
            name = "Root"
            frame = Rect(0.0, 0.0, 100.0, 80.0)
        }
        val parent = Box("parent").apply {
            name = "Parent Box"
            frame = Rect(10.0, 20.0, 120.0, 90.0)
            code = """
                // Parent narrative #demo
                // This leading comment should become Markdown.
                println("parent")
            """.trimIndent()
        }
        val child = Box("child").apply {
            name = "Child Box"
            frame = Rect(30.0, 40.0, 120.0, 90.0)
            code = """
                // Child narrative
                val fenced = "```"
            """.trimIndent()
        }

        root.children = mutableSetOf(parent)
        parent.parents = mutableSetOf(root)
        parent.children = mutableSetOf(child)
        child.parents = mutableSetOf(parent)

        val markdown = SavingContext().exportDocumentMarkdown(setOf(child, parent, root), dir.path)

        val rootIndex = markdown.indexOf("field-box id=\"root\"")
        val parentIndex = markdown.indexOf("field-box id=\"parent\"")
        val childIndex = markdown.indexOf("field-box id=\"child\"")
        require(rootIndex >= 0 && parentIndex > rootIndex && childIndex > parentIndex) {
            "Expected parent-first box ordering in document.md"
        }
        require("```kotlin [field-property box=\"parent\" name=\"code\" path=\"parent_code_.kt\"]" in markdown) {
            "Expected fenced Kotlin block with machine-readable property metadata"
        }
        require("````kotlin [field-property box=\"child\" name=\"code\" path=\"child_code_.kt\"]" in markdown) {
            "Expected adaptive fence when code contains triple backticks"
        }
        require("`#demo`" in markdown && "This leading comment should become Markdown." in markdown) {
            "Expected leading code comments and tags to be pulled into narrative Markdown"
        }
        require("\"blockProperties\"" in markdown && "\"sha256\"" in markdown) {
            "Expected machine-readable block manifest in the box EDN header"
        }

        println("DocumentMarkdownSmokeTest ok")
    }
}
