package afield.setup

import java.io.File
import java.util.concurrent.TimeUnit

internal data class Extra(
    val id: String,
    val title: String,
    val group: String,
    val guide: String,
    val tools: Set<String> = emptySet(),
    val task: String? = null,
    val platforms: Set<String> = setOf("linux", "mac", "windows"),
    val advice: String,
)

// Keep the first two numbers stable for existing setup choices. A selected extra is a request,
// never evidence that its libraries, models or runtime work. Only explicit BUILD runs tasks.
internal val extrasCatalog = listOf(
    Extra("vulkan", "Vulkan diagnostics", "Graphics", "public/docs/installation.md", setOf("vulkaninfo"), advice = "Check /welcome#vulkan after launch. Diagnostic packages do not install your GPU driver."),
    Extra("python", "Python boxes / JEP prerequisites", "Python", "public/docs/python.md", setOf("python3"), advice = "Select an interpreter and install JEP in /welcome after launch. JEP must match Field's bundled JAR; restart Field afterward."),
    Extra("cv", "Python computer vision", "Python", "python/fieldcv/README.md", setOf("uv"), advice = "uv sync --inexact --group cv; bind Field's Python panel to this .venv and install matching JEP there. Includes OpenCV, MediaPipe and NumPy; camera permissions are separate."),
    Extra("voice", "Local speech-to-text", "Python", "python/fieldvoice/README.md", setOf("uv"), advice = "uv sync --inexact --group voice; select that interpreter or set FIELD_VOICE_PYTHON. Whisper models download on first use; Apple silicon uses mlx-whisper, other platforms use faster-whisper. Microphone permissions and model/device checks happen in Field."),
    Extra("ceres", "Ceres solver", "Native libraries", "public/docs/chain_ceres_manifold_contours.md", setOf("python3", "cmake", "compiler"), "buildCeresRedistributable", advice = "Builds pinned Ceres/Eigen and the JNI wrapper from source; needs network and several minutes. Check with ./gradlew smokeCeres after preparation."),
    Extra("tetgen", "TetGen tetrahedral meshing", "Native libraries", "public/docs/tetgen.md", setOf("python3", "cmake", "compiler"), "buildTetgenNative", advice = "Downloads pinned TetGen source and builds the native wrapper."),
    Extra("paint", "Oil-paint brush simulation", "Native libraries", "public/docs/paint_canvas.md", setOf("cargo", "rustc", "compiler"), "buildPaintNative", advice = "Needs Rust 2024-capable Cargo/rustc (at least 1.85); use a current stable Rust toolchain if distro packages are older. Downloads the pinned paint crate on first build."),
    Extra("libpd", "Pure Data / libpd", "Native libraries", "public/docs/pure_data.md", setOf("python3", "cmake", "git", "compiler"), "buildLibpdNative", advice = "Builds the pinned multi-instance libpd. Native Windows builds need Visual Studio C++ Build Tools."),
    Extra("csound", "Csound", "Native libraries", "public/docs/csound.md", setOf("python3", "cmake", "compiler", "flex", "bison", "pkg-config"), "buildCsoundNative", setOf("linux", "mac"), "Needs libsndfile development headers as well. On Windows install Csound separately; the source-build script does not support Windows."),
    Extra("manifold", "Manifold solid geometry", "Native libraries", "public/docs/manifold.md", advice = "Install Manifold's C binding (MANIFOLD_CBIND=ON), then check ./gradlew smokeManifold. macOS: brew install manifold. Other systems: use the guide for library paths and source-build instructions."),
    Extra("lua", "LuaJIT boxes", "Native libraries", "public/docs/lua.md", advice = "Install LuaJIT's shared library; macOS: brew install luajit; Debian/Ubuntu: sudo apt install libluajit-5.1-2. See the guide for platform library selection."),
    Extra("syphon", "Syphon video sharing", "Native libraries", "public/docs/syphon.md", setOf("git", "xcrun"), "buildSyphonNative", setOf("mac"), "macOS only; downloads the pinned Syphon framework and builds Field's bridge. Check with a Syphon sender/receiver after launch."),
    Extra("shaders", "Slang / learned and GPU-training shaders", "Graphics", "optional/lighting/README.md", setOf("uv", "git"), "buildLearnedShaders", advice = "Fetches the pinned Slang compiler and RTXNS submodule and compiles shader variants. GPU capabilities are checked separately; preparing shaders does not promise NVIDIA cooperative-vector support."),
    Extra("dlss", "NVIDIA DLSS / NGX", "Graphics", "dev_docs/lighting/ADVANCED_VULKAN.md", setOf("cmake", "compiler"), platforms = setOf("linux"), advice = "Follow the guide to prepare the pinned NVIDIA SDK, then bash tools/lighting/build_ngx.sh. Requires compatible NVIDIA hardware/driver and the SDK license; setup does not fetch it automatically."),
    Extra("opendlssnr", "OpenDLSS-NR", "Graphics", "public/docs/neural_reconstruction.md", setOf("python3", "cmake", "compiler"), platforms = setOf("linux"), advice = "Linux x86-64 only. ./gradlew buildOpenDlssNr explicitly fetches weights and builds the adapter; see the guide for model terms and Vulkan feature checks. This large preparation is left for later."),
    Extra("kratos", "Kratos physics worker", "Workers and models", "optional/kratos/README.md", setOf("uv"), advice = "Separate environment: uv sync --project optional/kratos --frozen --python 3.12. Native wheels cover Linux x86-64 and Windows x64; macOS uses the documented linux/amd64 container. Probe with Kratos.probeAny()."),
    Extra("tracker", "Neural video particle tracking", "Workers and models", "optional/particle-tracker/README.md", setOf("uv", "ffmpeg", "ffprobe", "compiler"), platforms = setOf("linux", "mac"), advice = "uv sync --project optional/particle-tracker --python 3.11 --frozen. Several GB of Torch dependencies; RAFT weights download on first use. Windows requires WSL. CUDA/CPU Linux tested; macOS MPS remains unverified."),
    Extra("transport", "Train stipple transports", "Workers and models", "optional/transport-flow/README.md", setOf("uv"), advice = "uv sync --project optional/transport-flow --python 3.12. Downloads Torch into a separate environment. Reading exported transports in Field needs none of this."),
    Extra("diffusion", "StreamDiffusion video processing", "Workers and models", "optional/streamdiffusion/README.md", platforms = setOf("linux"), advice = "Linux/NVIDIA only. Prepare a separate compatible backend, CUDA transport, weights and TensorRT cache using the guide. Large downloads; a successful environment install is not proof of working inference."),
    Extra("audio-ai", "Field Audio AI service", "Workers and models", "public/docs/field_audio_ai.md", advice = "Initialize only services/field-audio-ai, then follow its service setup for packages, model weights and optional remote credentials. Field's ordinary audio and vendored Faust need no AI service."),
    Extra("agents", "Agent / backdoor tools", "Developer tools", "dev_docs/agents/FIELD_BACKDOOR.md", setOf("uv"), advice = "uv sync --inexact --frozen --group backdoor. JVM-owned Playwright provides browser automation; the Python Playwright package is not required. No service is started by setup."),
    Extra("docs", "Documentation build and captures", "Developer tools", "dev_docs/testing/TESTING_STRATEGY.md", setOf("uv", "cargo", "python3", "cmake", "compiler"), advice = "Set JAVA_HOME to Field's provisioned JDK, then uv sync --inexact --frozen --group docs. HTML-only: FIELD_DOCS_RUNNER_MODE=never. Live captures additionally need ./gradlew buildDocsNativePrerequisites and the relevant Python/native/GPU dependencies."),
    Extra("ai", "Optional AI assistance", "AI", "public/docs/privacy.md", advice = "Configure providers in Field after launch. Read the trust page for transmitted information and local code-use telemetry. Setup never requests API keys or changes telemetry preferences."),
    Extra("openxr", "OpenXR / VR", "Graphics", "public/docs/imgui_vr_panel.md", advice = "Install and select an OpenXR runtime for your headset and OS. Field's OpenXR bindings ship with the app; headset/runtime availability is checked on the actual machine, not by installing Vulkan tools."),
    Extra("onnx", "ONNX model inference", "Workers and models", "public/docs/onnx.md", advice = "ONNX Runtime ships with Field. Depth Anything and CLIP weights download on first use; follow their model licenses/input requirements and allow space for caches. The documented inference route needs no separate Python or CUDA install."),
    Extra("samples", "Pinned Dirt audio samples", "Audio", "public/docs/sound_paths.md", setOf("python3"), "fetchDocsDirtSamples", advice = "Downloads and checksum-verifies Field's small pinned Dirt-Samples subset into the shared per-user cache. This is not the entire sample collection or an audio-AI model."),
    Extra("patchy-web", "Patchy layered painting editor", "Graphics", "public/docs/patchy.md", setOf("git", "python3", "cmake"), "buildPatchyWeb", advice = "Builds pinned Patchy plus Field's bridge patch in ~/.field4/patchy-web. Downloads Qt for WebAssembly and Emscripten (several GB); the Web editor is served automatically on the next Field launch. No Patchy source enters this checkout."),
    Extra("slang", "Slang shader compiler", "Graphics", "public/docs/afield_four_graphics.md", setOf("uv"), "prepareSlangCompiler", advice = "Fetches Field's pinned, checksummed slangc for live Slang boxes (vertex_fragment and friends). Field prefers it to the Vulkan SDK's and any slangc on PATH. Does not build the learned shaders."),
)

