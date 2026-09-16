package afield.four.texture.mega

import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import afield.four.modules.ShaderSPIRVUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The seam between the generated GLSL and [MegaTexture.bind], checked without a GPU.
 *
 * `MegaTexture.bind` resolves three resources *by GLSL name* — the atlas sampler, the page table
 * and the feedback buffer — and a `Module` only asks for the names its shader actually declares.
 * Rename one on either side and nothing fails to compile in Kotlin; the module builds, the
 * resolver never fires, and the box gets "can't find anything to build a texture out of" at
 * runtime on a machine with a Vulkan device. So the contract is checked here: compile the real
 * template output with shaderc, introspect the SPIR-V, and assert the names, sets and bindings are
 * the ones the config promises.
 *
 * It also catches the ordinary thing, which is a syntax error in a Kotlin string.
 */
class MegaTextureShaderTest {

    private val layout = MegaTextureLayout(
        imageCount = 1500,
        levels = 7,
        atlasEdge = 2048,
        layersPerLevel = 2,
        maxBaseLevelLayers = 64,
        topPageWidth = 512      // the square 8..512 ladder
    )

    /** The rectangular ladder a film set gets: top page exactly one 1920x798 frame. */
    private val filmLayout = MegaTextureLayout(
        imageCount = 1639,
        levels = 8,
        atlasEdge = 2048,
        layersPerLevel = 2,
        maxBaseLevelLayers = 64,
        topPageWidth = 1920,
        topPageHeight = 798
    )
    private val config = MegaTextureConfig()

    private fun fragment(body: String): String = """
        #version 450
        ${megaAllSource(layout, config)}
        layout(location = 0) in vec2 ftc;
        layout(location = 1) flat in uint fid;
        layout(location = 0) out vec4 outColor;
        void main() {
        $body
        }
    """.trimIndent()

