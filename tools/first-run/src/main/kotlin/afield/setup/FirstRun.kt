package afield.setup

import java.io.File
import java.util.Properties
import kotlin.system.exitProcess

internal data class InstallPlan(val commands: List<List<String>>, val notes: List<String>)

internal fun packagePlan(manager: String?, desktop: Boolean, missing: Set<String>, extras: Set<String>, ubuntuT64: Boolean): InstallPlan {
    val packages = linkedSetOf<String>()
    val notes = mutableListOf<String>()
    val commands = mutableListOf<List<String>>()
    if (manager != null && manager != "brew") packages += missing.filter { it in setOf("curl", "tar", "gzip") }
    val buildMissing = desktop && missing.any { it in setOf("g++", "cmake", "make", "xcrun") }
    when (manager) {
        "apt-get" -> {
            if ("git-lfs" in missing) packages += "git-lfs"
            if (buildMissing) packages += listOf("build-essential", "cmake")
            if (desktop) packages += listOf(if (ubuntuT64) "libgtk-3-0t64" else "libgtk-3-0", "libnss3", if (ubuntuT64) "libasound2t64" else "libasound2", "libgbm1", "libxkbcommon0")
            if ("vulkan" in extras) packages += listOf("libvulkan1", "vulkan-tools")
            if ("python" in extras) packages += listOf("python3", "python3-dev", "python3-venv", "build-essential")
            if (packages.isNotEmpty()) commands += listOf("sudo", "apt-get", "install", "--") + packages
        }
        "dnf" -> {
            if ("git-lfs" in missing) packages += "git-lfs"
            if (buildMissing) packages += listOf("gcc", "gcc-c++", "make", "cmake")
            if (desktop) packages += listOf("gtk3", "nss", "alsa-lib", "mesa-libgbm", "libxkbcommon")
            if ("vulkan" in extras) packages += listOf("vulkan-loader", "vulkan-tools")
            if ("python" in extras) packages += listOf("python3", "python3-devel", "gcc", "gcc-c++", "make")
            if (packages.isNotEmpty()) commands += listOf("sudo", "dnf", "install", "--") + packages
        }
        "pacman" -> {
            if ("git-lfs" in missing) packages += "git-lfs"
            if (buildMissing) packages += listOf("base-devel", "cmake")
            if (desktop) packages += listOf("gtk3", "nss", "alsa-lib", "mesa", "libxkbcommon")
            if ("vulkan" in extras) packages += listOf("vulkan-icd-loader", "vulkan-tools")
            if ("python" in extras) packages += listOf("python", "base-devel")
            // Do not refresh only the package index on Arch: that can create a partial upgrade.
            if (packages.isNotEmpty()) commands += listOf("sudo", "pacman", "-S", "--needed", "--") + packages
            if (packages.isNotEmpty()) notes += "Use a fully updated Arch system. If package downloads return 404, update the system normally before retrying."
        }
        "brew" -> {
            if ("git-lfs" in missing) packages += "git-lfs"
            if ("python" in extras) packages += "python@3.12"
            if (packages.isNotEmpty()) commands += listOf("brew", "install", "--") + packages
            if (buildMissing || "python" in extras) notes += "Apple Command Line Tools: xcode-select --install (finish Apple's dialog, then rerun setup)."
            if ("vulkan" in extras) notes += "Use the welcome page's Vulkan panel for macOS SDK/driver setup; Linux Vulkan packages do not apply."
        }
        else -> {
            if (missing.isNotEmpty()) notes += "Install missing tools with your platform's package manager: ${missing.sorted().joinToString()}."
            if ("vulkan" in extras) notes += "Use the welcome page's Vulkan panel for your platform's driver/SDK instructions."
            if ("python" in extras) notes += "Install CPython and its development tools, then use the welcome page's Python panel to select it and install JEP."
        }
    }
    if ("python" in extras) notes += "This installs Python prerequisites, not JEP. After launch, select the interpreter in the Python panel and install JEP matching Field's bundled 4.3.1 JAR."
    if ("vulkan" in extras) notes += "Vulkan diagnostics are not a GPU driver. Install the driver for your GPU and check the Vulkan panel after launch."
    return InstallPlan(commands, notes)
}

internal fun findExecutable(name: String, environment: Map<String, String> = System.getenv()): File? {
    val windows = System.getProperty("os.name").startsWith("Windows")
    val suffixes = if (windows) listOf(".exe", ".cmd", ".bat", "") else listOf("")
    return environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value.orEmpty()
        .split(File.pathSeparator).filter { it.isNotBlank() }.flatMap { dir -> suffixes.map { File(dir, name + it) } }
        .firstOrNull { it.isFile && (windows || it.canExecute()) }
}

private fun commandDisplay(command: List<String>): String = command.joinToString(" ") { arg ->
    if (arg.any { it.isWhitespace() || it in "'\"$;&" }) "'" + arg.replace("'", "'\\''") + "'" else arg
}

private fun readAnswer(prompt: String): String? {
    print(prompt)
    System.out.flush()
    return readlnOrNull()?.trim()
}

private fun runCommand(command: List<String>, root: File): Boolean {
    println("Running: ${commandDisplay(command)}")
    return runCatching { ProcessBuilder(command).directory(root).inheritIO().start().waitFor() == 0 }
        .onFailure { println("Could not run it: ${it.message}") }.getOrDefault(false)
}

private fun hasLfsPointers(root: File): Boolean = File(root, "libs").listFiles()?.any { file ->
    file.isFile && file.length() < 1024 && file.readText().startsWith("version https://git-lfs.github.com")
} == true

