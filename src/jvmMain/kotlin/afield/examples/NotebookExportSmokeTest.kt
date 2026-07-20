package afield.examples

import afield.Box
import afield.SavingContext
import afield.SessionInformation
import afield.python.isPythonRoot
import afield.server.exportNotebookCells
import afield.server.importNotebookCells
import afield.thisSessionInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/*
FIELD_TEST
id: examples.export.ipynb-notebook
runner: jvm-main
entrypoint: afield.examples.NotebookExportSmokeTest
tags: [smoke, agents, python, import, export, notebook]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "NotebookExportSmokeTest ok"
*/
object NotebookExportSmokeTest {

    // Mixed fixture: markdown intro, two code cells, a trailing markdown note.
    private val NOTEBOOK = """
        {
         "cells": [
          {"cell_type": "markdown", "metadata": {}, "source": ["# Analysis\n", "intro prose"]},
          {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [],
           "source": ["import numpy as np\n", "x = np.arange(5)"]},
          {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [],
           "source": ["print(x.sum())"]},
          {"cell_type": "markdown", "metadata": {}, "source": ["done."]}
         ],
         "metadata": {},
         "nbformat": 4,
         "nbformat_minor": 5
        }
    """.trimIndent()

    // A cell reduced to its (type, source) — the round-trip identity we assert.
    private data class CellShape(val type: String, val source: String)

    private fun cellShapes(notebookJson: String): List<CellShape> {
        val nb = Json.parseToJsonElement(notebookJson).jsonObject
        val cells = (nb["cells"] as JsonArray).map { it.jsonObject }
        return cells.map { cell ->
            val type = cell["cell_type"]!!.jsonPrimitive.content
            val srcEl = cell["source"]!!
            val source = when (srcEl) {
                is JsonArray -> srcEl.joinToString("") { (it as JsonPrimitive).contentOrNull ?: "" }
                is JsonPrimitive -> srcEl.contentOrNull ?: ""
                else -> ""
            }
            CellShape(type, source)
        }
    }

    private fun freshSession(name: String): Pair<SessionInformation, Box> {
        val workspace = Files.createTempDirectory("afield-notebook-export").toFile()
        val document = File(workspace, name).also { it.mkdirs() }
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
        return session to root
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // --- 1. Import the fixture into a python-root column. ------------------
        val (sessionA, _) = freshSession("docA")
        thisSessionInfo.set(sessionA)
        val exported: String
        try {
            val created = importNotebookCells(
                notebookJson = NOTEBOOK,
                fileName = "analysis.ipynb",
                anchorX = 100.0,
                anchorY = 50.0,
            )
            val pythonRoot = created[0]
            require(pythonRoot.isPythonRoot == true) { "import must produce a python-root parent" }

            // --- 2. Export the python-root back out. --------------------------
            exported = exportNotebookCells(pythonRoot, sessionA)
        } finally {
            thisSessionInfo.remove()
        }

        // --- 3. The export is valid nbformat-4 with the right cells. ----------
        val nb = Json.parseToJsonElement(exported).jsonObject
        require(nb["nbformat"]!!.jsonPrimitive.int == 4) { "nbformat must be 4, got ${nb["nbformat"]}" }
        require(nb["nbformat_minor"] != null) { "nbformat_minor missing" }
        require(nb["metadata"] is JsonObject) { "top-level metadata missing" }

        val exportedShapes = cellShapes(exported)
        // 4 fixture cells, one blank-safe: all 4 here are non-blank, so 4 out.
        val expected = listOf(
            CellShape("markdown", "# Analysis\nintro prose"),
            CellShape("code", "import numpy as np\nx = np.arange(5)"),
            CellShape("code", "print(x.sum())"),
            CellShape("markdown", "done."),
        )
        require(exportedShapes == expected) {
            "Exported cells wrong.\n  expected=$expected\n  got=$exportedShapes"
        }

        // Per-cell nbformat-4 envelope on code cells.
        val cells = (nb["cells"] as JsonArray).map { it.jsonObject }
        cells.filter { it["cell_type"]!!.jsonPrimitive.content == "code" }.forEach { c ->
            require(c["outputs"] is JsonArray && (c["outputs"] as JsonArray).isEmpty()) {
                "code cell must have empty outputs array"
            }
            require(c["execution_count"]!!.let { it is JsonPrimitive && it.contentOrNull == null }) {
                "code cell execution_count must be null"
            }
            require(c["metadata"] is JsonObject) { "code cell metadata missing" }
            require(c["source"] is JsonArray) { "source must be a JSON array of lines" }
        }

        // --- 4. Re-import the export; assert round-trip equality. -------------
        val (sessionB, _) = freshSession("docB")
        thisSessionInfo.set(sessionB)
        val reexported: String
        try {
            val created2 = importNotebookCells(
                notebookJson = exported,
                fileName = "roundtrip.ipynb",
                anchorX = 200.0,
                anchorY = 80.0,
            )
            require(created2.size == 5) { "re-import should yield root + 4 cells, got ${created2.size}" }
            val root2 = created2[0]
            reexported = exportNotebookCells(root2, sessionB)
        } finally {
            thisSessionInfo.remove()
        }

        val roundtripShapes = cellShapes(reexported)
        require(roundtripShapes == exportedShapes) {
            "Round-trip cells diverged.\n  first=$exportedShapes\n  second=$roundtripShapes"
        }
        require(roundtripShapes.count { it.type == "code" } == 2) { "expected 2 code cells after round-trip" }
        require(roundtripShapes.count { it.type == "markdown" } == 2) { "expected 2 markdown cells after round-trip" }

        // Save the fixture as a self-contained demo artifact next to the worklog.
        runCatching {
            File(System.getProperty("afield.export.fixtureOut") ?: return@runCatching).writeText(exported)
        }

        println("NotebookExportSmokeTest ok")
    }
}
