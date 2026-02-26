@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalDistributionDsl::class)

import java.net.URL
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalDistributionDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack

val lwjglVersion = "3.4.0-SNAPSHOT"
val jomlVersion = "1.10.7"
val `joml-primitivesVersion` = "1.10.0"

val osName = System.getProperty("os.name")!!
val osArch = System.getProperty("os.arch")!!
println("$osName, $osArch")
val lwjglNatives = when {
    arrayOf("Mac OS X", "Darwin").any { osName.startsWith(it) } ->
        if (osArch == "aarch64") "natives-macos-arm64" else "natives-macos"

    arrayOf("Windows").any { osName.startsWith(it) } ->
        "natives-windows"

    arrayOf("Linux").any { osName.startsWith(it) } ->
        "natives-linux"

    else ->
        throw Error("Unrecognized or unsupported platform. Please set \"lwjglNatives\" manually")
}
val extraLwjglNativeClassifiers =
    if (lwjglNatives == "natives-macos-arm64") listOf("natives-macos") else emptyList()

val kotlinVersion = "2.3.0"
val serializationVersion = "1.6.0"
val ktorVersion = "3.3.0"
val logbackVersion = "1.2.11"
val imguiVersion = "1.90.0"
val smileVersion = "5.0.1"
val javaToolchainVersion = 25

// Bytedeco FFmpeg for video support
val ffmpegVersion = "7.1-1.5.11"
val javacppVersion = "1.5.11"
//val kotlinWrappersVersion = "1.0.0-pre.621"
val electronDir = layout.projectDirectory.dir("electron")

fun requiredRunJvmArgs(): List<String> {
    val os = System.getProperty("os.name").lowercase()
    val onWindowsOrLinux = os.contains("windows") || os.contains("linux")

    return buildList {
        add("--add-opens=java.base/java.net=ALL-UNNAMED")
        add("-Dorg.lwjgl.system.stackSize=256")
        add("-Xlog:gc*,safepoint:file=build/gc-safepoint.log:time,uptime,level,tags")
        if (!onWindowsOrLinux) {
            add("-XstartOnFirstThread")
            add("-Djoml.format=false")
            add("-Dorg.lwjgl.util.Debug=true")
            add("-Dorg.lwjgl.util.DebugLoader=true")
        }
        add("--add-modules")
        add("jdk.incubator.vector")
    }
}

plugins {
    kotlin("multiplatform") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.dokka") version "2.1.0"
    id("me.filippov.gradle.jvm.wrapper") version "0.14.0"
    id("com.github.node-gradle.node") version "7.1.0"
}

