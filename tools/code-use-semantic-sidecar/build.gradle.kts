plugins {
    kotlin("jvm") version "2.5.0-Beta1"
    kotlin("plugin.serialization") version "2.5.0-Beta1"
    application
}

val analysisApiVersion = "2.5.0-Beta1"
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
    // Until 2.4.20 these two shipped inside kotlin-compiler-common-for-ide; 2.5.0 splits them out,
    // and without them every AA session build fails at runtime on a missing
    // META-INF/analysis-api/low-level-api-fir.xml (the compile does not notice).
    implementation("org.jetbrains.kotlin:low-level-api-fir-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:symbol-light-classes-for-ide:$analysisApiVersion") {
        isTransitive = false
    }
    // The Analysis API's POMs bring kotlin-reflect transitively, which `isTransitive = false` drops.
    // LLFirTypeLazyResolver reads a property reference's getter, so without it every completion
    // fails with KotlinReflectionNotSupportedError and Field quietly serves K1.
    implementation(kotlin("reflect"))
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

// Phase 1 completion spike runner: java -cp <runtime> afield.tools.codeuse.CompletionSpike
tasks.register<JavaExec>("runSpike") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("afield.tools.codeuse.CompletionSpike")
    jvmArgs("-Dapple.awt.UIElement=true", "-Xmx6g")
    args((project.findProperty("spikeArgs") as String? ?: "").split(" ").filter { it.isNotBlank() })
}
