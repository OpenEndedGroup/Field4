plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("afield.stdlib.linalg.codegen.MainKt")
}

val generatedRoot = rootProject.layout.projectDirectory.dir("src/generatedLinalg/kotlin")
val generatedDocsRoot = rootProject.layout.projectDirectory.dir("public/docs")

tasks.named<JavaExec>("run") {
    args(
        "--output", generatedRoot.asFile.absolutePath,
        "--docs-output", generatedDocsRoot.asFile.absolutePath,
    )
}

tasks.register<JavaExec>("checkGeneratedLinalg") {
    group = "verification"
    description = "Checks that committed linear-algebra sources match the semantic model."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args(
        "--output", generatedRoot.asFile.absolutePath,
        "--docs-output", generatedDocsRoot.asFile.absolutePath,
        "--check",
    )
}
