package afield.runtime.kotlin

import kotlin.script.experimental.api.ResultValue

/*
FIELD_TEST
id: kotlin.repl-variable-rebinding.smoke
runner: jvm-main
entrypoint: afield.runtime.kotlin.ReplVariableRebindingSmokeTest
tags: [smoke, kotlin, k2, repl, regression]
timeoutSec: 240
expect:
  exitCode: 0
  stdoutContains:
    - "REPL variable rebinding smoke passed"
*/
/** Real K2 closure checks for declaration-time resets and the snapshots they must preserve. */
object ReplVariableRebindingSmokeTest {
    private fun KotlinInterface2.run(code: String): Any? {
        var failure: String? = null
        val result = execAndWait(
            code,
            { failure = "compile: $it" },
            { failure = "evaluate: $it" },
            { failure = "exception: $it" },
            emptyList(),
        )
        check(failure == null) { "$code: $failure" }
        return (result as? ResultValue.Value)?.value
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val ki = KotlinInterface2()
        try {
            ki.run("var x = 10; val tick = { ++x }; val readFirstX = { x }")
            check(ki.run("tick()") == 11)
            ki.run("val readThrough = { readFirstX() }")
            check(ki.run("readThrough()") == 11)
            check(ki.run("tick()") == 12) { "Cached readers must not fork mutable backing classes" }
            ki.run("var x = 20; val readSecondX = { x }")
            check(ki.run("tick()") == 21)
            check(ki.run("readFirstX()") == 21)
            check(ki.run("x") == 20) { "Declaration propagation is not continuous shared storage" }
            ki.run("var x = 30")
            check(ki.run("readFirstX()") == 30)
            check(ki.run("readSecondX()") == 30) { "Every older compatible slot must reset" }
            check(ki.run("tick()") == 31)
            ki.run("var x = 30")
            check(ki.run("tick()") == 31) { "Repeated declarations must reset older closures again" }
            ki.run("private var URL = 1; val readURL = { URL }")
            ki.run("readURL()")
            ki.run("private var URL = 2")
            check(ki.run("readURL()") == 2)
            ki.run("private var URL = 2")
            check(ki.run("readURL()") == 2)
            println("mutable closures: repeated declarations reset every older compatible slot")

            ki.run("var maybe: String? = \"before\"; val readMaybe = { maybe }")
            ki.run("var maybe: String? = null")
            check(ki.run("readMaybe()") == null)
            ki.run("var typed = 7; val readInt = { typed }")
            ki.run("var typed = \"seven\"; val readString = { typed }")
            ki.run("var typed = 9")
            check(ki.run("readInt()") == 9)
            check(ki.run("readString()") == "seven")
            println("types: nullable resets work; incompatible older bindings retain their values")

            ki.run("val gain = 2.0; val readGain = { gain }")
            ki.run("val gain = 3.0")
            check(ki.run("readGain()") == 2.0)
            ki.run("var resource = mutableListOf(1); val ownedResource = resource; val cleanup = { ownedResource.clear() }")
            ki.run("var resource = mutableListOf(2)")
            ki.run("cleanup()")
            check(ki.run("resource.single()") == 2) { "Old cleanup must not target the replacement" }
            check(ki.run("ownedResource.isEmpty()") == true)
            println("snapshots: val captures and exact-instance cleanup stay fixed")

            ki.run("var delegated by kotlin.properties.Delegates.observable(1) { _, _, _ -> }; val readDelegate = { delegated }")
            ki.run("var delegated by kotlin.properties.Delegates.observable(2) { _, _, _ -> }")
            check(ki.run("readDelegate()") == 1)
            println("delegates: declaration reset does not invoke arbitrary delegate setters")

            val separate = KotlinInterface2()
            try {
                separate.run("var x = 100")
                check(ki.run("readFirstX()") == 31)
            } finally {
                separate.dispose()
            }

            ki.run("var stable = 4; val readStable = { stable }")
            val readStable = ki.context.vars.getValue("readStable").value as Function0<*>
            var failed = false
            ki.execAndWait(
                "var stable: Int = error(\"intentional failed initializer\")",
                { error("Expected evaluation failure, got compile failure: $it") },
                { failed = true },
                { failed = true },
                emptyList(),
            )
            check(failed)
            check(readStable.invoke() == 4) { "Failed initialization changed a running closure" }
            println("failure: unsuccessful declarations preserve the old value")
            println("REPL variable rebinding smoke passed")
        } finally {
            ki.dispose()
        }
    }
}
