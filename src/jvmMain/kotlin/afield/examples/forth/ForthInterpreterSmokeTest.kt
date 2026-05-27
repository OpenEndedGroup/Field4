package afield.examples.forth

import afield.forth.ForthInterpreter

/*
FIELD_TEST
id: forth.interpreter.smoke
runner: jvm-main
entrypoint: afield.examples.forth.ForthInterpreterSmokeTest
tags: [smoke, examples, forth]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "Forth interpreter smoke tests passed"
*/
object ForthInterpreterSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Forth interpreter smoke tests failed" }
    }

    fun runTests(): Boolean {
        return try {
            recurseRunsCurrentWord()
            dictionaryToolsDescribeAndForgetUserWords()
            returnStackWordsMoveTemporaryValues()
            beginAgainCanBeLeft()
            diagnosticsIncludeCallStackAndStackSnapshot()
            visualizationWordsSendHtmlToDisplayChannels()
            println("Forth interpreter smoke tests passed")
            true
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            false
        }
    }

    private fun recurseRunsCurrentWord() {
        val forth = ForthInterpreter()
        val diagnostics = forth.eval(
            """
            : fact ( n -- n! )
              dup 1 <= if
                drop 1
              else
                dup 1- recurse *
              then ;
            5 fact
            """.trimIndent()
        )

        require(diagnostics.isEmpty()) { diagnostics.toString() }
        require(forth.popNum() == 120.0) { "Expected 120.0 on stack" }
    }

    private fun dictionaryToolsDescribeAndForgetUserWords() {
        val forth = ForthInterpreter()
        val output = StringBuilder()
        forth.onOutput = { output.append(it) }

        require(forth.eval(": sqr dup * ; see sqr").isEmpty())
        require(": sqr dup * ;" in output.toString()) { "Missing see output: $output" }

        require(forth.eval("forget sqr").isEmpty())
        val diagnostics = forth.eval("2 sqr")

        require("Unknown word: 'sqr'" in diagnostics.single().message)
    }

    private fun returnStackWordsMoveTemporaryValues() {
        val forth = ForthInterpreter()
        val diagnostics = forth.eval(
            """
            : add-twice >r r@ + r> + ;
            10 3 add-twice
            """.trimIndent()
        )

        require(diagnostics.isEmpty()) { diagnostics.toString() }
        require(forth.popNum() == 16.0) { "Expected 16.0 on stack" }
    }

    private fun beginAgainCanBeLeft() {
        val forth = ForthInterpreter()
        val diagnostics = forth.eval(
            """
            variable n
            0 n !
            : count3 begin
              1 n +!
              n @ 3 >= if leave then
            again ;
            count3
            n @
            """.trimIndent()
        )

        require(diagnostics.isEmpty()) { diagnostics.toString() }
        require(forth.popNum() == 3.0) { "Expected 3.0 on stack" }
    }

    private fun diagnosticsIncludeCallStackAndStackSnapshot() {
        val forth = ForthInterpreter()
        val diagnostics = forth.eval(
            """
            : inner nope ;
            : outer 10 inner ;
            outer
            """.trimIndent()
        )

        val message = diagnostics.single().message
        require("Unknown word: 'nope'" in message)
        require("data stack: <1> 10.0" in message)
        require("call stack: inner@1 -> outer@2" in message)
    }

    private fun visualizationWordsSendHtmlToDisplayChannels() {
        val forth = ForthInterpreter()
        val displays = mutableListOf<Pair<String, Any?>>()
        forth.onDisplay = { channel, content -> displays += channel to content }

        val diagnostics = forth.eval(
            """
            s"<b>raw</b>" html
            0.5 1.0 s"progress" bar
            42 s"answer" watch
            1 2 3 stack-display
            plot-stack
            """.trimIndent()
        )

        require(diagnostics.isEmpty()) { diagnostics.toString() }
        require(displays.map { it.first } == listOf("output", "progress", "answer", "stack", "plot")) {
            "Unexpected display channels: ${displays.map { it.first }}"
        }
        require("<b>raw</b>" in displays[0].second.toString())
        require("50.0%" in displays[1].second.toString())
        require("answer" in displays[2].second.toString())
    }
}
