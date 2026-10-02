package afield.ai.core

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.io.File
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The description rubric for AI4's core tools.
 *
 * The 2026-09-30 held-out measurement (dev_docs/ai/AI4_SEES_ITS_OUTPUT.md, "Held out") found that the
 * lever on rounds per task is the tool description: one rewritten `CompileKotlinRequest` description
 * took held-out rounds from 30/31 to 21/22, and lessons added nothing on top. So a tool that ships a
 * bare description is a measurable cost, and this test is the gate.
 *
 * Each core tool's description (class description plus its parameter descriptions) is scored on four
 * questions, each a phrase class rather than a judgement:
 *
 * - **returns**: does it say what comes back?
 * - **when**: does it say when to use it, or when to use something else?
 * - **fails**: does it name a failure mode or a refusal?
 * - **example**: does it show an example argument (`e.g.`, "for example", a backticked literal)?
 *
 * [MINIMUM] of them must hold, and [Commentary] (a sink, not a tool) is exempt. The scorecard is
 * written to `build/ai4-tool-descriptions.tsv` so a docs page can quote it.
 * See dev_docs/ai/AI4_TOOL_DESCRIPTIONS.md.
 */
class ToolDescriptionRubricTest {

    data class Score(val name: String, val returns: Boolean, val whenToUse: Boolean, val fails: Boolean, val example: Boolean, val chars: Int) {
        val total: Int get() = listOf(returns, whenToUse, fails, example).count { it }
        val missing: List<String> get() = buildList {
            if (!returns) add("returns"); if (!whenToUse) add("when"); if (!fails) add("fails"); if (!example) add("example")
        }
    }

    companion object {
        const val MINIMUM = 4
        private val EXEMPT = setOf("Commentary")

        /**
         * Tools that still miss the rubric, as of 2026-10-02. Shrink-only: a listed tool that now
         * passes fails the test until it is removed here, and no tool may be added. The box-graph and
         * execution tools the eval tasks use were rewritten first; these are the rest.
         */
        val NOT_YET_REWRITTEN: Set<String> = setOf(
            "AttachmentRequest",
            "ChecklistReadRequest",
            "ChecklistUpdateRequest",
            "CodeUseCallersForSourceLineRequest",
            "CodeUseEarlyDocumentSymbolsRequest",
            "CodeUseHotSymbolsRequest",
            "CodeUseQueryRequest",
            "CodeUseReferencesForCodeRequest",
            "CodeUseRelatedSourceForCodeRequest",
            "CodeUseSemanticAnalyzeCodeRequest",
            "DocumentBoxNavigateRequest",
            "DocumentGuideAppendRequest",
            "DocumentGuideCreateRequest",
            "DocumentGuideListRequest",
            "DocumentGuidePatchRequest",
            "DocumentGuideReadRequest",
            "DocumentGuideSearchRequest",
            "DocumentSourceExportRequest",
            "DocumentSourcePatchRequest",
            "DocumentSourceReadRequest",
            "EditFileRequest",
            "EvaluateKotlinExpressionRequest",
            "ListAttachmentsRequest",
            "ListContentsRequest",
            "ListDefinitionsRequest",
            "ListKotlinContextVariablesRequest",
            "MacroListRequest",
            "MacroReadRequest",
            "MacroRunRequest",
            "MacroWriteRequest",
            "ReloadShadersRequest",
            "ScratchpadReadRequest",
            "ScratchpadUpdateRequest",
            "SemanticRetrievalRebuildRequest",
            "SemanticRetrievalSearchRequest",
            "SemanticRetrievalStatusRequest",
            "WorkspaceSymbolSearch"
        )
        private val RETURNS = Regex("""(?i)\b(returns?|answers?|reports?|replies|gives back|lists?|attaches|you get|comes back|result)\b""")
        private val WHEN = Regex("""(?i)\b(use (it|this|when|for|to|before|after|instead)|call (it|this) (when|before|after|first)|instead of|prefer|do not (use|call)|don't (use|call)|only when|whenever|before (you|editing|running|calling)|after (you|editing|running|a))\b""")
        private val FAILS = Regex("""(?i)\b(fails?|failure|errors?|refuses?|unavailable|not found|no such|missing|if (there is )?no|empty if|throws|rejects?|cannot)\b""")
        private val EXAMPLE = Regex("""(?i)(e\.g\.|for example|example:|such as|`[^`]+`|'[^' ]+'|"[^" ]+")""")

        fun descriptionOf(cls: Class<*>): String {
            val classDesc = cls.kotlin.findAnnotation<JsonClassDescription>()?.value.orEmpty()
            val params = cls.kotlin.primaryConstructor?.parameters.orEmpty().mapNotNull { p ->
                p.findAnnotation<JsonPropertyDescription>()?.value
                    ?: runCatching { cls.getDeclaredField(p.name!!).getAnnotation(JsonPropertyDescription::class.java)?.value }.getOrNull()
            }
            return (listOf(classDesc) + params).joinToString("\n")
        }

        fun score(cls: Class<*>): Score {
            val all = descriptionOf(cls)
            val classDesc = cls.kotlin.findAnnotation<JsonClassDescription>()?.value.orEmpty()
            return Score(
                cls.simpleName,
                returns = RETURNS.containsMatchIn(classDesc),
                whenToUse = WHEN.containsMatchIn(classDesc),
                fails = FAILS.containsMatchIn(all),
                example = EXAMPLE.containsMatchIn(all),
                chars = classDesc.length,
            )
        }

        fun coreScores(): List<Score> {
            val tools = ToolRegistry()
            registerBuiltInAgentTools(tools)
            return tools.coreToolClasses().filter { it.simpleName !in EXEMPT }.map(::score)
        }
    }

