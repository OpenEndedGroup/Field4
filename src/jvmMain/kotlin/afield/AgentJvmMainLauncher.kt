package afield

import java.lang.reflect.Modifier
import kotlin.system.exitProcess

object AgentJvmMainLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: afield.AgentJvmMainLauncher <entrypoint> [args...]")
            exitProcess(2)
        }

        val entrypoint = args.first()
        val forwardedArgs = args.drop(1).toTypedArray()
        val exitCode = runCatching {
            invokeEntrypoint(entrypoint, forwardedArgs)
        }.getOrElse { throwable ->
            System.err.println("AgentJvmMainLauncher failed for $entrypoint: ${throwable.message}")
            throwable.printStackTrace(System.err)
            1
        }
        exitProcess(exitCode)
    }

    private fun invokeEntrypoint(entrypoint: String, args: Array<String>): Int {
        val clazz = Class.forName(entrypoint)

        if (invokeMainIfPresent(clazz, args)) {
            return 0
        }

        val runTests = invokeRunTestsIfPresent(clazz)
        if (runTests != null) {
            return if (runTests) 0 else 1
        }

        error("No supported main() or runTests() entrypoint found on $entrypoint")
    }

    private fun invokeMainIfPresent(clazz: Class<*>, args: Array<String>): Boolean {
        val methods = clazz.methods.filter { it.name == "main" }
        for (method in methods) {
            val target = invocationTarget(clazz, method.modifiers)
            when (method.parameterCount) {
                0 -> {
                    method.invoke(target)
                    return true
                }

                1 -> {
                    val parameter = method.parameterTypes[0]
                    if (parameter.isArray && parameter.componentType == String::class.java) {
                        method.invoke(target, args)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun invokeRunTestsIfPresent(clazz: Class<*>): Boolean? {
        val method = clazz.methods.firstOrNull {
            it.name == "runTests" && it.parameterCount == 0
        } ?: return null
        val target = invocationTarget(clazz, method.modifiers)
        val result = method.invoke(target)
        return when (result) {
            null -> true
            is Boolean -> result
            else -> true
        }
    }

    private fun invocationTarget(clazz: Class<*>, modifiers: Int): Any? {
        if (Modifier.isStatic(modifiers)) {
            return null
        }
        return clazz.getField("INSTANCE").get(null)
    }
}
