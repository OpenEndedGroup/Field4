plugins {
    kotlin("jvm") version "2.5.0-Beta1"
    application
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.5.0-Beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test-junit"))
}

// No jvmToolchain: built on the JDK Gradle runs on (the wrapper's Corretto), which every checkout
// has. A different pinned major needs a second JDK that nothing provisions.
application {
    mainClass.set("afield.tools.refactor.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Dapple.awt.UIElement=true", "-Xmx1g")
}

// Build this distribution before a package cutover. It has no dependency on Field's sources,
// generated files, custom compiler, server, or native libraries.
tasks.test { maxHeapSize = "1g" }
