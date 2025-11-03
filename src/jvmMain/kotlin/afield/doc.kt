package afield

import afield.four.util.Window
import afield.four.util.lastWindowToOpen
import afield.stdlib.invoke
import com.microsoft.playwright.Page
import kotlinx.coroutines.*
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryStack
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.regex.Pattern
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val FFMPEG = "/opt/homebrew/bin/ffmpeg"

fun coerceToDouble(orDefault: Any?): Double {
    return when (orDefault) {
        is Number -> return orDefault.toDouble()
        is String -> return orDefault.toDouble()
        null -> Double.POSITIVE_INFINITY
        is Boolean -> if (orDefault) 1.0 else 0.0
        else -> {
            print("warning cannot understand $orDefault as a number")
            Double.POSITIVE_INFINITY
        }
    }
}

/**
 * the idea here is that we have two kinds of ways of 'exporting' a Field + Workspace based on metadata associated with the boxes, one for humans and one for AI (incrementally resyncable)
 */

// baseDirectory points to the base of the mkdocs site, subDirectory is relative to that, where we're going to be putting things and where we're going to be putting assets associated with them
suspend fun exportToDocumentation(
    root: Box = thisSessionInfo.get().root, baseDirectory: String, subDirectory: String, docName: String
) {

    /*

    TODO — add template and autocomplete for yaml tags

    the plan:
    get all of the boxes from root
    get all of those boxes metadata
    the ones that have 'doc: ' are documentable, with the box preamble given by that markdown string (which can contain headers for example)

    special flags
    execBeforeDoc: integer, executes the two code before we actually document it. This can be good for having things on the screen to actually document. This is true even if we don't have doc: as a header
    docPreamble: adds some text to the documentation preamble, before every piece of code is documented
    boxPreamble: adds some text to the documentation preamble, before this piece of code is documented (presumably useful for AI as well?)
    docOrder: set a priority for documenting

    //%-[[ ... some way of waiting for a screenshot to render... ]] should be used to split large boxes into partially editable chunks?
    [[boxShot(10)]] — takes a screenshot of this box, padded by 10 pixels (this should include all children, and we need a mechanism to open subpanels)
    [[sheetShot()]] — takes a screenshot of all the boxes in the sheet that are currently on the screen (you can hide drivers to the left, we're not going to scroll there)
    [[windowShot(...)]] — takes a screenshot of the graphics system window I suppose?

    docOnly {

    } — runs code only if we are in documentation generating mode, this code is removed from code blocks, only actually does anything if execBeforeDoc is true?

    this produces markdown with yaml frontmatter with a download button and an open in Field button

    we need an execute between //%- blocks and move cursor down

     */

    var mainDocument = ""

    val allMetadata = root.children.filter { !it.metadata.isEmpty() }.map {
        it to parseMetadata(it.metadata)
    }.sortedBy { coerceToDouble(it.second.getOrDefault("docOrder", Double.POSITIVE_INFINITY)) }

    val execNeeded =
        allMetadata.sortedBy { coerceToDouble(it.second.getOrDefault("execBeforeDoc", Double.POSITIVE_INFINITY)) }

    // execute execNeeded
    // todo

    // collect frontmatter
    allMetadata.forEach {
        val preamble = it.second.getOrDefault("boxPreamble", "").toString()
        if (!preamble.isEmpty()) {
            mainDocument += "\n${preamble.trim()}\n"
        }
    }

    // collect sections
    allMetadata.forEach {
        val preamble = it.second.getOrDefault("docPreamble", "").toString()
        if (!preamble.isEmpty()) {
            mainDocument += "\n${preamble.trim()}\n"
        }
        var index = 0
        val ff = format(it.first, it.first.code, baseDirectory + "/" + subDirectory) {
            val p = Path(baseDirectory) / Path(subDirectory) / Path("../assets")
            p.createDirectories()
            p.absolutePathString() + "/o_${(index++).toString().padStart(4, '0')}_${abs(docName.hashCode())}_"
        }
        mainDocument += "\n${ff}\n"
    }

    val fn = Path(baseDirectory) / Path(subDirectory) / Path(docName + ".md")
    fn.createParentDirectories()

    println("would write --\n${mainDocument}\n-- to ${fn}")
    if (mainDocument.length > 0) fn.writeText(mainDocument)
}

