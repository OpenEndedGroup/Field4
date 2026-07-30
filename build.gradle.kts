@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalDistributionDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalDistributionDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.PathSensitivity
import org.gradle.jvm.tasks.Jar
import java.io.InputStream
import java.io.PrintStream
import java.io.ByteArrayOutputStream
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.HttpURLConnection
import java.net.URI
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.net.ssl.*

val lwjglVersion = "3.4.2-SNAPSHOT"

val osName = System.getProperty("os.name")!!
val osArch = System.getProperty("os.arch")!!
println("$osName, $osArch")
val normalizedOsArch = osArch.lowercase()
val lwjglNatives = when {
    arrayOf("Mac OS X", "Darwin").any { osName.startsWith(it) } ->
        if (normalizedOsArch == "aarch64" || normalizedOsArch == "arm64") "natives-macos-arm64" else "natives-macos"

    arrayOf("Windows").any { osName.startsWith(it) } ->
        "natives-windows"

    arrayOf("Linux").any { osName.startsWith(it) } ->
        when (normalizedOsArch) {
            "aarch64", "arm64" -> "natives-linux-arm64"
            "x86_64", "amd64" -> "natives-linux"
            else -> throw Error("Unsupported Linux architecture for LWJGL natives: $osArch")
        }

    else ->
        throw Error("Unrecognized or unsupported platform. Please set \"lwjglNatives\" manually")
}
val extraLwjglNativeClassifiers =
    if (lwjglNatives == "natives-macos-arm64") listOf("natives-macos") else emptyList()


val kotlinVersion = "2.4.0"
val kotlinExperimentalCompilerArgs = listOf(
    "-Xcollection-literals",
    "-Xname-based-destructuring=only-syntax",
)
val serializationVersion = "1.6.0"
val ktorVersion = "3.3.0"
val logbackVersion = "1.2.11"
val imguiVersion = "1.90.0"
val smileVersion = "5.0.1"
val luceneVersion = "10.3.2"
val onnxRuntimeVersion = "1.27.0"
val djlTokenizersVersion = "0.36.0"
val javaToolchainVersion = 25
val graalvmVersion = "25.0.2"

// JVM args that unlock CPU vectorization on our GraalVM toolchain.
//
// GraalVM defaults to the Graal (JVMCI) compiler as the *host* JIT. Under that compiler Lucene refuses
// its Panama SIMD path (it hard-gates on -XX:+UseJVMCICompiler), and Graal doesn't auto-vectorize plain
// scalar numeric loops the way C2 does. Forcing C2 as the host JIT — while keeping JVMCI *enabled* —
// unlocks Lucene's internal SIMD and C2 scalar-loop autovectorization, and silences the recurring
// "Java runtime is using JVMCI Compiler; Java vector incubator API can't be enabled." warning.
//
// Truffle/GraalWasm are unaffected: they keep JIT-compiling guest JS/WASM through JVMCI (verified: guest
// code still runs on the optimizing "GraalVM CE" Truffle runtime, not the interpreter fallback).
// (--add-modules jdk.internal.vm.ci is intentionally dropped: JVMCI is built in on GraalVM and unused by
// our own code; -XX:+EnableJVMCI keeps it available for Truffle.)
val vectorizationJvmArgs = listOf(
    "--add-modules", "jdk.incubator.vector",
    "-XX:+EnableJVMCI",
    "-XX:-UseJVMCICompiler",
)

// Bytedeco FFmpeg for video support
val ffmpegVersion = "6.1.1-1.5.10"
val opencvVersion = "4.9.0-1.5.10"
val openblasVersion = "0.3.26-1.5.10"
val javacppVersion = "1.5.10"
val javacvVersion = "1.5.10"
//val kotlinWrappersVersion = "1.0.0-pre.621"
val electronDir = layout.projectDirectory.dir("electron")
fun abletonLinkNativeLibraryName(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("windows") -> "abl_link_jvm.dll"
        os.contains("mac") || os.contains("darwin") -> "libabl_link_jvm.dylib"
        else -> "libabl_link_jvm.so"
    }
}

val abletonLinkNative = layout.projectDirectory.file("libs/${abletonLinkNativeLibraryName()}")
val ceresNative = layout.projectDirectory.file("libs/libken_ceres_jvm.dylib")
val printRunJvmClasspath = providers.gradleProperty("printRunJvmClasspath")
    .map(String::toBoolean)
    .orElse(true)
val linalgArenaChecks = providers.gradleProperty("linalgArenaChecks")
    .map { value ->
        value.toBooleanStrictOrNull()
            ?: error("-PlinalgArenaChecks must be true or false, got '$value'")
    }
    .orElse(true)
