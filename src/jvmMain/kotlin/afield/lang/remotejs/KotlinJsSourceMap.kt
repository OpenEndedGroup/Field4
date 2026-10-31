package afield.lang.remotejs

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The source map of a Kotlin→JS box's compiled module, remembered per box so that output arriving
 * after the run can still be given a line.
 *
 * The map is a by-product of the compile, and the output that needs it usually arrives once the run
 * has finished: an error from a timer, a `console.log` from an event handler, each carrying a line of
 * generated JavaScript and nothing else. `RemoteJS.kt` builds a map the moment [KotlinToJsCompiler]
 * returns one and remembers it under the box id; `KotlinJsHeadProfiles.kt` asks for it when a reply
 * needs a line; the next compile of that box replaces it.
 *
 * None of this reports a failure to the box. A compile that produced no map, or a map whose
 * `mappings` the decoder rejects, leaves the box with none, and its output keeps the generated line
 * it came with. A position that maps somewhere other than the box's own Kotlin is a null from
 * [editorLine], and [editorLineForStack] goes on to the next frame of the stack.
 */

/**
 * Maps a line in the JavaScript a Kotlin→JS box compiled to back to the line in the box's editor.
 *
 * The head reports runtime errors and routed `console.log` calls at the *generated* JavaScript line
 * (`line 802` for an error on line 3 of the box). The compiler's source map (v3) takes a generated
 * line and column to a line of the prepared Kotlin source, and [KotlinToJsCompiler.PreparedSource]
 * takes that to the editor line. Frames in the Kotlin stdlib map to another source and are skipped,
 * so an exception thrown inside `reduce` lands on the user line that called it.
 */
class KotlinJsSourceMap(json: String, private val userSourceSuffix: String, private val toEditorLine: (Int) -> Int) {

    private class Segment(val column: Int, val source: Int, val line: Int)

    private val sources: List<String>
    private val lines: List<List<Segment>>

    init {
        val o = JSONObject(json)
        val s = o.optJSONArray("sources")
        sources = (0 until (s?.length() ?: 0)).map { s!!.getString(it) }
        lines = decode(o.optString("mappings", ""))
    }

    /** The one-based editor line for generated (one-based) `line` / `column`, or null when that
     *  position is not in the box's own Kotlin. */
    fun editorLine(line: Int, column: Int = 1): Int? {
        val segments = lines.getOrNull(line - 1)?.takeIf { it.isNotEmpty() } ?: return null
        val col0 = column - 1
        val hit = segments.lastOrNull { it.column <= col0 } ?: segments.first()
        val source = sources.getOrNull(hit.source) ?: return null
        if (!source.endsWith(userSourceSuffix)) return null
        return toEditorLine(hit.line + 1).takeIf { it > 0 }
    }

    /** The editor line of the innermost frame of [stack] in [codeName] that is the box's own code. */
    fun editorLineForStack(stack: String, codeName: String): Int? =
        STACK_LOCATION.findAll(stack)
            .filter { it.groupValues[1] == codeName }
            .firstNotNullOfOrNull { editorLine(it.groupValues[2].toInt(), it.groupValues[3].toInt()) }

    companion object {
        // Same frame shape as the head's errors.js; the code name is `box_<name>|<uid>`.
        private val STACK_LOCATION = Regex("""(?:\(|\s|^)((?:box_|field-box:|https?:|file:)[^\s()]*?):(\d+):(\d+)(?:\)|\s|$)""")

        private val byBox = ConcurrentHashMap<String, KotlinJsSourceMap>()

        /** Remember the map of the module a box last sent, for output that arrives later. */
        fun remember(boxId: String, map: KotlinJsSourceMap?) {
            if (map == null) byBox.remove(boxId) else byBox[boxId] = map
        }

        /** The map of the module box `boxId` last compiled, or null when it has none to map through. */
        fun forBox(boxId: String): KotlinJsSourceMap? = byBox[boxId]

        private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        private fun decode(mappings: String): List<List<Segment>> {
            val out = ArrayList<List<Segment>>()
            var source = 0
            var srcLine = 0
            var srcCol = 0
            var name = 0
            for (lineText in mappings.split(';')) {
                var genCol = 0
                val segs = ArrayList<Segment>()
                for (seg in lineText.split(',')) {
                    if (seg.isEmpty()) continue
                    val v = vlq(seg)
                    genCol += v[0]
                    if (v.size >= 4) {
                        source += v[1]; srcLine += v[2]; srcCol += v[3]
                        if (v.size >= 5) name += v[4]
                        segs += Segment(genCol, source, srcLine)
                    }
                }
                out += segs
            }
            return out
        }

        private fun vlq(s: String): IntArray {
            val values = ArrayList<Int>()
            var value = 0
            var shift = 0
            for (c in s) {
                val digit = B64.indexOf(c)
                require(digit >= 0) { "bad source map character '$c'" }
                value += (digit and 31) shl shift
                if (digit and 32 != 0) {
                    shift += 5
                } else {
                    val negative = value and 1 == 1
                    val v = value ushr 1
                    values += if (negative) -v else v
                    value = 0; shift = 0
                }
            }
            return values.toIntArray()
        }
    }
}
