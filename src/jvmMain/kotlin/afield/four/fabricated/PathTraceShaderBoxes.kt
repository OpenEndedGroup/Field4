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
