@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.JarURLConnection
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.time.Duration

/** What [StudyBuildContext.runProcess] saw: the exit code, the retained [log], and the last progress message. */
data class StudyBuildProcessResult(val exitCode: Int, val log: File, val lastMessage: String?)

private val helperMapper = jacksonObjectMapper()

internal fun StudyBuilds.runProcess(attempt: StudyBuildRecord, command: List<String>, progressFile: String?, stall: Duration?,
    deadline: Duration?, log: String, environment: Map<String, String>, progressSpan: ClosedFloatingPointRange<Double>,
    check: Boolean): StudyBuildProcessResult {
    require(command.isNotEmpty()) { "Name the program to run" }
    require(stall == null || stall.isPositive()) { "The stall limit must be positive" }
    require(deadline == null || deadline.isPositive()) { "The deadline must be positive" }
    require(progressSpan.start >= 0.0 && progressSpan.endInclusive <= 1.0 && progressSpan.start <= progressSpan.endInclusive) {
        "The progress span must lie within 0..1"
    }
    val program = File(command.first()).name
    val logFile = artifactFile(attempt.id, log).also { it.parentFile.mkdirs() }
    val progressTarget = progressFile?.let { artifactFile(attempt.id, it) }
    checkCancelled(attempt.id)
    val process = ProcessBuilder(command).directory(attemptDirectory(attempt.id)).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile)).also { it.environment().putAll(environment) }.start()
    val started = System.nanoTime()
    var lastChange = started
    var seen: Any? = null
    var lastMessage: String? = null
    fun failure(code: String, message: String, exitCode: Int? = null) = StudyBuildFailure(StudyBuildProblem(code, message,
        listOf("Inspect $log in this attempt's directory."),
        mapOf("command" to command, "log" to log, "lastMessage" to lastMessage, "exitCode" to exitCode)))
    // A progress file the program is rewriting can be read half-written; that read is skipped, not reported.
    fun observe(): Any? = if (progressTarget == null) logFile.length() else runCatching { progressTarget.readText() }.getOrNull()
    fun report(text: Any?) {
        val node = (text as? String)?.let { runCatching { helperMapper.readTree(it) }.getOrNull() } ?: return
        val fraction = node.path("fraction").takeIf { it.isNumber }?.asDouble()?.takeIf { it.isFinite() } ?: return
        node.path("message").takeIf { it.isTextual }?.asText()?.let { lastMessage = it.take(4000) }
        val scaled = progressSpan.start + fraction.coerceIn(0.0, 1.0) * (progressSpan.endInclusive - progressSpan.start)
        progress(attempt.id, maxOf(scaled, recordedProgress(attempt.id)), lastMessage ?: "Running $program")
    }
    try {
        while (!process.waitFor(PROCESS_POLL_MS, TimeUnit.MILLISECONDS)) {
            checkCancelled(attempt.id)
            val now = System.nanoTime()
            val observed = observe()
            if (observed != seen) { seen = observed; lastChange = now; if (progressTarget != null) report(observed) }
            if (deadline != null && now - started >= deadline.inWholeNanoseconds)
                throw failure("process-deadline", "$program did not finish within $deadline, so it was stopped.")
            if (stall != null && now - lastChange >= stall.inWholeNanoseconds)
                throw failure("process-stalled", "$program made no progress for $stall" +
                    (lastMessage?.let { " (last: $it)" } ?: "") + ", so it was stopped.")
        }
        observe().let { if (it != seen && progressTarget != null) report(it) }
        val exit = process.exitValue()
        if (check && exit != 0) throw failure("process-failed", "$program exited with code $exit.", exit)
        return StudyBuildProcessResult(exit, logFile, lastMessage)
    } finally {
        if (process.isAlive) stopProcessTree(process)
    }
}

private const val PROCESS_POLL_MS = 200L

