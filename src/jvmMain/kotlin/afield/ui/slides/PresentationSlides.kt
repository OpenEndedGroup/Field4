package afield.ui.slides

import afield.app.protocol.AudienceContent
import afield.document.workspace.file
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Editable content for a talk. Live actions remain explicit Kotlin guide steps. */
@Serializable
data class PresentationSlides(
    val schema: String = "field.presentation.slides.v1",
    val stylesheet: String = "presentation.css",
    val slides: List<PresentationSlide> = emptyList()
) {
    fun validated(): PresentationSlides = apply {
        require(schema == "field.presentation.slides.v1") { "Unknown slide file schema" }
        require(slides.size <= 1000 && slides.map { it.id }.distinct().size == slides.size) { "Use at most 1000 uniquely identified slides" }
        require(stylesheet.isEmpty() || (stylesheet.length <= 512 && !stylesheet.contains('"') && !stylesheet.contains('<') && !stylesheet.contains('>'))) { "Invalid stylesheet path" }
        slides.forEach { slide ->
            require(slide.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "Use a short stable slide ID: ${slide.id}" }
            require(slide.title.isNotBlank() && slide.title.length <= 4096) { "A slide needs a title" }
            require(slide.markdown.length <= 100_000 && slide.notes.length <= 32_000) { "Slide text exceeds the limit" }
            require(slide.section.size <= 16 && slide.section.all { it.isNotBlank() && it.length <= 4096 }) { "Invalid slide section" }
            require(slide.cues.size <= 100 && slide.cues.all { it.title.isNotBlank() && it.title.length <= 4096 && it.markdown.length <= 100_000 }) { "Invalid slide cues" }
        }
    }
}

@Serializable
data class PresentationSlide(val id: String, val title: String, val markdown: String,
    val notes: String = "", val section: List<String> = emptyList(), val cues: List<PresentationReveal> = emptyList())

/** A content-only reveal replaces the slide body and never executes code. */
@Serializable
data class PresentationReveal(val title: String, val markdown: String)

/** Document-local files shared by the Kotlin builder and AI4's revision-checked slide tools. */
class PresentationSlideFiles(root: File) {
    private val root = root.canonicalFile
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private fun target(path: String): File {
        require(path.startsWith("files/") && path.endsWith(".slides.json") && !File(path).isAbsolute) { "Use files/<name>.slides.json inside this document" }
        val raw = File(root, path).toPath().normalize()
        require(raw.startsWith(File(root, "files").toPath())) { "Slide files must stay inside files/" }
        var probe = raw
        while (probe != root.toPath()) {
            require(!Files.isSymbolicLink(probe)) { "Slide paths cannot contain symbolic links" }
            probe = requireNotNull(probe.parent)
        }
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(File(root, "files").canonicalFile.toPath()) && file.toPath().startsWith(root.toPath())) { "Slide files must stay inside this document" }
        return file
    }

    data class Snapshot(val revision: String, val deck: PresentationSlides)
    fun read(path: String): Snapshot = readFile(target(path))

    companion object {
        private val writeLock = Any()
        private val decoder = Json
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun readFile(file: File): Snapshot {
            require(file.isFile && file.length() <= 2_000_000) { "Slide file is missing or exceeds 2 MB: ${file.name}" }
            val bytes = file.readBytes()
            require(bytes.size <= 2_000_000) { "Slide file exceeds 2 MB" }
            return Snapshot(hash(bytes), decoder.decodeFromString<PresentationSlides>(bytes.toString(Charsets.UTF_8)).validated())
        }
    }

    /** Empty revision creates a new file; every replacement must name the exact revision just read. */
    fun write(path: String, revision: String, deck: PresentationSlides): Snapshot = synchronized(writeLock) {
        deck.validated()
        val file = target(path)
        fun matches() = if (file.exists()) revision.isNotEmpty() && readFile(file).revision == revision else revision.isEmpty()
        check(matches()) { "Slide file changed. Read again and reconcile; nothing was overwritten." }
        val bytes = json.encodeToString(PresentationSlides.serializer(), deck).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2_000_000) { "Slide file exceeds 2 MB" }
        file.parentFile.mkdirs()
        val tmp = Files.createTempFile(file.parentFile.toPath(), ".slides-", ".tmp")
        try {
            Files.write(tmp, bytes)
            check(matches()) { "Slide file changed. Read again and reconcile; nothing was overwritten." }
            if (file.exists()) runCatching { Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(file.toPath())) }
            Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(tmp) }
        readFile(file)
    }
}

/** Insert editable Markdown slides at this point in the talk. Refresh reloads existing slide bodies from disk. */
fun SlideShowBuilder.slides(path: String) = slides(path.file)

/** Load a specific slide file; relative media still resolves in the presentation document's files directory. */
fun SlideShowBuilder.slides(file: File) {
    val deck = PresentationSlideFiles.readFile(file).deck
    fun body(markdown: String, css: String): AudienceContent {
        val style = if (css.isEmpty()) "" else "<link rel=\"stylesheet\" data-field-presentation-style href=\"${css.replace("&", "&amp;").replace("\"", "&quot;")}\">"
        return AudienceContent(html = style + SlideMarkdown.render(markdown))
    }
    deck.slides.forEach { slide ->
        fun SlideShowBuilder.add(depth: Int) {
            if (depth < slide.section.size) section(slide.section[depth]) { add(depth + 1) }
            else step(slide.title, id = slide.id) {
                markdownNote(slide.notes)
                audienceProvider = {
                    val current = PresentationSlideFiles.readFile(file).deck
                    val value = current.slides.singleOrNull { it.id == slide.id } ?: error("Slide '${slide.id}' was removed; rebuild the Presentation box")
                    body(value.markdown, current.stylesheet)
                }
                slide.cues.forEachIndexed { index, reveal -> cue(reveal.title) {
                    audienceProvider = {
                        val current = PresentationSlideFiles.readFile(file).deck
                        val value = current.slides.singleOrNull { it.id == slide.id } ?: error("Slide was removed; rebuild the Presentation box")
                        val next = value.cues.getOrNull(index) ?: error("Cue was removed; rebuild the Presentation box")
                        body(next.markdown, current.stylesheet)
                    }
                } }
            }
        }
        add(0)
    }
}
