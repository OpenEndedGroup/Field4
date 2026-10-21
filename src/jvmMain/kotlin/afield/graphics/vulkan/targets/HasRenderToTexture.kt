package afield.graphics.vulkan.targets

import afield.graphics.render.Module
import afield.graphics.render.ModulePointer

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