suspend fun format(inside: Box, s: String, baseDir: String, allocateFilename: () -> String): String {
    var chunks = splitKotlinIntoChunks(s)
    chunks = CommentCommandRewriter.apply(chunks, CommentCommandRewriter.Options(false)) { command, parameter ->
        when (command) {
            "screenshot" -> {
                val fn = allocateFilename() + ".png"
                globalPlaywriteContext?.screenshot(Page.ScreenshotOptions().setPath(Path(fn)))

                processPng(Path(fn).absolutePathString())

                val p = fn.removePrefix(baseDir).removePrefix("/")
                "![screenshot of the whole Field document]($p)"
            }

            "render_screenshot" -> {
                lastWindowToOpen?.let {
                    val fn = allocateFilename() + ".png"
                    it.raiseAndScreenshot(30, fn)
                    processPng(fn, bottomCropPx = 10, topCropPx = 50, leftCropPx = 10, rightCropPx = 10)
                    val p = fn.removePrefix(baseDir).removePrefix("/")
                    "![screenshot of the whole Field document]($p)"
                } ?: "[[render_screenshot failed]]"
            }

            "render_clip" -> {
                lastWindowToOpen?.let {
                    val fn = allocateFilename() + ".mp4"
                    it.raiseAndRecord(30, fn)
                    val p = fn.removePrefix(baseDir).removePrefix("/")

                    """<video controls autoplay loop muted>
                    <source src="../${p}" type="video/mp4">
                    </video>"""
                } ?: "[[render_screenshot failed]]"
            }


            "property" -> {
                val lang = Box.ioSeparateProperties[parameter] ?: "kotlin"
                inside.getPropertyRaw(parameter, null)?.let {
                    "```$lang\n$it\n```"
                } ?: ""
            }

            else -> {
                println(" warning, don't understand `${command}`")
                "[[$command]]"
            }
        }
    }

    var currentLineNo = 1
    return chunks.joinToString("\n") { (type, contents, indent, kind) ->
        when (type) {
            ChunkType.CODE -> if (contents.trim().isEmpty()) {
                ""
            } else {
                ("```kotlin linenums=\"$currentLineNo\" \n$contents\n```").also { currentLineNo += contents.count { it == '\n' } - 1 }

            }

            ChunkType.COMMENT -> {
                if (indent == null) "\n${contents}"
                else if (indent > 0) {
                    if (indent == 1) {
                        "\n<p class='codeFollowIndex1'>${contents}</p>\n"
                    } else {
                        "\n<p class='codeFollowIndex2'>${contents}</p>\n"
                    }
                } else {
                    "\n${contents}"
                }
            }
        }
    }
}


object CommentCommandRewriter {
    data class Options(val skipInsideFencedCodeBlocks: Boolean = true)

    private val commandPattern = Regex("""(?<!\\)\[\[([^\s\]]+)(?:\s+((?:(?!]]).)*))?]]""")

    suspend fun apply(
        chunks: List<Chunk>,
        options: Options = Options(),
        replacer: suspend (command: String, parameter: String) -> String
    ): List<Chunk> = chunks.map { c ->
        if (c.type != ChunkType.COMMENT) c
        else c.copy(text = rewriteMarkdown(c.text, replacer, options))
    }

