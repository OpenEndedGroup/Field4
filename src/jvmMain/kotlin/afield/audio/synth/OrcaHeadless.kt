@file:afield.runtime.completion.CompletionCategory("Patterns")
package afield.audio.synth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.SandboxPolicy
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess
import java.io.OutputStream

/**
 * ORCA with no page: its own operators, from the same vendored scripts `orca()` loads in a box's
 * output, run in the JVM through GraalJS one frame at a time, as fast as you step it.
 *
 * ```kotlin
 * val headless = OrcaHeadless("D4\n.:03C", bpm = 120)
 * headless.orca.onNote { println("${it.frame} ${it.midi}") }
 * headless.step(64)
 * ```
 *
 * [orca] is an ordinary [Orca], fed the same reports a page sends, so everything a box does with a
 * live one — `onNote`, `o[x, y]`, `onChange`, `variable` — works here too, and a change made in a
 * callback lands at the next frame, as it does live. What it lacks is a page: no keyboard, no
 * drawing, no MIDI device, and of the commander only `play`, `stop`, `bpm:`, `frame:` and `write:`.
 * JavaScript runs here too: [evaluate] at once, or [Orca.eval] / [Orca.js] at the next step.
 * Time is the frame count: each [step] is one ORCA frame, `60 / bpm / 4` seconds of music, however
 * long it took to compute. That is what makes an offline render of an ORCA program possible.
 *
 * @param cols the smallest width the grid has, as a box's output gives ORCA room; a wider [grid]
 *   keeps its width.
 * @param rows the smallest height the grid has.
 */
class OrcaHeadless(grid: String? = null, bpm: Int = 120, cols: Int = 64, rows: Int = 24) : AutoCloseable {
    companion object {
        private const val VENDOR = "/vendor/orca/223e45c/scripts/"
        private val SCRIPTS = listOf(
            "core/library.js", "core/operator.js", "core/orca.js", "core/transpose.js", "core/io.js",
            "core/io/cc.js", "core/io/midi.js", "core/io/mono.js", "core/io/osc.js", "core/io/udp.js",
        ).map { VENDOR + it } + "/orca_capture.js"

        private val sources: List<Source> by lazy {
            SCRIPTS.map { path ->
                val text = OrcaHeadless::class.java.getResource(path)?.readText()
                    ?: throw IllegalStateException("ORCA script $path is not on the classpath")
                Source.newBuilder("js", text, path).build()
            }
        }

        /** One engine for every instance, so ORCA's scripts are parsed once; contexts are never shared. */
        private val engine: Engine by lazy {
            Engine.newBuilder("js")
                .sandbox(SandboxPolicy.CONSTRAINED)
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .option("engine.WarnInterpreterOnly", "false")
                .build()
        }

        // The page's client, cut down to what the operators, the IO objects and orca_capture.js touch.
        // Reports and commands cross as JSON strings, in the same shape orca_field.js uses.
        private val HEADLESS = """
            const client = { orca: new Orca(library), clock: { speed: { value: 120 }, isPaused: false },
              cursor: { x: 0, y: 0 }, grid: { w: 8, h: 8 }, commander: { trigger: (text) => fieldOrcaCommand(`${'$'}{text}`) } }
            client.io = new IO(client)
            const events = [], results = []
            let millis = 0, lastGrid = null, sent = 0
            const capture = fieldOrcaCapture(client, (event) => { event.frame = client.orca.f; event.at = millis; events.push(event) })

            function fieldOrcaLoad (text, cols, rows) {
              const lines = fieldOrcaRectangle(text || '.').split('\n')
              const w = Math.max(cols, lines[0].length), h = Math.max(rows, lines.length)
              while (lines.length < h) lines.push('')
              client.orca.load(w, h, lines.map((line) => line.padEnd(w, '.')).join('\n'), client.orca.f)
            }

            function fieldOrcaFrame () {
              if (client.clock.isPaused) return
              client.io.clear()
              client.orca.run()
              client.io.run()
              millis += 60000 / client.clock.speed.value / 4
            }

            function fieldOrcaReport () {
              const s = `${'$'}{client.orca}`
              const report = { frame: client.orca.f, bpm: client.clock.speed.value, paused: client.clock.isPaused,
                t: millis, events: events.splice(0), cursor: client.cursor }
              if (sent++ === 0) report.hello = true
              if (s !== lastGrid) { report.grid = s; report.w = client.orca.w; report.h = client.orca.h; lastGrid = s }
              if (Object.keys(client.orca.variables).length) report.variables = client.orca.variables
              if (results.length) report.results = results.splice(0)
              return JSON.stringify(report)
            }

            function fieldOrcaApply (json, cols, rows) {
              for (const c of JSON.parse(json).commands || []) {
                switch (c.op) {
                  case 'load': fieldOrcaLoad(c.grid, cols, rows); break
                  case 'write': client.orca.writeBlock(c.x, c.y, c.text); break
                  case 'set': client.orca.write(c.x, c.y, c.glyph); break
                  case 'variable': capture.hold(c.name, c.value); break
                  case 'eval': fieldOrcaEval(c, results); break
                  case 'bpm': client.clock.speed.value = Math.min(300, Math.max(60, c.value)); break
                  case 'frame': client.orca.f = c.value; break
                  case 'play': client.clock.isPaused = false; break
                  case 'stop': client.clock.isPaused = true; break
                  case 'command': fieldOrcaCommand(`${'$'}{c.text}`); break
                }
              }
            }

            function fieldOrcaEvaluate (js) {
              const value = (0, eval)(js)
              return JSON.stringify(value === undefined ? null : value)
            }

            function fieldOrcaCommand (text) {
              const [name, value = ''] = text.split(':')
              const parts = value.split(';')
              if (name === 'play') client.clock.isPaused = false
              else if (name === 'stop') client.clock.isPaused = true
              else if (name === 'bpm') client.clock.speed.value = Math.min(300, Math.max(60, parseInt(value)))
              else if (name === 'frame') client.orca.f = parseInt(value)
              else if (name === 'write') client.orca.writeBlock(parseInt(parts[1]) || 0, parseInt(parts[2]) || 0, parts[0])
            }
        """.trimIndent()
    }