// Children are listed before the parent stops, because a child whose parent has exited is no longer its descendant.
private fun stopProcessTree(process: Process) {
    val tree = process.descendants().toList()
    fun force() { tree.forEach { it.destroyForcibly() }; process.destroyForcibly() }
    tree.forEach { it.destroy() }
    process.destroy()
    try {
        if (!process.waitFor(2, TimeUnit.SECONDS)) force()
        tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
    } catch (e: InterruptedException) {
        force(); Thread.currentThread().interrupt()
    }
}

/**
 * A hash of the contents of [files], each named by its file name, for a stage's `recipeVersion` or an
 * input, so a build goes stale exactly when the code it runs changes. A directory contributes every file
 * under it that [include] accepts (by default, not hidden files and not Python bytecode), named by its
 * path inside the directory. Where the files live is not hashed, so moving the document keeps the identity.
 */
fun fileIdentity(files: Iterable<File>, include: (File) -> Boolean = { !it.name.startsWith(".") && it.extension != "pyc" }): String {
    val entries = sortedMapOf<String, String>()
    for (file in files) {
        require(file.exists()) { "No file '${file.path}' to identify" }
        val named = if (!file.isDirectory) sequenceOf(file.name to file)
            else file.walkTopDown().onEnter { it == file || include(it) }.filter { it.isFile && include(it) }
                .map { "${file.name}/${it.relativeTo(file).invariantSeparatorsPath}" to it }
        for ((name, found) in named) require(entries.put(name, sha256(found.readBytes())) == null) {
            "Two files are named '$name'; identify them separately or by their directory"
        }
    }
    require(entries.isNotEmpty()) { "Name at least one file to identify" }
    return SourceStudy.hash(helperMapper.writeValueAsString(entries))
}

/** [fileIdentity] of [files]. */
fun fileIdentity(vararg files: File): String = fileIdentity(files.asList())

/**
 * A hash of the compiled code of [classes], each with every class compiled inside it (nested classes,
 * companions, anonymous objects), for a stage's `recipeVersion`. Name the classes that do the build's
 * work rather than a whole provider, so editing unrelated code leaves results current. A class whose
 * compiled code cannot be read, such as one defined in a box, is refused: identify its source with
 * [fileIdentity] instead.
 */
fun classIdentity(classes: Iterable<KClass<*>>): String {
    val entries = sortedMapOf<String, String>()
    classes.forEach { type -> compiledClasses(type.java).forEach { (name, bytes) -> entries[name] = sha256(bytes) } }
    require(entries.isNotEmpty()) { "Name at least one class to identify" }
    return SourceStudy.hash(helperMapper.writeValueAsString(entries))
}

/** [classIdentity] of [classes]. */
fun classIdentity(vararg classes: KClass<*>): String = classIdentity(classes.asList())

/** The bytecode of [type] and of every class whose binary name begins with `type.name$`, by binary name. */
internal fun compiledClasses(type: Class<*>): Map<String, ByteArray> {
    val path = type.name.replace('.', '/') + ".class"
    val url = (type.classLoader ?: ClassLoader.getSystemClassLoader()).getResource(path)
        ?: error("Cannot read the compiled code of ${type.name}; identify its source files with fileIdentity instead")
    val result = sortedMapOf(type.name to url.readBytes())
    val inner = path.removeSuffix(".class") + "$"
    when (url.protocol) {
        "file" -> File(url.toURI()).parentFile.listFiles().orEmpty()
            .filter { it.name.startsWith(inner.substringAfterLast('/')) && it.name.endsWith(".class") }
            .forEach { result[listOf(type.packageName, it.nameWithoutExtension).filter(String::isNotEmpty).joinToString(".")] = it.readBytes() }
        "jar" -> (url.openConnection() as JarURLConnection).also { it.useCaches = false }.jarFile.use { jar ->
            jar.entries().asSequence().filter { it.name.startsWith(inner) && it.name.endsWith(".class") }
                .forEach { entry -> result[entry.name.removeSuffix(".class").replace('/', '.')] = jar.getInputStream(entry).use { it.readBytes() } }
        }
    }
    return result
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