    suspend fun Regex.replaceSuspend(
        input: CharSequence, transform: suspend (MatchResult) -> CharSequence
    ): String {
        val sb = StringBuilder(input.length + 32)
        var last = 0
        for (m in findAll(input)) {
            sb.append(input, last, m.range.first)
            sb.append(transform(m))
            last = m.range.last + 1
            // optional: let other coroutines run if there are many matches
            yield()
        }
        sb.append(input, last, input.length)
        return sb.toString()
    }

    suspend private fun rewriteMarkdown(
        markdown: String, replacer: suspend (String, String) -> String, options: Options
    ): String {
        if (!options.skipInsideFencedCodeBlocks) {
            return commandPattern.replaceSuspend(markdown) { m ->
                val cmd = m.groupValues[1].trim()
                val param = m.groupValues.getOrNull(2)?.trim().orEmpty()
                replacer(cmd, param)
            }
        }
        val lines = markdown.split('\n')
        val out = StringBuilder(markdown.length + 32)
        var inFence = false
        var fenceMarker: String? = null
        fun fenceOpenerOrCloser(line: String): String? {
            val t = line.trimStart()
            val marker = when {
                t.startsWith("```") -> t.takeWhile { it == '`' }
                t.startsWith("~~~") -> t.takeWhile { it == '~' }
                else -> null
            }
            return if (marker != null && marker.length >= 3) marker else null
        }
        for ((idx, raw) in lines.withIndex()) {
            if (!inFence) {
                val opener = fenceOpenerOrCloser(raw)
                if (opener != null) {
                    inFence = true; fenceMarker = opener; out.append(raw)
                } else {
                    out.append(commandPattern.replaceSuspend(raw) { m ->
                        val cmd = m.groupValues[1].trim()
                        val param = m.groupValues.getOrNull(2)?.trim().orEmpty()
                        replacer(cmd, param)
                    })
                }
            } else {
                val closer = fenceOpenerOrCloser(raw)
                if (closer != null && fenceMarker != null && closer[0] == fenceMarker!![0]) {
                    inFence = false; fenceMarker = null
                }
                out.append(raw)
            }
            if (idx < lines.lastIndex) out.append('\n')
        }
        return out.toString()
    }
}

enum class ChunkType { CODE, COMMENT }
enum class CommentStyle { LINE, BLOCK }

data class Chunk(
    val type: ChunkType, val text: String, val indentUnits: Int? = null,       // only set for COMMENT/LINE
    val style: CommentStyle? = null     // LINE or BLOCK for comments
)

