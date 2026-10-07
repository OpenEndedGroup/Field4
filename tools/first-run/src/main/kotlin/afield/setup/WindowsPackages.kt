package afield.setup

// Exact IDs verified against Microsoft's winget-pkgs manifests. Keep this deliberately small:
// drivers, Vulkan SDKs, MSVC workloads and model services retain their own setup guides.
internal val wingetPackages = mapOf(
    "git-lfs" to "GitHub.GitLFS",
    "python" to "Python.Python.3.12",
    "uv" to "astral-sh.uv",
    "cmake" to "Kitware.CMake",
    "cargo" to "Rustlang.Rustup",
    "rustc" to "Rustlang.Rustup",
    "rustup" to "Rustlang.Rustup",
)

internal fun wingetPlan(missing: Set<String>): InstallPlan {
    val ids = missing.mapNotNull(wingetPackages::get).distinct()
    val commands = ids.map { id ->
        listOf("winget", "install", "--id", id, "--exact", "--source", "winget", "--interactive", "--no-upgrade")
    }
    val notes = mutableListOf<String>()
    if (commands.isNotEmpty()) {
        notes += "Winget will show installer and license prompts. Existing installations will not be upgraded."
        if ("python" in missing) notes += "In the Python installer, enable Add python.exe to PATH. Configure Python/JEP in Field after launch."
        notes += "After installing, open a fresh terminal and rerun .\\field.bat --setup before building optional libraries."
    }
    val manual = missing.filterNot { it in wingetPackages }
    if (manual.isNotEmpty()) notes += "Install these prerequisites separately: ${manual.sorted().joinToString()}."
    return InstallPlan(commands, notes)
}

internal fun commandSucceeded(command: List<String>, exitCode: Int): Boolean = exitCode == 0 ||
    (command.firstOrNull() == "winget" && command.getOrNull(1) == "install" && "--no-upgrade" in command &&
        // Official WinGet HRESULTs: PACKAGE_ALREADY_INSTALLED and UPDATE_NOT_APPLICABLE.
        exitCode in setOf(-1978335135, -1978335189))

internal fun shouldDeferPreparation(manager: String?, installed: Boolean): Boolean = manager == "winget" && installed