    @Test
    fun everyCoreToolDescriptionMeetsTheRubric() {
        val scores = coreScores()
        assertTrue(scores.size >= 30, "expected the full core tool set, saw ${scores.size}")
        val out = File("build/ai4-tool-descriptions.tsv")
        out.parentFile.mkdirs()
        out.writeText(
            "tool\treturns\twhen\tfails\texample\tscore\tchars\n" +
                scores.joinToString("\n") { s ->
                    "${s.name}\t${s.returns}\t${s.whenToUse}\t${s.fails}\t${s.example}\t${s.total}\t${s.chars}"
                } + "\n"
        )
        val below = scores.filter { it.total < MINIMUM || it.chars < 80 }
        val healed = NOT_YET_REWRITTEN.filter { n -> scores.any { it.name == n } && below.none { it.name == n } }
        val gone = NOT_YET_REWRITTEN.filter { n -> scores.none { it.name == n } }
        if (healed.isNotEmpty() || gone.isNotEmpty()) {
            fail("Remove from NOT_YET_REWRITTEN (now passing: $healed; no longer registered: $gone)")
        }
        val weak = below.filter { it.name !in NOT_YET_REWRITTEN }
        if (weak.isNotEmpty()) {
            fail(
                "${weak.size} of ${scores.size} AI4 tool descriptions miss the rubric " +
                    "(say what it returns, when to use it, how it fails, and an example argument):\n" +
                    weak.joinToString("\n") { "  ${it.name}: score ${it.total}/4, ${it.chars} chars, missing ${it.missing}" }
            )
        }
    }

    @JsonClassDescription("Stop a running box and everything it launched.")
    data class BareFixtureRequest(val box: String)

    /** The negative control: the pre-2026-10-02 StopBox description scores below the bar. */
    @Test
    fun aBareDescriptionFailsTheRubric() {
        val s = score(BareFixtureRequest::class.java)
        assertTrue(s.total < MINIMUM && s.chars < 80, "a bare description must fail; got $s")
    }
}
