@file:afield.runtime.completion.CompletionCategory("Audio")
package afield.audio.synth

import afield.lang.stacksl.cpu.GlslSamplers
import afield.lang.stacksl.cpu.GlslValue
import afield.lang.stacksl.cpu.newInvocation
import afield.lang.stacksl.cpu.parseGlslShader
import afield.lang.stacksl.StackSLCompileOptions
import afield.lang.stacksl.compileStackSL
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.fm1.hooks
runner: jvm-main
entrypoint: afield.audio.synth.FM1HookCheck
tags: [smoke, synth, audio, wasm, cpu, network, stacksl]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "fm1 hooks checked"
*/

/**
 * Checks StackSL hooks in the FM-1's firmware ([FM1Hooks], [FM1Sim.hook]):
 *
 *  - every StackSL program on the docs pages either compiles to a hook or is refused for reading a
 *    texture or a push constant, and each hook's `eval` agrees with Field's CPU GLSL interpreter on the same GLSL at
 *    a spread of inputs, so the C++ lowering means what the GLSL means;
 *  - an identity hook leaves a track's sound as it was, and a folding hook changes it, while the
 *    sequence keeps playing through the swaps;
 *  - a hook that traps is taken out and reported, and the track plays on;
 *  - a note hook moves, keeps or drops the notes a track's sequence plays, as read back through
 *    the track's MIDI out, and its `eval` says what it does to one note;
 *  - the `hooks` and `note-hooks` clips on `fm1.md` are reproducible.
 *
 * Needs the network the first time, to clone the firmware and fetch the WASI SDK.
 */
object FM1HookCheck {

