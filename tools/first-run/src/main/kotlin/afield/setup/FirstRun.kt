package afield.setup

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal data class InstallPlan(val commands: List<List<String>>, val notes: List<String>)

internal fun usesT64Packages(release: String): Boolean {
    val values = release.lineSequence().mapNotNull { line ->
        val parts = line.split('=', limit = 2)
        if (parts.size == 2) parts[0] to parts[1].trim('"', '\'') else null
    }.toMap()
    val major = values["VERSION_ID"]?.substringBefore('.')?.toIntOrNull() ?: return false
    return (values["ID"] == "ubuntu" && major >= 24) || (values["ID"] == "debian" && major >= 13)
}

internal fun packagePlan(manager: String?, desktop: Boolean, missing: Set<String>, extras: Set<String>, ubuntuT64: Boolean): InstallPlan {
    val packages = linkedSetOf<String>()
    val notes = mutableListOf<String>()
    val commands = mutableListOf<List<String>>()
    if (manager != null && manager != "brew") packages += missing.filter { it in setOf("curl", "tar", "gzip") }
    val native = extras.any { it in setOf("ceres", "tetgen", "libpd", "csound", "paint", "syphon", "docs", "tracker") }
    val python = extras.any { it in setOf("python", "cv", "voice", "ceres", "tetgen", "libpd", "csound", "docs", "tracker") }
    val buildMissing = (desktop || native) && missing.any { it in setOf("g++", "cmake", "make", "xcrun") }
    when (manager) {
        "apt-get" -> {
            if ("git-lfs" in missing) packages += "git-lfs"
            if (buildMissing) packages += listOf("build-essential", "cmake")
            if (desktop) packages += listOf(if (ubuntuT64) "libgtk-3-0t64" else "libgtk-3-0", "libnss3", if (ubuntuT64) "libasound2t64" else "libasound2", "libgbm1", "libxkbcommon0")
            if ("vulkan" in extras) packages += listOf("libvulkan1", "vulkan-tools")
            if (python) packages += listOf("python3", "python3-dev", "python3-venv", "build-essential")
            if ("csound" in extras) packages += listOf("flex", "bison", "pkg-config", "libsndfile1-dev")
            if (missing.any { it in setOf("cargo", "rustc") }) packages += "cargo"
            if ("tracker" in extras) packages += "ffmpeg"
            if (packages.isNotEmpty()) commands += listOf("sudo", "apt-get", "install", "--") + packages
        }
        "dnf" -> {
            if ("git-lfs" in missing) packages += "git-lfs"
            if (buildMissing) packages += listOf("gcc", "gcc-c++", "make", "cmake")
            if (desktop) packages += listOf("gtk3", "nss", "alsa-lib", "mesa-libgbm", "libxkbcommon")
            if ("vulkan" in extras) packages += listOf("vulkan-loader", "vulkan-tools")
            if (python) packages += listOf("python3", "python3-devel", "gcc", "gcc-c++", "make")
            if ("csound" in extras) packages += listOf("flex", "bison", "pkgconf-pkg-config", "libsndfile-devel")
            if (missing.any { it in setOf("cargo", "rustc") }) packages += "cargo"
            if ("tracker" in extras) packages += "ffmpeg-free"
            if (packages.isNotEmpty()) commands += listOf("sudo", "dnf", "install", "--") + packages
        }
        "pacman" -> {
            if ("git-lfs" in missing) packages += "git-lfs"
            if (buildMissing) packages += listOf("base-devel", "cmake")
            if (desktop) packages += listOf("gtk3", "nss", "alsa-lib", "mesa", "libxkbcommon")
            if ("vulkan" in extras) packages += listOf("vulkan-icd-loader", "vulkan-tools")
            if (python) packages += listOf("python", "base-devel")
            if ("csound" in extras) packages += listOf("flex", "bison", "pkgconf", "libsndfile")
            if (missing.any { it in setOf("cargo", "rustc") }) packages += "rust"
            if ("tracker" in extras) packages += "ffmpeg"
            // Do not refresh only the package index on Arch: that can create a partial upgrade.
            if (packages.isNotEmpty()) commands += listOf("sudo", "pacman", "-S", "--needed", "--") + packages
            if (packages.isNotEmpty()) notes += "Use a fully updated Arch system. If package downloads return 404, update the system normally before retrying."
        }
        "brew" -> {
            if ("git-lfs" in missing) packages += "git-lfs"
            if (python) packages += "python@3.12"
            if ("cmake" in missing && native) packages += "cmake"
            if (missing.any { it in setOf("cargo", "rustc") }) packages += "rust"
            if ("csound" in extras) packages += listOf("flex", "bison", "pkg-config", "libsndfile")
            if ("tracker" in extras) packages += "ffmpeg"
            if ("manifold" in extras) packages += "manifold"
            if ("lua" in extras) packages += "luajit"
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
    if ("uv" in missing) notes += "Install uv using its official instructions before running the selected Python/agent/shader tools: https://docs.astral.sh/uv/getting-started/installation/"
    if ("cl" in missing) notes += "Native Windows builds need Visual Studio C++ Build Tools; rerun setup from its Developer PowerShell so cl.exe is on PATH."
    if (manager == "brew" && "csound" in extras) notes += "Homebrew flex/bison may be keg-only; expose their bin directories on PATH before building Csound."
    return InstallPlan(commands, notes)
}

internal fun findExecutable(name: String, environment: Map<String, String> = System.getenv()): File? {
    val windows = System.getProperty("os.name").startsWith("Windows")
    val suffixes = if (windows) listOf(".exe", ".cmd", ".bat", "") else listOf("")
    return environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value.orEmpty()
        .split(File.pathSeparator).filter { it.isNotBlank() }.flatMap { dir -> suffixes.map { File(dir, name + it) } }
        .firstOrNull { it.isFile && (windows || it.canExecute()) }
}

internal fun toolAvailable(name: String): Boolean {
    if (findExecutable(name) != null) return true
    if (name != "git-lfs") return false
    // Git for Windows can resolve bundled LFS through its exec-path even when only Git/cmd is
    // on the user's PATH. Use the same subcommand the launcher checks instead of rejecting it.
    val git = findExecutable("git") ?: return false
    return runCatching {
        val proc = ProcessBuilder(git.absolutePath, "lfs", "version").redirectErrorStream(true).start()
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            false
        } else proc.exitValue() == 0
    }.getOrDefault(false)
}

internal fun commandDisplay(command: List<String>): String = command.joinToString(" ") { arg ->
    if (arg.any { it.isWhitespace() || it in "'\"$;&" }) "'" + arg.replace("'", "'\\''") + "'" else arg
}

private fun readAnswer(prompt: String): String? {
    print(terminalUi.prompt(prompt))
    System.out.flush()
    return readlnOrNull()?.trim()
}

private fun runCommand(command: List<String>, root: File): Boolean {
    terminalUi.paragraph("Running")
    terminalUi.command(command)
    return runCatching { ProcessBuilder(command).directory(root).inheritIO().start().waitFor() == 0 }
        .onFailure { println(terminalUi.error("Could not run it: ${it.message}")) }.getOrDefault(false)
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
    val ui = terminalUi
    ui.welcome()
    ui.status("Your machine", "$os / $arch")
    ui.status("Free space", "%.1f GB".format(root.usableSpace / 1e9), root.usableSpace < 10_000_000_000L)
    println()
    ui.paragraph("Field brings its own Java and Node. Kotlin and the editor work without Python, AI keys or GPU tools.")
    ui.paragraph("First build: allow 20–25 minutes, 10 GB free space and preferably 16 GB RAM. Later launches reuse caches.", dim = true)
    if ((os.startsWith("Mac") && arch !in setOf("aarch64", "arm64")) || (os.startsWith("Windows") && arch !in setOf("amd64", "x86_64"))) {
        println(ui.error("This platform is not supported by Field's pinned GraalVM toolchain."))
        ui.paragraph("See public/docs/installation.md.")
        exitProcess(1)
    }
    val manager = when {
        os.startsWith("Mac") -> "brew".takeIf { toolAvailable(it) }
        os.startsWith("Linux") -> listOf("apt-get", "dnf", "pacman").firstOrNull { toolAvailable(it) }
        else -> null
    }
    val basic = mutableSetOf("git-lfs")
    if (!os.startsWith("Windows")) basic += listOf("curl", "tar", "gzip")
    if (os.startsWith("Linux")) basic += listOf("g++", "cmake", "make")
    if (os.startsWith("Mac")) basic += "xcrun"
    val platform = platformId(os)
    val coreTools = if ("--server-only" in args) basic.filter { it in setOf("git-lfs", "curl", "tar", "gzip") } else basic.toList()
    val coreMissing = coreTools.filter { !toolAvailable(it) }
    ui.status("Launch tools", if (coreMissing.isEmpty()) "found" else "missing " + coreMissing.sorted().joinToString(), coreMissing.isNotEmpty())
    val lfsPointers = hasLfsPointers(root)
    ui.status("Compiler content", if (lfsPointers) "Git LFS download needed" else "present", lfsPointers)
    if (root.usableSpace < 10_000_000_000L) ui.paragraph("Make room before the initial build or optional downloads.")
    if (!interactive) {
        printExtras(root, platform)
        ui.paragraph("Read-only report: no interactive terminal, no packages installed, no preferences changed.")
        ui.paragraph("Run ./field.sh --setup (Windows: field.bat --setup) in a terminal to choose.")
        return
    }
    val previous = Properties().also { if (config.isFile) config.inputStream().use(it::load) }
    val defaultMode = if ("--server-only" in args || previous.getProperty("launchMode") == "server-only") "2" else "1"
    ui.section(1, "How would you like to start?")
    ui.choice(1, "Desktop app [recommended]", "Field in its own window.")
    ui.choice(2, "Browser / server", "Use your browser; skip Electron and desktop bridge compilers.")
    val mode = readAnswer("Choose 1 or 2 [$defaultMode]: ")?.ifEmpty { defaultMode } ?: return
    if (mode !in setOf("1", "2")) { println(ui.error("Choose 1 or 2. No changes made.")); exitProcess(1) }
    ui.section(2, "Make it yours")
    printExtras(root, platform, compact = true)
    var choice = readAnswer("Extras by number or name / more / Enter to skip: ") ?: return
    if (choice.equals("more", ignoreCase = true)) {
        printExtras(root, platform)
        choice = readAnswer("Extras by number or name; spaces between choices: ") ?: return
    }
    val extras = try { selectedExtras(choice) } catch (error: IllegalArgumentException) {
        println(ui.error("${error.message} No changes made."))
        exitProcess(1)
    }
    val applicable = extrasCatalog.filter { it.id in extras && platform in it.platforms }.map { it.id }.toSet()
    val required = basic.filter { mode == "1" || it in setOf("git-lfs", "curl", "tar", "gzip") }
    val selectedTools = required.toSet() + extraTools(extras, platform)
    val selectedMissing = selectedTools.filter { !toolAvailable(it) }.toSet()
    val release = File("/etc/os-release").takeIf { it.isFile }?.readText().orEmpty()
    val plan = packagePlan(manager, mode == "1", selectedMissing, applicable, usesT64Packages(release))
    val prepare = preparationCommands(extras, platform)
    ui.section(3, "Your setup plan")
    ui.status("Launch mode", if (mode == "1") "Desktop app" else "Browser / server")
    ui.status("Optional features", if (extras.isEmpty()) "none needed to get started" else extras.joinToString(", "))
    ui.status("Prerequisite tools", if (selectedMissing.isEmpty()) "found" else "missing " + selectedMissing.sorted().joinToString(), selectedMissing.isNotEmpty())
    if (extras.isNotEmpty()) printExtras(root, platform, extras)
    println()
    ui.paragraph("System packages", dim = true)
    plan.commands.forEach(ui::command)
    plan.notes.forEach { ui.paragraph(it, dim = true) }
    if (plan.commands.isEmpty()) ui.paragraph("Nothing to install through a package manager.")
    if (prepare.isNotEmpty()) {
        println()
        ui.paragraph("Optional builds / downloads", dim = true)
        prepare.forEach(ui::command)
    }
    println()
    ui.paragraph("Only the commands you approve will run. No API keys requested; no services started or LAN access enabled.", dim = true)
    if (plan.commands.isNotEmpty()) {
        ui.paragraph("Your package manager may ask for administrator credentials.", dim = true)
        val install = readAnswer("INSTALL to run system packages / Enter for later: ") ?: return
        if (install == "INSTALL" && plan.commands.any { !runCommand(it, root) }) {
            println(ui.error("Installation failed. Fix the reported error and rerun --setup."))
            exitProcess(1)
        }
    }
    // Only obtain LFS content with the human's explicit consent, and never interpret a shell string.
    val pointer = hasLfsPointers(root)
    if (pointer && toolAvailable("git-lfs")) {
        ui.paragraph("The compiler content still needs its Git LFS download.")
        ui.command(listOf("git", "lfs", "install", "--local"))
        ui.command(listOf("git", "lfs", "pull"))
        if (readAnswer("PULL to fetch compiler content / Enter for later: ") == "PULL") {
            if (!runCommand(listOf("git", "lfs", "install", "--local"), root) || !runCommand(listOf("git", "lfs", "pull"), root)) exitProcess(1)
        }
    }
    val ready = required.all { toolAvailable(it) } && !hasLfsPointers(root)
    var built = false
    if (prepare.isNotEmpty()) {
        if (readAnswer("BUILD to prepare the displayed extras / Enter for later: ") == "BUILD") {
            // Only tools for executable preparations gate BUILD; guidance-only selections do not.
            val buildExtras = extrasCatalog.filter { it.id in applicable && it.task != null }.map { it.id }.toSet()
            val buildMissing = extraTools(buildExtras, platform).filter { !toolAvailable(it) }
            if (!ready || buildMissing.isNotEmpty()) {
                println(ui.error("Cannot prepare yet. Resolve core prerequisites/LFS and missing build tools: ${buildMissing.joinToString()}. Rerun --setup."))
                exitProcess(1)
            }
            if ("paint" in buildExtras) {
                val errors = paintToolchainErrors()
                if (errors.isNotEmpty()) {
                    errors.forEach { println(ui.error(it)) }
                    exitProcess(1)
                }
            }
            if (prepare.any { !runCommand(it, root) }) {
                println(ui.error("Optional preparation failed. Fix the first reported error and rerun --setup."))
                exitProcess(1)
            }
            built = true
            ui.success("Preparation complete")
            ui.paragraph("Use each feature's guide/runtime probe to verify it before use.")
            printExtras(root, platform, buildExtras)
        }
    }
    val settings = Properties().apply {
        setProperty("completed", ready.toString())
        setProperty("launchMode", if (mode == "1") "desktop" else "server-only")
        setProperty("extras", extras.sorted().joinToString(","))
        setProperty("preparedExtras", if (built) extrasCatalog.filter { it.id in applicable && it.task != null }.joinToString(",") { it.id } else "")
    }
    config.parentFile.mkdirs()
    config.outputStream().use { settings.store(it, "Field first-run choices; no credentials") }
    ui.section(4, if (ready) "You're set" else "A little more to do")
    if (ready) ui.success("Ready for Field's first build") else println(ui.warning("Required tools or Git LFS content are still missing."))
    ui.paragraph("Saved choices: ${config.absolutePath}", dim = true)
    if (extras.isNotEmpty()) ui.paragraph("Selected extras are saved requests. Preparation and runtime availability are separate checks.", dim = true)
    println()
    ui.paragraph("Launch: ${if (os.startsWith("Windows")) ".\\field.bat" else "./field.sh"}${if (mode == "2") " --server-only" else ""}")
    ui.paragraph("Revisit any time: ${if (os.startsWith("Windows")) ".\\field.bat" else "./field.sh"} --setup", dim = true)
    if (!ready) exitProcess(1)
}
