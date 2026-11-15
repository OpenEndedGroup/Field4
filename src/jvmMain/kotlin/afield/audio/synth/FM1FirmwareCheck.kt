@file:afield.runtime.completion.CompletionCategory("Audio")
package afield.audio.synth

import java.nio.file.Files
import kotlin.math.abs

/*
FIELD_TEST
id: synth.fm1.firmware
runner: jvm-main
entrypoint: afield.audio.synth.FM1FirmwareCheck
tags: [smoke, synth, audio, wasm, cpu, network]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "fm1 firmware checked"
*/

/**
 * Builds the FM-1 firmware from source and checks what `fm1.md` says about it: that a module built
 * by [FM1Firmware.build] from the unchanged source plays, that [FM1FirmwareBuildException] carries
 * the compiler's message, and that `firmware-swap` (the 303 crushed four seconds into ACID ALLEY by
 * [FM1Sim.loadFirmware]) is reproducible, leaves the first four seconds as they were, and changes
 * the rest while the sequence keeps playing.
 *
 * Needs the network the first time, to clone the source and fetch the WASI SDK.
 */
object FM1FirmwareCheck {

    @JvmStatic
    fun main(args: Array<String>) {
        val source = FM1Firmware.checkout()
        val copy = Files.createTempDirectory("fm1-check").toFile()
        try {
            source.copyRecursively(copy, overwrite = true)
            FM1Firmware.reset(copy)

            val t0 = System.nanoTime()
            val pristine = FM1Firmware.build(copy).readBytes()
            println("built the unchanged source in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s, ${pristine.size} bytes")
            FM1Sim.create(pristine).use { sim ->
                sim.preset(1)
                check(sim.projectName.isNotBlank()) { "the built module has no project name" }
                sim.start()
                val l = FloatArray(4800); val r = FloatArray(4800)
                var peak = 0f
                repeat(40) { sim.render(l, r, 4800, 48_000f); peak = maxOf(peak, l.maxOf { abs(it) }) }
                check(peak > 0.02f) { "the module built from source is silent (peak $peak)" }
                println("module built from source plays ${sim.projectName} (peak ${"%.3f".format(peak)})")
            }

            FM1Firmware.patch(copy, FM1SoundDocs.CRUSH_FILE, FM1SoundDocs.CRUSH_FIND, "out[i]=(int32_t)(sample*16777216)")
            val failure = runCatching { FM1Firmware.build(copy) }.exceptionOrNull()
            check(failure is FM1FirmwareBuildException && "bass303.c" in failure.message.orEmpty()) {
                "a broken build reports the compiler's error naming the file, got $failure"
            }
            println("a broken build reports: ${failure.message!!.lineSequence().first()}")
        } finally {
            copy.deleteRecursively()
        }

        val (a, _) = FM1SoundDocs.render("firmware-swap")
        val (b, _) = FM1SoundDocs.render("firmware-swap")
        check(a.contentEquals(b)) { "firmware-swap: two renders differ, so a published clip could not be reproduced" }
        val swap = (FM1SoundDocs.SWAP_SECONDS * FM1SoundDocs.SR).toInt() / FM1SoundDocs.BLOCK * FM1SoundDocs.BLOCK
        // The same clip on the shipped firmware all the way through.
        val unswapped = FM1Sim.create().use { sim -> renderStarted(sim, a.size) }
        check((0 until swap).all { a[it] == unswapped[it] }) { "firmware-swap changed the audio before the swap" }
        var diff = 0.0; var energy = 0.0
        for (i in swap until a.size) { diff += (a[i] - unswapped[i]).let { it * it }; energy += a[i] * a[i] }
        check(energy > 0.0 && diff / energy > 0.01) { "firmware-swap sounds the same after the swap (${diff / energy})" }
        println("firmware-swap: deterministic, identical before ${FM1SoundDocs.SWAP_SECONDS} s, " +
            "after it differs by ${"%.0f".format(100 * kotlin.math.sqrt(diff / energy))}% of its level")
        println("fm1 firmware checked")
    }

    /** ACID ALLEY started by MIDI Start, rendered the way [FM1SoundDocs.render] does, for [frames]. */
    private fun renderStarted(sim: FM1Sim, frames: Int): FloatArray {
        sim.preset(1)
        val node = FM1SimNode(sim)
        val ctx = SynthContext(FM1SoundDocs.SR.toFloat(), FM1SoundDocs.BLOCK)
        val l = FloatArray(FM1SoundDocs.BLOCK); val r = FloatArray(FM1SoundDocs.BLOCK)
        var block = 0L
        fun pull() {
            ctx.cycleId = ++block
            ctx.time = (block - 1).toFloat() * FM1SoundDocs.BLOCK / FM1SoundDocs.SR
            node.render(l, r, FM1SoundDocs.BLOCK, ctx)
        }
        repeat((FM1SoundDocs.SETTLE_SECONDS * FM1SoundDocs.SR / FM1SoundDocs.BLOCK).toInt()) { pull() }
        sim.start()
        val out = FloatArray(frames)
        var at = 0
        while (at < frames) { pull(); System.arraycopy(l, 0, out, at, minOf(FM1SoundDocs.BLOCK, frames - at)); at += FM1SoundDocs.BLOCK }
        return out
    }
}