fun splitKotlinIntoChunks(src: String): List<Chunk> {
    val chunks = mutableListOf<Chunk>()
    val code = StringBuilder()
    var i = 0
    val n = src.length

    fun flushCode() {
        if (code.isNotEmpty()) {
            chunks += Chunk(ChunkType.CODE, code.toString())
            code.setLength(0)
        }
    }

    fun isAtLineStartIgnoringSpace(pos: Int): Boolean {
        var j = pos - 1
        while (j >= 0 && src[j] != '\n') j--
        var k = j + 1
        while (k < pos && src[k].isWhitespace() && src[k] != '\n') k++
        return k == pos
    }

    fun consumeRawString(start: Int): Int {
        var j = start + 3
        while (j + 2 < n) {
            if (src[j] == '"' && src[j + 1] == '"' && src[j + 2] == '"') return j + 3
            j++
        }
        return n
    }

    fun consumeNormalString(start: Int): Int {
        var j = start + 1
        while (j < n) {
            val c = src[j]
            if (c == '\\') j += 2
            else if (c == '"') return j + 1
            else j++
        }
        return n
    }

    fun consumeCharLiteral(start: Int): Int {
        var j = start + 1
        while (j < n) {
            val c = src[j]
            if (c == '\\') j += 2
            else if (c == '\'') return j + 1
            else j++
        }
        return n
    }

    fun String.stripLineCommentPrefix(): String {
        val trimmedStart = this.dropWhile { it.isWhitespace() && it != '\n' }
        var body = trimmedStart.removePrefix("//")
        if (body.startsWith(" ")) body = body.drop(1)
        return body
    }

    fun countIndentUnitsBeforeSlashes(line: String): Int {
        // Count leading tabs and spaces *before* the first non-ws (which should be '/')
        var idx = 0
        var tabs = 0
        var spaces = 0
        while (idx < line.length) {
            when (line[idx]) {
                '\t' -> {
                    tabs++; idx++; }

                ' ' -> {
                    spaces++; idx++; }

                else -> break
            }
        }
        // If the line isn't actually a // comment, this value would be meaningless,
        // but we only call this for full-line // comments.
        return tabs + (spaces / 4)
    }

    fun String.trimCommonIndentSmart(): String = this.trimIndent()

    while (i < n) {
        // protect strings/char literals from comment parsing
        if (i + 3 <= n && src.startsWith("\"\"\"", i)) {
            val end = consumeRawString(i)
            code.append(src, i, end); i = end; continue
        }
        if (src[i] == '"') {
            val end = consumeNormalString(i)
            code.append(src, i, end); i = end; continue
        }
        if (src[i] == '\'') {
            val end = consumeCharLiteral(i)
            code.append(src, i, end); i = end; continue
        }

        // /* ... */ block comment (anywhere)
        if (i + 1 < n && src[i] == '/' && src[i + 1] == '*') {
            flushCode()
            val start = i + 2
            var j = start
            var endFound = -1
            while (j < n - 1) {
                if (src[j] == '*' && src[j + 1] == '/') {
                    endFound = j; break
                }
                j++
            }
            val content = if (endFound >= 0) src.substring(start, endFound) else src.substring(start)
            val cleaned = content.trimCommonIndentSmart()
            chunks += Chunk(ChunkType.COMMENT, cleaned, indentUnits = 0, style = CommentStyle.BLOCK)
            i = if (endFound >= 0) endFound + 2 else n
            continue
        }

        // Full-line // comment block
        if (i + 1 < n && src[i] == '/' && src[i + 1] == '/' && isAtLineStartIgnoringSpace(i)) {
            flushCode()

            fun beginningOfLine(pos: Int): Int {
                var p = pos
                while (p > 0 && src[p - 1] != '\n' && src[p - 1] != '\r') p--
                return p
            }

            fun endOfLine(from: Int): Int {
                val nl = src.indexOf('\n', from)
                val cr = src.indexOf('\r', from)
                val e = when {
                    nl == -1 && cr == -1 -> -1
                    nl == -1 -> cr
                    cr == -1 -> nl
                    else -> minOf(nl, cr)
                }
                return if (e == -1) n else e
            }

            val lines = mutableListOf<String>()
            var j = beginningOfLine(i)   // ✅ start at BOL, not the slash
            var minIndentUnits = Int.MAX_VALUE

            while (j < n) {
                val lineStart = j
                val lineEnd = endOfLine(lineStart)

                // skip leading indent on this line
                var k = lineStart
                while (k < lineEnd && (src[k] == ' ' || src[k] == '\t')) k++

                // Is this a full-line // comment?
                val isFullLineSlashes = (k + 1 < lineEnd && src[k] == '/' && src[k + 1] == '/')
                if (!isFullLineSlashes) break

                // Count indent units BEFORE //
                var tabs = 0;
                var spaces = 0
                for (p in lineStart until k) {
                    if (src[p] == '\t') tabs++ else if (src[p] == ' ') spaces++
                }
                minIndentUnits = minOf(minIndentUnits, tabs + spaces / 4)

                // Extract the body after //
                var bodyStart = k + 2
                if (bodyStart < lineEnd && src[bodyStart] == ' ') bodyStart++ // strip one space
                val body = src.substring(bodyStart, lineEnd)
                lines += body

                // advance to start of next line (skip \r\n pairs too)
                j = if (lineEnd < n) {
                    val next = lineEnd + 1
                    if (next < n && src[lineEnd] == '\r' && src[next] == '\n') next + 1 else next
                } else lineEnd
            }

            val block = lines.joinToString("\n").trimIndent()
            val indentUnits = if (minIndentUnits == Int.MAX_VALUE) 0 else minIndentUnits
            chunks += Chunk(ChunkType.COMMENT, block, indentUnits = indentUnits, style = CommentStyle.LINE)
            i = j
            continue
        }

        // Otherwise code
        code.append(src[i]); i++
    }

    flushCode()
    return chunks
}


