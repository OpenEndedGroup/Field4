package afield.runtime.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlslFormatterCommentTest {
    @Test
    fun restoresDoubleDigitCommentIndicesWithoutPrefixReplacement() {
        val comments = (0..24).map { "// distinct note $it" }
        val result = formatGlslCode(comments.joinToString("\n") + "\nvoid main(){}\n")
        assertEquals(comments, result.lineSequence().filter { it.startsWith("//") }.toList())
        assertEquals(result, formatGlslCode(result))
    }

    @Test
    fun preservesBlankLineAfterVersionDirective() {
        val result = formatGlslCode("#version 450\n\nvoid main(){float x=1;}\n")
        assertTrue(result.startsWith("#version 450\n\nvoid main()"))
        assertEquals(result, formatGlslCode(result))
    }

    @Test
    fun preservesAlignedBlockCommentAndIgnoresItsBraces() {
        val table = "/*\n * member      units\n * position    metres  { }\n * motion      pixels\n */"
        val result = formatGlslCode("$table\nvoid main(){float x=1;}\n")
        assertTrue(result.startsWith(table + "\n"))
        assertTrue(result.contains("void main() {\n    float x=1;\n}"))
        assertEquals(result, formatGlslCode(result))
    }

    @Test
    fun keepsTrailingCommentsWithTheirStatements() {
        val result = formatGlslCode("struct S { uint a; // bytes\nuint b; // steps\n};\n")
        assertTrue(result.contains("    uint a; // bytes\n    uint b; // steps\n"))
        assertEquals(result, formatGlslCode(result))
    }

    @Test
    fun trailingCommentDoesNotHideOpeningOrClosingBrace() {
        val result = formatGlslCode("void main(){ // frame\nif(true){ // branch\nfloat x=1;\n} // branch end\n} // end\n")
        assertTrue(result.contains("    if(true) { // branch\n        float x=1;\n    } // branch end\n} // end"))
        assertEquals(result, formatGlslCode(result))
    }

    @Test
    fun preservesLiteralSpacesAndCommentLikeText() {
        val source = "#include \"folder  name//file.glsl\"\nvoid main(){float x=1;}\n"
        val result = formatGlslCode(source)
        assertTrue(result.startsWith("#include \"folder  name//file.glsl\"\n"))
        assertEquals(result, formatGlslCode(result))
    }

    @Test
    fun placeholderLikeSourceDoesNotCollide() {
        val source = "//FIELD_GLSL_PROTECTED_0\nvoid main(){float x=1; //FIELD_GLSL_PROTECTED_1\n}\n"
        val result = formatGlslCode(source)
        assertTrue(result.contains("//FIELD_GLSL_PROTECTED_0\n"))
        assertTrue(result.contains("float x=1; //FIELD_GLSL_PROTECTED_1\n"))
        assertEquals(result, formatGlslCode(result))
    }
}