val linalgBuildProfileDirectory = layout.buildDirectory.dir("generated/linalg-profile/kotlin")
val generateLinalgBuildProfile = tasks.register("generateLinalgBuildProfile") {
    group = "code generation"
    description = "Generates compile-time linear-algebra arena safety flags."
    inputs.property("arenaChecks", linalgArenaChecks)
    outputs.dir(linalgBuildProfileDirectory)
    doLast {
        val output = linalgBuildProfileDirectory.get().file(
            "afield/stdlib/linalg/arena/ArenaBuildProfile.kt"
        ).asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            // Generated by generateLinalgBuildProfile. Do not edit.
            package afield.stdlib.linalg.arena

            internal const val ARENA_CHECKS_COMPILED: Boolean = ${linalgArenaChecks.get()}
            """.trimIndent() + "\n"
        )
    }
}
val fieldAotCacheFile = layout.buildDirectory.file("aot/field.aot")
val fieldAotCacheStampFile = layout.buildDirectory.file("aot/field.aot.stamp")
val fieldAotTrainingPort = providers.gradleProperty("fieldAotPort")
    .orElse(providers.environmentVariable("AFIELD_AOT_PORT"))
    .orElse("9478")
val fieldAotTrainingHttpPort = providers.gradleProperty("fieldAotHttpPort")
    .orElse(providers.environmentVariable("AFIELD_AOT_HTTP_PORT"))
val fieldAotTrainingTimeoutSeconds = providers.gradleProperty("fieldAotTrainingTimeoutSeconds")
    .orElse(providers.environmentVariable("AFIELD_AOT_TRAINING_TIMEOUT_SECONDS"))
    .map { it.toLongOrNull() ?: 90L }
    .orElse(90L)
val kotlinRuntimeModulesShadowedByLocalJars = listOf(
    "kotlin-compiler-embeddable",
    "kotlin-daemon-embeddable",
    "kotlin-scripting-compiler-embeddable",
    "kotlin-scripting-compiler-impl-embeddable",
    "kotlin-scripting-jvm",
    "kotlin-scripting-common",
    "kotlin-script-runtime",
)

fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

fun booleanSetting(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
    "1", "true", "yes", "on", "enabled" -> true
    "0", "false", "no", "off", "disabled" -> false
    else -> null
}

fun fieldAotCacheSetting(): String? = firstNonBlank(
    providers.gradleProperty("fieldAotCache").orNull,
    System.getProperty("afield.aot.cache"),
    System.getenv("AFIELD_AOT_CACHE")
)

fun requestedFieldAotCacheFile(defaultWhenDisabled: Boolean = false): File? {
    val defaultCache = fieldAotCacheFile.get().asFile
    val configured = fieldAotCacheSetting()

    return when (booleanSetting(configured)) {
        true -> defaultCache
        false -> defaultCache.takeIf { defaultWhenDisabled }
        null -> configured?.let(::File) ?: defaultCache
    }
}

fun fieldAotAutoGenerateEnabled(): Boolean {
    val configured = firstNonBlank(
        providers.gradleProperty("fieldAotAutoGenerate").orNull,
        System.getProperty("afield.aot.autoGenerate"),
        System.getenv("AFIELD_AOT_AUTO_GENERATE")
    )
    booleanSetting(configured)?.let { return it }
    if (booleanSetting(fieldAotCacheSetting()) == false) return false

    val requested = requestedFieldAotCacheFile() ?: return false
    val cacheSetting = fieldAotCacheSetting()
    return requested.exists() || cacheSetting != null
}

fun fieldAotCacheIsCurrent(
    classpath: FileCollection,
    javaLauncher: org.gradle.jvm.toolchain.JavaLauncher,
    cacheFile: File,
    streamableObjectArgs: List<String>,
): Boolean {
    val stampFile = fieldAotCacheStampFile.get().asFile
    return cacheFile.exists() &&
        stampFile.exists() &&
        stampFile.readText() == fieldAotBuildFingerprint(
            classpath,
            javaLauncher,
            cacheFile,
            streamableObjectArgs,
        )
}

data class FieldAotRuntimeLaunch(
    val cacheJvmArgs: List<String>,
    val statusJvmArgs: List<String>,
)

fun fieldAotStatusJvmArgs(
    state: String,
    summary: String,
    cacheFile: File?,
    autoGenerate: Boolean,
    attempted: Boolean,
    detail: String,
): List<String> = listOf(
    "-Dafield.aot.state=$state",
    "-Dafield.aot.summary=$summary",
    "-Dafield.aot.cache.path=${cacheFile?.absolutePath.orEmpty()}",
    "-Dafield.aot.autoGenerate=$autoGenerate",
    "-Dafield.aot.attempted=$attempted",
    "-Dafield.aot.detail=$detail",
)

fun fieldAotRuntimeLaunch(
    classpath: FileCollection,
    javaLauncher: org.gradle.jvm.toolchain.JavaLauncher,
    logger: org.gradle.api.logging.Logger,
): FieldAotRuntimeLaunch {
    val autoGenerate = fieldAotAutoGenerateEnabled()
    val candidate = requestedFieldAotCacheFile() ?: return FieldAotRuntimeLaunch(
        cacheJvmArgs = emptyList(),
        statusJvmArgs = fieldAotStatusJvmArgs(
            state = "disabled",
            summary = "not attempted",
            cacheFile = null,
            autoGenerate = autoGenerate,
            attempted = false,
            detail = "AOT cache is disabled by fieldAotCache/AFIELD_AOT_CACHE."
        )
    )
    val cacheSetting = fieldAotCacheSetting()

    if (!candidate.exists()) {
        if (cacheSetting != null) {
            logger.lifecycle("Field AOT cache ${candidate.absolutePath} does not exist; launching without AOT.")
        }
        val notAttempted = cacheSetting == null && !autoGenerate
        return FieldAotRuntimeLaunch(
            cacheJvmArgs = emptyList(),
            statusJvmArgs = fieldAotStatusJvmArgs(
                state = if (notAttempted) "not-attempted" else "missing",
                summary = if (notAttempted) "not attempted" else "missing",
                cacheFile = candidate,
                autoGenerate = autoGenerate,
                attempted = !notAttempted,
                detail = if (notAttempted) {
                    "No AOT cache was requested. Start with ./gradlew runDesktop -PfieldAotCache=true to generate and use it."
                } else {
                    "Configured AOT cache file does not exist; launching without AOT."
                }
            )
        )
    }

    val streamableObjectArgs = aotStreamableObjectJvmArgs(javaLauncher.metadata.languageVersion.asInt(), logger)
    if (!fieldAotCacheIsCurrent(classpath, javaLauncher, candidate, streamableObjectArgs)) {
        logger.lifecycle(
            "Field AOT cache ${candidate.absolutePath} is stale for the current Field jar/classpath; " +
                "launching without AOT. Run ./gradlew generateFieldAotCache to rebuild it."
        )
        return FieldAotRuntimeLaunch(
            cacheJvmArgs = emptyList(),
            statusJvmArgs = fieldAotStatusJvmArgs(
                state = "stale",
                summary = "stale",
                cacheFile = candidate,
                autoGenerate = autoGenerate,
                attempted = true,
                detail = "AOT cache fingerprint does not match the current Field jar/classpath; launching without AOT."
            )
        )
    }

    return FieldAotRuntimeLaunch(
        cacheJvmArgs = listOf("-XX:AOTCache=${candidate.absolutePath}"),
        statusJvmArgs = fieldAotStatusJvmArgs(
            state = "working",
            summary = "working",
            cacheFile = candidate,
            autoGenerate = autoGenerate,
            attempted = true,
            detail = "AOT cache fingerprint matches the current Field jar/classpath."
        )
    )
}

fun MutableList<String>.addOrReplaceFieldAotCacheArgs(args: List<String>) {
    removeAll { it.startsWith("-XX:AOTCache=") }
    addAll(args)
}

fun fieldAotRuntimeClasspathFiles(classpath: FileCollection): List<File> {
    val jvmJarFile = tasks.named<Jar>("jvmJar").get().archiveFile.get().asFile
    val files = mutableListOf(jvmJarFile)
    classpath.asPath
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map(::File)
        .filter { it.isFile && it.absoluteFile != jvmJarFile.absoluteFile }
        .forEach { file ->
            if (files.none { it.absoluteFile == file.absoluteFile }) files += file
        }
    return files
}

fun fieldAotRuntimeClasspath(classpath: FileCollection): String =
    fieldAotRuntimeClasspathFiles(classpath).joinToString(File.pathSeparator) { it.absolutePath }

fun fieldAotClasspathFingerprint(classpath: FileCollection): String =
    fieldAotRuntimeClasspathFiles(classpath).joinToString("\n") { file ->
        "${file.absolutePath}:${file.length()}:${file.lastModified()}"
    }

fun fieldAotBuildFingerprint(
    classpath: FileCollection,
    javaLauncher: org.gradle.jvm.toolchain.JavaLauncher,
    cacheFile: File,
    streamableObjectArgs: List<String>,
): String = listOf(
    "cache=${cacheFile.absolutePath}",
    "java=${javaLauncher.metadata.languageVersion}:${javaLauncher.executablePath.asFile.absolutePath}",
    "jvmArgs=${requiredRunJvmArgs().joinToString("\u0000")}",
    "streamableObjectArgs=${streamableObjectArgs.joinToString("\u0000")}",
    "classpath=${fieldAotClasspathFingerprint(classpath)}",
).joinToString("\n")

fun requiredRunJvmArgs(): List<String> {
    val os = System.getProperty("os.name").lowercase()
    val onWindowsOrLinux = os.contains("windows") || os.contains("linux")

    return buildList {
        add("-Xmx8g")
        add("--add-opens=java.base/java.net=ALL-UNNAMED")
        // Headless AWT is mandatory in any JVM that also passes -XstartOnFirstThread (below).
        // AWT's macOS font manager dispatches to the process's first thread and blocks until it
        // answers, but that thread belongs to GLFW and never pumps a CFRunLoop — so the first
        // java.awt.Font.createGlyphVector deadlocks the caller forever. Headless AWT keeps the
        // whole font/glyph/raster stack (which afield.stdlib.typography is built on) and drops
        // only the windowing toolkit, which Field does not use: it renders through GLFW/Vulkan
        // and its UI is Electron. Must be a -D on the command line, since it is read once when
        // the first AWT class initialises.
        add("-Djava.awt.headless=true")
        add("-Dorg.lwjgl.system.stackSize=256")
        add("-Xlog:gc*,safepoint:file=build/gc-safepoint.log:time,uptime,level,tags")
        add("--enable-native-access=ALL-UNNAMED")
        add("-Dableton.link.library=${abletonLinkNative.asFile.absolutePath}")
        add("-Dafield.ceres.library=${ceresNative.asFile.absolutePath}")
        if (!onWindowsOrLinux) {
            add("-XstartOnFirstThread")
            add("-Xdock:name=Field4")
            val dockIcon = electronDir.file("icon-1024.png").asFile
            if (dockIcon.exists()) {
                add("-Xdock:icon=${dockIcon.absolutePath}")
            }
            add("-Dorg.lwjgl.util.Debug=true")
            add("-Dorg.lwjgl.util.DebugLoader=true")
        }
        addAll(vectorizationJvmArgs)
    }
}

fun desktopLowLatencyJvmArgs(): List<String> = listOf(
    "-XX:+UseZGC",
    "-Xms8g",
    "-Xmx8g",
    "-XX:+AlwaysPreTouch",
    "-XX:-ZUncommit",
    "-XX:+DisableExplicitGC",
    "-XX:ReservedCodeCacheSize=512m",
)

fun MutableList<String>.addOrReplaceJvmOptions(args: List<String>) {
    fun optionKey(arg: String): String = when {
        arg.startsWith("-Xms") -> "-Xms"
        arg.startsWith("-Xmx") -> "-Xmx"
        arg.startsWith("-XX:+") || arg.startsWith("-XX:-") -> arg.substringAfter("-XX:").drop(1).substringBefore("=")
        arg.startsWith("-XX:") -> arg.substringAfter("-XX:").substringBefore("=")
        else -> arg.substringBefore("=")
    }

    args.forEach { arg ->
        val key = optionKey(arg)
        removeAll { optionKey(it) == key }
        add(arg)
    }
}

fun configuredAi4TraceJvmArgs(): List<String> {
    val trace = firstNonBlank(
        providers.gradleProperty("ai4Trace").orNull,
        providers.gradleProperty("ai4TracePath").orNull,
        System.getProperty("afield.ai4.trace"),
        System.getProperty("afield.ai4.trace.path"),
        System.getenv("AFIELD_AI4_TRACE"),
        System.getenv("AFIELD_AI4_TRACE_PATH")
    )
    val max = firstNonBlank(
        providers.gradleProperty("ai4TraceMax").orNull,
        System.getProperty("afield.ai4.trace.max"),
        System.getenv("AFIELD_AI4_TRACE_MAX")
    )

    return buildList {
        trace?.let { add("-Dafield.ai4.trace=$it") }
        max?.let { add("-Dafield.ai4.trace.max=$it") }
    }
}

fun MutableList<String>.addOrReplaceSystemProperties(args: List<String>) {
    args.forEach { arg ->
        val key = arg.substringBefore("=")
        removeAll { it.substringBefore("=") == key }
        add(arg)
    }
}

// Remote debugging (JDWP) is OPT-IN, OFF by default. A plain `./gradlew runJvm`
// does NOT open a debug port. It turns on only when AFIELD_JDWP_PORT (or
// -Dafield.jdwp.port) is set to a port — which the backdoor python launcher does
// automatically (tools/field_backdoor.py sets AFIELD_JDWP_PORT), so agent probes
// work against backdoor-launched Field without exposing a debug port on manual runs.
// off/none/false/0 (or unset) keep it disabled.
fun jdwpPortSetting(): String? {
    val raw = (System.getProperty("afield.jdwp.port")
        ?: System.getenv("AFIELD_JDWP_PORT")
        ?: return null).trim()
    if (raw.isEmpty() || raw.lowercase() in setOf("off", "none", "false", "no", "disabled", "0")) {
        return null
    }
    return raw
}

fun jdwpHostSetting(): String =
    (System.getProperty("afield.jdwp.host")
        ?: System.getenv("AFIELD_JDWP_HOST")
        ?: "127.0.0.1").trim()

fun configuredJdwpArg(): String? {
    val port = jdwpPortSetting() ?: return null
    val suspendFlag = (System.getProperty("afield.jdwp.suspend")
        ?: System.getenv("AFIELD_JDWP_SUSPEND")
        ?: "false").trim().lowercase() in setOf("1", "true", "yes", "on")
    // suspend=n is deliberate: never freeze Field's render/compute loop for a probe.
    return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=${if (suspendFlag) "y" else "n"},address=${jdwpHostSetting()}:$port"
}

plugins {
    kotlin("multiplatform") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("me.filippov.gradle.jvm.wrapper") version "0.14.0"
    id("com.github.node-gradle.node") version "7.1.0"
}

jvmWrapper {
    unixJvmInstallDir = "${"$"}{HOME}/.field4/gradle-jvm"
    winJvmInstallDir = "%LOCALAPPDATA%\\gradle-jvm"
    linuxAarch64JvmUrl = "https://corretto.aws/downloads/latest/amazon-corretto-17-aarch64-linux-jdk.tar.gz"
    linuxX64JvmUrl = "https://corretto.aws/downloads/latest/amazon-corretto-17-x64-linux-jdk.tar.gz"
    macAarch64JvmUrl = "https://corretto.aws/downloads/latest/amazon-corretto-17-aarch64-macos-jdk.tar.gz"
    macX64JvmUrl = "https://corretto.aws/downloads/latest/amazon-corretto-17-x64-macos-jdk.tar.gz"
    windowsX64JvmUrl = "https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaToolchainVersion)
        // Constrain vendor to avoid Foojay returning non-directly-downloadable builds (which can 4xx on /redirect).
//        vendor = JvmVendorSpec.ADOPTIUM
        vendor = JvmVendorSpec.GRAAL_VM
    }
    }

group = "afield"
version = "1.1-SNAPSHOT"

val managedNodeVersion = "22.12.0"
val managedNpmVersion = "10.9.0"
val managedNodePlatform = when {
    osName.lowercase().contains("windows") -> "win"
    osName.lowercase().contains("mac") || osName.lowercase().contains("darwin") -> "darwin"
    osName.lowercase().contains("linux") -> "linux"
    else -> throw GradleException("Unsupported Node platform for Electron wrapper: $osName")
}
val managedNodeArch = when (osArch.lowercase()) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> throw GradleException("Unsupported Node architecture for Electron wrapper: $osArch")
}

fun managedNodeExecutable(): File {
    val nodeDir = layout.projectDirectory
        .dir(".gradle/nodejs/node-v${managedNodeVersion}-${managedNodePlatform}-${managedNodeArch}")
        .asFile
    return if (managedNodePlatform == "win") File(nodeDir, "node.exe") else File(nodeDir, "bin/node")
}

fun electronHostBinary(): File {
    val distDir = electronDir.file("node_modules/electron/dist").asFile
    return when (managedNodePlatform) {
        "win" -> File(distDir, "electron.exe")
        "darwin" -> {
            val field4 = File(distDir, "Field4.app/Contents/MacOS/Field4")
            val electronAlias = File(distDir, "Electron.app/Contents/MacOS/Field4")
            val electron = File(distDir, "Electron.app/Contents/MacOS/Electron")
            when {
                field4.exists() -> field4
                electronAlias.exists() -> electronAlias
                else -> electron
            }
        }
        else -> File(distDir, "electron")
    }
}

fun writeWindowsIconFromPng(sourcePng: File, targetIco: File) {
    val source = ImageIO.read(sourcePng)
        ?: throw GradleException("Could not decode Electron icon source at ${sourcePng.absolutePath}")
    val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
    val pngEntries = sizes.map { size ->
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(source, 0, 0, size, size, null)
        } finally {
            graphics.dispose()
        }

        ByteArrayOutputStream().use { bytes ->
            ImageIO.write(image, "png", bytes)
            size to bytes.toByteArray()
        }
    }

    targetIco.parentFile.mkdirs()
    targetIco.outputStream().use { out ->
        fun writeShort(value: Int) {
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
        }

        fun writeInt(value: Int) {
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
            out.write((value ushr 16) and 0xff)
            out.write((value ushr 24) and 0xff)
        }

        writeShort(0) // reserved
        writeShort(1) // icon
        writeShort(pngEntries.size)

        var imageOffset = 6 + (pngEntries.size * 16)
        pngEntries.forEach { (size, pngBytes) ->
            out.write(if (size == 256) 0 else size)
            out.write(if (size == 256) 0 else size)
            out.write(0) // colors
            out.write(0) // reserved
            writeShort(1) // color planes
            writeShort(32) // bits per pixel
            writeInt(pngBytes.size)
            writeInt(imageOffset)
            imageOffset += pngBytes.size
        }

        pngEntries.forEach { (_, pngBytes) -> out.write(pngBytes) }
    }
}

node {
    download.set(true)
    version.set(managedNodeVersion)
    npmVersion.set(managedNpmVersion)
    nodeProjectDir.set(electronDir.asFile)
}

configurations.matching { it.name in setOf("jvmRuntimeClasspath", "jvmMainRuntimeClasspath") }.configureEach {
    kotlinRuntimeModulesShadowedByLocalJars.forEach { moduleName ->
        exclude(group = "org.jetbrains.kotlin", module = moduleName)
    }
}

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://central.sonatype.com/repository/maven-snapshots")
    maven("https://repo.kotlin.link")
    maven(url = "https://repo.clojars.org/")
//    maven("https://jogamp.org/deployment/maven")
}



kotlin {
    jvm {
        binaries {
            executable {
                mainClass.set("afield.Server")

                println(System.getProperty("os.name"))
                var args = if (System.getProperty("os.name").lowercase()
                        .contains("windows") || System.getProperty("os.name").lowercase()
                        .contains("linux")
                ) mutableListOf("--add-opens=java.base/java.net=ALL-UNNAMED") else mutableListOf(
                    "-XstartOnFirstThread",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "-Dorg.lwjgl.util.Debug=true","-Dorg.lwjgl.util.DebugLoader=true"
                )

                // See requiredRunJvmArgs: -XstartOnFirstThread makes non-headless AWT deadlock on
                // the first glyph lookup, and Field's own UI needs no AWT toolkit.
                args += "-Djava.awt.headless=true"
                args += "-Dorg.lwjgl.system.stackSize=257"
                args += "-Xlog:gc*,safepoint:file=build/gc-safepoint.log:time,uptime,level,tags"
                args += "--enable-native-access=ALL-UNNAMED"
                args += "-Dableton.link.library=${abletonLinkNative.asFile.absolutePath}"
                args += "-Dafield.ceres.library=${ceresNative.asFile.absolutePath}"

                applicationDefaultJvmArgs.set(args)
            }
        }

    }
    js {
        useEsModules()

        browser {
            webpackTask {
                mainOutputFileName = "afield.mjs"
            }
            useEsModules()
        }
        binaries.executable()
    }



    tasks.withType<KotlinJsCompile>().configureEach {
        compilerOptions {
            target = "es2015"
            freeCompilerArgs.addAll(kotlinExperimentalCompilerArgs)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
                implementation("io.ktor:ktor-client-core:$ktorVersion")
//                implementation("org.jetbrains.kotlinx:kotlinx-rpc-core")
            }
        }

        val targetOs = when {
            osName == "Mac OS X" -> "macos"
            osName.startsWith("Win") -> "windows"
            osName.startsWith("Linux") -> "linux"
            else -> error("Unsupported OS: $osName")
        }

        val targetArch = when (osArch) {
            "x86_64", "amd64" -> "x64"
            "aarch64" -> "arm64"
            else -> error("Unsupported arch: $osArch")
        }

        val version = "0.9.47"
        val target = "${targetOs}-${targetArch}"

        // FFmpeg platform classifier for bytedeco (uses slightly different naming convention)
        val ffmpegPlatform = when (targetOs) {
            "macos" -> if (targetArch == "arm64") "macosx-arm64" else "macosx-x86_64"
            "windows" -> "windows-x86_64"
            "linux" -> if (targetArch == "arm64") "linux-arm64" else "linux-x86_64"
            else -> error("Unsupported platform for FFmpeg: $targetOs-$targetArch")
        }

        val jvmMain by getting {

            kotlin.srcDir("src/generatedLinalg/kotlin")
            kotlin.srcDir(linalgBuildProfileDirectory)

            val personalSrc = file("src/personal")
            if (personalSrc.exists()) {
                kotlin.srcDir("src/personal/kotlin")
                resources.srcDir("src/personal/resources")
            }

            dependencies {

                val personalDeps = file("src/personal/dependencies.gradle.kts")
                if (personalDeps.exists()) {
                    apply(from = personalDeps.path)
                }

                implementation("org.slf4j:slf4j-api:2.0.16")
                runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

                // Cross-platform terminal styling + capability/size detection (enables Windows VT).
                // Backs afield.logger.PinnedConsole.
                implementation("com.github.ajalt.mordant:mordant:3.0.2")

                implementation("eu.sirotin.kotunil:kotunil-jvm:4.2.3")

                implementation(project.dependencies.platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

                implementation("org.lwjgl:lwjgl:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-assimp:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-openal:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-par:$lwjglVersion")
                //                implementation("org.lwjgl:lwjgl-openvr:${lwjglVersion}")
                implementation("org.lwjgl:lwjgl-openxr:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-stb:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-tinyexr:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-vma:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-shaderc:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-spvc:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-vulkan")

                implementation("io.github.spair:imgui-java-binding:$imguiVersion")
                implementation("io.github.spair:imgui-java-natives-$targetOs:$imguiVersion")

                val addLwjglNativeArtifacts: (String) -> Unit = { classifier ->
                    implementation("org.lwjgl:lwjgl-glfw::$classifier")
                    implementation("org.lwjgl:lwjgl-stb::$classifier")
                    implementation("org.lwjgl:lwjgl-tinyexr::$classifier")
                    implementation("org.lwjgl:lwjgl-shaderc::$classifier")
                    implementation("org.lwjgl:lwjgl-spvc::$classifier")
                    implementation("org.lwjgl:lwjgl::$classifier")
                    implementation("org.lwjgl:lwjgl-assimp::$classifier")
                    implementation("org.lwjgl:lwjgl-openal::$classifier")
                    implementation("org.lwjgl:lwjgl-opengl::$classifier")
                    if (!classifier.contains("macos")) implementation("org.lwjgl:lwjgl-openxr::$classifier")
                    implementation("org.lwjgl:lwjgl-par::$classifier")
                    implementation("org.lwjgl:lwjgl-vma::$classifier")
                    if (classifier.contains("macos")) implementation("org.lwjgl:lwjgl-vulkan::$classifier")
                }

                addLwjglNativeArtifacts(lwjglNatives)
                extraLwjglNativeClassifiers.forEach(addLwjglNativeArtifacts)

                implementation("io.github.earcut4j:earcut4j:2.2.2")

//                implementation("io.insert-koin:koin-ktor:4.0.4")
                implementation("io.ktor:ktor-client-okhttp:$ktorVersion")

                implementation("org.jetbrains.trove4j:trove4j:20160824")
                implementation("org.json:json:20240303")
                implementation("org.xerial:sqlite-jdbc:3.47.2.0")
                implementation("org.apache.lucene:lucene-core:$luceneVersion")
                implementation("org.apache.lucene:lucene-analysis-common:$luceneVersion")
                implementation("org.apache.lucene:lucene-queryparser:$luceneVersion")
                implementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntimeVersion")
                implementation("ai.djl.huggingface:tokenizers:$djlTokenizersVersion")

                implementation("io.arrow-kt:arrow-core:1.2.4")
                implementation("io.arrow-kt:arrow-fx-coroutines:1.2.4")
                implementation("org.jspecify:jspecify:1.0.0")

                implementation("io.ktor:ktor-serialization:$ktorVersion")
                implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-server-cors:$ktorVersion")
                implementation("io.ktor:ktor-server-compression:$ktorVersion")
                implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
                implementation("io.ktor:ktor-server-netty:$ktorVersion")
                implementation("io.ktor:ktor-server-websockets:$ktorVersion")
                implementation("io.ktor:ktor-server-partial-content:$ktorVersion")
                implementation("io.ktor:ktor-server-auto-head-response:$ktorVersion")
                implementation("ch.qos.logback:logback-classic:$logbackVersion")
                implementation("io.ktor:ktor-serialization-gson:$ktorVersion")

                implementation("org.apache.commons:commons-math4-core:4.0-beta1")
                implementation("org.apache.commons:commons-math4-legacy:4.0-beta1")
                implementation("org.dyn4j:dyn4j:5.0.2")
                implementation("ai.koog:koog-agents:0.5.0")

                implementation("com.microsoft.playwright:playwright:1.57.0")


                // for parse tree rewriting — still useful for remote js
//                implementation("org.openjdk.nashorn:nashorn-core:15.6")
                implementation(files("libs/nashorn-core-15.6.jar"))
                implementation(files("libs/ableton-link-ffm.jar"))
                implementation("org.ow2.asm:asm:7.3.1")
                implementation("org.ow2.asm:asm-commons:7.3.1")
                implementation("org.ow2.asm:asm-tree:7.3.1")
                implementation("org.ow2.asm:asm-util:7.3.1")

                implementation("com.aallam.openai:openai-client:4.0.1")
                implementation("com.openai:openai-java:4.29.0")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.1")

                implementation("org.jetbrains:markdown:0.5.2")
                implementation("io.github.sashirestela:simple-openai:3.22.2")

                val customKotlinVersion = "2.4.255-SNAPSHOT"

                /*
                gently customized versions of the kotlin scripting front-end to provide better completion and skip the history check on the repl evaluator to allow us to reused compiled fragments
                 */
                implementation(files("libs/analysis-api-$customKotlinVersion.jar"))
                implementation(files("libs/analysis-api-fir-generator-$customKotlinVersion.jar"))

                implementation(files("libs/jep-4.3.1.jar"))

                implementation(files("libs/kotlin-compiler-embeddable-$customKotlinVersion.jar"))
                implementation(files("libs/kotlin-scripting-compiler-impl-embeddable-$customKotlinVersion.jar"))
                implementation(files("libs/kotlin-scripting-compiler-embeddable-$customKotlinVersion.jar"))
                implementation("com.facebook:ktfmt:0.64")

                implementation(files("libs/kotlin-scripting-ide-services-$customKotlinVersion.jar"))
                implementation(files("libs/kotlin-scripting-common-$customKotlinVersion.jar"))
                implementation(files("libs/kotlin-scripting-jsr223-$customKotlinVersion.jar"))
                implementation(files("libs/kotlin-scripting-jvm-$customKotlinVersion.jar"))
                implementation(files("libs/kotlin-scripting-jvm-host-$customKotlinVersion.jar"))
                implementation(files("libs/kotlin-script-runtime-$customKotlinVersion.jar"))
                implementation(files("libs/names-$customKotlinVersion.jar"))

//                implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$customKotlinVersion")
                implementation(files("libs/kotlin-scripting-dependencies-$customKotlinVersion.jar"))
                implementation(files("libs/kotlin-scripting-dependencies-maven-$customKotlinVersion.jar"))

                // EXPERIMENT: Wither compiler plugin (kyay10/wither) wired into the K2 REPL.
                // - annotations jar carries io.github.kyay10.wither.with/context (script-visible via
                //   dependenciesFromClassloader wholeClasspath)
                // - plugin jar is located on java.class.path at runtime and passed to the REPL
                //   compiler via -Xplugin (see KotlinInterface2 init)
                implementation(files("libs/wither-annotations.jar"))
                implementation(files("libs/wither-plugin.jar"))
                implementation("org.apache.maven:maven-core:3.8.8")
                implementation("org.apache.maven.resolver:maven-resolver-api:1.9.22")
                implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.22")
                implementation("org.apache.maven.resolver:maven-resolver-impl:1.9.22")
                implementation("org.apache.maven.resolver:maven-resolver-transport-file:1.9.22")
                implementation("org.apache.maven.resolver:maven-resolver-transport-wagon:1.9.22")
                implementation("org.apache.maven.resolver:maven-resolver-util:1.9.22")
                implementation("org.apache.maven.wagon:wagon-http:3.5.3")
                implementation("commons-io:commons-io:2.18.0")


                implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")

                implementation("org.jetbrains.skiko:skiko-awt-runtime-$target:$version")

                implementation("com.google.devtools.ksp:symbol-processing-aa-embeddable:2.1.0-1.0.29")
                implementation("com.google.devtools.ksp:symbol-processing-api:2.1.0-1.0.29")
                implementation("com.google.devtools.ksp:symbol-processing:2.1.0-1.0.29")

                implementation("space.kscience:plotlykt-core:0.7.1.1")
                implementation("space.kscience:plotlykt-server:0.7.1.1")

                implementation("org.locationtech.jts:jts-core:1.20.0")

                implementation("io.github.classgraph:classgraph:4.8.179")

                implementation("com.illposed.osc:javaosc-core:0.9")

                implementation("org.ejml:ejml-simple:0.43.1")
                implementation("com.github.jelmerk:hnswlib-core:1.1.0")
                implementation("com.github.quickhull3d:quickhull3d:1.0.0")
                implementation("com.github.haifengl:smile-core:$smileVersion")
                implementation("com.github.haifengl:smile-kotlin:$smileVersion")
                implementation("org.clojure:clojure:1.11.3")
                implementation("org.clojure:tools.reader:1.5.1")
                implementation("compliment:compliment:0.5.5")
                implementation("org.clojure:data.json:2.5.0")

                // personal only
                implementation("org.yaml:snakeyaml:2.4")

                // Bytedeco FFmpeg for video texture support
                implementation("org.bytedeco:javacpp:$javacppVersion")
                implementation("org.bytedeco:javacv:$javacvVersion")
                implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
                implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$ffmpegPlatform")
                implementation("org.bytedeco:opencv:$opencvVersion")
                implementation("org.bytedeco:opencv:$opencvVersion:$ffmpegPlatform")
                implementation("org.bytedeco:openblas:$openblasVersion:$ffmpegPlatform")

                implementation("com.github.stephengold:jolt-jni-Windows64:3.6.0")
                implementation("com.github.stephengold:jolt-jni-Linux64:3.6.0:DebugSp")
                implementation("com.github.stephengold:jolt-jni-Linux_ARM64:3.6.0:DebugSp")
                implementation("com.github.stephengold:jolt-jni-Windows64:3.6.0:DebugSp")
                implementation("com.github.stephengold:jolt-jni-MacOSX64:3.6.0:DebugSp")
                implementation("com.github.stephengold:jolt-jni-MacOSX_ARM64:3.6.0:DebugSp")

                implementation("org.graalvm.polyglot:polyglot:${graalvmVersion}")
                implementation("org.graalvm.polyglot:wasm:${graalvmVersion}")
                implementation("org.graalvm.polyglot:js:${graalvmVersion}")

            }
        }


        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.11.0")
                implementation("io.ktor:ktor-client-js:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-client-websockets:$ktorVersion")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-styled-next:1.2.4-pre.779")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }



    }

}

val generateLinalg = tasks.register("generateLinalg") {
    group = "code generation"
    description = "Generates Field's linear-algebra heap and arena sources."
    dependsOn(":tools:linalg-generator:run")
}

val checkGeneratedLinalg = tasks.register("checkGeneratedLinalg") {
    group = "verification"
    description = "Checks that generated linear-algebra sources are current."
    dependsOn(":tools:linalg-generator:checkGeneratedLinalg")
}



//// include JS artifacts in any JAR we generate
tasks.named<Jar>("jvmJar").configure {
    val productionJs = providers.gradleProperty("isProduction")
        .map(String::toBoolean)
        .orElse(false)
        .get()
    val taskName = if (productionJs
        || project.gradle.startParameter.taskNames.contains("installDist")
    ) {
        "jsBrowserProductionWebpack"
    } else {
        "jsBrowserDevelopmentWebpack"
    }

    val webpackTask = tasks.named<KotlinWebpack>(taskName)
    dependsOn(webpackTask)
    from(webpackTask.map { it.mainOutputFile.get().asFile }) {
        into("static")
    }
}



tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_24)
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
            javaParameters.set(true)
            freeCompilerArgs.addAll(kotlinExperimentalCompilerArgs)
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs(vectorizationJvmArgs)
}

tasks.named("compileKotlinJvm") {
    dependsOn(generateLinalgBuildProfile)
}

tasks.register<JavaExec>("runTrackingResonatorResynthesisExample") {
    group = "application"
    description = "Analyzes a WAV file with TrackingResonatorBank and writes three additive-resynthesis WAV variants."
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    classpath = sourceSets["jvmMain"].runtimeClasspath
    mainClass.set("afield.examples.audio.TrackingResonatorResynthesisExampleKt")
    jvmArgs(vectorizationJvmArgs)
}

tasks.register<JavaExec>("runDocsPanelCodeLinksSmokeTest") {
    group = "verification"
    description = "Exercises the documentation-panel code-link backend (KDoc linkification + source-card resolution) against the real Kotlin source index."
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    classpath = sourceSets["jvmMain"].runtimeClasspath
    mainClass.set("afield.examples.DocsPanelCodeLinksSmokeTest")
    jvmArgs(vectorizationJvmArgs)
}

tasks.register<JavaExec>("generateCodebaseReference") {
    group = "documentation"
    description = "Regenerates the committed codebase reference (public/docs/reference/) from the KDoc in afield.stdlib and afield.visualization. Pass -Pout=<dir> to write elsewhere; with no output dir the generator drift-checks the committed pages instead."
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    classpath = sourceSets["jvmMain"].runtimeClasspath
    mainClass.set("afield.docs.StdlibReferenceGenerator")
    val out = (project.findProperty("out") as String?) ?: "public/docs/reference"
    args(file(out).absolutePath)
}

tasks.register<JavaExec>("checkCodebaseReference") {
    group = "verification"
    description = "Fails if public/docs/reference/ is not what the current KDoc produces. Same drift guard as the docs.codebase-reference.generate FIELD_TEST, without needing a Field server."
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    classpath = sourceSets["jvmMain"].runtimeClasspath
    mainClass.set("afield.docs.StdlibReferenceGenerator")
}

// public/docs/reference/ and dev_docs/kdoc_worklist.md are generated from the KDoc in
// afield.stdlib and afield.visualization, so *any* edit to a summary in those packages can put them
// out of date — and the pages are published. Wiring the byte-exact guard into `check` means the
// staleness is caught by an ordinary build rather than only by the docs.codebase-reference.generate
// FIELD_TEST, which needs the test catalog to be run. Fix a failure with:
//   ./gradlew generateCodebaseReference
// A KDoc block that the extractor cannot attach to the declaration below it is *not* caught by the
// byte-exact guard above: the generated page stays well-formed, it just carries the wrong prose (or
// none at all), because the extractor slides an unattachable block onto whatever declaration parses
// next. That shipped a wrong row on 2026-07-26. Fix a failure by moving the block — see
// dev_docs/kdoc_reference_contract.md.
tasks.register<JavaExec>("checkKDocAttachment") {
    group = "verification"
    description = "Fails if a KDoc block in src/ does not attach to the declaration it sits above — the silent failure that publishes one symbol's prose under another's name. Pass --args=--verbose to list the known-benign sites too."
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    classpath = sourceSets["jvmMain"].runtimeClasspath
    mainClass.set("afield.docs.KDocAttachmentGuard")
}

tasks.named("check") {
    dependsOn("checkCodebaseReference")
    dependsOn("checkKDocAttachment")
}

tasks.register<JavaExec>("runSidecarPathParseSmokeTest") {
    group = "verification"
    description = "Verifies FileWatchManager.parseSidecarPath splits underscore-bearing box ids/properties without spawning phantom boxes."
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    classpath = sourceSets["jvmMain"].runtimeClasspath
    mainClass.set("afield.examples.SidecarPathParseSmokeTest")
}

tasks.register<JavaExec>("runSimdVerletBulkKernelsExample") {
    group = "application"
    description = "Worked example of the generated bulk SIMD vector kernels and their SimdVerlet helpers (velocity read-out, spring-to-target force)."
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    classpath = sourceSets["jvmMain"].runtimeClasspath
    mainClass.set("afield.examples.SimdVerletBulkKernelsExample")
    jvmArgs(vectorizationJvmArgs)
}

tasks.register<JavaExec>("runImGuiVrPanelDemo") {
    group = "application"
    description = "ImGui-as-a-VR-panel demo: Dear ImGui -> RTT -> stereo quad, packed side-by-side, blitted to the desktop window; mouse/controller ray hit-testing. Pass --args=\"capture <out.png>\" for a verification capture."
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    classpath = sourceSets["jvmMain"].runtimeClasspath
    mainClass.set("afield.examples.four.ImGuiVrPanelDemo")
    // -XX:+EnableJVMCI (from vectorizationJvmArgs, inside requiredRunJvmArgs) is experimental on the
    // default Adoptium toolchain, so it must be unlocked first.
    jvmArgs("-XX:+UnlockExperimentalVMOptions")
    jvmArgs(requiredRunJvmArgs())
}

tasks.named<JavaExec>("runJvm").configure {
    val requiredJvmArgs = requiredRunJvmArgs()

    doFirst {
        val merged = (jvmArgs ?: emptyList<Any>()).map { it.toString() }.toMutableList()
        requiredJvmArgs.forEach { arg ->
            if (!merged.contains(arg)) merged += arg
        }
        configuredJdwpArg()?.let { jdwpArg ->
            if (merged.none { it.startsWith("-agentlib:jdwp=") }) {
                merged += jdwpArg
            }
            // Tell the in-JVM RuntimeDebugProbeService which port/host to attach the
            // JDI helper to. Needed because the port may come from the default rather
            // than an env var the app JVM would otherwise inherit.
            merged.addOrReplaceSystemProperties(
                listOf(
                    "-Dafield.jdwp.port=${jdwpPortSetting()}",
                    "-Dafield.jdwp.host=${jdwpHostSetting()}",
                )
            )
        }
        merged.addOrReplaceSystemProperties(configuredAi4TraceJvmArgs())
        val aotLaunch = fieldAotRuntimeLaunch(classpath, javaLauncher.get(), logger)
        if (aotLaunch.cacheJvmArgs.isNotEmpty()) {
            classpath = files(fieldAotRuntimeClasspathFiles(classpath))
        }
        merged.addOrReplaceSystemProperties(aotLaunch.statusJvmArgs)
        merged.addOrReplaceFieldAotCacheArgs(aotLaunch.cacheJvmArgs)
        setJvmArgs(merged)

        if (printRunJvmClasspath.get()) {
            val entries = classpath.files.map { it.absolutePath }
            val reportFile = layout.buildDirectory.file("reports/runJvm-classpath.txt").get().asFile
            reportFile.parentFile.mkdirs()
            reportFile.writeText(entries.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()))

            val shadowedKotlinEntries = entries.filter { entry ->
                entry.contains("/.gradle/caches/modules-2/") &&
                    kotlinRuntimeModulesShadowedByLocalJars.any { moduleName ->
                        entry.substringAfterLast('/').startsWith("$moduleName-")
                    }
            }

            logger.lifecycle("runJvm runtime classpath (${entries.size} entries):")
            entries.forEachIndexed { index, entry ->
                logger.lifecycle("  ${index + 1}: $entry")
            }
            logger.lifecycle("runJvm runtime classpath written to ${reportFile.absolutePath}")
            if (shadowedKotlinEntries.isNotEmpty()) {
                logger.warn("runJvm runtime classpath still contains Kotlin compiler/scripting modules shadowed by local jars:")
                shadowedKotlinEntries.forEach { entry ->
                    logger.warn("  $entry")
                }
            }
        }
    }

    dependsOn(tasks.named("jvmJar"))
    if (fieldAotAutoGenerateEnabled()) {
        dependsOn("generateFieldAotCache")
    }
}

fun urlStatus(url: String, method: String = "GET", timeoutMs: Int = 1000): Int? =
    runCatching {
        (URI(url).toURL().openConnection() as HttpURLConnection).run {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            if (method == "POST") {
                doOutput = true
                outputStream.use { }
            }
            try {
                responseCode
            } finally {
                disconnect()
            }
        }
    }.getOrNull()

fun aotStreamableObjectJvmArgs(javaMajorVersion: Int, logger: org.gradle.api.logging.Logger): List<String> {
    val configured = firstNonBlank(
        providers.gradleProperty("fieldAotStreamableObjects").orNull,
        System.getProperty("afield.aot.streamableObjects"),
        System.getenv("AFIELD_AOT_STREAMABLE_OBJECTS")
    )
    val requested = booleanSetting(configured) ?: (javaMajorVersion >= 26)
    if (!requested) return emptyList()
    if (javaMajorVersion < 26) {
        logger.lifecycle(
            "Skipping -XX:+AOTStreamableObjects: JEP 516 requires JDK 26+, " +
                "but the Field toolchain is JDK $javaMajorVersion."
        )
        return emptyList()
    }
    return listOf("-XX:+AOTStreamableObjects")
}

tasks.register("cleanFieldAotCache") {
    group = "application"
    description = "Deletes Field's generated JVM AOT cache."

    doLast {
        val cacheFile = fieldAotCacheFile.get().asFile
        val stampFile = fieldAotCacheStampFile.get().asFile
        if (cacheFile.exists()) {
            cacheFile.delete()
            logger.lifecycle("Deleted Field AOT cache at ${cacheFile.absolutePath}")
        }
        if (stampFile.exists()) {
            stampFile.delete()
            logger.lifecycle("Deleted Field AOT cache stamp at ${stampFile.absolutePath}")
        }
    }
}

tasks.register("generateFieldAotCache") {
    group = "application"
    description = "Starts Field briefly with -XX:AOTCacheOutput to generate the JVM AOT cache used by runJvm/runDesktop."

    dependsOn(tasks.named("jvmJar"))
    dependsOn(tasks.named("generateLocalKeystore"))
    inputs.files(providers.provider {
        val runJvmTask = tasks.named<JavaExec>("runJvm").get()
        fieldAotRuntimeClasspathFiles(runJvmTask.classpath)
    })
        .withPropertyName("fieldAotRuntimeClasspath")
        .withPathSensitivity(PathSensitivity.ABSOLUTE)
    inputs.property("fieldAotClasspathFingerprint", providers.provider {
        val runJvmTask = tasks.named<JavaExec>("runJvm").get()
        fieldAotClasspathFingerprint(runJvmTask.classpath)
    })
    inputs.property("fieldAotJvmArgs", providers.provider {
        requiredRunJvmArgs().joinToString("\u0000")
    })
    inputs.property("fieldAotJavaLauncher", providers.provider {
        val javaLauncher = tasks.named<JavaExec>("runJvm").get().javaLauncher.get()
        "${javaLauncher.metadata.languageVersion}:${javaLauncher.executablePath.asFile.absolutePath}"
    })
    outputs.file(providers.provider {
        requestedFieldAotCacheFile(defaultWhenDisabled = true) ?: fieldAotCacheFile.get().asFile
    })
    outputs.file(fieldAotCacheStampFile)

    doLast {
        val runJvmTask = tasks.named<JavaExec>("runJvm").get()
        val javaLauncher = runJvmTask.javaLauncher.get()
        val javaMajorVersion = javaLauncher.metadata.languageVersion.asInt()
        if (javaMajorVersion < 25) {
            throw GradleException("Field AOT cache generation requires JDK 25+; selected toolchain is JDK $javaMajorVersion.")
        }

        val cacheFile = requestedFieldAotCacheFile(defaultWhenDisabled = true) ?: fieldAotCacheFile.get().asFile
        val stampFile = fieldAotCacheStampFile.get().asFile
        val streamableObjectArgs = aotStreamableObjectJvmArgs(javaMajorVersion, logger)
        val fingerprint = fieldAotBuildFingerprint(
            runJvmTask.classpath,
            javaLauncher,
            cacheFile,
            streamableObjectArgs,
        )
        if (cacheFile.exists() && stampFile.exists() && stampFile.readText() == fingerprint) {
            logger.lifecycle("Field AOT cache is current at ${cacheFile.absolutePath}")
            return@doLast
        }

        cacheFile.parentFile.mkdirs()
        stampFile.parentFile.mkdirs()
        if (cacheFile.exists()) cacheFile.delete()
        if (stampFile.exists()) stampFile.delete()

        val port = fieldAotTrainingPort.get().toIntOrNull()
            ?: throw GradleException("fieldAotPort/AFIELD_AOT_PORT must be an integer.")
        val httpPort = fieldAotTrainingHttpPort.orNull?.toIntOrNull() ?: (port + 10000)
        if (httpPort !in 1..65535) {
            throw GradleException("Field AOT HTTP port $httpPort is outside the valid port range.")
        }

        val backendMain = runJvmTask.mainClass.orNull
            ?: throw GradleException("runJvm has no mainClass configured")
        val javaExecutable = javaLauncher.executablePath.asFile.absolutePath
        val backendJvmArgs = requiredRunJvmArgs().toMutableList()
        backendJvmArgs.addOrReplaceSystemProperties(configuredAi4TraceJvmArgs())
        backendJvmArgs += streamableObjectArgs
        backendJvmArgs += "-XX:AOTCacheOutput=${cacheFile.absolutePath}"

        val os = System.getProperty("os.name").lowercase()
        val isWindows = os.contains("windows")
        val backendCommand = mutableListOf<String>()
        backendCommand += javaExecutable
        backendCommand += backendJvmArgs

        if (isWindows) {
            val argFile = File(project.layout.buildDirectory.asFile.get(), "tmp/aot-classpath-argfile.txt")
            argFile.parentFile.mkdirs()
            argFile.writeText("-cp\n${fieldAotRuntimeClasspath(runJvmTask.classpath)}\n")
            backendCommand += "@${argFile.absolutePath}"
        } else {
            backendCommand += "-cp"
            backendCommand += fieldAotRuntimeClasspath(runJvmTask.classpath)
        }

        backendCommand += backendMain

        logger.lifecycle("Generating Field AOT cache at ${cacheFile.absolutePath}")
        logger.lifecycle("Starting Field AOT training server on http://localhost:$httpPort/health")
        val process = ProcessBuilder(backendCommand)
            .directory(project.projectDir)
            .redirectErrorStream(true)
            .apply {
                environment()["PORT"] = port.toString()
                environment()["AFIELD_HTTP_PORT"] = httpPort.toString()
                environment()["AFIELD_PORT_FALLBACK_COUNT"] = "1"
                environment()["AFIELD_SYSTEM_BROWSER"] = "true"
                environment()["AFIELD_SKIP_WORKSPACE_PERSIST"] = "true"
            }
            .start()

        val outputThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> logger.lifecycle("[field-aot] $line") }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val healthUrl = "http://localhost:$httpPort/health"
        val deadline = System.currentTimeMillis() + fieldAotTrainingTimeoutSeconds.get() * 1000L
        var ready = false
        while (System.currentTimeMillis() < deadline && process.isAlive) {
            if (urlStatus(healthUrl) == 200) {
                ready = true
                break
            }
            Thread.sleep(250)
        }

        if (!ready) {
            process.destroyForcibly()
            throw GradleException("Field AOT training server did not become healthy at $healthUrl.")
        }

        urlStatus("http://localhost:$httpPort/api/shutdown-instance", method = "POST", timeoutMs = 5000)
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GradleException("Field AOT training server did not exit after shutdown.")
        }
        outputThread.join(1000)

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw GradleException("Field AOT training server exited with code $exitCode.")
        }
        if (!cacheFile.exists()) {
            throw GradleException("Field AOT cache was not written at ${cacheFile.absolutePath}.")
        }

        stampFile.writeText(fingerprint)
        logger.lifecycle("Generated Field AOT cache at ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
    }
}

tasks.named("npmInstall") {
    group = "application"
    description = "Installs npm dependencies for the Electron wrapper."
}

val electronInstall = tasks.register("electronInstall") {
    group = "application"
    description = "Installs Electron wrapper dependencies in electron/."
    dependsOn(tasks.named("npmInstall"))

    doLast {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val pngIcon = electronDir.file("icon-1024.png").asFile
        val windowsIcon = electronDir.file("Field4.ico").asFile

        val hostBinary = electronHostBinary()
        if (!hostBinary.exists()) {
            val nodeExe = managedNodeExecutable()
            val installScript = electronDir.file("node_modules/electron/install.js").asFile
            if (!nodeExe.exists()) {
                throw GradleException("Managed Node not found at ${nodeExe.absolutePath}; run ./gradlew nodeSetup")
            }
            if (!installScript.exists()) {
                throw GradleException("Electron installer not found at ${installScript.absolutePath}; run ./gradlew npmInstall")
            }
            logger.lifecycle("Installing Electron binary with managed Node ${managedNodeVersion}.")
            val exit = ProcessBuilder(nodeExe.absolutePath, installScript.absolutePath)
                .directory(project.projectDir)
                .inheritIO()
                .start()
                .waitFor()
            if (exit != 0 || !hostBinary.exists()) {
                throw GradleException("Electron binary install failed; expected ${hostBinary.absolutePath}")
            }
        }

        if (pngIcon.exists() && (!windowsIcon.exists() || windowsIcon.lastModified() < pngIcon.lastModified())) {
            writeWindowsIconFromPng(pngIcon, windowsIcon)
            logger.lifecycle("Generated Windows app icon from ${pngIcon.absolutePath}.")
        } else if (!pngIcon.exists()) {
            logger.warn("Electron PNG icon not found at ${pngIcon.absolutePath}; skipping Windows icon generation.")
        }

        if (!isMac) return@doLast

        val distDir = electronDir.file("node_modules/electron/dist").asFile
        val electronApp = File(distDir, "Electron.app")
        val field4App = File(distDir, "Field4.app")

        if (electronApp.exists() && !field4App.exists()) {
            Files.move(electronApp.toPath(), field4App.toPath())
            Files.createSymbolicLink(electronApp.toPath(), Paths.get("Field4.app"))
            logger.lifecycle("Renamed Electron.app bundle to Field4.app and created Electron.app symlink.")
        } else if (!electronApp.exists() && field4App.exists()) {
            Files.createSymbolicLink(electronApp.toPath(), Paths.get("Field4.app"))
            logger.lifecycle("Created Electron.app symlink to existing Field4.app bundle.")
        }

        val appBundle = if (field4App.exists()) field4App else electronApp
        val plistFile = File(appBundle, "Contents/Info.plist")
        if (!plistFile.exists()) {
            logger.warn("Electron Info.plist not found at ${plistFile.absolutePath}; skipping app name patch.")
            return@doLast
        }

        fun replacePlistString(source: String, key: String, value: String): String {
            val pattern = Regex("""(<key>$key</key>\s*<string>)([^<]*)(</string>)""")
            val replaced = source.replace(pattern, "$1$value$3")
            if (replaced != source) return replaced

            val insertion = """
                |	<key>$key</key>
                |	<string>$value</string>
                |""".trimMargin()
            return source.replaceFirst("</dict>", "$insertion\n</dict>")
        }

        val iconSource = electronDir.file("Field4.icns").asFile
        if (iconSource.exists()) {
            val resourcesDir = File(appBundle, "Contents/Resources")
            resourcesDir.mkdirs()
            iconSource.copyTo(File(resourcesDir, "Field4.icns"), overwrite = true)
            logger.lifecycle("Installed macOS app icon from ${iconSource.absolutePath}.")
        } else {
            logger.warn("macOS app icon not found at ${iconSource.absolutePath}; skipping bundle icon patch.")
        }

        val original = plistFile.readText()
        var updated = original
        updated = replacePlistString(updated, "CFBundleDisplayName", "Field4")
        updated = replacePlistString(updated, "CFBundleName", "Field4")
        updated = replacePlistString(updated, "CFBundleExecutable", "Field4")
        updated = replacePlistString(updated, "CFBundleIdentifier", "com.field4.desktop")
        updated = replacePlistString(updated, "CFBundleIconFile", "Field4.icns")

        if (updated != original) {
            plistFile.writeText(updated)
            logger.lifecycle("Patched macOS Electron app name to Field4 in Info.plist.")
        }

        val macOsDir = File(appBundle, "Contents/MacOS")
        val electronExecutable = File(macOsDir, "Electron")
        val field4Executable = File(macOsDir, "Field4")
        if (electronExecutable.exists() && !field4Executable.exists()) {
            runCatching {
                Files.createSymbolicLink(field4Executable.toPath(), Paths.get("Electron"))
            }.onFailure {
                electronExecutable.copyTo(field4Executable, overwrite = true)
                field4Executable.setExecutable(true)
            }
            logger.lifecycle("Prepared macOS executable alias: ${field4Executable.absolutePath}")
        }
    }
}

tasks.register("dumpFieldLaunch") {
    group = "application"
    description = "Writes build/field-launch/{java,args} so ./field.sh and .\\field.ps1 can launch " +
        "afield.Server directly in a terminal (stdout stays a TTY, so the pinned console renders)."
    dependsOn(tasks.named("jvmJar"))
    dependsOn(tasks.named("generateLocalKeystore"))
    dependsOn(electronInstall)
    outputs.upToDateWhen { false }

    doLast {
        val runJvmTask = tasks.named<JavaExec>("runJvm").get()
        val javaExecutable = runJvmTask.javaLauncher.get().executablePath.asFile.absolutePath
        val mainClass = runJvmTask.mainClass.orNull
            ?: throw GradleException("runJvm has no mainClass configured")

        // Mirror runDesktop's backend args: the executable's default JVM args + requiredRunJvmArgs().
        val jvmArgs = (runJvmTask.jvmArgs ?: emptyList()).map { it.toString() }.toMutableList()
        requiredRunJvmArgs().forEach { if (it !in jvmArgs) jvmArgs += it }

        val outDir = File(project.layout.buildDirectory.asFile.get(), "field-launch")
        outDir.mkdirs()
        File(outDir, "java").writeText(javaExecutable + System.lineSeparator())
        // Java @argfile: one token per line. Paths are assumed free of whitespace, as elsewhere in
        // this build (see the runDesktop Windows @argfile at the classpath block below).
        File(outDir, "args").writeText(
            buildString {
                jvmArgs.forEach { appendLine(it) }
                appendLine("-cp")
                appendLine(runJvmTask.classpath.asPath)
                appendLine(mainClass)
            }
        )

        // Electron launch spec, so field.sh / field.ps1 can open the Electron shell alongside the
        // terminal-hosted backend (same binary/args/dir runDesktop uses). The scripts orchestrate both.
        val os = System.getProperty("os.name").lowercase()
        val electronArgs = if (os.contains("windows") || os.contains("mac")) {
            emptyList()
        } else {
            listOf(
                "--ignore-gpu-blocklist", "--disable-gpu-process-crash-limit",
                "--enable-gpu-rasterization", "--enable-zero-copy", "--ozone-platform-hint=auto",
            )
        }
        File(outDir, "electron").writeText(electronHostBinary().absolutePath + System.lineSeparator())
        File(outDir, "electron-dir").writeText(electronDir.asFile.absolutePath + System.lineSeparator())
        File(outDir, "electron-args").writeText(
            electronArgs.joinToString(separator = System.lineSeparator(), postfix = if (electronArgs.isEmpty()) "" else System.lineSeparator())
        )
        logger.lifecycle("Wrote ${outDir}/ — launch Field with ./field.sh (macOS/Linux) or .\\field.ps1 (Windows).")
    }
}

tasks.register("runDesktop") {
    group = "application"
    description = "Runs backend and Electron together, then shuts both down cleanly. Use -PwithDevTools=true to open DevTools."

    dependsOn(electronInstall)
    dependsOn(tasks.named("jvmJar"))
    dependsOn(tasks.named("generateLocalKeystore"))
    if (fieldAotAutoGenerateEnabled()) {
        dependsOn("generateFieldAotCache")
    }
    outputs.upToDateWhen { false }

    doLast {
        val runJvmTask = tasks.named<JavaExec>("runJvm").get()
        val javaExecutable = runJvmTask.javaLauncher.get().executablePath.asFile.absolutePath
        val backendMain = runJvmTask.mainClass.orNull
            ?: throw GradleException("runJvm has no mainClass configured")

        fun parseBooleanFlag(value: String?): Boolean = when (value?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            else -> false
        }

        fun highlightImportantUrlLines(line: String): String {
            val isTargetLine =
                line.contains("last document: https://") ||
                    line.contains("last document: http://") ||
                    line.contains("welcome page: https://") ||
                    line.contains("welcome page: http://")
            if (!isTargetLine) return line

            val green = "\u001B[32m"
            val reset = "\u001B[0m"
            val urlPattern = Regex("""https?://\S+""")
            return line.replace(urlPattern) { matchResult ->
                green + matchResult.value + reset
            }
        }

        fun forwardProcessOutput(process: Process) {
            fun pump(input: InputStream, sink: PrintStream): Thread {
                val thread = Thread {
                    input.use { source ->
                        source.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                val rendered = highlightImportantUrlLines(line)
                                synchronized(sink) {
                                    sink.println(rendered)
                                    sink.flush()
                                }
                            }
                        }
                    }
                }
                thread.isDaemon = true
                thread.start()
                return thread
            }

            fun pumpRaw(input: InputStream, sink: PrintStream): Thread {
                val thread = Thread {
                    input.use { source ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            synchronized(sink) {
                                sink.write(buffer, 0, read)
                                sink.flush()
                            }
                        }
                    }
                }
                thread.isDaemon = true
                thread.start()
                return thread
            }

            pump(process.inputStream, System.out)
            pumpRaw(process.errorStream, System.err)
        }

        val withDevTools = parseBooleanFlag(project.findProperty("withDevTools")?.toString()) ||
            parseBooleanFlag(System.getenv("WITH_DEVTOOLS"))
        if (withDevTools) {
            logger.lifecycle("DevTools enabled for Electron this run.")
        }
        val port = System.getenv("PORT")?.trim()?.takeIf { it.isNotEmpty() } ?: "9092"
        val portNumber = port.toIntOrNull()
            ?: throw GradleException("PORT must be an integer for runDesktop; got '$port'.")

        fun isPortListening(port: Int): Boolean {
            return runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                    true
                }
            }.getOrDefault(false) || runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("::1", port), 250)
                    true
                }
            }.getOrDefault(false)
        }

        if (isPortListening(portNumber)) {
            throw GradleException(
                "Refusing to run Field desktop: port $port is already in use. " +
                    "Close the existing Field server or set PORT to a free port. " +
                    "Electron will not be launched against an existing server."
            )
        }

        val backendJvmArgs = (runJvmTask.jvmArgs ?: emptyList()).toMutableList()
        requiredRunJvmArgs().forEach { arg ->
            if (!backendJvmArgs.contains(arg)) backendJvmArgs += arg
        }
        backendJvmArgs.addOrReplaceJvmOptions(desktopLowLatencyJvmArgs())
        backendJvmArgs.addOrReplaceSystemProperties(configuredAi4TraceJvmArgs())
        val aotLaunch = fieldAotRuntimeLaunch(runJvmTask.classpath, runJvmTask.javaLauncher.get(), logger)
        backendJvmArgs.addOrReplaceSystemProperties(aotLaunch.statusJvmArgs)
        backendJvmArgs.addOrReplaceFieldAotCacheArgs(aotLaunch.cacheJvmArgs)
        val backendClasspath = if (aotLaunch.cacheJvmArgs.isNotEmpty()) {
            fieldAotRuntimeClasspath(runJvmTask.classpath)
        } else {
            runJvmTask.classpath.asPath
        }

        val os = System.getProperty("os.name").lowercase()
        val isWindows = os.contains("windows")

        val backendCommand = mutableListOf<String>()
        backendCommand += javaExecutable
        backendCommand += backendJvmArgs

        // On Windows, the classpath can exceed the ~32K command-line limit.
        // Write it to a temporary argfile and use Java's @argfile syntax.
        if (isWindows) {
            val argFile = File(project.layout.buildDirectory.asFile.get(), "tmp/classpath-argfile.txt")
            argFile.parentFile.mkdirs()
            argFile.writeText("-cp\n${backendClasspath}\n")
            backendCommand += "@${argFile.absolutePath}"
        } else {
            backendCommand += "-cp"
            backendCommand += backendClasspath
        }

        backendCommand += backendMain
        backendCommand += (runJvmTask.args ?: emptyList())

        logger.lifecycle("Starting backend process via runJvm settings.")
        val backendProcess = ProcessBuilder(backendCommand)
            .directory(project.projectDir)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .apply {
                environment()["PORT"] = port
                environment()["AFIELD_PORT_FALLBACK_COUNT"] = "1"
            }
            .start()
        forwardProcessOutput(backendProcess)
        val isMac = os.contains("mac")
        val linuxElectronGpuArgs = listOf(
            "--ignore-gpu-blocklist",
            "--disable-gpu-process-crash-limit",
            "--enable-gpu-rasterization",
            "--enable-zero-copy",
            "--ozone-platform-hint=auto",
        )
        val electronCommand = if (isWindows) {
            listOf(electronHostBinary().absolutePath, ".")
        } else if (isMac) {
            listOf(electronHostBinary().absolutePath, ".")
        } else {
            listOf(electronHostBinary().absolutePath) + linuxElectronGpuArgs + "."
        }
        val electronBinary = File(electronCommand.first())
        if (!electronBinary.exists()) {
            backendProcess.destroyForcibly()
            throw GradleException("Electron binary not found at ${electronBinary.absolutePath}. Run ./gradlew electronInstall")
        }

        val healthUrl = "https://localhost:${port}/health"
        val appUrlEndpoint = "https://localhost:${port}/app-url"

        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        val socketFactory = sslContext.socketFactory
        val hostnameVerifier = HostnameVerifier { _, _ -> true }

        fun getHttps(url: String): Pair<Int, String> {
            val connection = (URI(url).toURL().openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = socketFactory
                this.hostnameVerifier = hostnameVerifier
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
            }
            return try {
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { reader -> reader.readText() }
                    ?.trim()
                    .orEmpty()
                code to body
            } finally {
                connection.disconnect()
            }
        }

        fun stopProcessTree(process: Process) {
            if (!process.isAlive) return
            process.toHandle().descendants().forEach { it.destroy() }
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.toHandle().descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }

        val deadline = System.currentTimeMillis() + 120_000
        var backendReady = false
        while (System.currentTimeMillis() < deadline) {
            if (!backendProcess.isAlive) {
                throw GradleException("Backend exited before becoming ready (exit code ${backendProcess.exitValue()}).")
            }

            val healthy = runCatching {
                val (code, body) = getHttps(healthUrl)
                code in 200..299 && body.contains("ok", ignoreCase = true)
            }.getOrDefault(false)

            if (healthy) {
                backendReady = true
                break
            }

            Thread.sleep(500)
        }

        if (!backendReady) {
            stopProcessTree(backendProcess)
            throw GradleException("Backend did not become reachable at ${healthUrl} within 120 seconds.")
        }
        logger.lifecycle("Backend is reachable at ${healthUrl}.")

        val appUrl = runCatching {
            val (code, body) = getHttps(appUrlEndpoint)
            if (code in 200..299 && body.isNotBlank()) body else "https://localhost:${port}/index.html"
        }.getOrDefault("https://localhost:${port}/index.html")
        logger.lifecycle("Launching Electron with APP_URL=${appUrl}.")

        val electronProcess = ProcessBuilder(electronCommand)
            .directory(electronDir.asFile)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .apply {
                environment()["APP_URL"] = appUrl
                environment()["WITH_DEVTOOLS"] = if (withDevTools) "1" else "0"
            }
            .start()
        forwardProcessOutput(electronProcess)

        val stopping = AtomicBoolean(false)

        fun shutdownAll() {
            if (!stopping.compareAndSet(false, true)) return
            stopProcessTree(electronProcess)
            stopProcessTree(backendProcess)
        }

        val shutdownHook = Thread { shutdownAll() }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        val backendWatcher = Thread {
            val code = backendProcess.waitFor()
            if (!stopping.get()) {
                logger.warn("Backend exited with code ${code}; closing Electron.")
                shutdownAll()
            }
        }
        backendWatcher.isDaemon = true
        backendWatcher.start()

        val electronExit = try {
            electronProcess.waitFor()
        } finally {
            shutdownAll()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }

        if (electronExit != 0) {
            throw GradleException("Electron exited with code ${electronExit}.")
        }
    }
}

