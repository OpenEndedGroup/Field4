package afield.four.synth

import afield.Box
import afield.ReturnValueRegistry
import afield.Runloop
import afield.captureLaunchEpoch
import afield.stopTrackedJobs
import com.illposed.osc.ByteArrayListBytesReceiver
import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCSerializerAndParserBuilder
import java.nio.file.Files
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/*
FIELD_TEST
id: synth.sonic-pi.protocol
runner: jvm-main
entrypoint: afield.four.synth.SonicPiProtocolSmokeTest
tags: [smoke, synth, audio, sonic-pi]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "SonicPi protocol smoke tests passed"
*/
/**
 * Everything about the Sonic Pi v5 control protocol that can be checked without Sonic Pi installed:
 * the daemon's port line, and the OSC encoding of an authenticated message.
 *
 * The encoding half is here because of a real bug, just not one in this codebase. A hand-rolled OSC
 * encoder that pads a already-4-aligned typetag by a further four bytes shifts every argument after
 * it, so `/run-code`'s token arrives as `0` and Sonic Pi silently ignores the message — while
 * unauthenticated messages like `/version` keep working perfectly, which sends you looking in
 * entirely the wrong place. These assertions pin the alignment JavaOSC gives us, so if the OSC
 * dependency is ever swapped or reimplemented the failure shows up here rather than as silence.
 */
object SonicPiProtocolSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "SonicPi protocol smoke tests failed" }
    }

    fun runTests(): Boolean {
        val failures = mutableListOf<String>()

        println("SonicPiPorts.parse")
        checkPortLine(failures)

        println("OSC argument alignment")
        checkOscAlignment(failures)

        println("log and error decoding")
        checkDecoding(failures)

        println("activity decorations")
        checkActivityDecorations(failures)

        println("Linux AppImage install layout")
        checkAppImageInstallLayout(failures)

        return if (failures.isEmpty()) {
            println("SonicPi protocol smoke tests passed")
            true
        } else {
            println("SonicPi protocol smoke tests FAILED:")
            failures.forEach { println("  - $it") }
            false
        }
    }

    private fun checkAppImageInstallLayout(failures: MutableList<String>) {
        val appDir = Files.createTempDirectory("field4-sonicpi-appdir-").toFile()
        try {
            val home = appDir.resolve("usr/share/sonic-pi")
            val daemon = home.resolve("app/server/ruby/bin/daemon.rb").apply {
                parentFile.mkdirs()
                writeText("")
            }
            val ruby = appDir.resolve("usr/ruby/bin/ruby").apply {
                parentFile.mkdirs()
                writeText("")
                setExecutable(true)
            }
            appDir.resolve("usr/lib").mkdirs()
            appDir.resolve("usr/ruby/lib").mkdirs()

            val install = SonicPiInstall.of(appDir)
            if (install == null) {
                failures += "an extracted Linux AppImage layout was not discovered"
                return
            }
            if (install.home != home.canonicalFile) failures += "AppImage home: ${install.home} != $home"
            if (install.daemon != daemon.canonicalFile) failures += "AppImage daemon: ${install.daemon} != $daemon"
            if (install.ruby != ruby.canonicalFile) failures += "AppImage Ruby: ${install.ruby} != $ruby"
            val libraries = install.environment["LD_LIBRARY_PATH"].orEmpty()
            if (!libraries.contains(appDir.resolve("usr/lib").absolutePath)) {
                failures += "AppImage environment omitted usr/lib: $libraries"
            }
            if (!libraries.contains(appDir.resolve("usr/ruby/lib").absolutePath)) {
                failures += "AppImage environment omitted usr/ruby/lib: $libraries"
            }
        } finally {
            appDir.deleteRecursively()
        }
    }

    private fun checkPortLine(failures: MutableList<String>) {
        // The exact line a real v5.0.0 daemon printed on this machine.
        val real = "30201 30198 30197 30199 4560 -1374997887"
        val parsed = SonicPiPorts.parse(real)
        if (parsed == null) {
            failures += "failed to parse a real daemon port line: $real"
        } else {
            if (parsed.daemon != 30201) failures += "daemon port: ${parsed.daemon} != 30201"
            if (parsed.guiListenToSpider != 30198) failures += "guiListenToSpider: ${parsed.guiListenToSpider} != 30198"
            if (parsed.guiSendToSpider != 30197) failures += "guiSendToSpider: ${parsed.guiSendToSpider} != 30197"
            if (parsed.scsynth != 30199) failures += "scsynth port: ${parsed.scsynth} != 30199"
            if (parsed.oscCues != 4560) failures += "oscCues: ${parsed.oscCues} != 4560"
            if (parsed.token != -1374997887) failures += "token: ${parsed.token} != -1374997887"
        }

        // The token is a signed 32-bit int and is negative about half the time, so a positive-only
        // pattern would work on most boots and mysteriously hang on the others.
        if (SonicPiPorts.parse("1 2 3 4 4560 2147483647")?.token != 2147483647) {
            failures += "a positive token at Int.MAX_VALUE should parse"
        }
        if (SonicPiPorts.parse("  1 2 3 4 4560 -7  ")?.token != -7) {
            failures += "surrounding whitespace should be tolerated"
        }

        // Everything else the daemon prints must NOT look like a port line.
        val rejects = listOf(
            "",
            "   ",
            "1 2 3",
            "1 2 3 4 4560",
            "1 2 3 4 4560 -7 8",
            "Sonic Pi Daemon booting...",
            "port 30201 selected",
            "1 2 3 4 4560 -7 trailing",
            "1.5 2 3 4 4560 -7",
            "1 2 3 4 4560 99999999999999999999"
        )
        for (line in rejects) {
            if (SonicPiPorts.parse(line) != null) failures += "should not parse as a port line: '$line'"
        }

        // The token must not leak into logs or screenshots via toString.
        val shown = SonicPiPorts(1, 2, 3, 4, 4560, -1374997887).toString()
        if (shown.contains("-1374997887")) failures += "toString leaked the token: $shown"
    }

    private fun checkActivityDecorations(failures: MutableList<String>) {
        // Ages in ms, as sampled from /flash recency. Line 3 just fired, line 5 a moment ago, line 9
        // is fading, line 12 stopped long enough ago that it should not be lit at all.
        val ages = mapOf(3 to 10L, 5 to 200L, 9 to 500L, 12 to 5_000L)

        val lit = sonicPiActivityDecorations(ages, lineOffset = 0)
        if (lit.size != 3) failures += "expected 3 lit lines, got ${lit.size}: ${lit.map { it.lineStart }}"
        if (lit.any { it.lineStart == 12 }) failures += "a line silent for 5s should have decayed away"
        val classes = lit.associate { it.lineStart to it.classname }
        if (classes[3] != SonicPiHeat.HOT.className) failures += "a 10ms-old flash should be hot: ${classes[3]}"
        if (classes[5] != SonicPiHeat.WARM.className) failures += "a 200ms-old flash should be warm: ${classes[5]}"
        if (classes[9] != SonicPiHeat.COOL.className) failures += "a 500ms-old flash should be cool: ${classes[9]}"

        // Every decoration must carry the CSS for its own class, or the client has a class name with
        // no rule behind it and the line silently does not light up.
        lit.forEach { d ->
            if (!d.classDef.containsKey(d.classname)) failures += "${d.classname} shipped without its CSS"
            if (d.classDef[d.classname]?.contains(".${d.classname}") != true) {
                failures += "${d.classname}'s CSS does not define that selector"
            }
        }

        // Sonic Pi's line numbers are region-relative; the offset puts them on the box's lines.
        val shifted = sonicPiActivityDecorations(ages, lineOffset = 4)
        if (shifted.map { it.lineStart } != listOf(7, 9, 13)) {
            failures += "offset not applied: ${shifted.map { it.lineStart }}"
        }
        // 1-based, so nothing may land above line 1 however odd the offset.
        if (sonicPiActivityDecorations(mapOf(1 to 0L), lineOffset = -5).isNotEmpty()) {
            failures += "a negative box line should be dropped, not clamped into the wrong row"
        }
        if (sonicPiActivityDecorations(emptyMap(), 0).isNotEmpty()) failures += "silence should decorate nothing"

        val single = sonicPiActivityDecorations(mapOf(2 to 0L), lineOffset = 0).single()
        if (single.colStart != 1) failures += "decoration should start at column 1, got ${single.colStart}"
        if (single.colEnd <= single.colStart) failures += "decoration should span the line"
    }

    private fun checkOscAlignment(failures: MutableList<String>) {
        val token = -1374997887

        // `,is` is the shape of /run-code, and the shape the padding bug destroys.
        val runCode = OSCMessage("/run-code", listOf<Any?>(token, "puts \"hi\""))
        // `,i` alone is the shape of /daemon/keep-alive, which stays correct even when padding is
        // wrong — which is exactly why the bug presents as "the daemon lives but ignores me".
        val keepAlive = OSCMessage("/daemon/keep-alive", listOf<Any?>(token))
        val mixer = OSCMessage("/mixer-output-volume", listOf<Any?>(token, 0.5f, 0))

        for (message in listOf(runCode, keepAlive, mixer)) {
            val bytes = serialize(message)
            if (bytes == null) {
                failures += "could not serialize ${message.address}"
                continue
            }
            if (bytes.size % 4 != 0) {
                failures += "${message.address} serialized to ${bytes.size} bytes, not a multiple of 4"
            }
            val parser = OSCSerializerAndParserBuilder().buildParser()
            val roundTripped = runCatching { parser.convert(ByteBuffer.wrap(bytes)) as? OSCMessage }.getOrNull()
            if (roundTripped == null) {
                failures += "${message.address} did not survive a parse round-trip"
                continue
            }
            if (roundTripped.address != message.address) {
                failures += "address changed: ${roundTripped.address} != ${message.address}"
            }
            val first = roundTripped.arguments?.firstOrNull()
            if (first != token) {
                failures += "${message.address}: token decoded as $first, expected $token " +
                    "(this is the alignment bug — Sonic Pi would log 'Invalid token: 0')"
            }
            if (roundTripped.arguments?.size != message.arguments.size) {
                failures += "${message.address}: argument count changed"
            }
        }
    }

    private fun serialize(message: OSCMessage): ByteArray? {
        val receiver = ByteArrayListBytesReceiver()
        val serializer = OSCSerializerAndParserBuilder().buildSerializer(receiver)
        return runCatching {
            serializer.write(message)
            receiver.toByteArray()
        }.getOrNull()
    }

    private fun checkDecoding(failures: MutableList<String>) {
        // Shapes taken verbatim from a live v5.0.0 session, including the Ruby-side `.inspect`
        // quoting of the thread name and of a `puts` payload.
        val log = decodeMultiMessage(listOf(1, "\"\"", "0.0", 1, 1, "\"field4-handshake-8817\""))
        if (log.jobId != 1) failures += "log jobId: ${log.jobId} != 1"
        if (log.lines.size != 1) failures += "log lines: ${log.lines.size} != 1"
        if (!log.text.contains("field4-handshake-8817")) failures += "log text lost the payload: ${log.text}"
        if (log.thread != "") failures += "thread name should be unquoted, got '${log.thread}'"

        val multi = decodeMultiMessage(listOf(7, "\"live_loop\"", "1.25", 2, 0, "first", 1, "second"))
        if (multi.lines.size != 2) {
            failures += "a two-line multi_message should decode two lines, got ${multi.lines.size}"
        } else if (multi.text != "first\nsecond") {
            failures += "multi-line text joined wrong: '${multi.text}'"
        }
        if (multi.thread != "live_loop") failures += "thread name: '${multi.thread}' != 'live_loop'"
        if (multi.runtime != 1.25) failures += "runtime: ${multi.runtime} != 1.25"

        // Malformed messages must not throw — this runs on the OSC receive thread, where the log is
        // decorative but an exception is not.
        val truncated = runCatching { decodeMultiMessage(listOf(3)) }
        if (truncated.isFailure) failures += "a truncated multi_message threw: ${truncated.exceptionOrNull()}"
        else if (truncated.getOrNull()?.lines?.isNotEmpty() == true) {
            failures += "a truncated multi_message should decode to no lines"
        }

        val overclaimed = runCatching { decodeMultiMessage(listOf(3, "\"\"", "0.0", 5, 0, "only-one")) }
        if (overclaimed.isFailure) failures += "an over-claiming multi_message threw: ${overclaimed.exceptionOrNull()}"
        else if (overclaimed.getOrNull()?.lines?.size != 1) {
            failures += "a message claiming 5 pairs but carrying 1 should decode that 1, " +
                "got ${overclaimed.getOrNull()?.lines?.size}"
        }

        if (decodeLogInfo(listOf(1, "Welcome to Sonic Pi v5.0")).text != "Welcome to Sonic Pi v5.0") {
            failures += "/log/info did not decode its text"
        }

        // /error and /syntax_error carry their arguments in *different* orders.
        val err = decodeError(
            listOf(3, "Runtime Error … `this_fn_does_not_exist`", "eval:1:in 'block'", 1, 0, 22, "this_fn_does_not_exist 1"),
            syntax = false
        )
        if (err.line != 1) failures += "error line: ${err.line} != 1"
        if (err.lastColumn != 22) failures += "error lastColumn: ${err.lastColumn} != 22"
        if (!err.sourceLine.contains("this_fn_does_not_exist")) failures += "error lost its source line"
        if (!err.backtrace.contains("eval:1")) failures += "error lost its backtrace"

        val syn = decodeError(
            listOf(4, "Syntax Error: unexpected end", "play 60 do", 9, "9", 3, 11),
            syntax = true
        )
        if (syn.line != 9) failures += "syntax error line: ${syn.line} != 9"
        if (syn.sourceLine != "play 60 do") failures += "syntax error source line: '${syn.sourceLine}'"
        if (syn.firstColumn != 3) failures += "syntax error firstColumn: ${syn.firstColumn} != 3"
        if (!syn.toString().contains("syntax error")) failures += "syntax errors should say so: ${syn}"
        if (sonicPiErrorBoxLine(6, 2) != 8) {
            failures += "Sonic Pi line 6 at box offset 2 should target Field line 8"
        }
        if (sonicPiErrorBoxLine(-1, 2) != -1) {
            failures += "an unknown Sonic Pi error line should remain untargeted"
        }
    }
}