    private fun introspect(name: String, source: String, kind: ShaderKind): Introspection {
        val spirv = ShaderSPIRVUtils.compileShader(name, source, kind)
        assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "$name produced empty SPIR-V")
        val introspection = Introspection(source, kind, sourceName = name)
        assertTrue(introspection.valid, "$name did not introspect")
        return introspection
    }

    @Test
    fun theFragmentTemplateCompiles() {
        introspect(
            "megaTexture.frag",
            fragment("            outColor = megaTexture(fid, ftc);"),
            ShaderKind.FRAGMENT_SHADER
        )
    }

    @Test
    fun everyHelperTheTemplateAdvertisesIsCallable() {
        // Naming them in one shader is the cheapest way to keep the KDoc's list honest.
        introspect(
            "megaTexture-helpers.frag",
            fragment(
                """
                    int resident = megaResidentLevel(fid);
                    float aspect = megaAspect(fid);
                    vec4 mean = megaAverageColor(fid);
                    float pixels = megaScreenPixels(ftc);
                    int level = megaLevelForPixels(pixels);
                    megaRequest(fid, level);
                    vec4 page = megaSampleResident(fid, ftc);
                    outColor = mean * 0.0 + page + vec4(float(resident), aspect, float(level), 0.0) * 0.0;
                """.trimIndent()
            ),
            ShaderKind.FRAGMENT_SHADER
        )
    }

    /**
     * The vertex slot must not pull in anything that needs derivatives.
     *
     * `dFdx` in a vertex shader is a compile error, so this is the check that `mega.vertex` really
     * is the derivative-free subset rather than an alias for `mega.all`.
     */
    @Test
    fun theVertexSlotCompilesAsAVertexShader() {
        val source = """
            #version 450
            ${megaVertexSource(layout, config)}
            layout(location = 0) out vec2 ftc;
            layout(location = 1) flat out uint fid;
            void main() {
                fid = uint(gl_InstanceIndex);
                ftc = vec2(float(gl_VertexIndex & 1), float((gl_VertexIndex >> 1) & 1));
                gl_Position = vec4(ftc * vec2(megaAspect(fid), 1.0), 0.0, 1.0);
            }
        """.trimIndent()
        introspect("megaTexture.vert", source, ShaderKind.VERTEX_SHADER)
    }

    @Test
    fun theVertexSlotHasNoDerivatives() {
        val vertexOnly = megaVertexSource(layout, config)
        for (forbidden in listOf("dFdx", "dFdy", "fwidth")) {
            assertTrue(
                forbidden !in vertexOnly,
                "mega.vertex must not use $forbidden; that would only compile in a fragment stage"
            )
        }
        assertTrue("dFdx" in megaFragmentSource(layout, config), "the fragment slot is where derivatives live")
    }

    /**
     * The rectangular ladder compiles, and the constants it emits are the frame's own numbers.
     *
     * Worth its own case because the level maths changed shape with it: `megaLevelForPixels`
     * counts halvings down from the top page rather than shifts up from the base, and that is
     * arithmetic no Kotlin compiler checks.
     */
    @Test
    fun theFilmLadderCompilesAndCarriesItsOwnSize() {
        val source = """
            #version 450
            ${megaAllSource(filmLayout, config)}
            layout(location = 0) in vec2 ftc;
            layout(location = 1) flat in uint fid;
            layout(location = 0) out vec4 outColor;
            void main() { outColor = megaTexture(fid, ftc); }
        """.trimIndent()
        introspect("megaTexture-film.frag", source, ShaderKind.FRAGMENT_SHADER)

        val declarations = megaDeclarationsSource(filmLayout, config)
        assertTrue("megaTopPageWidth  = 1920.0" in declarations, declarations)
        assertTrue("megaTopPageHeight = 798.0" in declarations, declarations)
        assertTrue("megaBasePageWidth = 15.0" in declarations, declarations)
        assertTrue(
            "megaMinPageSize" !in declarations,
            "the square-ladder constant should be gone, not left to mislead: \n$declarations"
        )
    }

    @Test
    fun bindingsMatchTheConfig() {
        val introspection = introspect(
            "megaTexture.frag",
            fragment("            outColor = megaTexture(fid, ftc);"),
            ShaderKind.FRAGMENT_SHADER
        )

        val atlas = introspection.textures.firstOrNull { it.name == config.atlasName }
        assertNotNull(atlas, "no sampler named '${config.atlasName}'; found ${introspection.textures.map { it.name }}")
        assertEquals(config.descriptorSet, atlas.set)
        assertEquals(config.atlasBinding, atlas.binding)
        assertEquals(0, atlas.arrayCount, "the atlas is one sampler2DArray, not an array of samplers")

        val byName = introspection.ssbo.associateBy { it.name }
        val pages = byName[config.pageTableName]
        assertNotNull(pages, "no storage buffer named '${config.pageTableName}'; found ${byName.keys}")
        assertEquals(config.descriptorSet, pages.set)
        assertEquals(config.pageTableBinding, pages.binding)

        val wanted = byName[config.feedbackName]
        assertNotNull(wanted, "no storage buffer named '${config.feedbackName}'; found ${byName.keys}")
        assertEquals(config.descriptorSet, wanted.set)
        assertEquals(config.feedbackBinding, wanted.binding)
    }

    /**
     * Both buffers must introspect as runtime-sized, because that is what makes
     * `Module`'s `ssboSizer` — and therefore `MegaTexture.bind`'s override of it — get asked at
     * all. A fixed-size array would silently keep whatever size the GLSL declared.
     */
    @Test
    fun bothBuffersAreRuntimeSizedSoTheSizerIsConsulted() {
        val introspection = introspect(
            "megaTexture.frag",
            fragment("            outColor = megaTexture(fid, ftc);"),
            ShaderKind.FRAGMENT_SHADER
        )
        for (name in listOf(config.pageTableName, config.feedbackName)) {
            val ssbo = introspection.ssbo.first { it.name == name }
            assertEquals(0, ssbo.size, "$name should have no statically known size")
        }
    }

    @Test
    fun renamingInTheConfigRenamesInTheShader() {
        val renamed = MegaTextureConfig().apply {
            atlasName = "photoAtlas"
            pageTableName = "PhotoPages"
            feedbackName = "PhotoWanted"
            descriptorSet = 0
            atlasBinding = 20
            pageTableBinding = 21
            feedbackBinding = 22
        }
        val source = """
            #version 450
            ${megaAllSource(layout, renamed)}
            layout(location = 0) in vec2 ftc;
            layout(location = 1) flat in uint fid;
            layout(location = 0) out vec4 outColor;
            void main() { outColor = megaTexture(fid, ftc); }
        """.trimIndent()
        val introspection = introspect("megaTexture-renamed.frag", source, ShaderKind.FRAGMENT_SHADER)
        assertEquals(
            listOf("photoAtlas" to 20),
            introspection.textures.map { it.name to it.binding }
        )
        assertEquals(
            setOf("PhotoPages" to 21, "PhotoWanted" to 22),
            introspection.ssbo.map { it.name to it.binding }.toSet()
        )
    }

    @Test
    fun theTemplateMapCoversWhatItsDocumentationClaims() {
        val template = megaTextureTemplate(layout, config)
        assertEquals(
            setOf(
                "declarations", "functions", "fragment", "vertex", "all",
                "imageCount", "levels", "atlasEdge",
                "basePageWidth", "basePageHeight", "topPageWidth", "topPageHeight"
            ),
            template.keys
        )
        assertEquals("1500", template["imageCount"])
        assertEquals("512", template["topPageWidth"], "7 levels topping out at 512")
        assertEquals("8", template["basePageWidth"], "and starting at 8")
    }
}