fun JavaExec.configureCeresSmokeTask(entrypoint: String, taskDescription: String) {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    group = "verification"
    description = taskDescription
    classpath = sourceSets["jvmMain"].runtimeClasspath
    mainClass.set(entrypoint)
    workingDir = projectDir
    jvmArgs(requiredRunJvmArgs())
    inputs.file(ceresNative)
    doFirst {
        if (!ceresNative.asFile.exists()) {
            throw GradleException("Ceres native library not found at ${ceresNative.asFile.absolutePath}")
        }
    }
}

val smokeCeresGeometryPrimitives = tasks.register<JavaExec>("smokeCeresGeometryPrimitives") {
    configureCeresSmokeTask(
        "afield.examples.ceres.CeresGeometryPrimitivesSmokeKt",
        "Runs the Ceres FFM geometry primitive smoke example.",
    )
}

val smokeCeresArchitecturalDsl = tasks.register<JavaExec>("smokeCeresArchitecturalDsl") {
    configureCeresSmokeTask(
        "afield.examples.ceres.CeresArchitecturalDslSmokeKt",
        "Runs the Ceres architectural DSL smoke example.",
    )
}

val smokeCeresInteractionExamples = tasks.register<JavaExec>("smokeCeresInteractionExamples") {
    configureCeresSmokeTask(
        "afield.examples.ceres.CeresInteractionExamplesKt",
        "Runs the Ceres interaction examples smoke suite.",
    )
}

