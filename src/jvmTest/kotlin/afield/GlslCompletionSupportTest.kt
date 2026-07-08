package afield

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlslCompletionSupportTest {
    @Test
    fun completesVulkanTextureSizeBuiltin() {
        val completion = completeFragmentAtMarker(
            """
            layout(set = 0, binding = 0) uniform sampler2D sourceTex;

            void main() {
                ivec2 size = textureS/*caret*/
            }
            """.trimIndent()
        )

        assertNotNull(completion.metadata.firstOrNull { it.text == "textureSize" })
    }

    @Test
    fun completesDeclaredGlslFunctions() {
        val completion = completeFragmentAtMarker(
            """
            float ripple(vec2 uv, float phase) {
                return sin(uv.x + phase);
            }

            vec3 shadeNormal(in vec3 normal);

            void main() {
                vec3 color = shadeN/*caret*/
            }
            """.trimIndent()
        )

        val shadeNormal = completion.metadata.firstOrNull { it.text == "shadeNormal" }
        assertNotNull(shadeNormal)
        assertTrue(shadeNormal.tail.contains("local function(vec3)", ignoreCase = true), shadeNormal.tail)
        assertTrue(shadeNormal.documentation.contains("vec3 shadeNormal(in vec3 normal)"), shadeNormal.documentation)
    }

    private fun completeFragmentAtMarker(sourceWithMarker: String): Completion {
        val marker = "/*caret*/"
        val position = sourceWithMarker.indexOf(marker)
        require(position >= 0) { "Missing $marker" }
        val source = sourceWithMarker.replace(marker, "")
        return GlslCompletionSupport.complete(
            Box("glsl-completion-test"),
            CompletionRequest(
                uid = "glsl-completion-test",
                line = 1,
                allCode = source,
                column = 1,
                position = position,
                property = "fragment"
            )
        )
    }
}