fun main(args: Array<String>) {
    val root = args.indexOf("--root").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let(::File) ?: File(".")
    val config = args.indexOf("--config").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let(::File)
        ?: File(System.getProperty("user.home"), ".field4/first-run.properties")
    val interactive = "--interactive" in args && System.console() != null
    val os = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    println("\nWelcome to Field 4\n")
    println("A local live-coding environment. Kotlin and the editor work without Python, AI keys, or GPU tools.")
    println("Machine: $os / $arch")
    println("Java and Node are downloaded by Field; native graphics additionally need a GPU driver and display.")
    println("Allow 10 GB of free space and, for this source beta, preferably 16 GB RAM.")
    println("The initial download/build can take several minutes. Nothing selected here enables LAN access.")
    if ((os.startsWith("Mac") && arch !in setOf("aarch64", "arm64")) || (os.startsWith("Windows") && arch !in setOf("amd64", "x86_64"))) {
        println("This platform is not supported by Field's pinned GraalVM toolchain. See public/docs/installation.md.")
        exitProcess(1)
    }
    val manager = when {
        os.startsWith("Mac") -> "brew".takeIf { findExecutable(it) != null }
        os.startsWith("Linux") -> listOf("apt-get", "dnf", "pacman").firstOrNull { findExecutable(it) != null }
        else -> null
    }
    val basic = mutableSetOf("git-lfs")
    if (!os.startsWith("Windows")) basic += listOf("curl", "tar", "gzip")
    if (os.startsWith("Linux")) basic += listOf("g++", "cmake", "make")
    if (os.startsWith("Mac")) basic += "xcrun"
    val missing = basic.filter { findExecutable(it) == null }.toSet()
    println("Required desktop tools: ${if (missing.isEmpty()) "found" else "missing " + missing.sorted().joinToString()}")
    if (!interactive) {
        println("No interactive terminal: no packages installed and no preferences changed.")
        println("Run ./field.sh --setup (Windows: field.bat --setup) in a terminal for installation choices.")
        return
    }
    val previous = Properties().also { if (config.isFile) config.inputStream().use(it::load) }
    val defaultMode = if ("--server-only" in args || previous.getProperty("launchMode") == "server-only") "2" else "1"
    println("\nHow would you like to start?\n  1. Desktop app\n  2. Browser/server only (no Electron or desktop bridge compilers)")
    val mode = readAnswer("Choose 1 or 2 [$defaultMode]: ")?.ifEmpty { defaultMode } ?: return
    if (mode !in setOf("1", "2")) { println("No changes made; choose 1 or 2 next time."); exitProcess(1) }
    println("\nOptional extras (you can add them later):\n  1. Vulkan diagnostic tools (GPU driver installed separately)\n  2. Python box prerequisites (configure JEP from Field's welcome page)\n  Enter for neither.")
    val choice = readAnswer("Extras, separated by spaces: ") ?: return
    val selections = choice.split(Regex("[ ,]+" )).filter { it.isNotEmpty() }.toSet()
    if (selections.any { it !in setOf("1", "2") }) { println("No changes made; use the displayed numbers."); exitProcess(1) }
    val extras = selections.map { if (it == "1") "vulkan" else "python" }.toSet()
    val release = File("/etc/os-release").takeIf { it.isFile }?.readText().orEmpty()
    val ubuntuT64 = Regex("(?m)^ID=\"?ubuntu\"?$").containsMatchIn(release) &&
        Regex("(?m)^VERSION_ID=\"?(\\d+)").find(release)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 24 } == true
    val plan = packagePlan(manager, mode == "1", missing, extras, ubuntuT64)
    println("\nInstallation plan:")
    plan.commands.forEach { println("  ${commandDisplay(it)}") }
    plan.notes.forEach { println("  $it") }
    if (plan.commands.isEmpty()) println("  No package-manager commands proposed.")
    println("AI is optional: add your own API key after launch if you want it. Setup never asks for keys.")
    println("Docs/agent tooling uses uv; audio AI/model services are optional separate installations.")
    if (plan.commands.isNotEmpty()) {
        val install = readAnswer("Type INSTALL to run these commands (may request administrator credentials), or Enter to leave them for later: ") ?: return
        if (install == "INSTALL" && plan.commands.any { !runCommand(it, root) }) {
            println("Installation failed. No completion marker saved; correct the reported error and rerun --setup.")
            exitProcess(1)
        }
    }
    // Only obtain LFS content with the human's explicit consent, and never interpret a shell string.
    val pointer = hasLfsPointers(root)
    if (pointer && findExecutable("git-lfs") != null) {
        println("\nThe compiler content is still a Git LFS pointer.")
        if (readAnswer("Type PULL to run git lfs install --local and git lfs pull, or Enter to leave it for later: ") == "PULL") {
            if (!runCommand(listOf("git", "lfs", "install", "--local"), root) || !runCommand(listOf("git", "lfs", "pull"), root)) exitProcess(1)
        }
    }
    val required = basic.filter { mode == "1" || it in setOf("git-lfs", "curl", "tar", "gzip") }
    val ready = required.all { findExecutable(it) != null } && !hasLfsPointers(root)
    val settings = Properties().apply {
        setProperty("completed", ready.toString())
        setProperty("launchMode", if (mode == "1") "desktop" else "server-only")
        setProperty("extras", extras.sorted().joinToString(","))
    }
    config.parentFile.mkdirs()
    config.outputStream().use { settings.store(it, "Field first-run choices; no credentials") }
    println("\nSaved choices to ${config.absolutePath}.")
    println(if (ready) "Ready for the build. Reopen this setup any time with --setup." else "Required tools or Git LFS content are still missing. Resolve them before launching; --server-only skips desktop compilers.")
}
