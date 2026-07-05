package afield.ml.tests

import afield.ml.FieldMLCompiler
import afield.ml.ast.BinaryOp
import afield.ml.ast.EBinary
import afield.ml.ast.ECall
import afield.ml.ast.EField
import afield.ml.ast.ELet
import afield.ml.ast.EList
import afield.ml.ast.EMatch
import afield.ml.ast.ERecord
import afield.ml.ast.ETuple
import afield.ml.ast.ETypeDecl
import afield.ml.ast.EVar
import afield.ml.core.Evaluator
import afield.ml.core.renderFieldMLValue
import afield.ml.lex.Lexer
import afield.ml.lex.TokenKind
import afield.ml.parse.Parser
import afield.ml.type.TAdt
import afield.ml.type.TBool
import afield.ml.type.TInt
import afield.ml.type.TList
import afield.ml.type.TRecord
import afield.ml.type.TTuple
import afield.ml.type.TypePrinter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldMLCompilerTest {
    @Test
    fun lexesLiteralsAndIdentifiers() {
        val result = Lexer("""let answer = 42 "ok" true 3.5""").lex()

        assertTrue(result.diagnostics.isEmpty())
        assertEquals(
            listOf(TokenKind.LET, TokenKind.IDENT, TokenKind.EQ, TokenKind.INT, TokenKind.STRING, TokenKind.TRUE, TokenKind.FLOAT, TokenKind.EOF),
            result.tokens.map { it.kind },
        )
    }

    @Test
    fun parsesLetBindings() {
        val expr = parse(
            """
            let x = 10
            x
            """.trimIndent(),
        )

        val let = assertIs<ELet>(expr)
        assertEquals("x", let.name)
        assertIs<EVar>(let.body)
    }

    @Test
    fun parsesTopLevelLocalLetExpression() {
        val expr = parse("let x = 10 in x + 1")

        val let = assertIs<ELet>(expr)
        assertEquals("x", let.name)
        assertIs<EBinary>(let.body)
    }

    @Test
    fun parsesAdtDeclarationAndMatch() {
        val expr = parse(
            """
            type Option a = None | Some a
            match Some 10 with
            | None -> 0
            | Some x -> x
            """.trimIndent(),
        )

        val typeDecl = assertIs<ETypeDecl>(expr)
        assertEquals("Option", typeDecl.declaration.name)
        assertEquals(listOf("None", "Some"), typeDecl.declaration.constructors.map { it.name })
        assertIs<EMatch>(typeDecl.body)
    }

    @Test
    fun parsesFunctionApplicationLeftAssociatively() {
        val expr = parse("f x y")

        val outer = assertIs<ECall>(expr)
        assertIs<ECall>(outer.function)
        assertEquals("y", assertIs<EVar>(outer.argument).name)
    }

    @Test
    fun parsesPipeAsFunctionApplicationToPreviousValue() {
        val expr = parse("10 |> add 32")

        val call = assertIs<ECall>(expr)
        val partial = assertIs<ECall>(call.function)
        assertEquals("add", assertIs<EVar>(partial.function).name)
        assertEquals("32", assertIs<afield.ml.ast.EInt>(partial.argument).value.toString())
    }

    @Test
    fun parsesRecords() {
        val expr = parse(
            """
            {
              x = 10.0
              y = 20.0
            }
            """.trimIndent(),
        )

        val record = assertIs<ERecord>(expr)
        assertEquals(listOf("x", "y"), record.fields.map { it.name })
    }

    @Test
    fun parsesFieldAccess() {
        val expr = parse("p.x")

        val field = assertIs<EField>(expr)
        assertEquals("x", field.field)
    }

    @Test
    fun parsesOperatorPrecedence() {
        val expr = parse("1 + 2 * 3")

        val add = assertIs<EBinary>(expr)
        assertEquals(BinaryOp.ADD, add.op)
        assertEquals(BinaryOp.MUL, assertIs<EBinary>(add.right).op)
    }

    @Test
    fun parsesTupleAndListLiterals() {
        assertEquals(2, assertIs<ETuple>(parse("(1, true)")).elements.size)
        assertEquals(3, assertIs<EList>(parse("[1, 2, 3]")).elements.size)
    }

    @Test
    fun infersSimpleType() {
        val result = compileOk("10 + 32")

        assertEquals(TInt, result.typed!!.type)
    }

    @Test
    fun infersPolymorphicIdentityFunction() {
        val result = compileOk(
            """
            let id x = x
            {
              a = id 10
              b = id true
            }
            """.trimIndent(),
        )

        val type = assertIs<TRecord>(result.typed!!.type)
        assertEquals(TInt, type.fields["a"])
        assertEquals(TBool, type.fields["b"])
    }

    @Test
    fun reportsTypeErrorForAddingIntAndFloat() {
        val result = FieldMLCompiler.compile("10 + 1.0")

        assertFalse(result.successful)
        assertTrue(result.diagnostics.any { "Type mismatch" in it.message })
    }

    @Test
    fun reportsTypeErrorForAddingIntAndBool() {
        val result = FieldMLCompiler.compile(
            """
            let x = 10
            x + true
            """.trimIndent(),
        )

        assertFalse(result.successful)
        assertTrue(result.diagnostics.any { "Type mismatch" in it.message || "Expected Int or Float" in it.message })
    }

    @Test
    fun reportsTypeErrorForMissingRecordField() {
        val result = FieldMLCompiler.compile(
            """
            let p = { x = 10.0 }
            p.y
            """.trimIndent(),
        )

        assertFalse(result.successful)
        assertTrue(result.diagnostics.any { "missing field 'y'" in it.message })
    }

    @Test
    fun emitsKotlinForSimpleArithmetic() {
        val result = compileOk("10 + 32")

        assertTrue(result.kotlin!!.contains("fun runFieldML(): Any?"))
        assertTrue(result.kotlin.contains("+"))
    }

    @Test
    fun emitsKotlinForFunctions() {
        val result = compileOk(
            """
            let scale x =
              x * 2.0
            scale 21.0
            """.trimIndent(),
        )

        assertTrue(result.kotlin!!.contains("{ x: Any? ->"))
        assertTrue(result.kotlin.contains("as (Any?) -> Any?"))
    }

    @Test
    fun emitsKotlinForRecords() {
        val result = compileOk(
            """
            let p = {
              x = 10.0
              y = 20.0
            }
            {
              x = p.x * 2.0
              y = p.y * 2.0
            }
            """.trimIndent(),
        )

        assertTrue(result.kotlin!!.contains("data class FRecord"))
        assertTrue(result.kotlin.contains("\"x\" to"))
    }

    @Test
    fun maxExampleInfersInt() {
        val result = compileOk(
            """
            let max a b =
              if a > b then a else b
            max 10 20
            """.trimIndent(),
        )

        assertEquals("Int", TypePrinter().print(result.typed!!.type))
    }

    @Test
    fun infersAdtConstructorApplication() {
        val result = compileOk(
            """
            type Option a = None | Some a
            Some 10
            """.trimIndent(),
        )

        val type = assertIs<TAdt>(result.typed!!.type)
        assertEquals("Option", type.name)
        assertEquals(TInt, type.arguments.single())
    }

    @Test
    fun infersMatchOverAdt() {
        val result = compileOk(
            """
            type Option a = None | Some a
            let value = Some 10
            match value with
            | None -> 0
            | Some x -> x
            """.trimIndent(),
        )

        assertEquals(TInt, result.typed!!.type)
    }

    @Test
    fun supportsWildcardPattern() {
        val result = compileOk(
            """
            type Option a = None | Some a
            match None with
            | Some x -> x
            | _ -> 42
            """.trimIndent(),
        )

        assertEquals(TInt, result.typed!!.type)
    }

    @Test
    fun infersTuplesAndLists() {
        val tuple = compileOk("(10, true)")
        val tupleType = assertIs<TTuple>(tuple.typed!!.type)
        assertEquals(listOf(TInt, TBool), tupleType.elements)

        val list = compileOk("[1, 2, 3]")
        val listType = assertIs<TList>(list.typed!!.type)
        assertEquals(TInt, listType.element)
    }

    @Test
    fun rejectsMixedListElements() {
        val result = FieldMLCompiler.compile("[1, true]")

        assertFalse(result.successful)
        assertTrue(result.diagnostics.any { "Type mismatch" in it.message })
    }

    @Test
    fun matchesTupleAndListPatterns() {
        val tuple = compileOk("match (10, 32) with | (a, b) -> a + b")
        assertEquals("42", renderFieldMLValue(Evaluator().evaluate(tuple.core!!)))

        val list = compileOk("match [10, 32] with | [a, b] -> a + b | _ -> 0")
        assertEquals("42", renderFieldMLValue(Evaluator().evaluate(list.core!!)))
    }

    @Test
    fun reportsMatchBranchTypeMismatch() {
        val result = FieldMLCompiler.compile(
            """
            type Option a = None | Some a
            match Some 10 with
            | None -> false
            | Some x -> x
            """.trimIndent(),
        )

        assertFalse(result.successful)
        assertTrue(result.diagnostics.any { "Type mismatch" in it.message })
    }

    @Test
    fun emitsKotlinForAdtsAndMatch() {
        val result = compileOk(
            """
            type Option a = None | Some a
            match Some 10 with
            | None -> 0
            | Some x -> x
            """.trimIndent(),
        )

        assertTrue(result.kotlin!!.contains("data class FAdt"))
        assertTrue(result.kotlin.contains("FAdt(\"Some\""))
        assertTrue(result.kotlin.contains("tag == \"Some\""))
    }

    @Test
    fun adtCanCarryTupleAndListPayload() {
        val result = compileOk(
            """
            type Option a = None | Some a
            match Some (1, [2, 3]) with
            | Some pair -> pair
            | None -> (0, [])
            """.trimIndent(),
        )

        assertEquals("(1, [2, 3])", renderFieldMLValue(Evaluator().evaluate(result.core!!)))
    }

    @Test
    fun emitsKotlinForTuplesAndLists() {
        val result = compileOk("(1, [2, 3])")

        assertTrue(result.kotlin!!.contains("data class FTuple"))
        assertTrue(result.kotlin.contains("FTuple(listOf"))
        assertTrue(result.kotlin.contains("listOf(2, 3)"))
    }

    private fun parse(source: String) =
        Parser(Lexer(source).lex().tokens).parse().also {
            assertTrue(it.diagnostics.isEmpty(), it.diagnostics.joinToString("\n") { diagnostic -> diagnostic.message })
        }.expr!!

    private fun compileOk(source: String) =
        FieldMLCompiler.compile(source).also {
            assertTrue(it.successful, it.diagnostics.joinToString("\n") { diagnostic -> diagnostic.message })
            assertNotNull(it.typed)
            assertNotNull(it.core)
            assertNotNull(it.kotlin)
        }
}
