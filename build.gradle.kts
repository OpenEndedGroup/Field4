@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalDistributionDsl::class)

import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.sourceSets
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JsModuleKind
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalDistributionDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack

val lwjglVersion = "3.3.6"
val jomlVersion = "1.10.7"
val `joml-primitivesVersion` = "1.10.0"

val lwjglNatives = Pair(
    System.getProperty("os.name")!!,
    System.getProperty("os.arch")!!
).let { (name, arch) ->
    when {
        arrayOf("Mac OS X", "Darwin").any { name.startsWith(it) } ->
            "natives-macos-arm64"

        arrayOf("Windows").any { name.startsWith(it) } ->
            "natives-windows"

        else ->
            throw Error("Unrecognized or unsupported platform. Please set \"lwjglNatives\" manually")
    }
}

val kotlinVersion = "2.2.10"
val serializationVersion = "1.6.0"
val ktorVersion = "3.3.0"
val logbackVersion = "1.2.11"
//val kotlinWrappersVersion = "1.0.0-pre.621"

plugins {
    kotlin("multiplatform") version "2.2.10"
//    application
    kotlin("plugin.serialization") version "2.2.10"
//    id("org.jetbrains.kotlinx.rpc.plugin") version "0.2.4"

    id("me.filippov.gradle.jvm.wrapper") version "0.14.0"
}

jvmWrapper {
    unixJvmInstallDir = "${"$"}{HOME}/my-custom-path/gradle-jvm"
    winJvmInstallDir = "%LOCALAPPDATA%\\gradle-jvm"
    linuxAarch64JvmUrl =
        "https://corretto.aws/downloads/resources/11.0.9.12.1/amazon-corretto-11.0.9.12.1-linux-aarch64.tar.gz"
    linuxX64JvmUrl = "https://corretto.aws/downloads/latest/amazon-corretto-11-x64-linux-jdk.tar.gz"
    macAarch64JvmUrl = "https://cdn.azul.com/zulu/bin/zulu11.45.27-ca-jdk11.0.10-macosx_aarch64.tar.gz"
    macX64JvmUrl = "https://corretto.aws/downloads/latest/amazon-corretto-11-x64-macos-jdk.tar.gz"
    windowsX64JvmUrl = "https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

group = "afield"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://repo.kotlin.link")
    maven(url = "https://repo.clojars.org/")

}


