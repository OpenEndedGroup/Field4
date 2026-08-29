@file:Suppress("unused")

package afield.four.fabricated

import afield.Box
import afield.RunsAt
import afield.four.modules.ModulePointer
import afield.four.modules.Parameters
import afield.macroRegistration
import afield.macros.Insert
import afield.macros.MacroRegistry
import afield.macros.MacroRegistry.callRewrite
import afield.stdlib.macro_moduleHereWithDefaultShaders
import kotlinx.coroutines.DelicateCoroutinesApi

/**
 * A box-owned Gaussian-splat graphics module, initially seeded with Field's built-in mono shader.
 *
 * This follows the same path as `plyModelHere`: the macro copies the starter vertex/fragment pair
 * into this box, compiles their reflected interface, and rewrites the call to `_moduleHere`. From
 * then on the shader is ordinary live Field source rather than source hidden inside the renderer.
 */
fun Box.gaussianSplatModuleHere(p: Parameters.() -> Unit): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException(
        "gaussianSplatModuleHere should not be callable directly, but should be expanded as a macro"
    )
}

/**
 * The multiview counterpart to [gaussianSplatModuleHere]. Use this for a `RenderToTexture` whose
 * `multiviewViewMask` is non-zero; the seeded vertex shader exposes left/right model-view and
 * projection matrices and selects them with `gl_ViewIndex`.
 */
fun Box.gaussianSplatMultiviewModuleHere(p: Parameters.() -> Unit): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException(
        "gaussianSplatMultiviewModuleHere should not be callable directly, but should be expanded as a macro"
    )
}

@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_gaussianSplatModuleHere(args: List<String>): Insert? =
    macro_moduleHereWithDefaultShaders(
        args = args,
        vertex = GaussianSplatRenderer.splatVertexShader,
        fragment = GaussianSplatRenderer.splatFragmentShader
    )

@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_gaussianSplatMultiviewModuleHere(args: List<String>): Insert? =
    macro_moduleHereWithDefaultShaders(
        args = args,
        vertex = GaussianSplatRenderer.splatMultiviewVertexShader,
        fragment = GaussianSplatRenderer.splatFragmentShader
    )

@RunsAt(macroRegistration)
fun registerGaussianSplatModuleMacros() {
    callRewrite["gaussianSplatModuleHere"] = { box, _, args ->
        box.macro_gaussianSplatModuleHere(args)
    }
    callRewrite["gaussianSplatMultiviewModuleHere"] = { box, _, args ->
        box.macro_gaussianSplatMultiviewModuleHere(args)
    }
}
