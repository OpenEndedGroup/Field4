plugins {
    kotlin("jvm") version "2.5.0-Beta1"
    application
}
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
// Reuse Server.kt's actual wordmark, without bringing Field or its compiler/native dependencies
// into this independent application. Only this dependency-free source file joins the setup code.
kotlin.sourceSets.getByName("main").kotlin.apply {
    srcDir("../../src/jvmMain/kotlin/afield/internal")
    include("afield/setup/**", "asciiWelcome.kt")
}
application { mainClass.set("afield.setup.FirstRunKt") }
dependencies {
    implementation("org.jline:jline-reader:3.30.6")
    implementation("org.jline:jline-terminal-jansi:3.30.6")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }

// This independent build uses the wrapper's JDK 17. It never configures Field's build or needs LFS.
tasks.register("prepareFirstRun") {
    dependsOn("installDist")
    outputs.upToDateWhen { false }
    doLast {
        fun token(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val launch = layout.buildDirectory.dir("launch").get().asFile.also { it.mkdirs() }
        val java = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val libraries = layout.buildDirectory.dir("install/field-first-run/lib").get().asFile
            .listFiles()!!.filter { it.extension == "jar" }.sortedBy { it.name }
        File(launch, "java").writeText(java.absolutePath + "\n")
        File(launch, "args").writeText("-cp\n" + token(libraries.joinToString(File.pathSeparator) { it.absolutePath }) + "\nafield.setup.FirstRunKt\n")
    }
}
