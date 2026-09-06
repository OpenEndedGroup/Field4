package afield.docs

import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import afield.stacksl.cpu.StackSLCpu
import afield.stdlib.StackAutoReport
import afield.stdlib.stackAutoShaderReport
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Every `stack.auto` StackSL program committed in an agent transcript still compiles.**
 *
 * ## The defect this closes
 *
 * `dev_docs/agents/transcripts/gpu_state.yaml` binds a StackSL program to the `force` slot of its
 * compute shader. Commit `6607a7edc` added a *builtin* StackSL word `fbm3`, and that transcript's
 * program at the time opened with `: fbm3 … ;`. `StackSLCompiler.compile` reports a definition
 * that shadows a builtin as a hard ERROR (`src/jvmMain/kotlin/afield/stacksl/StackSL.kt:163`), and
 * the live setter `compileStackAutoShaderFunctionReporting` *throws* on a failed compile, so the
 * simulation box aborted at that one assignment. The scene has since been rewritten around the
 * builtin `fbm4` and the collision is gone; the hole that let it through is what this closes.
 *
 * Nothing went red for the eight days in between. The transcript is in
 * [afield.examples.DemoTranscriptExecSmokeTest.COMPILE_ONLY], so its box bodies go through the real
 * K2 path but are never **evaluated** — and `simModule.force = """…"""` is a runtime assignment,
 * not a compile-time one.
 *
 * The committed figure survived, which is worth stating because it is the near miss rather than the
 * damage: `public/docs/assets/gpu_state/particle-field.png` was last captured on a commit that is
 * not a descendant of `6607a7edc`, so it still showed the working field. The exposure was the *next*
 * `force=true` recapture of that page, which would have photographed a scene whose force law had
 * silently become the no-op fallback.
 *
 * This is the general shape of the `COMPILE_ONLY` blind spot that
 * [DocumentedApiEvaluationTest] measures and ledgers. It is not fixable in general without running
 * the transcripts — but it *is* fixable for this one language, because the compile is pure.
 *
 * ## Why this needs neither a GPU nor an eval
 *
 * [stackAutoShaderReport] is the production compile with the production
 * `StackSLCompileOptions` — same prefix, same `tolerant = false`, same entrypoint handling — and it
 * touches no device, no box and no window. The only thing standing between a committed transcript
 * and that function is getting the program text back out of the YAML, which is what the rest of
 * this class is.
 *
 * ## Extraction, and why it is deliberately narrow
 *
 * A slot is a *pair*: a `{{ stack.auto.NAME }}` marker in one of a box's shader properties, and a
 * Kotlin string assigned to `.NAME` in that same box's `code`. Both halves are required
 * ([slotsIn]), so a Kotlin property that merely happens to share a name with nothing is not
 * compiled, and a marker with no binding is not invented.
 *
 * The YAML is parsed with snakeyaml rather than scanned as text — the property values are block
 * scalars and the indentation is the syntax. Only the *assignment* is a regex, and only two literal
 * shapes are recognised ([bindingsFor]): a raw `"""…"""` and a single-line `"…"`. Both occur in the
 * corpus today. A binding built by concatenation, or held in a variable, is invisible here and is
 * meant to be: **under-matching is the safe direction**, because a program this class never sees is
 * exactly as covered as it was before this class existed, whereas a mis-parsed fragment is a red
 * build over nothing.
 *
 * Which makes the floor below the load-bearing part. A guard whose extractor silently degrades to
 * matching nothing reports `failures=0` over an empty population — which is the *same* failure mode
 * as the bug it was written for, wearing the guard's own uniform.
 *
 * ## What it does not claim
 *
 * That the program is *right*. `input xyz 2.0 * fbm x 0.5 - 8.0 *` compiles and so does a program
 * that renders a flat disc; only a GPU capture can tell those apart, and
 * [theCommittedProgramsAreStackBalanced] is the nearest cheap proxy — it holds every program to the
 * same balance analysis the live-coding output channel shows the person editing it. Both directions
 * are asserted so the guard cannot pass by finding nothing.
 */
class TranscriptStackAutoCompileTest {

    private companion object {

        /**
         * Floor on the `stack.auto` programs the extractor recovers from the corpus.
         *
         * Measured 2026-09-06: **2** — `gpu_state.yaml` and `docs_capture/gpu_state_flat_fbm.yaml`,
         * one slot each. Floored at 2 rather than pinned because a new transcript that binds a slot
         * should not have to edit this number; it is here only so that a regression in [slotsIn] or
         * [bindingsFor] cannot turn this class into a green tick over an empty list.
         */
        const val MIN_PROGRAMS = 2

        /** Where the corpus lives. `docs_capture/` holds capture variants, so the walk is recursive. */
        const val TRANSCRIPTS = "dev_docs/agents/transcripts"

        /**
         * `{{ stack.auto.NAME[:entrypoint][@sampler] }}`, the grammar
         * `afield.stdlib.parseStackAutoShaderTarget` accepts. The whole payload is captured and
         * split here the same way it splits it.
         */
        val MARKER = Regex("""\{\{\s*stack\.auto\.([^}]+?)\s*}}""")

        /** The identifier rules the production parser enforces, so a bad marker is skipped, not compiled. */
        val SLOT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val ENTRYPOINT_NAME = Regex("[A-Za-z_][A-Za-z0-9_-]*")
    }

