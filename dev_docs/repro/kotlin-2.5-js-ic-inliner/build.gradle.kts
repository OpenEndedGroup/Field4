import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile
plugins { kotlin("multiplatform") version "2.5.0-Beta1" }
repositories { mavenCentral() }
kotlin { js { nodejs(); binaries.executable() } }
tasks.withType<Kotlin2JsCompile>().configureEach {
    compilerOptions { target = "es2015"; freeCompilerArgs.addAll("-Xcollection-literals") }
}
