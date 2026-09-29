package afield.examples.four

import afield.Box
import afield.four.Renderer
import afield.four.modules.Module
import afield.four.modules.ShaderBindingChange
import afield.four.modules.ShaderBindingKind
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.util.Options
import afield.four.util.Window
import afield.preamble
import afield.stdlib.ShaderTransitionNotes
import afield.stdlib.attachShader
import afield.stdlib.linalg.Vector4f
import afield.stdlib.reloadShader
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.shader-reload.transitions
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.ShaderReloadTransitionSmoke
tags: [smoke, four, gpu, shaders, lifecycle]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "shader reload transitions PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/**
 * The reload-transition matrix, on a real device: one box, one graphics module, and a full reload
 * (`reloadShader`) per row, with frames rendered between them so buffers are written and retirements go
 * through the renderer's deferred queue. Each row checks what the module holds afterwards, what the
 * preamble says, which buffers and values were carried, and what the person was told.
 *
 * | row | shader change | expected |
 * | --- | --- | --- |
 * | 1 | compile error | `false`, module, preamble and buffers untouched, error on `fragment` |
 * | 2 | arithmetic only | kept: same buffer, values carried, no summary |
 * | 3 | `Light` renamed `Lamp`, same layout | renamed: same buffer and values; `code` line using `Light` marked |
 * | 4 | `Lamp` grows a `vec4 extra` | resized: new buffer, old retired, `gain` carried, `extra` zero |
 * | 5 | `color` vec4 → float | retyped: `color` dropped and said so, `gain` carried to its new offset |
 * | 6 | block `Extra` added | added |
 * | 7 | block `Extra` removed | removed, its buffer retired |
 * | 8 | every push constant removed | each removed, preamble drops them |
 *
 * A module that compiles but cannot be built is `four.shader-reload.transaction`'s row 2. A stage
 * added or removed cannot happen to a graphics module (it always has two); the classification is covered
 * by `ShaderInterfaceTransitionTest` on reflection alone.
 */
object ShaderReloadTransitionSmoke {
    private val VERTEX = """
        #version 450
        layout(location = 0) in vec3 pos;
        void main() { gl_Position = vec4(pos, 1.0); }
    """.trimIndent()

    private fun fragment(blocks: String, use: String, push: Boolean = true) = """
        #version 450
        $blocks
        ${if (push) "layout(push_constant) uniform Push { vec4 tint; } pc;" else ""}
        layout(location = 0) out vec4 outColor;
        void main() { outColor = $use${if (push) " + pc.tint" else ""}; }
    """.trimIndent()

    private val LIGHT = "layout(std140, set = 0, binding = 0) uniform Light { vec4 color; float gain; };"
    private val LAMP = "layout(std140, set = 0, binding = 0) uniform Lamp { vec4 color; float gain; };"
    private val LAMP_GROWN = "layout(std140, set = 0, binding = 0) uniform Lamp { vec4 color; float gain; vec4 extra; };"
    private val LAMP_RETYPED = "layout(std140, set = 0, binding = 0) uniform Lamp { float color; float gain; vec4 extra; };"
    private val EXTRA = "layout(std140, set = 0, binding = 1) uniform Extra { vec4 bias; };"