    /** A slot a box's shader declares: everything `{{ stack.auto.NAME:entry@sampler }}` carries. */
    private data class Slot(val name: String, val entrypoint: String?, val sampleTexture: String)

    /** One committed binding: a declared slot, the program bound to it, and where that was found. */
    private data class Program(val file: String, val slot: Slot, val source: String) {
        val where: String
            get() = "$file → stack.auto.${slot.name}" + (slot.entrypoint?.let { ":$it" } ?: "")
    }

    /**
     * The defect this class was written for: a program that no longer compiles at all.
     *
     * Fails on any ERROR diagnostic, which is precisely the condition under which
     * `compileStackAutoShaderFunctionReporting` throws and the box aborts mid-assignment.
     */
    @Test
    fun everyCommittedStackAutoProgramCompiles() {
        val programs = collectPrograms()
        assertTrue(
            programs.size >= MIN_PROGRAMS,
            "Found only ${programs.size} stack.auto programs in $TRANSCRIPTS (floor $MIN_PROGRAMS). " +
                "Either the corpus lost them or the extractor stopped matching — and an extractor " +
                "that matches nothing reports the same failures=0 as the bug this guard exists for."
        )

        val broken = programs.mapNotNull { p ->
            if (reportFor(p).compiled) null else "${p.where}\n" + errorsFor(p).joinToString("\n") { "    $it" }
        }

        assertTrue(
            broken.isEmpty(),
            "${broken.size} of ${programs.size} committed stack.auto program(s) no longer compile. " +
                "A COMPILE_ONLY transcript never evaluates its `<module>.<slot> = \"…\"` assignment, so " +
                "this is invisible to DemoTranscriptExecSmokeTest and shows up only as a wrong figure:\n\n" +
                broken.joinToString("\n\n")
        )
    }

    /**
     * The weaker property, asserted because the corpus can carry it today: no committed program has
     * anything for the `stack.auto` output channel to complain about.
     *
     * The `gpu_state` page *teaches* the unbalanced-definition failure mode — "open `vfield` with
     * `=c` instead of `>c` and the box's `stack.auto` output channel will tell you the definition
     * ends two deep" — but it teaches it as an instruction to the reader, not as a committed program.
     * Every program actually in the corpus is quiet, so this is fatal rather than advisory. If a
     * transcript ever needs to *ship* a deliberately unbalanced program as its subject, this is the
     * assertion to convert into a declared exemption; do that rather than deleting it, because a
     * silently-dropped balance check is how the first one got past.
     */
    @Test
    fun theCommittedProgramsAreStackBalanced() {
        val programs = collectPrograms()
        assertTrue(programs.size >= MIN_PROGRAMS, "extractor floor: ${programs.size} < $MIN_PROGRAMS")

        val noisy = programs
            .map { it to reportFor(it) }
            .filter { (_, report) -> report.compiled && !report.quiet }
            .map { (p, report) -> "${p.where}\n" + report.lines.joinToString("\n") { "    $it" } }

        assertTrue(
            noisy.isEmpty(),
            "${noisy.size} committed stack.auto program(s) compile but have something to say. These " +
                "are what a person editing the box would see in its `stack.auto` output channel, so " +
                "shipping one teaches the wrong thing by example:\n\n" + noisy.joinToString("\n\n")
        )
    }

    /** The verdict, from the production path the live setter uses. */
    private fun reportFor(p: Program): StackAutoReport =
        stackAutoShaderReport(p.source, p.slot.name, p.slot.entrypoint, p.slot.sampleTexture)

    /**
     * Why it did not compile, in the compiler's own words.
     *
     * [StackAutoReport] deliberately drops ERROR diagnostics — "the caller has already been told
     * separately", which is true of the live setter that throws them and false of every other
     * caller. So the message comes from a second compile of the same program under
     * [StackSLCpu.stackAutoOptions], the literal copy of the production option list that
     * `StackSLCpuExpansionTest` pins against `shaders.kt`. Two compiles rather than one, which for a
     * pure text-to-GLSL pass over two programs is not a cost worth designing around, and it keeps
     * the *verdict* on the production path where it belongs — this call only produces prose.
     *
     * The entrypoint is applied here because `stackAutoOptions` hard-codes the root case; the
     * production report is the one that has already ruled on it.
     */
    private fun errorsFor(p: Program): List<String> {
        val entrypoint = p.slot.entrypoint
        val options = StackSLCpu.stackAutoOptions(p.slot.name, p.slot.sampleTexture)
            .let { if (entrypoint == null) it else it.copy(entrypoint = entrypoint, useEntrypointAsRoot = true) }
        val errors = StackSLCompiler.compile(p.source, options).diagnostics
            .filter { it.severity == StackSLSeverity.ERROR }
            .map { it.toString() }
        return errors.ifEmpty { listOf("(no ERROR diagnostic — the two compile paths disagree, which is itself the bug)") }
    }

