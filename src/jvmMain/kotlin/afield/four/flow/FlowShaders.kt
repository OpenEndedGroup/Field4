package afield.four.flow

import afield.four.modules.ShaderIncludeResolution
import afield.stdlib.registerShaderIncludeRoot

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