    @JvmStatic
    fun main(args: Array<String>) {
        Options.doVulkanSDK()
        Options.enableVR = false
        Options.validate = true
        val window = Window("Shader reload transitions")
        val renderer = Renderer(window)
        println("validation active: ${renderer.instance.validationActive}")

        val errors = mutableListOf<String>()
        val box = Box("shader-reload-transitions").apply {
            name = "shader-reload-transitions"
            output.errorProxy = { message, property, line ->
                if (message != null) errors += "$property:$line:$message"
            }
            setPropertyRaw("code", "val m = _shader!!\nm.Light.gain = 0.5f\nprintln(m.Light.color)\n")
        }

        fun frames(n: Int) = repeat(n) {
            window.pollEvents()
            renderer.render()
        }

        val pointer = Module(parameters(renderer) {
            name = "shader-reload-transitions"
            vertex = VERTEX
            fragment = fragment(LIGHT, "color * gain")
        }).pointer()
        check(pointer.current.valid) { "initial module did not build: $errors" }
        renderer += "shader-reload-transitions" to pointer

        fun uniform(name: String) = pointer.current.uniforms[name] as? Module.Uniform
            ?: error("no uniform `$name`; the module has ${pointer.current.uniforms.keys}")
        fun structure(name: String) = pointer.current.liveUniformStructures[name]
            ?: error("no structure `$name`; the module has ${pointer.current.liveUniformStructures.keys}")
        fun offset(block: String, member: String) = pointer.current.fi.ubos.single { it.name == block }.members.single { it.name == member }.offset
        fun readFloat(block: String, member: String): Float {
            var v = Float.NaN
            uniform(block).buffer.buffers[0].mapAndRead_bytes { v = getFloat(offset(block, member)) }
            return v
        }
        fun reload(fragment: String): Boolean {
            box.setPropertyRaw("fragment", fragment)
            errors.clear()
            return reloadShader(box)
        }
        fun transition() = checkNotNull(pointer.lastReloadReport?.transition) { "the reload recorded no transition" }
        fun summary() = ShaderTransitionNotes.summary(box, "fragment").orEmpty()

        try {
            box.attachShader(pointer, overwriteProperties = true)
            check(errors.isEmpty()) { "attach reported errors: $errors" }
            structure("Light")["color"] = Vector4f(1f, 0.5f, 0.25f, 1f)
            structure("Light")["gain"] = 0.5f
            frames(4)
            check(readFloat("Light", "gain") == 0.5f) { "gain never reached the buffer: ${readFloat("Light", "gain")}" }
            val lightSource = uniform("Light").source
            val module0 = pointer.current
            val preamble0 = box.preamble

            // 1. compile error
            check(!reload(fragment(LIGHT, "color * gainz"))) { "a compile error reported success" }
            check(pointer.current === module0 && module0.valid) { "1: the module changed" }
            check(box.preamble == preamble0) { "1: the preamble changed" }
            check(uniform("Light").source === lightSource) { "1: the buffer changed" }
            check(errors.any { it.startsWith("fragment:") }) { "1: no error on the fragment property: $errors" }
            println("1. compile error: nothing changed, error on fragment")

            // 2. kept
            check(reload(fragment(LIGHT, "color * gain * 0.5"))) { "2: failed: $errors" }
            check(transition().quiet) { "2: an arithmetic edit changed the interface: ${transition().summary()}" }
            check(uniform("Light").source === lightSource) { "2: the kept block's buffer was not carried" }
            check(structure("Light").valueFor("gain") == 0.5f) { "2: gain was not carried" }
            check(summary().isEmpty()) { "2: a summary for no change: ${summary()}" }
            frames(4)
            check(readFloat("Light", "gain") == 0.5f) { "2: gain is no longer in the buffer" }
            println("2. kept: same buffer, values carried, nothing said")

            // 3. renamed
            check(reload(fragment(LAMP, "color * gain"))) { "3: failed: $errors" }
            val lamp = transition().bindings.single { it.kind == ShaderBindingKind.UNIFORM_BLOCK }
            check(lamp.change == ShaderBindingChange.RENAMED && lamp.oldName == "Light") { "3: $lamp" }
            check(uniform("Lamp").source === lightSource) { "3: a renamed block did not keep its buffer" }
            check(structure("Lamp").valueFor("gain") == 0.5f) { "3: a renamed block did not keep its values" }
            check("renamed to `Lamp`" in summary()) { "3: summary: ${summary()}" }
            check("graphics-ubo:Lamp" in box.preamble && "graphics-ubo:Light" !in box.preamble) { "3: preamble:\n${box.preamble}" }
            val marked = errors.filter { it.startsWith("code:") && "Light" in it }
            check(marked.any { it.startsWith("code:2:") } && marked.any { it.startsWith("code:3:") }) { "3: code lines using Light not marked: $errors" }
            println("3. renamed: buffer and values carried; ${marked.size} code line(s) marked; ${summary()}")
            frames(4)

            // 4. resized
            check(reload(fragment(LAMP_GROWN, "color * gain + extra"))) { "4: failed: $errors" }
            val grown = transition().bindings.single { it.kind == ShaderBindingKind.UNIFORM_BLOCK }
            check(grown.change == ShaderBindingChange.RESIZED) { "4: $grown" }
            val grownSource = uniform("Lamp").source
            check(grownSource !== lightSource) { "4: a grown block kept its old, smaller buffer" }
            check(lightSource!!.result!!.buffers.all { it.isReleased }) { "4: the old buffer was not retired" }
            check(structure("Lamp").valueFor("gain") == 0.5f) { "4: gain was not re-applied" }
            frames(4)
            check(readFloat("Lamp", "gain") == 0.5f) { "4: gain did not reach the new buffer" }
            check(readFloat("Lamp", "extra") == 0f) { "4: the new member is not zero" }
            check("buffer reallocated" in summary() && "`extra` added" in summary()) { "4: summary: ${summary()}" }
            println("4. resized: new buffer, old retired, gain re-applied, extra zero; ${summary()}")

            // 5. retyped member
            check(reload(fragment(LAMP_RETYPED, "vec4(color * gain) + extra"))) { "5: failed: $errors" }
            check(structure("Lamp").valueFor("color") == null) { "5: a vec4 was carried into a float" }
            check(structure("Lamp").valueFor("gain") == 0.5f) { "5: gain was not carried" }
            check("`Lamp.color` dropped" in summary()) { "5: the dropped value was not reported: ${summary()}" }
            check("`color` vec4→float" in summary()) { "5: summary: ${summary()}" }
            frames(4)
            check(offset("Lamp", "gain") != 16) { "5: the fixture did not move gain" }
            check(readFloat("Lamp", "gain") == 0.5f) { "5: gain is not at its new offset" }
            check(errors.any { it.startsWith("code:3:") && "color" in it }) { "5: the code line using color was not marked: $errors" }
            println("5. retyped: color dropped and reported, gain at its new offset; ${summary()}")

            // 6. added
            check(reload(fragment("$LAMP_RETYPED\n$EXTRA", "vec4(color * gain) + extra + bias"))) { "6: failed: $errors" }
            check(transition().of(ShaderBindingKind.UNIFORM_BLOCK, "Extra")?.change == ShaderBindingChange.ADDED) { "6: ${transition()}" }
            check("graphics-ubo:Extra" in box.preamble) { "6: preamble lacks Extra" }
            frames(4)
            check(readFloat("Extra", "bias") == 0f) { "6: an added block is not zero" }
            val extraSource = uniform("Extra").source!!
            println("6. added: ${summary()}")

            // 7. removed
            check(reload(fragment(LAMP_RETYPED, "vec4(color * gain) + extra"))) { "7: failed: $errors" }
            check(transition().bindings.any { it.name == "Extra" && it.change == ShaderBindingChange.REMOVED }) { "7: ${transition()}" }
            check(extraSource.result!!.buffers.all { it.isReleased }) { "7: a removed block's buffer was not retired" }
            check("graphics-ubo:Extra" !in box.preamble) { "7: preamble still has Extra" }
            check("uniform block `Extra` removed" in summary()) { "7: summary: ${summary()}" }
            println("7. removed: buffer retired; ${summary()}")
            frames(4)

            // 8. every push constant removed
            check(reload(fragment(LAMP_RETYPED, "vec4(color * gain) + extra", push = false))) { "8: failed: $errors" }
            check(transition().bindings.filter { it.kind == ShaderBindingKind.PUSH_CONSTANT }.map { it.change } == listOf(ShaderBindingChange.REMOVED)) { "8: ${transition()}" }
            check("graphics-pc-structure:tint" !in box.preamble) { "8: preamble still has tint" }
            check("push constant `tint` removed" in summary()) { "8: summary: ${summary()}" }
            frames(4)
            println("8. push constants removed: ${summary()}")
        } finally {
            try {
                pointer.current.cleanup()
                renderer.cleanup()
            } finally {
                window.cleanup()
            }
        }
        println("shader reload transitions PASSED")
        exitProcess(0)
    }
}
