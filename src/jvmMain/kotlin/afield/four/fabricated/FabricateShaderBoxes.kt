package afield.four.fabricated

import afield.Box
import afield.Rect
import afield.SessionInformation
import afield.box
import afield.four.modules.ModulePointer
import afield.stdlib.attachShader
import afield.thisSessionInfo
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Expose Fabricate's built-in shaders the same way user-authored shaders work: each pass becomes an
 * editable box with `vertex`/`fragment` GLSL tabs, live introspection, and `alt r` hot reload.
 *
 * Nothing here is new low-level machinery — it reuses [Box.attachShader], which installs the reload
 * actions, copies the module's source into the box's editable properties, and (through `reloadShader`)
 * rebuilds by copying the module's existing `Parameters`, so the renderer's own `textureResolver`,
 * attachment formats, and push-constant seeding survive every edit. Only the GLSL source changes.
 */

/**
 * All built-in graphics [ModulePointer]s in this bundle, keyed by a stable pass name. Includes the
 * deferred passes, the post-stack effects (motion blur, FXAA, plus any tone-map/bloom/user effects
 * added so far), and any per-material textured g-buffer variants registered by glTF import. Modules
 * are de-duplicated by identity, so passes that also appear in the post stack are listed once.
 */
fun StandardRenderer.Bundle.shaderModules(): LinkedHashMap<String, ModulePointer> {
    val out = LinkedHashMap<String, ModulePointer>()
    val seen = Collections.newSetFromMap(IdentityHashMap<ModulePointer, Boolean>())
    fun add(name: String, module: ModulePointer) {
        if (seen.add(module)) out[name] = module
    }

    add(FabricateFrameGraph.PASS_GBUFFER, gBufferModule)
    add(FabricateFrameGraph.PASS_SHADOW, shadowModule)
    add(FabricateFrameGraph.PASS_AO, aoModule)
    add(FabricateFrameGraph.PASS_LIGHTING, lightingModule)
    add("std-transparent-copy", transparentCopyModule)
    add(FabricateFrameGraph.PASS_TRANSPARENCY, transparentModule)
    add(FabricateFrameGraph.PASS_MOTION_BLUR, motionBlurModule)
    add(FabricateFrameGraph.PASS_ANTI_ALIAS, antiAliasModule)

    // Textured g-buffer variants created during glTF import live on the g-buffer target.
    gBuffer.modules.forEach { (name, module) ->
        if (module !== gBufferModule) add("std-gbuffer/$name", module)
    }

    // Post-stack effects (tone mapping, bloom, user shader effects). Motion blur / FXAA reuse the
    // modules already added above and are skipped by the identity de-dup.
    postStack.effects().forEach { effect ->
        add("std-post/${effect.name}", effect.module)
    }

    return out
}

/**
 * CPU-only view of the built-in shader sources, for inspection/diffing without a session or GPU.
 * Returns pass name -> (vertex source, fragment source).
 */
fun StandardRenderer.Bundle.shaderSources(): LinkedHashMap<String, Pair<String, String>> {
    val out = LinkedHashMap<String, Pair<String, String>>()
    shaderModules().forEach { (name, module) ->
        val params = module.current.parameters
        out[name] = params.vertex to params.fragment
    }
    return out
}

/**
 * Bind every built-in shader in this bundle to its own editable box (see [shaderModules]). Each box
 * is named `"$namePrefix/$passName"`, gets the pass's GLSL in its `vertex`/`fragment` tabs, and can
 * be edited and hot-reloaded with `alt r` (or ABI-preserving `alt shift enter`) like any user shader.
 *
 * Call this in a live session after the bundle (and any post effects / glTF you want to expose) are
 * set up; re-calling it picks up newly added modules and leaves existing edits intact. Boxes are laid
 * out in a simple grid starting at [origin]. Returns the pass-name -> box map.
 */
fun StandardRenderer.Bundle.bindShadersToBoxes(
    namePrefix: String = "fabricate",
    root: SessionInformation = thisSessionInfo.get(),
    origin: Rect = Rect(40.0, 40.0, 340.0, 200.0),
    columns: Int = 3,
    gap: Double = 24.0
): LinkedHashMap<String, Box> {
    require(columns > 0) { "columns must be positive" }
    val boxes = LinkedHashMap<String, Box>()
    shaderModules().entries.forEachIndexed { index, (name, module) ->
        val id = "$namePrefix/$name"
        val b = box(id, root = root)
        b.name = "$namePrefix · $name"
        val col = index % columns
        val row = index / columns
        b.frame = Rect(
            origin.x + col * (origin.w + gap),
            origin.y + row * (origin.h + gap),
            origin.w,
            origin.h
        )
        b.attachShader(module)
        boxes[name] = b
    }
    return boxes
}

/**
 * Bind a single built-in module to a named box. Useful when you only want to hack on one pass, e.g.
 * `bundle.bindShaderToBox("std-lighting", bundle.lightingModule)`.
 */
fun StandardRenderer.Bundle.bindShaderToBox(
    name: String,
    module: ModulePointer,
    namePrefix: String = "fabricate",
    root: SessionInformation = thisSessionInfo.get(),
    frame: Rect = Rect(40.0, 40.0, 340.0, 200.0)
): Box {
    val b = box("$namePrefix/$name", root = root)
    b.name = "$namePrefix · $name"
    b.frame = frame
    b.attachShader(module)
    return b
}
