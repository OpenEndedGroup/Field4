/**
 * Packaged GLSL helpers (`grain.glsl`, `strokes.glsl`, `motion.glsl`, `tiles.glsl`) for flow
 * shaders, reachable as `#include "flow/..."`.
 *
 * The include resolver in `shaders.kt` already falls back to [FlowShaders.source] for `flow/`
 * paths, so a document needs no setup; [FlowShaders.install] registers the root explicitly,
 * replacing any custom `flow` registration. Passes, resources and artistic controls stay in
 * editable boxes.
 */

package afield.simulation.flow

import afield.graphics.shader.ShaderIncludeResolution
import afield.graphics.shader.registerShaderIncludeRoot

/** Small shader functions; passes, resources and artistic controls remain editable boxes. */
object FlowShaders {
    private val names = setOf("grain.glsl", "strokes.glsl", "motion.glsl", "tiles.glsl")
    fun source(name: String): String {
        require(name in names) { "Unknown flow shader helper: $name" }
        return checkNotNull(javaClass.getResourceAsStream("/afield/four/flow/$name")) {
            "Missing packaged flow shader: $name"
        }.bufferedReader().use { it.readText() }
    }
    /** Explicitly register the packaged root, replacing a custom "flow" registration.
     * Ordinary includes already have a built-in fallback, so documents need no setup call.
     */
    fun install() {
        registerShaderIncludeRoot("flow") { request ->
            if (request.includePath in names)
                ShaderIncludeResolution("flow/${request.includePath}", source(request.includePath))
            else null
        }
    }
}
