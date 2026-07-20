package afield.examples

import afield.Box
import afield.BoxExecution
import afield.CompletionRequest
import afield.Execute
import afield.ExecuteSymbol
import afield.KotlinExecution
import afield.Message
import afield.SavingContext
import afield.SessionInformation
import afield.runtime.applyRuntimeToggle
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/*
FIELD_TEST
id: examples.runtime.toggle-multi-parent
runner: jvm-main
entrypoint: afield.examples.RuntimeToggleMultiParentSmokeTest
tags: [smoke, runtime, language, toggle]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "RuntimeToggleMultiParentSmokeTest ok"
*/
object RuntimeToggleMultiParentSmokeTest {

    // Minimal stand-in for a language execution (e.g. PythonExecution). applyRuntimeToggle
    // only cares about its runtime class identity, not its behavior.
    private class FakeExecution : BoxExecution {
        override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {}
        override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {}
        override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) {}
        override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {}
        override fun stop(inside: Box) {}
        override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {}
        override val languageName: String get() = "fake"
    }

    private fun toggle(boxes: List<Box>, isRoot: (Box) -> Boolean) {
        applyRuntimeToggle(
            boxes = boxes,
            isRoot = { isRoot(it) },
            runtimeClass = FakeExecution::class,
            newExecution = { FakeExecution() },
            language = "fake",
            customization = "",
            notifyCategory = "test",
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val workspace = Files.createTempDirectory("afield-toggle").toFile()
        val document = File(workspace, "doc").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")

        val docRoot = Box("root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = docRoot,
            ephemeral = true,
        )
        docRoot.inside = session
        session.boxes[docRoot.id] = docRoot
        thisSessionInfo.set(session)

        try {
            fun sessionBox(id: String) = Box(id).also {
                it.inside = session
                session.boxes[it.id] = it
            }

            // A cell that is a child of BOTH a language-root marker and the (non-root)
            // document root — the notebook-import topology now that cells stay on the root.
            val langRoot = sessionBox("langRoot")
            val cell = sessionBox("cell")
            docRoot.connect(cell)
            langRoot.connect(cell)
            val isRoot = { b: Box -> b === langRoot }

            // Pretend the language root already toggled the cell into its language, then run
            // the toggle with the NON-root document root FIRST — the adverse order whose
            // detach pass used to revert the cell back to Kotlin.
            cell.ke = FakeExecution()
            toggle(listOf(docRoot, langRoot), isRoot)
            require(cell.ke is FakeExecution) {
                "A cell with a language-root parent must NOT be reverted, got ${cell.ke::class.simpleName}"
            }

            // Sever the language-root parent: with no language-root parent left, the
            // document-root pass must revert it to Kotlin (the old behavior is preserved
            // for genuinely orphaned boxes).
            langRoot.disconnect(cell)
            toggle(listOf(docRoot), isRoot)
            require(cell.ke is KotlinExecution) {
                "A cell with no language-root parent must revert to Kotlin, got ${cell.ke::class.simpleName}"
            }

            println("RuntimeToggleMultiParentSmokeTest ok")
        } finally {
            thisSessionInfo.remove()
        }
    }
}