fun processPng(
    inputPath: String,
    bottomCropPx: Int = 100,
    borderMarginPx: Int = 20,
    topCropPx: Int = 0,
    leftCropPx: Int = 0,
    rightCropPx: Int = 0
) {
    val src = ImageIO.read(File(inputPath)) ?: error("Could not read image: $inputPath")
    val w0 = src.width
    val h0 = src.height

    // Normalize crops (non-negative)
    val cropTop = topCropPx.coerceAtLeast(0)
    val cropRight = rightCropPx.coerceAtLeast(0)
    val cropBottom = bottomCropPx.coerceAtLeast(0)
    val cropLeft = leftCropPx.coerceAtLeast(0)

    // Compute final crop rectangle on the original image, half-open [x0,x1) x [y0,y1)
    val x0 = min(cropLeft, w0)
    val y0 = min(cropTop, h0)
    val x1Ex = max(x0, w0 - cropRight)
    val y1Ex = max(y0, h0 - cropBottom)

    val baseW = x1Ex - x0
    val baseH = y1Ex - y0
    if (baseW <= 0 || baseH <= 0) return // nothing to write; would be empty

    val base = if (x0 == 0 && y0 == 0 && x1Ex == w0 && y1Ex == h0) src
    else src.getSubimage(x0, y0, baseW, baseH)

    val w = base.width
    val h = base.height
    require(w > 0 && h > 0) { "Image has zero dimension after crop." }

    // Border color: pixel (0,0) of the base image
    val borderColor = base.getRGB(0, 0)

    fun borderIsUniform(): Boolean {
        for (x in 0 until w) {
            if (base.getRGB(x, 0) != borderColor) return false
            if (base.getRGB(x, h - 1) != borderColor) return false
        }
        for (y in 0 until h) {
            if (base.getRGB(0, y) != borderColor) return false
            if (base.getRGB(w - 1, y) != borderColor) return false
        }
        return true
    }

    val result: BufferedImage = if (borderIsUniform()) {
        // Find bbox of non-border pixels
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                if (base.getRGB(xx, yy) != borderColor) {
                    if (xx < minX) minX = xx
                    if (yy < minY) minY = yy
                    if (xx > maxX) maxX = xx
                    if (yy > maxY) maxY = yy
                }
            }
        }

        if (maxX < 0 || maxY < 0) {
            // Entire image is border color
            base
        } else {
            // Desired bbox with margin (half-open coords)
            val desiredX0 = minX - borderMarginPx
            val desiredY0 = minY - borderMarginPx
            val desiredX1Ex = maxX + borderMarginPx + 1
            val desiredY1Ex = maxY + borderMarginPx + 1

            val desiredW = desiredX1Ex - desiredX0
            val desiredH = desiredY1Ex - desiredY0

            // Clamp desired to base image to get actual crop
            val cropX0 = max(0, desiredX0)
            val cropY0 = max(0, desiredY0)
            val cropX1Ex2 = min(w, desiredX1Ex)
            val cropY1Ex2 = min(h, desiredY1Ex)

            val cropW = cropX1Ex2 - cropX0
            val cropH = cropY1Ex2 - cropY0

            // Padding needed to reach desired margin
            val padLeft = cropX0 - desiredX0
            val padTop = cropY0 - desiredY0
            val padRight = desiredW - padLeft - cropW
            val padBottom = desiredH - padTop - cropH

            val cropped = base.getSubimage(cropX0, cropY0, cropW, cropH)

            if (padLeft == 0 && padTop == 0 && padRight == 0 && padBottom == 0) {
                cropped
            } else {
                val out = BufferedImage(desiredW, desiredH, BufferedImage.TYPE_INT_ARGB)
                val g: Graphics2D = out.createGraphics()
                try {
                    // Fill with border color
                    val fill = IntArray(desiredW * desiredH) { borderColor }
                    out.setRGB(0, 0, desiredW, desiredH, fill, 0, desiredW)
                    // Blit crop into padded position
                    g.drawImage(cropped, padLeft, padTop, null)
                } finally {
                    g.dispose()
                }
                out
            }
        }
    } else {
        // Non-uniform border → do not modify the file
        return
    }

    ImageIO.write(result, "png", File(inputPath))
}

