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
 * A Gaussian-splat shader pair seeded into a box as editable starter source, so the splat shader is
 * live Field code rather than source hidden inside `GaussianSplatRenderer`.
 *
 * The two entry points are call-shaped macros, so their Kotlin bodies only throw and the work is in
 * the expansion: it writes the renderer's starter vertex and fragment source into the box's
 * `vertex` and `fragment` properties, compiles them, reflects their interface into generated
 * bindings, and rewrites the call to `_moduleHere`. What comes back is an ordinary graphics
 * module, with the reload actions and shader-error reporting every other shader box has, and
 * `GaussianSplatRenderer.Cloud` takes it as `suppliedRenderModule`.
 *
 * There are two of them because multiview is a different vertex shader and not a flag.
 * `gaussianSplatMultiviewModuleHere` seeds the variant that requires `GL_EXT_multiview` and selects
 * a model-view and projection matrix per eye from `gl_ViewIndex`; use it when the cloud draws into
 * a `RenderToTexture` whose `multiviewViewMask` is non-zero, and the mono one otherwise. The
 * fragment shader is the same in both cases.
 *
 * The seeding happens once. `macro_moduleHereWithDefaultShaders` writes the starter source only
 * where the box has no shader source of its own, so an edited shader survives re-running the macro.
 */

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

/**
 * The macro expansion behind [gaussianSplatModuleHere]: the ordinary graphics-module expansion,
 * with `GaussianSplatRenderer`'s mono splat shader pair as the source to seed.
 *
 * It leaves `macro_moduleHereWithDefaultShaders`'s `resolvesToCall` at its default, so the call
 * this rewrites to is `_moduleHere` rather than a runtime name of its own. The rewriter calls it;
 * box code does not.
 */
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_gaussianSplatModuleHere(args: List<String>): Insert? =
    macro_moduleHereWithDefaultShaders(
        args = args,
        vertex = GaussianSplatRenderer.splatVertexShader,
        fragment = GaussianSplatRenderer.splatFragmentShader
    )

/**
 * [macro_gaussianSplatModuleHere] with the multiview vertex shader, behind
 * [gaussianSplatMultiviewModuleHere].
 *
 * Only the vertex stage differs between the two: the fragment shader seeded here is the same
 * `splatFragmentShader`.
 */
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_gaussianSplatMultiviewModuleHere(args: List<String>): Insert? =
    macro_moduleHereWithDefaultShaders(
        args = args,
        vertex = GaussianSplatRenderer.splatMultiviewVertexShader,
        fragment = GaussianSplatRenderer.splatFragmentShader
    )

/**
 * Puts both splat macros into `MacroRegistry.callRewrite`, under the names box code writes.
 *
 * Nothing calls this by name. `@RunsAt(macroRegistration)` is found by a classpath scan of
 * `afield`, and the `macroRegistration` phase fires whenever a new root is made.
 */
@RunsAt(macroRegistration)
fun registerGaussianSplatModuleMacros() {
    callRewrite["gaussianSplatModuleHere"] = { box, _, args ->
        box.macro_gaussianSplatModuleHere(args)
    }
    callRewrite["gaussianSplatMultiviewModuleHere"] = { box, _, args ->
        box.macro_gaussianSplatMultiviewModuleHere(args)
    }
}
