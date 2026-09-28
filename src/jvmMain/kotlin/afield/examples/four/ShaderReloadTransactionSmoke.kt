package afield.examples.four

import afield.Box
import afield.four.Renderer
import afield.four.modules.Module
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.util.Options
import afield.four.util.Window
import afield.preamble
import afield.stdlib._fragment
import afield.stdlib.attachShader
import afield.stdlib.reloadShader
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.shader-reload.transaction
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.ShaderReloadTransactionSmoke
tags: [smoke, four, gpu, shaders, lifecycle]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "shader reload transaction PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/**
 * A full shader reload (`reloadShader`, the *Reload Shader* action) is a transaction: the new module is
 * built first, and only a module that actually went live rewrites the box's preamble and retires the
 * one it replaced.
 *
 * Three reloads against one box, rendering frames between them so the retirement goes through the
 * renderer's real deferred queue:
 *
 * 1. a GLSL compile error — `false`, old module still current and valid, preamble untouched, error on
 *    the shader property;
 * 2. GLSL that compiles but whose module cannot be built, because the texture resolver refuses the new
 *    sampler — `false`, same guarantees, and the resolver's reason visible on `code`. Before the fix this
 *    rewrote the preamble to the new interface, kept the old module and returned `true`;
 * 3. a successful reload that changes the push-constant interface — `true`, preamble now describes the
 *    new member, the old module is invalid at once and its pipeline is destroyed a few frames later
 *    rather than when the GC finds it.
 */
object ShaderReloadTransactionSmoke {
    private val VERTEX = """
        #version 450
        layout(location = 0) in vec3 pos;
        void main() { gl_Position = vec4(pos, 1.0); }
    """.trimIndent()

    private val FRAGMENT_A = """
        #version 450
        layout(push_constant) uniform Push { vec4 tintA; } pc;
        layout(location = 0) out vec4 outColor;
        void main() { outColor = pc.tintA; }
    """.trimIndent()

    // `tintB` is not a member of the block this declares.
    private val FRAGMENT_COMPILE_ERROR = """
        #version 450
        layout(push_constant) uniform Push { vec4 tintA; } pc;
        layout(location = 0) out vec4 outColor;
        void main() { outColor = pc.tintB; }
    """.trimIndent()

    // Valid GLSL; the module fails because the resolver below throws for `boom`.
    private val FRAGMENT_UNRESOLVABLE = """
        #version 450
        layout(push_constant) uniform Push { vec4 tintB; } pc;
        layout(set = 0, binding = 0) uniform sampler2D boom;
        layout(location = 0) out vec4 outColor;
        void main() { outColor = pc.tintB * texture(boom, vec2(0.5)); }
    """.trimIndent()

    private val FRAGMENT_B = """
        #version 450
        layout(push_constant) uniform Push { vec4 tintB; } pc;
        layout(location = 0) out vec4 outColor;
        void main() { outColor = pc.tintB; }
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        Options.doVulkanSDK()
        Options.enableVR = false
        Options.validate = true
        val window = Window("Shader reload transaction")
        val renderer = Renderer(window)
        println("validation active: ${renderer.instance.validationActive}")

        val errors = mutableListOf<String>()
        val box = Box("shader-reload-transaction").apply {
            name = "shader-reload-transaction"
            output.errorProxy = { message, property, line ->
                if (message != null) errors += "$property:$line:$message"
            }
        }

        fun frames(n: Int) = repeat(n) {
            window.pollEvents()
            renderer.render()
        }

        val pointer = Module(parameters(renderer) {
            name = "shader-reload-transaction"
            vertex = VERTEX
            fragment = FRAGMENT_A
            textureResolver = { texture ->
                if (texture.name == "boom") throw IllegalStateException("smoke resolver refuses texture 'boom'")
                null
            }
        }).pointer()
        check(pointer.current.valid) { "initial module did not build: $errors" }
        renderer += "shader-reload-transaction" to pointer

        try {
            // attachShader reloads once, which writes the preamble for interface A.
            box.attachShader(pointer, overwriteProperties = true)
            check(errors.isEmpty()) { "attach reported errors: $errors" }
            val moduleA = pointer.current
            val preambleA = box.preamble
            check("graphics-pc-structure:tintA" in preambleA) { "preamble lacks tintA after attach:\n$preambleA" }
            val preparedA = box._fragment
            frames(4)

            // 1. compile error
            box.setPropertyRaw("fragment", FRAGMENT_COMPILE_ERROR)
            check(!reloadShader(box)) { "a compile error reported success" }
            check(pointer.current === moduleA && moduleA.valid) { "a compile error replaced or invalidated the module" }
            check(box.preamble == preambleA) { "a compile error rewrote the preamble:\n${box.preamble}" }
            check(box._fragment == preparedA) { "a compile error left the failed source as the prepared stage" }
            check(errors.any { it.startsWith("fragment:") }) { "no compile error on the fragment property: $errors" }
            println("1. compile error: kept module, kept preamble, error ${errors.first { it.startsWith("fragment:") }.lines().first()}")
            errors.clear()
            frames(4)

            // 2. compiles, but the module cannot be built
            box.setPropertyRaw("fragment", FRAGMENT_UNRESOLVABLE)
            check(!reloadShader(box)) { "a module that could not be built reported success" }
            check(pointer.current === moduleA && moduleA.valid) { "a failed module build replaced or invalidated the module" }
            check(box.preamble == preambleA) { "a failed module build rewrote the preamble:\n${box.preamble}" }
            check("tintB" !in box.preamble) { "preamble describes an interface that never went live" }
            check(box._fragment == preparedA) { "a failed module build left its source as the prepared stage" }
            val buildError = errors.firstOrNull { it.startsWith("code:") && "smoke resolver refuses texture 'boom'" in it }
            checkNotNull(buildError) { "the resolver's reason did not reach the box's code property: $errors" }
            check("previous module is still running" in buildError) { "the error does not say the reload did not happen: $buildError" }
            println("2. module build failure: kept module, kept preamble, error ${buildError.lines().first()}")
            errors.clear()
            frames(4)

            // 3. success with a changed interface
            box.setPropertyRaw("fragment", FRAGMENT_B)
            check(reloadShader(box)) { "a good reload failed: $errors" }
            val moduleB = pointer.current
            check(moduleB !== moduleA && moduleB.valid) { "a good reload did not install a new module" }
            check("graphics-pc-structure:tintB" in box.preamble) { "preamble lacks tintB:\n${box.preamble}" }
            check("graphics-pc-structure:tintA" !in box.preamble) { "preamble still describes tintA:\n${box.preamble}" }
            check(!moduleA.valid) { "the replaced module was not retired" }
            check(moduleA.pipeline != 0L) { "the replaced module's pipeline was destroyed synchronously, under frames in flight" }
            var waited = 0
            while (moduleA.pipeline != 0L && waited < 200) {
                frames(1)
                waited++
            }
            check(moduleA.pipeline == 0L) { "the replaced module's pipeline was never destroyed through the retirement queue" }
            check(errors.isEmpty()) { "a good reload reported errors: $errors" }
            println("3. interface change: new module live, preamble updated, old pipeline retired after $waited frame(s)")
        } finally {
            try {
                pointer.current.cleanup()
                renderer.cleanup()
            } finally {
                window.cleanup()
            }
        }
        println("shader reload transaction PASSED")
        exitProcess(0)
    }
}