fun captureRectToPng(rect: Rectangle, out: File) {
    // Clamp to screen bounds (helps on multi-monitor setups)
    val screen = Rectangle(Toolkit.getDefaultToolkit().screenSize)
    val r = rect.intersection(screen)
    val img: BufferedImage = Robot().createScreenCapture(r)
    ImageIO.write(img, "png", out)
}

fun activateThisProcessMac(processName: String) {
    // processName should match what shows in Activity Monitor / menu bar (often your bundle name; if running as "java", use "java")
    val script = """
        tell application "System Events"
            set frontmost of (first process whose name is "$processName") to true
        end tell
    """.trimIndent()
    val p = ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start()
    if (p.waitFor() != 0) {
        val err = p.inputStream.bufferedReader().readText()
        error("Failed to frontmost process '$processName': $err")
    }
}

// helper for docs, not for rendering things for real
suspend fun Window.raiseAndScreenshot(waitFrames: Int, outPath: String) {
    GLFW.glfwShowWindow(windowHandle)
    GLFW.glfwFocusWindow(windowHandle)
    activateThisProcessMac(processName = "java") // or your actual app/bundle name
    Thread.sleep(500)
    waitFrames { println(" -- waiting for window to come to front --"); yield() }
    Thread.sleep(500)
    captureGlfwWindow(windowHandle) { x, y, w, h ->
        val proc = ProcessBuilder(
            "screencapture", "-x", "-R", "$x,$y,$w,$h", outPath.toString()
        ).redirectErrorStream(true).start()
        val code = proc.waitFor()
        require(code == 0) { "screencapture failed (exit $code): ${proc.inputStream.bufferedReader().readText()}" }
    }
}

suspend fun Window.raiseAndRecord(waitFrames: Int, outPath: String) {
    GLFW.glfwShowWindow(windowHandle)
    GLFW.glfwFocusWindow(windowHandle)
    activateThisProcessMac(processName = "java") // or your actual app/bundle name
    Thread.sleep(500)
    waitFrames { println(" -- waiting for window to come to front --"); yield() }
    Thread.sleep(500)
    captureGlfwWindow(windowHandle) { x, y, w, h ->
        recordProcessReturnMp4WithScreencaptureAsync(x + 10, y + 40, w - 20, h - 50, File(outPath).toPath())
    }
}