tasks.register("smokeCeres") {
    group = "verification"
    description = "Runs all Ceres FFM smoke examples."
    dependsOn(smokeCeresGeometryPrimitives, smokeCeresArchitecturalDsl, smokeCeresInteractionExamples)
}

tasks.register<JavaExec>("runfilestore") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    group = "application"
    description = "Runs AI4 filestore CLI"

    classpath = sourceSets["jvmMain"].runtimeClasspath

    mainClass.set("afield.ai.AI4FilestoreCliKt")
    standardInput = System.`in`
}

tasks.register<JavaExec>("polishCompletionDocs") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    group = "application"
    description = "Runs AI4 completion-doc cache/source polishing CLI"

    classpath = sourceSets["jvmMain"].runtimeClasspath

    mainClass.set("afield.ai.core.completion.Ai4CompletionDocPolishCliKt")
    standardInput = System.`in`
}

tasks.register<JavaExec>("polishCompletionDocsDryRun") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    group = "application"
    description = "Runs AI4 completion-doc cache/source polishing CLI without writing files"

    classpath = sourceSets["jvmMain"].runtimeClasspath

    mainClass.set("afield.ai.core.completion.Ai4CompletionDocPolishCliKt")
    args("--dry-run")
    standardInput = System.`in`
}

tasks.register<JavaExec>("improveAiToolUse") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    group = "application"
    description = "Runs AI4 tool-use log review and prompt/tool-description improvement CLI"

    classpath = sourceSets["jvmMain"].runtimeClasspath

    mainClass.set("afield.ai.core.tooluse.AiToolUseImprovementCliKt")
    standardInput = System.`in`
}