kotlin {
    jvm {
//        withJava()
        binaries {
            executable {
                mainClass.set("afield.Server")
//                mainClass.set("afield.four.examples.NewMain7_dynamicNumberOfVerts")

                println(System.getProperty("os.name"))
                var args = if (System.getProperty("os.name").lowercase()
                        .contains("windows")
                ) listOf("--add-opens=java.base/java.net=ALL-UNNAMED") else listOf(
                    "-XstartOnFirstThread",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "-Djoml.format=false","-Dorg.lwjgl.util.Debug=true","-Dorg.lwjgl.util.DebugLoader=true"
                )

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


    js("jsChat") {
        browser {
            distribution {
                outputDirectory.set(projectDir.resolve("build/output_chat"))
            }
            webpackTask {
                mainOutputFileName = "afield_chat.js"
            }
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

        val osName = System.getProperty("os.name")
        val targetOs = when {
            osName == "Mac OS X" -> "macos"
            osName.startsWith("Win") -> "windows"
            osName.startsWith("Linux") -> "linux"
            else -> error("Unsupported OS: $osName")
        }

        val osArch = System.getProperty("os.arch")
        val targetArch = when (osArch) {
            "x86_64", "amd64" -> "x64"
            "aarch64" -> "arm64"
            else -> error("Unsupported arch: $osArch")
        }

        val version = "0.8.9" // or any more recent version
        val target = "${targetOs}-${targetArch}"



        val jvmMain by getting {

            dependencies {


                implementation("org.slf4j:slf4j-api:2.0.16")
                runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

                implementation(project.dependencies.platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

                implementation("org.lwjgl:lwjgl:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-assimp:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-glfw::$lwjglNatives")
                implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-openvr:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-opengl:${lwjglVersion}")
                implementation("org.lwjgl:lwjgl-stb:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-stb::$lwjglNatives")
                implementation("org.lwjgl:lwjgl-vma:$lwjglVersion")

                implementation("org.lwjgl:lwjgl-shaderc:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-shaderc::${lwjglNatives}")
                implementation("org.lwjgl:lwjgl:$lwjglVersion")
                implementation("org.lwjgl:lwjgl::$lwjglNatives")
                implementation("org.lwjgl:lwjgl-assimp:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-opengl::${lwjglNatives}")
                if (!lwjglNatives.contains("macos")) implementation("org.lwjgl:lwjgl-openvr::${lwjglNatives}")
                implementation("org.lwjgl:lwjgl-vma::${lwjglNatives}")

                implementation("org.lwjgl:lwjgl-stb:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-vma:$lwjglVersion")
                implementation("org.lwjgl:lwjgl-vulkan")

                if (lwjglNatives.contains("macos")) implementation("org.lwjgl:lwjgl-vulkan::${lwjglNatives}")
                implementation("org.joml:joml:$jomlVersion")
                implementation("org.joml:joml-primitives:${`joml-primitivesVersion`}")
                implementation("org.lwjgl:lwjgl-shaderc:$lwjglVersion")

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
                implementation("ai.koog:koog-agents:0.5.0")

                implementation("com.microsoft.playwright:playwright:1.48.0") // version as of Aug 2025


                // for parse tree rewriting — still useful for remote js
//                implementation("org.openjdk.nashorn:nashorn-core:15.6")
                implementation(files("libs/nashorn-core-15.6.jar"))
                implementation("org.ow2.asm:asm:7.3.1")
                implementation("org.ow2.asm:asm-commons:7.3.1")
                implementation("org.ow2.asm:asm-tree:7.3.1")
                implementation("org.ow2.asm:asm-util:7.3.1")

                implementation("com.aallam.openai:openai-client:4.0.1")
                implementation("com.openai:openai-java:3.4.1")
                implementation("org.jetbrains:markdown:0.5.2")
                implementation("io.github.sashirestela:simple-openai:3.22.2")

                val kotlinScriptingVersion = "2.2.0"

                /*
                gently customized versions of the kotlin scripting front-end to provide better completion and skip the history check on the repl evaluator to allow us to reused compiled fragments
                 */
                implementation(files("libs/kotlin-scripting-common-2.2.255-SNAPSHOT.jar"))
                implementation(files("libs/kotlin-scripting-jvm-2.2.255-SNAPSHOT.jar"))
                implementation(files("libs/jep-4.2.2.jar"))



                implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinScriptingVersion")
                implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-impl-embeddable:$kotlinScriptingVersion")
                implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:$kotlinScriptingVersion")
                implementation("com.facebook:ktfmt:0.54")

//                implementation("org.jetbrains.kotlin:kotlin-scripting-ide-services:$kotlinScriptingVersion")
                implementation(files("libs/kotlin-scripting-ide-services-2.2.255-SNAPSHOT.jar"))
//                implementation("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinScriptingVersion")
                implementation(files("libs/kotlin-scripting-ide-common-2.2.255-SNAPSHOT.jar"))


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


                // personal only
                implementation("org.yaml:snakeyaml:2.4")



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


        val jsChatMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.11.0")
                implementation("io.ktor:ktor-client-js:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-client-websockets:$ktorVersion")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-styled-next:1.2.4-pre.779")
                implementation(kotlin("stdlib-js"))
                implementation(kotlin("stdlib"))
            }
            applyDefaultHierarchyTemplate()
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
    from(webpackTask.map { it.mainOutputFile.get().asFile }) // bring output file along into the JAR
    into("static")
}

//tasks.named<KotlinWebpack>("jsChatBrowserDevelopmentWebpack").configure {
//    dependsOn(tasks.named("jsChatProductionExecutableCompileSync"))
//}
//tasks.named<KotlinWebpack>("jsChatBrowserProductionWebpack").configure {
//    dependsOn(tasks.named("jsChatDevelopmentExecutableCompileSync"))
//}
//
//tasks.named<KotlinWebpack>("jsBrowserDevelopmentWebpack").configure {
//    dependsOn(tasks.named("jsProductionExecutableCompileSync"))
//}
//tasks.named<KotlinWebpack>("jsBrowserProductionWebpack").configure {
//    dependsOn(tasks.named("jsDevelopmentExecutableCompileSync"))
//}


tasks.named<Jar>("jvmJar").configure {
    val taskName = if (project.hasProperty("isProduction")
        || project.gradle.startParameter.taskNames.contains("installDist")
    ) {
        "jsChatBrowserProductionWebpack"
    } else {
        "jsChatBrowserDevelopmentWebpack"
    }
    val webpackTask = tasks.named<KotlinWebpack>(taskName)
    dependsOn(webpackTask)
    from(webpackTask.map { it.mainOutputFile.get().asFile }) // bring output file along into the JAR
    into("static")
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
    dependsOn(tasks.named("jvmJar"))
}

tasks.register<JavaExec>("runai") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(24))
    })
    group = "application"
    description = "Runs ai3 tests"

    classpath = sourceSets["jvmMain"].runtimeClasspath

    mainClass.set("afield.ai.AI3Kt") // adjust to match your file/class
    standardInput = System.`in`
}

tasks.register<JavaExec>("runai2") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(24))
    })
    group = "application"
    description = "Runs ai2 chat"

    classpath = sourceSets["jvmMain"].runtimeClasspath

    mainClass.set("plans.mcp.ChatKt") // adjust to match your file/class
    standardInput = System.`in`
}

tasks.register<JavaExec>("runmcp") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(24))
    })
    group = "application"
    description = "Runs ai2 tests"

    classpath = sourceSets["jvmMain"].runtimeClasspath

    mainClass.set("afield.ai.SimpleMCP2Kt") // adjust to match your file/class
    standardInput = System.`in`
}


tasks.named("jvmDistTar") {
    enabled = false
}

tasks.named("jvmDistZip") {
    enabled = false
}

val personalDeps = file("src/personal/dependencies.gradle.kts")
if (personalDeps.exists()) {
    apply(from = personalDeps.path)
}

val personalSrc = file("src/personal")
if (personalSrc.exists()) {
    // add personal sources to jvmMain
    kotlin.sourceSets.named("jvmMain") {
        kotlin.srcDir("src/personal/kotlin")
        resources.srcDir("src/personal/resources")
    }

    // load personal-only dependencies from a file inside src/personal/
    val personalDeps = file("src/personal/dependencies.gradle.kts")
    if (personalDeps.exists()) {
        apply(from = personalDeps.path)
    }
}