suspend fun captureGlfwWindow(window: Long, callback: suspend (Int, Int, Int, Int) -> Unit) {
    var pxX = 0
    var pxY = 0
    var pxW = 0
    var pxH = 0

    MemoryStack.stackPush().use { stack ->
        val pX = stack.mallocInt(1)
        val pY = stack.mallocInt(1)
        val pW = stack.mallocInt(1)
        val pH = stack.mallocInt(1)
        val fL = stack.mallocInt(1)
        val fT = stack.mallocInt(1)
        val fR = stack.mallocInt(1)
        val fB = stack.mallocInt(1)
        val sX = stack.mallocFloat(1)
        val sY = stack.mallocFloat(1)

        // Content-area position (logical screen coords, excludes frame)
        GLFW.glfwGetWindowPos(window, pX, pY)
        // Content size (logical units)
        GLFW.glfwGetWindowSize(window, pW, pH)
        // Frame sizes (pixels added around content for borders/titlebar; reported in logical coords)
        GLFW.glfwGetWindowFrameSize(window, fL, fT, fR, fB)
        // Content scale (logical → physical pixel ratio)
        GLFW.glfwGetWindowContentScale(window, sX, sY)

        val contentX = pX.get(0)
        val contentY = pY.get(0)
        val contentW = pW.get(0)
        val contentH = pH.get(0)
        val frameL = fL.get(0)
        val frameT = fT.get(0)
        val frameR = fR.get(0)
        val frameB = fB.get(0)
        val scaleX = sX.get(0).toDouble()
        val scaleY = sY.get(0).toDouble()

        // Outer (decorated) window rect in logical screen coords
        val outerX = contentX - frameL
        val outerY = contentY - frameT
        val outerW = contentW + frameL + frameR
        val outerH = contentH + frameT + frameB

        // Convert to physical pixels for AWT Robot
        pxX = (outerX * scaleX).roundToInt()
        pxY = (outerY * scaleY).roundToInt()
        pxW = (outerW * scaleX).roundToInt()
        pxH = (outerH * scaleY).roundToInt()
    }
    callback(pxX, pxY, pxW, pxH)
}

data class CropBox(val w: Int, val h: Int, val x: Int, val y: Int)

data class ProcResult(val exitCode: Int, val out: String, val err: String)

suspend fun runProcessLogged(
    args: List<String>,
    onStdoutLine: ((String) -> Unit)? = null,
    onStderrLine: ((String) -> Unit)? = null,
    charset: Charset = Charsets.UTF_8
): ProcResult = withContext(Dispatchers.IO) {
    val p = ProcessBuilder(args).start()

    val outBuf = StringBuilder()
    val errBuf = StringBuilder()

    fun CoroutineScope.gobbler(reader: BufferedReader, sink: StringBuilder, cb: ((String) -> Unit)?) =
        launch(start = CoroutineStart.DEFAULT, context = Dispatchers.IO) {
            try {
                reader.useLines { lines ->
                    lines.forEach { line ->
                        println(line)
                        sink.appendLine(line)
                        cb?.invoke(line) // print live if you want
                    }
                }
            } catch (_: Throwable) { /* process ended; stream closed */
            }
        }

    val stdoutJob = coroutineScope {
        gobbler(p.inputStream.bufferedReader(charset), outBuf, onStdoutLine)
    }
    val stderrJob = coroutineScope {
        gobbler(p.errorStream.bufferedReader(charset), errBuf, onStderrLine)
    }

    // Non-blocking wait for process exit
    val exit = suspendCancellableCoroutine<Int> { cont ->
        p.onExit().thenAccept { cont.resume(it.exitValue()) {} }
        cont.invokeOnCancellation {
            runCatching { p.destroy() }
            GlobalScope.launch(Dispatchers.IO) {
                delay(200)
                if (p.isAlive) runCatching { p.destroyForcibly() }
            }
        }
    }

    // Make sure we drain any remaining output after process exit
    stdoutJob.join()
    stderrJob.join()

    ProcResult(exit, outBuf.toString(), errBuf.toString())
}

