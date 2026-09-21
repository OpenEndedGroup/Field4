package afield.four.fabricated

import afield.Box
import afield.RunsAt
import afield.macroRegistration
import afield.macros.MacroRegistry
import afield.four.Renderer
import afield.four.modules.*
import afield.four.targets.RenderToTexture
import afield.four.util.Camera
import afield.stdlib._compute
import afield.stdlib._computeModuleHere
import afield.stdlib.macro_computeModuleHereWithDefaultShader
import afield.stdlib.shaderTemplate

/**
 * `pathTracingHere`: a path tracer whose material function is the box's own editable compute
 * shader, authored and hot-reloaded exactly like any other Field shader.
 *
 * The integrator, `trace.comp`, calls one GLSL function at every surface hit,
 * `shadeMaterial(Material, PathTraceSurface)`, and the packaged copy of it returns its argument
 * unchanged. This file puts an editable copy of that function in the box, so a material is written
 * where shader code is normally written and reloads through the ordinary Reload Compute Shader
 * action. Replacing the *whole* integrator instead is the other direction, and that is
 * `PathTracingRenderer.reloadIntegrator`.
 *
 * Two comment markers in the packaged source are what make that possible. `pathTraceTemplates`
 * cuts it at `// MATERIAL_BEGIN` and `// MATERIAL_END` and puts the halves into the box's
 * `shaderTemplate` map as `pathTracing.preamble` and `pathTracing.integrator`, so the shader the
 * macro seeds is those two template slots with an identity `shadeMaterial` between them to edit.
 * Everything such a function needs is already in the preamble: the `Material` and
 * `PathTraceSurface` structs, the frame uniform, and the buffer references the scene is read
 * through.
 *
 * The material shader is compiled twice, once at expansion and once on the first frame, and which
 * source each of those sees is the thing to understand before reading `_pathTracingHere`. A macro
 * compiles at expansion time, because the reflected interface is what the generated Kotlin
 * accessors are built from, and at that moment no renderer has been resolved and no device asked
 * whether it supports ray queries. So the templates are seeded from the portable integrator, whose
 * reflection differs from the ray-query one only by an optional acceleration-structure binding;
 * then `_pathTracingHere` re-seeds them for the device it actually found and edits the
 * `HARDWARE_QUERY` define in the already-seeded `_compute` source, rather than recompiling through
 * the macro. Every later reload goes through the normal Field path and gets the device-specific
 * template.
 */

/** A named tracer whose material function lives in this box's normal compute shader property. */
@Suppress("UNUSED_PARAMETER")
fun Box.pathTracingHere(
    scene: PathTraceScene, environment: PathTraceEnvironment,
    width: Int = 640, height: Int = 360, camera: Camera? = null,
    name: String = "path-tracer", target: RenderToTexture? = null,
    backend: PathTracingRenderer.Backend = PathTracingRenderer.Backend.AUTO,
    configure: Parameters.() -> Unit = {}
): PathTracingRenderer = throw MacroRegistry.MacroNotExpandedException("pathTracingHere must be expanded as a macro")

private fun Box.pathTraceTemplates(hardware: Boolean) {
    val source = PathTracingRenderer.shader(hardware)
    shaderTemplate["pathTracing.preamble"] = source.substringBefore("// MATERIAL_BEGIN")
    shaderTemplate["pathTracing.integrator"] = source.substringAfter("// MATERIAL_END")
}

/**
 * What `pathTracingHere` rewrites to, and where the device is finally consulted: the [Renderer]
 * comes from the ambient context, hardware traversal is taken whenever the device advertises ray
 * queries and `backend` has not pinned the software BVH, and the tracer is built with this box's
 * compute module as its material module.
 *
 * It edits the *prepared* source rather than the property: `_compute` is the box's `compute` text
 * after template expansion, which is what makes flipping `HARDWARE_QUERY` a string edit on real
 * GLSL. A box over which no prepare pass has run has no `_compute` to edit, so this is called from
 * the expansion the macro writes and not by hand.
 */
fun Box._pathTracingHere(
    scene: PathTraceScene, environment: PathTraceEnvironment,
    width: Int = 640, height: Int = 360, camera: Camera? = null,
    name: String = "path-tracer", target: RenderToTexture? = null,
    backend: PathTracingRenderer.Backend = PathTracingRenderer.Backend.AUTO,
    configure: Parameters.() -> Unit = {}
): PathTracingRenderer {
    val renderer: Renderer = fromContext()
    val hardware = backend != PathTracingRenderer.Backend.COMPUTE_BVH && renderer.device.supportsRayQuery
    pathTraceTemplates(hardware)
    // Initial macro reflection is portable; only the optional AS binding differs on a query device.
    // Subsequent reloads expand the device-specific template above through the ordinary Field path.
    _compute = _compute!!.replace("#define HARDWARE_QUERY", "").let {
        if (hardware) it.replace("#version 460", "#version 460\n#define HARDWARE_QUERY") else it
    }
    return PathTracingRenderer(renderer, scene, environment, width, height, camera ?: Camera(renderer), backend, name, target) { p ->
        p.configure()
        _computeModuleHere(p)
    }.install()
}

/**
 * Registers the `pathTracingHere` rewrite, once per new root. The default shader it plants is the
 * preamble slot, a `shadeMaterial` that returns its material unchanged, and the integrator slot,
 * with a comment saying where push constants and includes go.
 */
@RunsAt(macroRegistration)
fun registerPathTraceShaderBoxes() {
    MacroRegistry.callRewrite["pathTracingHere"] = { box, _, args ->
        box.pathTraceTemplates(false)
        box.macro_computeModuleHereWithDefaultShader(args, """
            {{ pathTracing.preamble }}

            // Add push constants, shared includes, and material functions here.
            Material shadeMaterial(Material material, PathTraceSurface surface) {
                return material;
            }

            {{ pathTracing.integrator }}
        """.trimIndent(), "_pathTracingHere")
    }
}
