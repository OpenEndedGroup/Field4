plugins {
    kotlin("jvm") version "2.4.20-Beta1"
    kotlin("plugin.serialization") version "2.4.20-Beta1"
    application
}

val analysisApiVersion = "2.4.20-Beta1"
val intellijPlatformVersion = "242.8057"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlin:analysis-api-standalone-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:analysis-api-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:analysis-api-k2-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:analysis-api-impl-base-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:analysis-api-platform-interface-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:kotlin-compiler-common-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:kotlin-compiler-cli-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:kotlin-compiler-fir-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:kotlin-compiler-fe10-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:kotlin-compiler-ir-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:kotlin-compiler:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("com.jetbrains.intellij.platform:core:$intellijPlatformVersion") {
        isTransitive = false
    }
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
}

application {
    mainClass.set("afield.tools.codeuse.CodeUseSemanticSidecarKt")
    // This is a headless HTTP sidecar. The IntelliJ/Kotlin Analysis API it pulls in
    // initializes AWT, which on macOS otherwise makes the JVM a foreground app (Duke
    // icon in the Dock / alt-tab switcher). UIElement keeps AWT usable but hides the
    // process from the Dock and app switcher, keeping it a proper background service.
    applicationDefaultJvmArgs = listOf("-Dapple.awt.UIElement=true")
}

kotlin {
    jvmToolchain(25)
}