jvmWrapper {
    unixJvmInstallDir = "${"$"}{HOME}/my-custom-path/gradle-jvm"
    winJvmInstallDir = "%LOCALAPPDATA%\\gradle-jvm"
    linuxAarch64JvmUrl = "https://corretto.aws/downloads/resources/11.0.9.12.1/amazon-corretto-11.0.9.12.1-linux-aarch64.tar.gz"
    linuxX64JvmUrl = "https://corretto.aws/downloads/latest/amazon-corretto-11-x64-linux-jdk.tar.gz"
    macAarch64JvmUrl = "https://cdn.azul.com/zulu/bin/zulu11.45.27-ca-jdk11.0.10-macosx_aarch64.tar.gz"
    macX64JvmUrl = "https://corretto.aws/downloads/latest/amazon-corretto-11-x64-macos-jdk.tar.gz"
    windowsX64JvmUrl = "https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaToolchainVersion)
        // Constrain vendor to avoid Foojay returning non-directly-downloadable builds (which can 4xx on /redirect).
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

group = "afield"
version = "1.1-SNAPSHOT"

node {
    download.set(true)
    version.set("20.12.2")
    npmVersion.set("10.5.0")
    nodeProjectDir.set(electronDir.asFile)
}

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://central.sonatype.com/repository/maven-snapshots")
    maven("https://repo.kotlin.link")
    maven(url = "https://repo.clojars.org/")
    maven("https://jogamp.org/deployment/maven")

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
                    "-Djoml.format=false","-Dorg.lwjgl.util.Debug=true","-Dorg.lwjgl.util.DebugLoader=true"
                )

                args += "-Dorg.lwjgl.system.stackSize=257"
                args += "-Xlog:gc*,safepoint:file=build/gc-safepoint.log:time,uptime,level,tags"

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
                implementation("org.lwjgl:lwjgl-vma:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-shaderc:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-spvc:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-vulkan")

                implementation("io.github.spair:imgui-java-binding:$imguiVersion")
                implementation("io.github.spair:imgui-java-natives-$targetOs:$imguiVersion")

                val addLwjglNativeArtifacts: (String) -> Unit = { classifier ->
                    implementation("org.lwjgl:lwjgl-glfw::$classifier")
                    implementation("org.lwjgl:lwjgl-stb::$classifier")
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
                implementation("org.joml:joml:$jomlVersion")
                implementation("org.joml:joml-primitives:${`joml-primitivesVersion`}")

                implementation("io.github.earcut4j:earcut4j:2.2.2")

//                implementation("io.insert-koin:koin-ktor:4.0.4")
                implementation("io.ktor:ktor-client-okhttp:$ktorVersion")

                implementation("org.jetbrains.trove4j:trove4j:20160824")
                implementation("org.json:json:20240303")

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
                implementation("org.ow2.asm:asm:7.3.1")
                implementation("org.ow2.asm:asm-commons:7.3.1")
                implementation("org.ow2.asm:asm-tree:7.3.1")
                implementation("org.ow2.asm:asm-util:7.3.1")

                implementation("com.aallam.openai:openai-client:4.0.1")
                implementation("com.openai:openai-java:4.13.0")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.1")

                implementation("org.jetbrains:markdown:0.5.2")
                implementation("io.github.sashirestela:simple-openai:3.22.2")

                val kotlinScriptingVersion = "2.3.0"

                /*
                gently customized versions of the kotlin scripting front-end to provide better completion and skip the history check on the repl evaluator to allow us to reused compiled fragments
                 */
//                implementation(files("libs/kotlin-scripting-common-2.2.255-SNAPSHOT.jar"))
//                implementation(files("libs/kotlin-scripting-jvm-2.2.255-SNAPSHOT.jar"))
//                implementation(files("libs/kotlin-scripting-ide-services-2.2.255-SNAPSHOT.jar"))
//                implementation(files("libs/kotlin-scripting-ide-common-2.2.255-SNAPSHOT.jar"))

                implementation(files("libs/jep-4.2.2.jar"))


                implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinScriptingVersion")
                implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-impl-embeddable:$kotlinScriptingVersion")
                implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:$kotlinScriptingVersion")
                implementation("com.facebook:ktfmt:0.54")

                implementation(files("libs/kotlin-scripting-ide-services-2.3.255-SNAPSHOT.jar"))
                implementation(files("libs/kotlin-scripting-common-2.3.255-SNAPSHOT.jar"))
                implementation(files("libs/kotlin-scripting-jsr223-2.3.255-SNAPSHOT.jar"))
                implementation(files("libs/kotlin-scripting-jvm-2.3.255-SNAPSHOT.jar"))
                implementation(files("libs/kotlin-scripting-jvm-host-2.3.255-SNAPSHOT.jar"))

//                implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinScriptingVersion")
                implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies:$kotlinScriptingVersion")
                implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies-maven:$kotlinScriptingVersion")
                implementation("org.apache.maven.resolver:maven-resolver-api:2.0.7")

                implementation("org.joml:joml-experimental:1.10.7")

                implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")

                implementation("org.jetbrains.skiko:skiko-awt-runtime-$target:$version")

                implementation("com.google.devtools.ksp:symbol-processing-aa-embeddable:2.1.0-1.0.29")
                implementation("com.google.devtools.ksp:symbol-processing-api:2.1.0-1.0.29")
                implementation("com.google.devtools.ksp:symbol-processing:2.1.0-1.0.29")

                implementation("space.kscience:plotlykt-core:0.7.1.1")
                implementation("space.kscience:plotlykt-server:0.7.1.1")

                implementation("org.locationtech.jts:jts-core:1.20.0")

                implementation("io.github.classgraph:classgraph:4.8.179")

                implementation("com.github.sya-ri:kgit:1.1.0")

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

                // Processing support
                val jogampVersion = "2.5.0"
                val osName = System.getProperty("os.name").lowercase()
                val osArch = System.getProperty("os.arch").lowercase()
                val jogampClassifier = when {
                    osName.contains("mac") -> "natives-macosx-universal"
                    osName.contains("linux") -> if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                        "natives-linux-aarch64"
                    } else {
                        "natives-linux-amd64"
                    }
                    osName.contains("windows") -> "natives-windows-amd64"
                    else -> null
                }

                implementation("org.processing:core:4.3.1")
                implementation("org.jogamp.jogl:jogl-all:$jogampVersion")
                implementation("org.jogamp.jogl:nativewindow:$jogampVersion")
                implementation("org.jogamp.jogl:nativewindow-main:$jogampVersion")
                implementation("org.jogamp.gluegen:gluegen-rt:$jogampVersion")
                if (jogampClassifier != null) {
                    runtimeOnly("org.jogamp.jogl:jogl-all:$jogampVersion:$jogampClassifier")
                    runtimeOnly("org.jogamp.jogl:nativewindow:$jogampVersion:$jogampClassifier")
                    runtimeOnly("org.jogamp.gluegen:gluegen-rt:$jogampVersion:$jogampClassifier")
                }

                // personal only
                implementation("org.yaml:snakeyaml:2.4")

                // Bytedeco FFmpeg for video texture support
                implementation("org.bytedeco:javacpp:$javacppVersion")
                implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
                implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$ffmpegPlatform")
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



    }

}



