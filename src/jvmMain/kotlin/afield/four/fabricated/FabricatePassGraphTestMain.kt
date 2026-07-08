package afield.four.fabricated

/**
 * Tiny JVM smoke test for the pure ordered-pass data structure behind FabricateFrameGraph.
 * Run with the backdoor's run-jvm-main or any JVM launcher that includes jvmMain classes.
 */
object FabricatePassGraphTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val graph = FabricatePassGraph<String>(
            listOf(
                "gbuffer" to "G",
                "lighting" to "L",
                "aa" to "A"
            )
        )

        check(graph.names() == listOf("gbuffer", "lighting", "aa")) {
            "initial order was ${graph.names()}"
        }

        graph.insertAfter("bloom", "lighting", "B")
        check(graph.names() == listOf("gbuffer", "lighting", "bloom", "aa")) {
            "insertAfter order was ${graph.names()}"
        }

        graph.insertBefore("shadow", "gbuffer", "S")
        check(graph.names() == listOf("shadow", "gbuffer", "lighting", "bloom", "aa")) {
            "insertBefore order was ${graph.names()}"
        }

        graph.replace("bloom", "B2")
        check(graph["bloom"] == "B2") { "replace did not update bloom" }

        val removed = graph.remove("lighting")
        check(removed == "L") { "remove returned $removed" }
        check(graph.names() == listOf("shadow", "gbuffer", "bloom", "aa")) {
            "remove order was ${graph.names()}"
        }

        println("FabricatePassGraphTestMain ok: ${graph.names().joinToString(" -> ")}")
    }
}