    /** This ORCA: callbacks, cells, variables, commands, exactly as for one in a box's output. */
    val orca = Orca(null, "headless", grid?.trimIndent(), bpm, false)

    private val cols = cols.coerceIn(1, 400)
    private val rows = rows.coerceIn(1, 200)

    private val context: Context = Context.newBuilder("js")
        .engine(engine)
        .sandbox(SandboxPolicy.CONSTRAINED)
        .allowHostAccess(HostAccess.CONSTRAINED)
        .allowHostClassLookup { false }
        .allowHostClassLoading(false)
        .allowIO(IOAccess.NONE)
        .allowCreateThread(false)
        .allowNativeAccess(false)
        .allowCreateProcess(false)
        .allowEnvironmentAccess(EnvironmentAccess.NONE)
        .allowPolyglotAccess(PolyglotAccess.NONE)
        .out(OutputStream.nullOutputStream())
        .err(OutputStream.nullOutputStream())
        .build()

    init {
        try {
            // ORCA's desktop IO asks Node for dgram and node-osc; there is no Node here either.
            context.eval("js", "function require (name) { return undefined }")
            sources.forEach { context.eval(it) }
            context.eval("js", HEADLESS)
            call("fieldOrcaLoad", ".", this.cols, this.rows)
            exchange()
        } catch (e: Throwable) {
            context.close()
            throw e
        }
    }

    private fun call(name: String, vararg args: Any): String =
        context.getBindings("js").getMember(name).execute(*args).let { if (it.isString) it.asString() else "" }

    /** One report from ORCA to [orca], and its commands back. */
    private fun exchange() {
        val reply = orca.receive(Json.parseToJsonElement(call("fieldOrcaReport")))
        call("fieldOrcaApply", reply.toString(), cols, rows)
    }

    /** Run [frames] ORCA frames, calling [orca]'s callbacks after each. */
    fun step(frames: Int = 1) {
        repeat(frames) {
            call("fieldOrcaFrame")
            exchange()
        }
    }

    /**
     * Run [javascript] in this ORCA's context now, at global scope beside `client` and `library`,
     * and return its value as JSON. The headless counterpart of [Orca.eval], which waits for a page.
     */
    fun evaluate(javascript: String): JsonElement = Json.parseToJsonElement(call("fieldOrcaEvaluate", javascript))

    /** The length of one frame at the current tempo, in seconds. */
    val frameSeconds: Double get() = 60.0 / orca.bpm / 4.0

    override fun close() = context.close()
}