//// include JS artifacts in any JAR we generate
tasks.named<Jar>("jvmJar").configure {
    val taskName = if (project.hasProperty("isProduction")
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
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            freeCompilerArgs.add("-Xannotations-in-metadata")
        }
    }
}



tasks.named<JavaExec>("runJvm").configure {
    val requiredJvmArgs = requiredRunJvmArgs()

    doFirst {
        val merged = (jvmArgs ?: emptyList<Any>()).map { it.toString() }.toMutableList()
        requiredJvmArgs.forEach { arg ->
            if (!merged.contains(arg)) merged += arg
        }
        setJvmArgs(merged)
    }

    dependsOn(tasks.named("jvmJar"))
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
            return source.replace(pattern, "$1$value$3")
        }

        val original = plistFile.readText()
        var updated = original
        updated = replacePlistString(updated, "CFBundleDisplayName", "Field4")
        updated = replacePlistString(updated, "CFBundleName", "Field4")
        updated = replacePlistString(updated, "CFBundleExecutable", "Field4")
        updated = replacePlistString(updated, "CFBundleIdentifier", "com.field4.desktop")

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

tasks.register("runDesktop") {
    group = "application"
    description = "Runs backend and Electron together, then shuts both down cleanly. Use -PwithDevTools=true to open DevTools."

    dependsOn(electronInstall)
    dependsOn(tasks.named("jvmJar"))
    dependsOn(tasks.named("generateLocalKeystore"))
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

        val backendJvmArgs = (runJvmTask.jvmArgs ?: emptyList()).map { it.toString() }.toMutableList()
        requiredRunJvmArgs().forEach { arg ->
            if (!backendJvmArgs.contains(arg)) backendJvmArgs += arg
        }

        val backendCommand = mutableListOf<String>()
        backendCommand += javaExecutable
        backendCommand += backendJvmArgs
        backendCommand += "-cp"
        backendCommand += runJvmTask.classpath.asPath
        backendCommand += backendMain
        backendCommand += (runJvmTask.args ?: emptyList()).map { it.toString() }

        logger.lifecycle("Starting backend process via runJvm settings.")
        val backendProcess = ProcessBuilder(backendCommand)
            .directory(project.projectDir)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .apply {
                environment()["PORT"] = port
            }
            .start()
        forwardProcessOutput(backendProcess)

        val os = System.getProperty("os.name").lowercase()
        val isWindows = os.contains("windows")
        val isMac = os.contains("mac")
        val electronCommand = if (isWindows) {
            listOf(electronDir.file("node_modules/.bin/electron.cmd").asFile.absolutePath, ".")
        } else if (isMac) {
            val field4Primary = electronDir.file("node_modules/electron/dist/Field4.app/Contents/MacOS/Field4").asFile
            val field4Fallback = electronDir.file("node_modules/electron/dist/Electron.app/Contents/MacOS/Field4").asFile
            val electronFallback = electronDir.file("node_modules/electron/dist/Electron.app/Contents/MacOS/Electron").asFile
            val binary = when {
                field4Primary.exists() -> field4Primary
                field4Fallback.exists() -> field4Fallback
                else -> electronFallback
            }
            listOf(binary.absolutePath, ".")
        } else {
            listOf(electronDir.file("node_modules/.bin/electron").asFile.absolutePath, ".")
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
            val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
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

tasks.register<JavaExec>("runai") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    group = "application"
    description = "Runs ai3 tests"

    classpath = sourceSets["jvmMain"].runtimeClasspath

    mainClass.set("afield.ai.AI3Kt") // adjust to match your file/class
    standardInput = System.`in`
}

tasks.register<JavaExec>("runai2") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    group = "application"
    description = "Runs ai2 chat"

    classpath = sourceSets["jvmMain"].runtimeClasspath

    mainClass.set("plans.mcp.ChatKt") // adjust to match your file/class
    standardInput = System.`in`
}

tasks.register<JavaExec>("runmcp") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    })
    group = "application"
    description = "Runs ai2 tests"

    classpath = sourceSets["jvmMain"].runtimeClasspath

    mainClass.set("afield.ai.SimpleMCP2Kt") // adjust to match your file/class
    standardInput = System.`in`
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

tasks.register("generateApiDocs") {
    group = "documentation"
    description = "Generates API documentation (HTML) with Dokka"

    dependsOn("dokkaGenerateHtml")

    doLast {
        println("API documentation generated in build/dokka/html/")
        println("To build the MkDocs site, run: cd public && mkdocs build")
    }
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

        exec {
            workingDir = projectDir
            commandLine(
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



dokka {
    // HTML documentation (Dokka Gradle plugin v2)
    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))

        dokkaSourceSets.configureEach {
            perPackageOption {
                matchingRegex.set(".*")
                suppress.set(true)
            }

            perPackageOption {
                matchingRegex.set("""afield\.four(\..*)?""")
                suppress.set(false)
            }
            perPackageOption {
                matchingRegex.set("""afield\.stdlib(\..*)?""")
                suppress.set(false)
            }

            skipEmptyPackages.set(true)
        }
    }
}