tasks.register<JavaExec>("improveAiToolUseDryRun") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    group = "application"
    description = "Runs AI4 tool-use improvement CLI without writing source files"

    classpath = sourceSets["jvmMain"].runtimeClasspath

    mainClass.set("afield.ai.core.tooluse.AiToolUseImprovementCliKt")
    args("--dry-run")
    standardInput = System.`in`
}


	val generateLocalKeystore = tasks.register("generateLocalKeystore") {
		group = "application"
		description = "Generates a self-signed PKCS12 keystore for local HTTPS (config/field4-local.p12)."

		val javaLauncher = javaToolchains.launcherFor {
			languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
		}

    val keystoreFile = layout.projectDirectory.file("config/field4-local.p12").asFile
    outputs.file(keystoreFile)
    inputs.property("javaHome", javaLauncher.map { it.metadata.installationPath.asFile })

    doLast {
        if (keystoreFile.exists()) {
            logger.lifecycle("Keystore already exists at ${keystoreFile}; delete it to regenerate.")
            return@doLast
        }

        keystoreFile.parentFile.mkdirs()

        val javaHome = javaLauncher.get().metadata.installationPath.asFile
        val keytoolName = if (System.getProperty("os.name").lowercase().contains("windows")) "keytool.exe" else "keytool"
        val keytoolPath = javaHome.toPath().resolve("bin").resolve(keytoolName).toFile()

        if (!keytoolPath.exists()) {
            throw GradleException("keytool not found at ${keytoolPath.absolutePath}")
        }

        val exit = ProcessBuilder(
            keytoolPath.absolutePath,
            "-genkeypair",
            "-alias", "field4",
            "-keyalg", "RSA",
            "-storetype", "PKCS12",
            "-keystore", keystoreFile.absolutePath,
            "-storepass", "changeit",
            "-keypass", "changeit",
            "-dname", "CN=localhost",
            "-validity", "3650",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1"
        )
            .directory(projectDir)
            .inheritIO()
            .start()
            .waitFor()
        if (exit != 0) {
            throw GradleException("keytool failed with exit code $exit")
        }
    }
}

tasks.named<JavaExec>("runJvm").configure {
    dependsOn(generateLocalKeystore)
}


tasks.named("jvmDistTar") {
    enabled = false
}

tasks.named("jvmDistZip") {
    enabled = false
}
