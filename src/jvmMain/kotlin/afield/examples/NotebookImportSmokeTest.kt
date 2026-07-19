package afield.examples

import afield.Box
import afield.SavingContext
import afield.SessionInformation
import afield.python.isPythonRoot
import afield.server.importNotebookCells
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/*
FIELD_TEST
id: examples.import.ipynb-notebook
runner: jvm-main
entrypoint: afield.examples.NotebookImportSmokeTest
tags: [smoke, agents, python, import, notebook]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "NotebookImportSmokeTest ok"
*/
object NotebookImportSmokeTest {

    private val NOTEBOOK = """
        {
         "cells": [
          {"cell_type": "markdown", "metadata": {}, "source": ["# Title\n", "intro prose"]},
          {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [],
           "source": ["import numpy as np\n", "x = np.arange(5)"]},
          {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [],
           "source": ["print(x.sum())"]},
          {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": []}
         ],
         "metadata": {},
         "nbformat": 4,
         "nbformat_minor": 5
        }
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        val workspace = Files.createTempDirectory("afield-notebook-import").toFile()
        val document = File(workspace, "docA").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")

        val root = Box("root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = root,
            ephemeral = true,
        )
        root.inside = session
        session.boxes[root.id] = root

        thisSessionInfo.set(session)
        try {
            val created = importNotebookCells(
                notebookJson = NOTEBOOK,
                fileName = "sample.ipynb",
                anchorX = 100.0,
                anchorY = 50.0,
            )

            // Root + 3 non-empty cells (the empty code cell is skipped).
            require(created.size == 4) { "Expected 4 boxes (root + 3 cells), got ${created.size}" }

            val pythonRoot = created[0]
            require(pythonRoot.isPythonRoot == true) { "Parent box must have isPythonRoot=true" }
            require(pythonRoot.name == "python root") { "Root should be named 'python root', got '${pythonRoot.name}'" }

            val markdown = created[1]
            val code1 = created[2]
            val code2 = created[3]

            // Markdown cell: prose in `notes`, NOT a python child, indented.
            require(markdown.getPropertyRaw("notes") == "# Title\nintro prose") {
                "Markdown notes wrong: '${markdown.getPropertyRaw("notes")}'"
            }
            require(markdown.getPropertyRaw("default_property") == "notes") { "Markdown default_property should be notes" }
            require(markdown !in pythonRoot.children) { "Markdown cell must not be a python child" }
            require(root in markdown.parents) { "Markdown cell should remain a document-root box" }

            // Code cells: children of the python root, detached from the document root.
            listOf(code1, code2).forEach { cell ->
                require(cell in pythonRoot.children) { "Code cell must be a child of the python root" }
                require(pythonRoot in cell.parents) { "Code cell must list the python root as parent" }
                require(root !in cell.parents) { "Code cell must be detached from the document root" }
                require(cell !in root.children) { "Code cell must not remain in the document root's children" }
                require(cell.name.isBlank()) { "Cell boxes must be left unnamed for AI naming, got '${cell.name}'" }
            }
            require(code1.code == "import numpy as np\nx = np.arange(5)") { "code1 body wrong: '${code1.code}'" }
            require(code2.code == "print(x.sum())") { "code2 body wrong: '${code2.code}'" }

            // Vertical column layout, markdown indented relative to code.
            require(markdown.frame.x > code1.frame.x) { "Markdown cells should be indented past code cells" }
            require(code1.frame.x == 100.0) { "Code cells should sit at the column anchor x" }
            val ys = listOf(pythonRoot, markdown, code1, code2).map { it.frame.y }
            require(ys == ys.sorted() && ys.toSet().size == ys.size) { "Boxes should stack top-to-bottom: $ys" }

            println("NotebookImportSmokeTest ok")
        } finally {
            thisSessionInfo.remove()
        }
    }
}