/*
FIELD_TEST
id: synth.sonic-pi.live
runner: jvm-main
entrypoint: afield.four.synth.SonicPiLiveSmokeTest
tags: [smoke, synth, audio, sonic-pi, external]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "SonicPi live smoke tests finished"
*/
/**
 * Boots a real Sonic Pi and drives it, which is the only way to know any of this works.
 *
 * Needs Sonic Pi v5 installed. Without it the test says so, loudly, and passes — a machine with no
 * Sonic Pi is not a broken machine. Set `FIELD_SONIC_PI_REQUIRED=1` to turn that skip into a
 * failure, which is what you want on a machine that is supposed to have it.
 */
object SonicPiLiveSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "SonicPi live smoke tests failed" }
    }

    fun runTests(): Boolean {
        val required = System.getenv("FIELD_SONIC_PI_REQUIRED") == "1"
        val install = runCatching { SonicPiInstall.discover() }.getOrNull()
        if (install == null) {
            println("SKIPPED: no Sonic Pi install found, so nothing here was exercised.")
            println("  Install Sonic Pi v5, or set -Dsonicpi.home=/path/to/Contents/Resources.")
            if (required) {
                println("  FIELD_SONIC_PI_REQUIRED=1 is set, so this counts as a failure.")
                return false
            }
            println("SonicPi live smoke tests finished")
            return true
        }

        println("using install: ${install.home}")
        println("  ruby:   ${install.ruby}")
        println("  daemon: ${install.daemon}")

        val failures = mutableListOf<String>()

        // Before booting for real: a boot that fails *after* spawning the daemon must take the
        // daemon with it. Getting this wrong strands an audio server holding the cue port until
        // Sonic Pi's own kill switch notices ~90s later, which then quietly pushes the next boot
        // onto a different cue port. A 1ms deadline reaches the failure path with certainty.
        val baseline = sonicPiProcessCount()
        val premature = runCatching { SonicPi.shared(install, bootTimeoutMs = 1L) }
        if (premature.isSuccess) {
            failures += "a 1ms boot deadline should not have produced a working client"
            SonicPi.destroyShared()
        } else {
            println("failed boot rejected: ${premature.exceptionOrNull()?.message?.lineSequence()?.first()?.take(90)}")
            if (SonicPi.currentOrNull() != null) {
                failures += "a failed boot left a cached instance behind, so the next boot would reuse it"
            }
            if (!await(30_000L) { sonicPiProcessCount() <= baseline }) {
                failures += "a failed boot stranded a Sonic Pi process " +
                    "(${sonicPiProcessCount()} alive, baseline $baseline)"
            } else {
                println("failed boot stranded nothing")
            }
        }

        val sp = SonicPi.shared(install)
        try {
            println("ports: ${sp.ports}")

            if (!sp.awaitReady(180_000L)) {
                failures += "Sonic Pi never announced /spider/ready"
                // No point in the rest; nothing will answer.
                return report(failures)
            }
            println("spider ready")

            // The single most informative assertion here: a /ping that comes back proves we parsed
            // the token correctly, since every unauthenticated path would work regardless.
            if (!sp.ping(timeoutMs = 15_000L)) failures += "no /ack for /ping — token not accepted"
            else println("ping acknowledged (token accepted)")

            val version = sp.version(timeoutMs = 15_000L)
            println("version: $version")
            if (version == null) failures += "no /version reply"
            else if (!version.version.startsWith("v5")) {
                println("  note: this is ${version.version}, not a v5 — the protocol here targets v5")
            }

            val marker = "field4-live-smoke-${sp.ports.guiSendToSpider}"
            sp.run("puts \"$marker\"", workspace = "field4-smoke")
            if (!awaitLog(sp, marker, 30_000L)) {
                failures += "ran `puts \"$marker\"` but it never came back in the log"
            } else {
                println("run-code round-tripped through the log")
            }

            // Errors have to arrive too, or a typo in a box is invisible.
            val errorsBefore = sp.errors.size
            sp.run("this_fn_does_not_exist 1", workspace = "field4-smoke")
            if (!awaitErrors(sp, errorsBefore, 30_000L)) {
                failures += "bad code produced no /error"
            } else {
                val last = sp.errors.last()
                println("error reported: ${last.message.lineSequence().first().trim()}")
                if (!last.sourceLine.contains("this_fn_does_not_exist")) {
                    failures += "the error did not carry the offending source line: ${last.sourceLine}"
                }
                if (last.line <= 0) failures += "the error had no usable line number: ${last.line}"
            }

            // Make a sound, quietly, and confirm Sonic Pi says it did.
            val soundsBefore = sp.log.size
            sp.run("synth :beep, note: 72, amp: 0.1, release: 0.2", workspace = "field4-smoke")
            if (!awaitLog(sp, "synth", 20_000L, from = soundsBefore)) {
                failures += "a synth call produced no log line"
            } else {
                println("synth triggered")
            }

            // The box-facing one-liner, and the property that makes it safe to put in a box: a box
            // re-runs on every keystroke, so `sonicPi(code)` has to reuse the running Sonic Pi
            // rather than boot another audio server per edit.
            val processesBefore = sonicPiProcessCount()
            val reuseMarker = "field4-reuse-${sp.ports.guiSendToSpider}"
            val viaTopLevel = sonicPi("puts \"$reuseMarker\"", workspace = "field4-smoke")
            if (viaTopLevel.sonicPi !== sp) failures += "sonicPi() booted a different instance"
            if (!awaitLog(sp, reuseMarker, 30_000L)) failures += "sonicPi(code) never reached Sonic Pi"
            else println("sonicPi(code) reused the running instance")
            repeat(3) { sonicPi("puts \"$reuseMarker-$it\"", workspace = "field4-smoke") }
            if (sonicPiProcessCount() > processesBefore) {
                failures += "re-running a box booted another Sonic Pi " +
                    "(${sonicPiProcessCount()} alive, was $processesBefore)"
            }

            checkLiveLoopErrorReachesBox(sp, failures)
            checkStoppingTheBoxStopsTheMusic(sp, failures)

            sp.stopAll()
            if (!sp.isRunning) failures += "Sonic Pi died while we were talking to it"
        } catch (t: Throwable) {
            failures += "threw: ${t::class.simpleName}: ${t.message}"
            t.printStackTrace()
        } finally {
            SonicPi.destroyShared()
        }

        // Owned mode's whole contract: when Field lets go, Sonic Pi goes away.
        if (sp.isRunning) failures += "Sonic Pi was still running after close()"
        else println("closed cleanly, daemon exited")

        return report(failures)
    }

    private fun report(failures: List<String>): Boolean {
        return if (failures.isEmpty()) {
            println("SonicPi live smoke tests passed")
            println("SonicPi live smoke tests finished")
            true
        } else {
            println("SonicPi live smoke tests FAILED:")
            failures.forEach { println("  - $it") }
            println("SonicPi live smoke tests finished")
            false
        }
    }

    /**
     * The case that looks like success: a `live_loop` whose body raises on its own thread.
     *
     * `final_no te` is not a syntax error in Ruby — it parses as `final_no(te = …)` — so nothing
     * complains until the loop actually runs the line and hits a NoMethodError on a thread that is
     * not the one that started the run. Nothing is thrown back to the caller, and the report arrives
     * after the box has stopped running, so unless it is explicitly routed the failure is silent.
     */
    private fun checkLiveLoopErrorReachesBox(sp: SonicPi, failures: MutableList<String>) {
        val code = """
            live_loop :bikes do
              with_synth :dsaw do
                start_note = chord([:b1, :e2].choose, :minor).choose
                final_no te = chord([:b1, :e2].choose, :minor).choose
                p = play start_note, release: 8, amp: 0
                control p, note: final_note
              end
              sleep 8
            end
        """.trimIndent()

        val box = Box("sonic-pi-routing-test")
        val marks = CopyOnWriteArrayList<Triple<String, String, Int>>()
        box.output.errorProxy = { content, property, line ->
            marks += Triple(content?.toString() ?: "", property, line)
        }

        val workspace = "field4-routing-${sp.ports.guiSendToSpider}"
        sp.reportTo(workspace, box)
        sp.volume(0.0, silent = true)
        sp.run(code, workspace)

        // inMain queues onto the runloop, which nothing pumps in a headless jvm-main test.
        val arrived = awaitPumping(30_000L) { marks.any { it.first.isNotBlank() } }

        if (!arrived) {
            failures += "a live_loop error never reached the box it came from " +
                "(client saw ${sp.errors.size} error(s), box saw ${marks.size} mark(s))"
            return
        }

        val mark = marks.first { it.first.isNotBlank() }
        println("box error mark: line=${mark.third} property=${mark.second}")
        println("  ${mark.first.lineSequence().first()}")
        if (!mark.first.contains("final_no")) failures += "the mark did not name the bad call: ${mark.first}"
        if (mark.second != "code") failures += "the mark landed on property '${mark.second}', not 'code'"
        // Sonic Pi and Field's output protocol are both 1-based: line 4 remains line 4 here.
        if (mark.third != 4) failures += "the mark landed on line ${mark.third}, expected 4"
        if (!mark.first.contains("final_no te")) {
            failures += "the error-kind output omitted the offending source line: ${mark.first}"
        }

        // Re-running has to clear the stale marker, or a fixed typo stays underlined forever.
        marks.clear()
        sp.run("puts \"routing-cleared\"", workspace)
        val cleared = awaitPumping(20_000L) { marks.any { it.first.isEmpty() && it.third == -1 } }
        if (!cleared) failures += "a new run did not clear the previous error marker"
        else println("re-running cleared the stale marker")

        sp.stopReportingTo(workspace)
    }

    /**
     * A `live_loop` is owned by the box that started it: the box stays running while it plays, and
     * the stop button silences it.
     *
     * Goes through [ReturnValueRegistry] and [afield.stopTrackedJobs] rather than calling
     * [SonicPiLaunch.stop] directly, because the path that matters is the one the stop button takes.
     */
    private fun checkStoppingTheBoxStopsTheMusic(sp: SonicPi, failures: MutableList<String>) {
        val box = Box("sonic-pi-launch-test")
        val workspace = "field4-launch-${sp.ports.guiSendToSpider}"
        sp.volume(0.0, silent = true)

        val code = """
            live_loop :field_launch do
              sample :bd_haus, amp: 0
              sleep 0.25
            end
        """.trimIndent()

        val prior = sp.jobIdFor(workspace)
        sp.run(code, workspace)
        val spec = SonicPiLaunch(sp, workspace, prior)

        val running = CopyOnWriteArrayList<Boolean>()
        val recognised = ReturnValueRegistry.launch(box, spec, "default") { r, _, _ -> running += r }
        if (!recognised) {
            failures += "the return-value registry did not recognise a SonicPiLaunch, " +
                "so a box could never own its music"
            return
        }

        println("launch dispatched: epoch=${box.captureLaunchEpoch()} generation=${box.runGeneration["default"]}")

        // Wait for the notification rather than sampling once — the handler's coroutine is dispatched,
        // so "not yet" and "never" look identical if you look too early.
        if (!await(25_000L) { running.any { it } }) {
            failures += "the box was never reported as running (notifies=$running, jobs=${box.jobs.size})"
        }

        if (!await(25_000L) { spec.isPlaying && spec.activity().isNotEmpty() }) {
            failures += "the launch never started playing (jobId=${spec.jobId}, activity=${spec.activity()})"
            return
        }
        val jobId = spec.jobId
        if (jobId == null) {
            failures += "playing but no job id"
            return
        }
        println("launch playing: job $jobId, lines triggering sound ${spec.activity().keys.sorted()}")

        // The actual ask: stop the box, and the music stops.
        box.stopTrackedJobs()

        if (!await(25_000L) { !sp.isJobAlive(jobId) }) {
            failures += "stopping the box did not stop the Sonic Pi job"
            sp.stopAll()
            return
        }
        println("stopping the box stopped job $jobId")

        if (!await(10_000L) { running.lastOrNull() == false }) {
            failures += "the box was never reported as no longer running"
        }

        // And it goes quiet — but not instantly. Sonic Pi's flashes are `__delayed_flash`, queued
        // against the scheduled audio time, so whatever was already scheduled when the stop landed
        // still drains. What matters is that the drain ends rather than continuing forever.
        var last = -1
        var quietSamples = 0
        var elapsedMs = 0L
        while (elapsedMs < 8_000 && quietSamples < 3) {
            val now = sp.flashes.count { it.jobId == jobId }
            quietSamples = if (now == last) quietSamples + 1 else 0
            last = now
            Thread.sleep(250)
            elapsedMs += 250
        }
        if (quietSamples < 3) {
            failures += "the loop was still triggering sound ${elapsedMs}ms after the box stopped"
        } else {
            println("went quiet ${elapsedMs}ms after the stop, after draining already-scheduled events")
        }
    }

    /** Wait on a condition while pumping the runloop, since inMain work lands there. */
    private fun awaitPumping(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            runCatching { Runloop.runLoop.main.frame() }
            Thread.sleep(20)
        }
        return predicate()
    }

    /** How many Sonic Pi processes are alive right now, from this JVM's point of view. */
    private fun sonicPiProcessCount(): Long =
        ProcessHandle.allProcesses()
            .filter { it.info().commandLine().orElse("").contains("Sonic Pi.app") }
            .count()

    private fun awaitLog(sp: SonicPi, needle: String, timeoutMs: Long, from: Int = 0): Boolean =
        await(timeoutMs) { sp.log.drop(from).any { it.text.contains(needle) } }

    private fun awaitErrors(sp: SonicPi, before: Int, timeoutMs: Long): Boolean =
        await(timeoutMs) { sp.errors.size > before }

    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(100)
        }
        return predicate()
    }
}