suspend fun recordProcessReturnMp4WithScreencaptureAsync(
    x: Int,
    y: Int,
    w: Int,
    h: Int,           // region in device pixels
    out: Path,                                 // final MP4
    durationSec: Int = 5,
    margin: Int = 20,
    displayIndex: Int = 1,
    showClicks: Boolean = false,
    captureAudio: Boolean = false,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
): Path = withContext(Dispatchers.IO) {
    File("/tmp/tempScreenCapture.mov").delete()
    val tmpMov = File("/tmp/tempScreenCapture.mov").toPath()
    try {
        // 1) Start screencapture (video) asynchronously
        val args = buildList {
            addAll(listOf("screencapture", "-x", "-v"))
            addAll(listOf("-R", "$x,$y,$w,$h"))
            addAll(listOf("-V", durationSec.toString()))
            if (showClicks) add("-k")
            if (captureAudio) add("-g")
            add(tmpMov.toString())
        }

        val (res, err, out_) = runProcessLogged(args)
        if (res != 0) throw IllegalArgumentException(" problems screengrabbing args=`${args}` err='$err' out='$out_' -> $res")

        // 2) Detect tight crop (still off the main thread)
        val crop = detectCropBox(tmpMov, probeSeconds = durationSec)

        // 3) Encode with margin to web-ready MP4 (H.264 +faststart)
        encodeWithCropAndMargin(tmpMov, out, crop, margin, includeAudio = captureAudio)

        out
    } finally {
        runCatching { java.nio.file.Files.deleteIfExists(tmpMov) }
    }
}

private fun runAndCheck(args: List<String>, tag: String) {
    val p = ProcessBuilder(args).redirectErrorStream(true).start()
    val log = p.inputStream.bufferedReader().readText()
    val code = p.waitFor()
    require(code == 0) { "Command failed ($tag, exit=$code):\n${args.joinToString(" ")}\n$log" }
}

fun detectCropBox(input: Path, probeSeconds: Int = 4, limit: Int = 5, round: Int = 16): CropBox {
    val args = listOf(
        FFMPEG,
        "-v",
        "info",
        "-y",
        "-i",
        input.toString(),
        "-t",
        probeSeconds.toString(),
        "-vf",
        "edgedetect,cropdetect=$limit:$round:0",
        "-f",
        "null",
        "-"
    )
    val p = ProcessBuilder(args).redirectErrorStream(true).start()
    val log = p.inputStream.bufferedReader().readText()
    val code = p.waitFor()
    require(code == 0) { "ffmpeg cropdetect failed ($code)\n$log" }

    val m = Pattern.compile("crop=(\\d+):(\\d+):(\\d+):(\\d+)").matcher(log)
    var last: CropBox? = null
    while (m.find()) last = CropBox(m.group(1).toInt(), m.group(2).toInt(), m.group(3).toInt(), m.group(4).toInt())
    return last ?: error("No crop suggestion found")
}

fun encodeWithCropAndMargin(
    input: Path,
    output: Path,
    crop: CropBox,
    margin: Int = 20,
    crf: Int = 20,
    preset: String = "veryfast",
    includeAudio: Boolean = false
) {
    // First crop to detected box, then expand by margin but clamp to frame bounds.
    val filter = """
        [0:v]crop=${crop.w}:${crop.h}:${crop.x}:${crop.y}[c];
        [c]crop=
          'min(iw, ${crop.w} + 2*${margin})':
          'min(ih, ${crop.h} + 2*${margin})':
          'max(0,  ${crop.x} - ${margin})':
          'max(0,  ${crop.y} - ${margin})'[v]
    """.trimIndent().replace("\n", "")

    val args = buildList {
        addAll(listOf(FFMPEG, "-y", "-i", input.toString()))
        addAll(listOf("-filter_complex", filter, "-map", "[v]"))
        if (includeAudio) addAll(listOf("-map", "0:a?"))
        addAll(
            listOf(
                "-c:v",
                "libx264",
                "-crf",
                crf.toString(),
                "-preset",
                preset,
                "-pix_fmt",
                "yuv420p",
                "-movflags",
                "+faststart"
            )
        )
        if (includeAudio) addAll(listOf("-c:a", "aac", "-b:a", "128k")) else add("-an")
        add(output.toString())
    }
    runAndCheck(args, "ffmpeg-encode")
}