    private val INPUTS = listOf(
        floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0.25f, -0.1f, 0f, 0.5f), floatArrayOf(-0.6f, 0.3f, 1f, 1.75f),
        floatArrayOf(0.9f, 0.9f, 2f, 3.1f), floatArrayOf(0.1f, 0.7f, 5f, 10f), floatArrayOf(-0.33f, -0.95f, 7f, 0.01f),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val docs = File("public/docs")
        check(docs.isDirectory) { "run from the repository root (no public/docs here)" }
        val fence = Regex("""```[a-z_]*\s*\{[^}\n]*data-lang=stacksl[^}\n]*\}\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
        val programs = docs.listFiles { f -> f.name.endsWith(".md") }!!.sortedBy { it.name }
            .flatMap { f -> fence.findAll(f.readText()).map { it.groupValues[1].trim() } }
            .filter { it.isNotEmpty() }.distinct()

        FM1Sim.create(FM1Hooks.firmware()).use { sim ->
            check(sim.hookable) { "FM1Hooks.firmware() has no hook points" }
            var hooked = 0; var refused = 0; var notStackSL = 0; var worst = 0.0; var worstProgram = ""
            val disagree = mutableListOf<String>()
            for (program in programs) {
                val glsl = compileStackSL(program, StackSLCompileOptions(includeHeader = false, declareSamplers = false, sampleTextures = emptyList()))
                if (!glsl.ok) { notStackSL++; continue } // prose in a stacksl fence, not a program
                val hook = try {
                    FM1Hooks.compile(program)
                } catch (e: FM1HookException) {
                    check("reads a texture" in e.message.orEmpty() || "reads a push constant" in e.message.orEmpty()) { "\"$program\" was refused: ${e.message}" }
                    refused++; continue
                }
                sim.hook(0, hook)
                val shader = parseGlslShader(glsl.glsl).newInvocation(emptyMap(), GlslSamplers.REFUSE)
                for (x in INPUTS) {
                    val want = shader.call("stacksl", listOf(GlslValue.vec4(x[0], x[1], x[2], x[3]))).c
                    val got = sim.evalHook(0, x[0], x[1], x[2], x[3])!!
                    for (ch in 0 until 4) {
                        val w = want.getOrElse(ch) { 0f }
                        if (w.isNaN() && got[ch].isNaN()) continue
                        val err = abs(w - got[ch]) / maxOf(1f, abs(w))
                        if (err > worst) { worst = err.toDouble(); worstProgram = program }
                        if (err > 1e-3) disagree += "\"${program.take(60)}\" at ${x.toList()} ch $ch: interpreter $w, hook ${got[ch]}"
                    }
                }
                hooked++
            }
            println("docs StackSL: ${programs.size} fences, $hooked compile to hooks, $refused refused for reading textures or push constants, $notStackSL are not programs")
            println("largest relative difference from the CPU interpreter: ${"%.2e".format(worst)} (\"${worstProgram.take(50)}\")")
            disagree.take(10).forEach { println("  disagrees: $it") }
            check(disagree.isEmpty()) { "${disagree.size} hook evaluations disagree with the CPU interpreter" }
            check(hooked >= 60) { "only $hooked docs programs compiled to hooks" }

            val bad = runCatching { FM1Hooks.compile("in frobnicate") }.exceptionOrNull()
            check(bad is FM1HookException && "frobnicate" in bad.message.orEmpty()) { "a StackSL error names the word, got $bad" }
            sim.clearHooks()
        }

        // Sound: the 303 on track 0 of ACID ALLEY, unhooked, through an identity hook, and folded.
        fun render(setup: (FM1Sim) -> Unit): FloatArray = FM1Sim.create(FM1Hooks.firmware()).use { sim ->
            sim.preset(1)
            for (t in 1 until 8) sim.cc(t, FM1Sim.CC.VOLUME, 0)
            val l = FloatArray(4800); val r = FloatArray(4800)
            repeat(5) { sim.render(l, r, 4800, 48_000f) }
            setup(sim)
            sim.start()
            val out = FloatArray(4800 * 30)
            repeat(30) { sim.render(l, r, 4800, 48_000f); l.copyInto(out, it * 4800) }
            check(sim.isPlaying) { "the sequence stopped" }
            out
        }
        fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)
        val plain = render { }
        val identity = render { it.hook(0, "in") }
        val folded = render { it.hook(0, FM1SoundDocs.HOOK_FOLD) }
        val idDiff = rms(FloatArray(plain.size) { plain[it] - identity[it] })
        val foldDiff = rms(FloatArray(plain.size) { plain[it] - folded[it] })
        println("303 alone: rms ${"%.4f".format(rms(plain))}; identity hook differs by ${"%.2e".format(idDiff)}, fold by ${"%.4f".format(foldDiff)}")
        check(rms(plain) > 0.005) { "the 303 is silent" }
        check(idDiff < rms(plain) * 1e-4) { "an identity hook changed the sound (${idDiff})" }
        check(foldDiff > rms(plain) * 0.05) { "the fold hook did not change the sound (${foldDiff})" }

        // A hook that traps is taken out, and the track plays on.
        FM1Sim.create(FM1Hooks.firmware()).use { sim ->
            val trap = FM1Hooks.compileCpp(
                "trap", FM1Hooks.cpp("in").replace("    if (n > 128) n = 128;", "    __builtin_trap();")
            )
            sim.preset(1)
            sim.hook(0, trap)
            sim.start()
            val l = FloatArray(4800); val r = FloatArray(4800)
            var peak = 0f
            repeat(20) { sim.render(l, r, 4800, 48_000f); peak = maxOf(peak, l.maxOf { abs(it) }) }
            val error = sim.hookError(0)
            check(error != null) { "a trapping hook reported no error" }
            check(peak > 0.02f && sim.isPlaying) { "the firmware stopped after the hook trapped (peak $peak)" }
            println("a trapping hook is taken out and reported: ${error.lineSequence().first().take(80)}")
        }

        // Note hooks: what one note becomes, then the 303's own sequence read back through MIDI out.
        FM1Sim.create(FM1Hooks.firmware()).use { sim ->
            sim.noteHook(0, FM1SoundDocs.NOTE_OCTAVE)
            check(sim.evalNoteHook(0, 60, 100)!!.toList() == listOf(72, 100)) { "octave hook: ${sim.evalNoteHook(0, 60, 100)?.toList()}" }
            sim.noteHook(0, FM1SoundDocs.NOTE_FIFTHS)
            check(sim.evalNoteHook(0, 60, 90, step = 0)!!.toList() == listOf(60, 90)) { "fifths hook, even step" }
            check(sim.evalNoteHook(0, 60, 90, step = 3)!!.toList() == listOf(67, 90)) { "fifths hook, odd step" }
            sim.noteHook(0, "in 0 >y")
            check(sim.evalNoteHook(0, 60, 90) == null) { "a velocity of 0 drops the note" }
            val bad = runCatching { sim.noteHook(0, "in frobnicate") }.exceptionOrNull()
            check(bad is FM1HookException) { "a broken note program throws, got $bad" }
            check(sim.noteHookOf(0)!!.stacksl == "in 0 >y") { "a refused program leaves the old note hook" }
        }
        fun notesOn(program: String?): List<Int> = FM1Sim.create(FM1Hooks.firmware()).use { sim ->
            sim.preset(1)
            sim.midiOut(0, FM1Sim.MidiOut.ALSO)
            if (program != null) sim.noteHook(0, program)
            val l = FloatArray(4800); val r = FloatArray(4800)
            repeat(5) { sim.render(l, r, 4800, 48_000f) }
            sim.drainMidi()
            sim.start()
            repeat(40) { sim.render(l, r, 4800, 48_000f) }
            sim.drainMidi().filter { (it.bytes[0] and 0xF0) == 0x90 && it.bytes[2] > 0 }.map { it.bytes[1] }
        }
        val written = notesOn(null)
        val octave = notesOn(FM1SoundDocs.NOTE_OCTAVE)
        val dropped = notesOn("in 0 >y")
        check(written.size >= 8) { "the 303 played only ${written.size} notes" }
        check(octave == written.map { it + 12 }) { "an octave hook did not move every note up 12: $written -> $octave" }
        check(dropped.isEmpty()) { "a velocity-0 hook still played ${dropped.size} notes" }
        println("note hooks: the 303's ${written.size} notes ${written.take(6)}... come out ${octave.take(6)}... an octave up, and none with velocity 0")

        for (clip in listOf("hooks", "note-hooks")) {
            val (a, _) = FM1SoundDocs.render(clip)
            val (b, _) = FM1SoundDocs.render(clip)
            check(a.contentEquals(b)) { "$clip: two renders differ, so a published clip could not be reproduced" }
            println("$clip clip: ${a.size} frames, deterministic")
        }
        println("fm1 hooks checked")
    }
}
