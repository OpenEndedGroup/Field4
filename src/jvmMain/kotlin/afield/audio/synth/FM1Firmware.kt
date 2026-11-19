@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Builds the FM-1 simulator from its C source, so a box can change the firmware and hear it.
 *
 * ```kotlin
 * val source = FM1Firmware.checkout()
 * FM1Firmware.patch(source, "third_party/synth/bass303.c",
 *     "out[i]=(int32_t)(sample*16777216);",
 *     "out[i]=(int32_t)((int)(sample*4)*4194304);") // the 303 at three bits
 * fm1.loadFirmware(FM1Firmware.build(source))
 * ```
 *
 * [checkout] clones the open firmware (github.com/m-vave/fm1_open) at the commit Field's own module
 * was built from; [build] compiles it with the flags of the repository's `tools/Build-Web.ps1`
 * using WASI SDK 27, which [sdk] downloads once (about 115 MB, checked against the release's
 * SHA-256) into `~/.field4/toolchains`. A build takes a few seconds. [FM1Sim.loadFirmware] swaps
 * the result into a running simulator without stopping the sequence.
 *
 * The firmware is GPL-3.0 with a linking exception and its voice engines are MIT and Apache-2.0
 * (the repository's `LICENSE.md` and `THIRD_PARTY.md`); a module built from changed source is a
 * modified version of it, under the same terms.
 */
object FM1Firmware {

    /** The firmware repository. */
    const val REPOSITORY: String = "https://github.com/m-vave/fm1_open"

    /** The commit Field's built-in module (`fm1/fm1-sim.wasm`) was built from, "FM1 Open V14". */
    const val COMMIT: String = "4c7496daac04a95ff10d1afdcc8f9ead2dbabc41"

    /** The WASI SDK release [build] compiles with, the one the firmware's own web build pins. */
    const val SDK_VERSION: String = "27.0"

    /** The SHA-256 of each WASI SDK 27 release archive, by platform, as GitHub publishes them. */
    private val SDK_SHA256 = mapOf(
        "arm64-macos" to "055c3dc2766772c38e71a05d353e35c322c7b2c6458a36a26a836f9808a550f8",
        "x86_64-macos" to "163dfd47f989b1a682744c1ae1f0e09a83ff5c4bbac9dcd8546909ab54cda5a1",
        "arm64-linux" to "4cf4c553c4640e63e780442146f87d83fdff5737f988c06a6e3b2f0228e37665",
        "x86_64-linux" to "b7d4d944c88503e4f21d84af07ac293e3440b1b6210bfd7fe78e0afd92c23bc2",
        "x86_64-windows" to "4a576c13125c91996d8cc3b70b7ea0612c2044598d2795c9be100d15f874adf6",
        "arm64-windows" to "2eed3c05909e090b4bc04ed6bd3ff476d4a383d1d79c844dff1b047db5974ff2",
    )

    /** The compiler flags of `tools/Build-Web.ps1`, before the sources and the link flags. */
    val COMPILE_FLAGS: List<String> = listOf(
        "--target=wasm32-wasip1", "-std=c11", "-O2", "-fwrapv", "-fno-fast-math", "-ffp-contract=off",
        "-DFM1_HOST_TEST", "-DFM1_OPEN_WEB", "-Wall", "-Wextra", "-Werror", "-Wno-unused-function",
        "-Wno-array-parameter", "-Wno-unused-command-line-argument", "-mexec-model=reactor",
        "-Iweb/engine", "-Iweb/synth", "-Ithird_party/synth", "-Isdk/include", "-Iapp/synth", "-Iapp",
    )

    /** The link flags of `tools/Build-Web.ps1`: no symbols, a 128 KiB stack, 4 to 32 MiB of memory. */
    val LINK_FLAGS: List<String> = listOf(
        "-Wl,--strip-all", "-Wl,-z,stack-size=131072", "-Wl,--initial-memory=4194304", "-Wl,--max-memory=33554432",
    )

    private val home: File get() = File(System.getProperty("user.home"))

    /** Where [checkout] puts the source when given no folder: `~/.field4/fm1/fm1_open`. */
    val defaultSource: File get() = File(home, ".field4/fm1/fm1_open")

    /** This machine's WASI SDK platform name, such as `arm64-macos`. */
    fun platform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val a = if (arch == "aarch64" || arch == "arm64") "arm64" else if (arch == "x86_64" || arch == "amd64") "x86_64" else arch
        val o = when {
            os.contains("mac") -> "macos"
            os.contains("win") -> "windows"
            os.contains("linux") -> "linux"
            else -> os
        }
        return "$a-$o"
    }

    /**
     * The WASI SDK folder: `FM1_WASI_SDK` if set (the variable the firmware's own build reads),
     * otherwise `~/.field4/toolchains/wasi-sdk-27.0-<platform>`, downloaded and checked the first time.
     */
    fun sdk(): File {
        System.getenv("FM1_WASI_SDK")?.takeIf { it.isNotBlank() }?.let { path ->
            val dir = File(path)
            require(clangIn(dir).canExecute()) { "FM1Firmware: FM1_WASI_SDK=$path has no bin/clang" }
            return dir
        }
        val name = "wasi-sdk-$SDK_VERSION-${platform()}"
        val toolchains = File(home, ".field4/toolchains")
        val dir = File(toolchains, name)
        if (clangIn(dir).canExecute()) return dir
        val sha = SDK_SHA256[platform()]
            ?: error("FM1Firmware: no WASI SDK $SDK_VERSION for ${platform()}; set FM1_WASI_SDK to one you have")
        toolchains.mkdirs()
        val archive = File(toolchains, "$name.tar.gz")
        if (!archive.isFile || sha256(archive) != sha) {
            val url = "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${SDK_VERSION.substringBefore('.')}/$name.tar.gz"
            println("FM1Firmware: downloading WASI SDK $SDK_VERSION for ${platform()} (about 115 MB) from $url")
            val part = File(toolchains, "$name.tar.gz.part")
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            val response = client.send(
                HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "Field4").GET().build(),
                HttpResponse.BodyHandlers.ofFile(part.toPath())
            )
            check(response.statusCode() in 200..299) { "FM1Firmware: HTTP ${response.statusCode()} fetching $url" }
            val actual = sha256(part)
            check(actual == sha) { part.delete(); "FM1Firmware: $url has SHA-256 $actual, expected $sha" }
            Files.move(part.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        run(toolchains, listOf("tar", "-xzf", archive.name), "unpacking the WASI SDK")
        check(clangIn(dir).canExecute()) { "FM1Firmware: $archive did not unpack to $dir" }
        archive.delete()
        return dir
    }

    private fun clangIn(sdk: File): File = compiler(sdk, cxx = false)

    /** The SDK's C (or, with [cxx], C++) compiler. */
    internal fun compiler(sdk: File, cxx: Boolean): File =
        File(sdk, "bin/" + (if (cxx) "clang++" else "clang") + if (platform().endsWith("windows")) ".exe" else "")

    /**
     * The firmware source in [dir], cloning [REPOSITORY] at [COMMIT] there if it holds none yet.
     * A folder of your own (beside a document, say) gives you a copy to edit; the default one is
     * shared by every box that asks for it.
     */
    fun checkout(dir: File = defaultSource): File {
        if (File(dir, "web/engine/Simulator.c").isFile) return dir
        require(!dir.exists() || dir.listFiles().isNullOrEmpty()) {
            "FM1Firmware: $dir is not empty and holds no firmware source (no web/engine/Simulator.c)"
        }
        dir.parentFile?.mkdirs()
        println("FM1Firmware: cloning $REPOSITORY into $dir")
        run(dir.absoluteFile.parentFile, listOf("git", "clone", "--quiet", REPOSITORY, dir.absolutePath), "cloning the firmware")
        run(dir, listOf("git", "checkout", "--quiet", COMMIT), "checking out $COMMIT")
        return dir
    }

    /**
     * Replace [find] with [replace] in the source file [path] (relative to [source]).
     *
     * [find] must occur exactly once, so an edit written against one version of the source fails
     * loudly against another instead of silently doing nothing. Applying the same patch twice is
     * allowed: if [find] is gone and [replace] is there, the file is left as it is.
     */
    fun patch(source: File, path: String, find: String, replace: String) {
        val file = File(source, path)
        require(file.isFile) { "FM1Firmware: no $path in $source" }
        val text = file.readText()
        require(find.isNotEmpty()) { "FM1Firmware: a patch needs text to find" }
        var count = 0
        var at = text.indexOf(find)
        while (at >= 0) { count++; at = text.indexOf(find, at + 1) }
        if (count == 0 && text.contains(replace)) return
        require(count == 1) { "FM1Firmware: \"$find\" occurs $count times in $path; a patch needs exactly one" }
        file.writeText(text.replace(find, replace))
    }

    /** Undo every edit to the source in [source] (`git checkout .`), back to [COMMIT]. */
    fun reset(source: File) {
        run(source, listOf("git", "checkout", "--quiet", COMMIT, "--", "."), "resetting the firmware source")
    }

    /**
     * Compile the firmware in [source] to a simulator module at [out], and return [out].
     *
     * @throws FM1FirmwareBuildException with the compiler's own messages when the C does not
     *   compile (warnings count as errors, as in the firmware's own build)
     */
    fun build(source: File = defaultSource, out: File = File(source, "build/field/fm1-sim.wasm")): File {
        require(File(source, "web/engine/Simulator.c").isFile) { "FM1Firmware: $source holds no firmware source" }
        val sdk = sdk()
        val sources = listOf("app", "web", "third_party").flatMap { root ->
            File(source, root).walkTopDown()
                .filter { it.isFile && it.name.endsWith(".c") }
                .map { it.relativeTo(source).invariantSeparatorsPath }
                .filter { !it.startsWith("app/board/") }
                .toList()
        }.sorted()
        out.absoluteFile.parentFile.mkdirs()
        val command = listOf(clangIn(sdk).absolutePath, "--sysroot=${File(sdk, "share/wasi-sysroot").absolutePath}") +
            COMPILE_FLAGS + sources + LINK_FLAGS + listOf("-o", out.absolutePath)
        val (code, output) = exec(source, command)
        if (code != 0) throw FM1FirmwareBuildException(output.trim())
        return out
    }

    private fun run(dir: File, command: List<String>, what: String) {
        val (code, output) = exec(dir, command)
        check(code == 0) { "FM1Firmware: $what failed (exit $code): ${output.trim()}" }
    }

    internal fun exec(dir: File, command: List<String>): Pair<Int, String> {
        val p = ProcessBuilder(command).directory(dir).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        check(p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); "FM1Firmware: ${command.first()} did not finish" }
        return p.exitValue() to output
    }

    private fun sha256(file: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}

/** The firmware did not compile; [message] is the compiler's output, file and line first. */
class FM1FirmwareBuildException(message: String) : RuntimeException(message)
