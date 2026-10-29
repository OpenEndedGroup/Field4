package afield.graphics.vulkan.targets

import afield.graphics.render.Module
import afield.graphics.render.ModulePointer

/**
 * A [RenderToTexture] owner that modules can be attached to as if it were the target itself.
 *
 * [ElectronRTTView] implements [HasRenderToTexture] over the target it draws into, and
 * [WebRTCSink] forwards to the view it captures from, so a module reaches either of them the two
 * ways it reaches a [RenderToTexture]: `+view` inside `moduleHere { }`, or
 * `view += "name" to module`. The second form is the two operators here; the first is an operator
 * on `Parameters`, the `moduleHere { }` receiver.
 */

/**
 * Something that draws through a [RenderToTexture] it owns, such as `ElectronRTTView`, and can stand
 * in for it: `+view` inside `moduleHere { }` points the module at [rtt], and `view += "name" to module`
 * attaches the module to it.
 */
interface HasRenderToTexture {
    /** The target this object draws through. */
    val rtt: RenderToTexture
}

/** Attaches a module to [HasRenderToTexture.rtt], as `rtt += "name" to module` does. */
@JvmName("plusAssignPointer")
operator fun HasRenderToTexture.plusAssign(m: Pair<String, ModulePointer>) = rtt.plusAssign(m)

/** Attaches a module to [HasRenderToTexture.rtt], as `rtt += "name" to module` does. */
@JvmName("plusAssignModule")
operator fun HasRenderToTexture.plusAssign(m: Pair<String, Module>) = rtt.plusAssign(m)