    // ---- extraction ----------------------------------------------------------------------------

    private fun collectPrograms(): List<Program> {
        val dir = File(CitationGuard.repoRoot(), TRANSCRIPTS)
        assertTrue(dir.isDirectory, "transcript corpus not found at ${dir.absolutePath}")

        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "yaml" }
            .sortedBy { it.path }
            .flatMap { yaml ->
                val rel = yaml.relativeTo(CitationGuard.repoRoot()).path
                propertyMapsIn(Yaml().load<Any?>(yaml.readText())).flatMap { properties ->
                    programsIn(rel, properties)
                }
            }
            .toList()
    }

    /**
     * Every `properties:` map in a transcript, as a box's property set.
     *
     * A box is the unit that pairs a marker with a binding, and `properties` is that unit in the
     * YAML — under `create:` today, but the walk is generic so an op verb that grows one is covered
     * without an edit here. Non-string values (frames, numbers) are dropped: a property that is not
     * text is neither a shader nor box code.
     */
    private fun propertyMapsIn(node: Any?): List<Map<String, String>> = when (node) {
        is Map<*, *> -> buildList {
            (node["properties"] as? Map<*, *>)?.let { props ->
                add(props.entries.mapNotNull { (k, v) ->
                    val key = k as? String ?: return@mapNotNull null
                    val value = v as? String ?: return@mapNotNull null
                    key to value
                }.toMap())
            }
            node.values.forEach { addAll(propertyMapsIn(it)) }
        }

        is List<*> -> node.flatMap { propertyMapsIn(it) }
        else -> emptyList()
    }

    /** The declared slots of one box, keyed by slot name, from the `{{ stack.auto.… }}` markers. */
    private fun slotsIn(properties: Map<String, String>): Map<String, Slot> {
        val slots = linkedMapOf<String, Slot>()
        for (text in properties.values) {
            for (m in MARKER.findAll(text)) {
                val raw = m.groupValues[1].trim()
                val samplerAt = raw.indexOf('@')
                val body = (if (samplerAt >= 0) raw.substring(0, samplerAt) else raw).trim()
                val sampler = if (samplerAt >= 0) raw.substring(samplerAt + 1).trim() else "source"
                val name = body.substringBefore(':').trim()
                val entrypoint = body.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }

                // A marker the production parser would reject is not a slot; leave it to whatever
                // guard owns malformed templates rather than guessing at an interpretation here.
                if (!SLOT_NAME.matches(name)) continue
                if (entrypoint != null && !ENTRYPOINT_NAME.matches(entrypoint)) continue
                if (!SLOT_NAME.matches(sampler)) continue

                slots.putIfAbsent(name, Slot(name, entrypoint, sampler))
            }
        }
        return slots
    }

    /** The bound programs of one box: a declared slot, with a string literal assigned to its name. */
    private fun programsIn(file: String, properties: Map<String, String>): List<Program> {
        val slots = slotsIn(properties)
        if (slots.isEmpty()) return emptyList()

        return properties.values.flatMap { code ->
            slots.values.flatMap { slot ->
                bindingsFor(slot.name, code).map { Program(file, slot, it) }
            }
        }
    }

    /**
     * The string literals assigned to `.name` in one property's text.
     *
     * Deliberately two shapes and no more — a raw `"""…"""` and a single-line `"…"` — and both
     * require the quote to follow the `=` immediately, which is what keeps the prose in
     * `gpu_state.yaml`'s own comments (`// \`sim.force = ...\` would be a receiver-type mismatch`)
     * out of the population. The receiver is not matched at all, only the `.name` that follows it,
     * because it is a module pointer whose spelling is the box author's business.
     */
    private fun bindingsFor(name: String, code: String): List<String> {
        val assignment = Regex(
            """\.${Regex.escape(name)}\s*=\s*(?:"{3}([\s\S]*?)"{3}|"((?:[^"\\\n]|\\.)*)")"""
        )
        return assignment.findAll(code).map { m ->
            // Which alternative matched, not which is non-empty: an empty `""""""` is a legal
            // binding and `ifEmpty` would silently reroute it through the escape decoder.
            m.groups[1]?.value ?: unescape(m.groups[2]?.value.orEmpty())
        }.toList()
    }

    /** Kotlin's single-line escapes, for the `"…"` shape. A raw string has none by definition. */
    private fun unescape(literal: String): String {
        val out = StringBuilder(literal.length)
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            if (c != '\\' || i == literal.lastIndex) {
                out.append(c); i++; continue
            }
            when (val e = literal[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                else -> out.append(e)   // \" \\ \$ \'
            }
            i += 2
        }
        return out.toString()
    }
}
