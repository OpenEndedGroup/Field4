package afield.examples

import afield.graphics.ToneKotlinJs
import afield.remotejs.KotlinJsHeadProfiles
import afield.remotejs.KotlinToJsCompiler

/*
FIELD_TEST
id: remotejs.kotlinjs-tone-surface
runner: jvm-main
entrypoint: afield.examples.KotlinJsToneSurfaceSmokeTest
tags: [smoke, remotejs, kotlinjs, tone]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinJsToneSurfaceSmokeTest ok"
*/

/**
 * Validates the Tone.js computer-music head profile (`// {{ tone }}`) by compiling a sample that
 * exercises a broad slice of the surface against the GENERATED compiler `external` declarations —
 * the real proof the [ToneKotlinJs] binding emits valid Kotlin/JS for every bound name and that they
 * type-check together. Also asserts the marker routes to the `tone` profile and the parity/dynamic
 * split invariants ([afield.graphics.KotlinJsBinding]) hold for the Tone surface.
 *
 * Grounding: every name here is confirmed present in the vendored bundle
 * `extensions/remote_ar/vendor/tone/15.1.22/Tone.js` (which the RemoteAR head installs as `Tone`).
 * Audio *playback* requires a real browser head + a user gesture and is NOT verified here.
 */
object KotlinJsToneSurfaceSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val compiler = ToneKotlinJs.declarations
        val completion = ToneKotlinJs.completionDeclarations

        // Structural parity: compiler is `external`, completion is plain.
        require(compiler.contains("external object Tone")) { "compiler Tone must be external" }
        require(completion.contains("object Tone") && !completion.contains("external")) {
            "completion mirror must be plain (no `external`)"
        }

        // Core classes present in BOTH renderings.
        for (t in listOf("Synth", "PolySynth", "MonoSynth", "Oscillator", "Gain", "Filter", "Reverb",
            "FeedbackDelay", "Loop", "Sequence", "Transport", "Param", "Destination")) {
            require(compiler.contains("class $t")) { "compiler missing Tone.$t" }
            require(completion.contains("class $t")) { "completion missing Tone.$t" }
        }

        // Top-level Tone.* helpers exist as member funs in both façades.
        for (fn in listOf("fun now(", "fun start(", "fun getTransport(", "fun getDestination(",
            "fun dbToGain(", "fun mtof(")) {
            require(compiler.contains(fn)) { "compiler missing Tone helper `$fn`" }
            require(completion.contains(fn)) { "completion missing Tone helper `$fn`" }
        }

        // dynamic ↔ Any? split: Transport.position is a mutable dynamic for the compiler, typed for completion.
        require(compiler.contains("var position: dynamic")) { "Transport.position must be `dynamic` for the compiler" }
        require(completion.contains("var position: Any?")) { "Transport.position must be `Any?` for completion" }
        // Sequence's callback carries a dynamic value in the compiler, Any? in completion.
        require(compiler.contains("value: dynamic")) { "Sequence callback value must be `dynamic` for the compiler" }
        require(completion.contains("value: Any?")) { "Sequence callback value must be `Any?` for completion" }

        // Compiler-only glue must not leak into completion.
        require(compiler.contains("__toneLog")) { "compiler missing log glue" }
        require(!completion.contains("__toneLog")) { "completion must not carry compiler-only glue" }

        // The whole surface must type-check against the generated compiler declarations.
        val sample = """
            // {{ tone }}
            val synth = Tone.PolySynth()
            synth.toDestination()
            val reverb = Tone.Reverb(2.0)
            reverb.toDestination()
            synth.connect(reverb)
            synth.volume.value = -6.0
            Tone.start()
            val transport = Tone.getTransport()
            transport.bpm.value = 120.0
            transport.swing = 0.1
            transport.position = "0:0:0"
            val notes = arrayOf("C4", "E4", "G4")
            val repeatId = transport.scheduleRepeat({ time -> synth.triggerAttackRelease(notes, "8n", time) }, "2n")
            transport.clear(repeatId)
            val loop = Tone.Loop({ time -> synth.triggerAttackRelease("C2", "16n", time) }, "4n")
            loop.start(0.0).stop(8.0)
            val seq = Tone.Sequence({ time, note -> log("step " + note) }, arrayOf<Any?>("C4", "E4"), "8n")
            seq.start()
            val osc = Tone.Oscillator(220.0, "sawtooth")
            osc.frequency.value = 330.0
            osc.detune.value = 5.0
            osc.start()
            val filter = Tone.Filter(800.0, "lowpass")
            filter.Q.value = 4.0
            val delay = Tone.FeedbackDelay(0.25, 0.4)
            osc.chain(filter, delay, Tone.getDestination())
            val mono = Tone.MonoSynth()
            mono.toDestination()
            mono.triggerAttackRelease("A3", "4n", Tone.now())
            val gain = Tone.Gain(0.8)
            gain.gain.rampTo(0.2, 0.5)
            val noise = Tone.Noise("pink")
            noise.type = "white"
            Tone.getDestination().volume.value = -3.0
            transport.start()
            log("tone surface ok " + Tone.version + " " + Tone.now() + " " + Tone.dbToGain(-6.0) +
                " " + Tone.mtof(60.0) + " " + osc.type + " " + filter.frequency.value + " " +
                delay.feedback.value + " " + reverb.decay + " " + transport.state + " " + gain.gain.value)
        """.trimIndent()

        val resolved = KotlinJsHeadProfiles.resolve(sample)
        require(resolved.profile === KotlinJsHeadProfiles.tone) { "sample must select the tone head" }
        val prepared = resolved.profile.prepareSource(resolved.source)
        val result = KotlinToJsCompiler.compile(prepared, moduleName = "tone_surface", wrapImplicitMain = false)
        require(result.success && result.js != null) {
            "Tone surface did not compile:\n${result.rawLog.takeLast(2000)}"
        }
        println("tone surface compiled: ${prepared.length} chars Kotlin -> ${result.js.length} chars JS")

        // Live completion (skip-tolerant — needs the AA sidecar; feeds `completionDeclarations`).
        System.getProperty("afield.completion.sidecarUrl")
            ?: System.getenv("AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL")?.let { System.setProperty("afield.completion.sidecarUrl", it) }
        val toneDot = complete("Tone.")
        if (toneDot == null) {
            println("no AA sidecar reachable; façade completion checks skipped")
        } else {
            require(toneDot.any { it.startsWith("PolySynth") } && toneDot.any { it.startsWith("getTransport") }) {
                "Tone. completion missing PolySynth/getTransport: ${toneDot.take(25)}"
            }
            val synthDot = complete("Tone.PolySynth().") ?: emptyList()
            require(synthDot.any { it.startsWith("triggerAttackRelease") } && synthDot.any { it.startsWith("toDestination") }) {
                "Tone.PolySynth(). completion missing triggerAttackRelease/toDestination: ${synthDot.take(25)}"
            }
            val txDot = complete("Tone.getTransport().") ?: emptyList()
            require(txDot.any { it.startsWith("scheduleRepeat") } && txDot.any { it.startsWith("bpm") }) {
                "Tone.getTransport(). completion missing scheduleRepeat/bpm: ${txDot.take(25)}"
            }
            println("completion: Tone. → PolySynth/getTransport present; PolySynth(). → triggerAttackRelease present; getTransport(). → scheduleRepeat present")
        }

        println("KotlinJsToneSurfaceSmokeTest ok")
    }

    /** Complete against the generated completion mirror; null if no sidecar answered (→ skip). */
    private fun complete(snippet: String): List<String>? {
        val decl = ToneKotlinJs.completionDeclarations
        val roots = afield.AaCompletionClient.kotlinStdlibRoots()
        var unreachable = true
        repeat(8) {
            val o = afield.AaCompletionClient.complete(
                snippet = snippet, cursor = snippet.length, preamble = "",
                declarations = decl, implicitReceivers = emptyList(), classpathRootsOverride = roots,
            )
            if (o.detail.let { !it.startsWith("no-sidecar") && !it.startsWith("error") && !it.startsWith("http") }) unreachable = false
            (o.result as? afield.CompletionResult.Success)?.let { return it.metadata.map { m -> m.text } }
            Thread.sleep(400)
        }
        return if (unreachable) null else emptyList()
    }
}