internal fun platformId(os: String): String = when {
    os.startsWith("Windows") -> "windows"
    os.startsWith("Mac") -> "mac"
    os.startsWith("Linux") -> "linux"
    else -> "other"
}

internal fun selectedExtras(answer: String): Set<String> {
    val tokens = answer.trim().split(Regex("[ ,]+" )).filter(String::isNotEmpty)
    return tokens.map { token ->
        token.toIntOrNull()?.let { extrasCatalog.getOrNull(it - 1)?.id }
            ?: extrasCatalog.firstOrNull { it.id == token }?.id
            ?: throw IllegalArgumentException("Unknown extra: $token; use the displayed numbers or names.")
    }.toSet()
}

internal fun preparationCommands(extras: Set<String>, platform: String): List<List<String>> {
    val wrapper = if (platform == "windows") listOf("cmd.exe", "/d", "/c", "gradlew.bat") else listOf("./gradlew")
    val tasks = extrasCatalog.filter { it.id in extras && platform in it.platforms }.mapNotNull { it.task }
    return if (tasks.isEmpty()) emptyList() else listOf(wrapper + listOf("--console=plain", "--max-workers=1") + tasks)
}

internal fun supportsRust2024(versionOutput: String): Boolean {
    val version = Regex("\\b(\\d+)\\.(\\d+)\\.\\d+").find(versionOutput) ?: return false
    val major = version.groupValues[1].toInt()
    return major > 1 || (major == 1 && version.groupValues[2].toInt() >= 85)
}

