package afield.runtime.completion

import kotlin.test.Test
import kotlin.test.assertEquals

/** Several declarations on one line, as the generated linalg writes its members: every one is indexed. */
class KotlinSourceIndexSameLineTest {
    @Test fun everyMemberOnASharedLineIsIndexedWithItsOwner() {
        val text = """
            class Matrix4f {
                fun rotate(q:Quaternionfc):Matrix4f=mul(Matrix4f().rotation(q));fun rotation(q:Quaternionfc):Matrix4f{val r=Matrix3f().rotation(q);return set(r)}
                fun rotate(angle:Float,x:Float,y:Float,z:Float):Matrix4f=rotate(Quaternionf().setAngleAxis(angle,x,y,z)); private fun scratch() = 1
            }
            val a = "x;fun notADeclaration()" ; val b = 2 // ;fun alsoNot()
        """.trimIndent()
        val names = KotlinSourceIndex.parseTopLevelDefinitions(text).map { it.name to it.line }
        assertEquals(listOf("Matrix4f" to 1, "Matrix4f.rotate" to 2, "Matrix4f.rotation" to 2, "Matrix4f.rotate" to 3,
            "Matrix4f.scratch" to 3, "a" to 5, "b" to 5), names)
        assertEquals("private", KotlinSourceIndex.parseTopLevelDefinitions(text).single { it.name == "Matrix4f.scratch" }.visibility)
    }

    @Test fun aSemicolonInsideABodyOrStringIsNotADeclarationBoundary() {
        assertEquals(emptyList(), KotlinSourceIndex.sameLineDeclarations("fun f() { a(); b() }"))
        assertEquals(emptyList(), KotlinSourceIndex.sameLineDeclarations("val s = \"a;b\""))
        assertEquals(listOf(" fun g() = 2"), KotlinSourceIndex.sameLineDeclarations("fun f() = 1; fun g() = 2"))
    }
}