internal fun paintToolchainErrors(): List<String> = listOf("cargo", "rustc").mapNotNull { tool ->
    val output = runCatching {
        val proc = ProcessBuilder(tool, "--version").redirectErrorStream(true).start()
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            error("version check timed out")
        }
        check(proc.exitValue() == 0)
        proc.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()
    if (output != null && supportsRust2024(output)) null else "$tool must support Rust 2024 (at least 1.85); install a current stable Rust toolchain."
}

internal fun extraTools(extras: Set<String>, platform: String): Set<String> = extrasCatalog
    .filter { it.id in extras && platform in it.platforms }.flatMap { it.tools }.map {
        when {
            it == "compiler" -> when (platform) { "windows" -> "cl"; "mac" -> "xcrun"; else -> "g++" }
            it == "python3" && platform == "windows" -> "python"
            else -> it
        }
    }.toSet()

internal fun artifactCandidates(root: File, id: String, platform: String, environment: Map<String, String> = System.getenv()): List<File> {
    if (id == "patchy-web") {
        val override = environment["AFIELD_PATCHY_WEB_ROOT"]?.takeIf { it.isNotBlank() }
        return if (override != null) listOf(File(override, "patchy.html"))
            else listOf(File(System.getProperty("user.home"), ".field4/patchy-web/site/field-patchy.json"))
    }
    fun library(name: String) = when (platform) {
        "windows" -> "$name.dll"
        "mac" -> "lib$name.dylib"
        else -> "lib$name.so"
    }
    val recipe = when (id) {
        "ceres" -> "AFIELD_CERES_LIBRARY" to listOf("build/ken-ceres-redistributable/${library("ken_ceres_jvm")}", "build/ken-ceres/${library("ken_ceres_jvm")}")
        "tetgen" -> "AFIELD_TETGEN_LIBRARY" to listOf("build/field-tetgen/build/${library("field_tetgen_jvm")}", "build/field-tetgen/build/Release/${library("field_tetgen_jvm")}")
        "paint" -> "AFIELD_PAINT_LIBRARY" to listOf("build/field-paint/release/${library("field_paint_jvm")}")
        "libpd" -> "AFIELD_PD_LIBRARY" to listOf("build/field-libpd/build/libs/${library("pd-multi")}", "build/field-libpd/build/libs/Release/${library("pd-multi")}")
        "syphon" -> "AFIELD_SYPHON_LIBRARY" to listOf("build/field-syphon/libfield_syphon.dylib")
        "shaders" -> "" to listOf("build/lighting/learned-shader/learned-scalar.spv")
        "slang" -> "AFIELD_SLANGC" to listOf("build/lighting/slangc.path")
        else -> return emptyList()
    }
    // libpd's Windows target keeps its lib prefix, unlike the other native wrappers.
    val paths = if (id == "libpd" && platform == "windows") recipe.second.map { it.replace("/pd-multi.dll", "/libpd-multi.dll") } else recipe.second
    val override = environment[recipe.first]?.takeIf { it.isNotBlank() }
    return if (override != null) listOf(File(override).let { if (it.isAbsolute) it else File(root, override) }) else paths.map { File(root, it) }
}

internal fun printExtras(root: File, platform: String, selected: Set<String>? = null) {
    val ui = terminalUi
    if (selected == null) {
        featureOptions(platform).forEach { println(featureRow(ui, it, false)) }
        println()
        ui.paragraph("Already included: Kotlin, JavaScript, Faust, ordinary audio and browser drawing.", dim = true)
        return
    }
    println()
    extrasCatalog.forEachIndexed { index, extra ->
        if (extra.id !in selected) return@forEachIndexed
        ui.choice(index + 1, extra.title)
        if (platform in extra.platforms) {
            val candidates = artifactCandidates(root, extra.id, platform)
            if (candidates.isNotEmpty()) {
                val present = candidates.firstOrNull { it.isFile && it.length() > 0 }
                if (present != null) ui.paragraph("Artifact found: $present (runtime not checked)", dim = true, indent = 6)
                else ui.paragraph("No local artifact found; an external library may be configured in Field.", dim = true, indent = 6)
            }
        }
        ui.paragraph(extra.advice, indent = 6)
        ui.paragraph("Guide: ${extra.guide}", dim = true, indent = 6)
    }
}
