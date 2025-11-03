package afield

import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class KotlinJS {
    companion object {
        val JS_LIBRARY = "/Users/marc/temp/kotlin-compiler-server/2.2.0-js/kotlin-stdlib-js-2.2.0.klib:/Users/marc/temp/kotlin-compiler-server/2.2.0-js/kotlin-dom-api-compat-2.2.0.klib:/Users/marc/temp/jvm-js-fullstack/build/libs/afield-js-1.0-SNAPSHOT.klib"
        val disp = Disposable {}
    }

    @OptIn(ExperimentalUuidApi::class)
    fun translate(s: String): String? {
        val k = K2JSCompiler()

        val u = Uuid.random().toString()

        val baseDir = File("/Users/marc/Desktop/temp_afield_translation/$u")
        baseDir.mkdirs()
        val klibPath = File("/Users/marc/Desktop/temp_afield_translation/$u/klib")
        klibPath.mkdirs()
        val outputPath = File("/Users/marc/Desktop/temp_afield_translation/$u/js")
        outputPath.mkdirs()
        val source = File(baseDir, "a.kt")
        source.writeText(s)

        val moduleName = "playground"

        /*
        additionalCompilerArgumentsForKLib = [-Xreport-all-warnings, -Wextra, -Xir-produce-klib-dir, -libraries=/Users/marc/temp/kotlin-compiler-server/2.2.0-js/kotlin-stdlib-js-2.2.0.klib:/Users/marc/temp/kotlin-compiler-server/2.2.0-js/kotlin-dom-api-compat-2.2.0.klib, -ir-output-dir=/private/var/folders/_4/6wtcgm5s0_g2cbs124t1s3fc0000gn/T/3ed24f02e64f40478e7d02c60ba5b129/klib, -ir-output-name=playground]

a2 = [-Xreport-all-warnings, -Wextra, -Xir-produce-js, -Xir-dce, -Xinclude=/private/var/folders/_4/6wtcgm5s0_g2cbs124t1s3fc0000gn/T/164dfca41c7b45a9aa6f79b4bf4b4b1a/klib, -libraries=/Users/marc/temp/kotlin-compiler-server/2.2.0-js/kotlin-stdlib-js-2.2.0.klib:/Users/marc/temp/kotlin-compiler-server/2.2.0-js/kotlin-dom-api-compat-2.2.0.klib, -ir-output-dir=/private/var/folders/_4/6wtcgm5s0_g2cbs124t1s3fc0000gn/T/164dfca41c7b45a9aa6f79b4bf4b4b1a/js, -ir-output-name=playground]
         */

        val filePaths = listOf(source.absolutePath)

        val additionalCompilerArgumentsForKLib = listOf(
            "-Xreport-all-warnings",
            "-Wextra",
            "-Xir-produce-klib-dir",
            "-libraries=${JS_LIBRARY}",
            "-ir-output-dir=$klibPath",
            "-ir-output-name=$moduleName",
            "-target=es2015",
            "-module-kind=es", "-Xes-classes", "-Xpartial-linkag=enable",
        )


        if (k.tryCompilation(filePaths + additionalCompilerArgumentsForKLib)) {
            if (k.tryCompilation(
                    listOf(
                        "-Xreport-all-warnings",
                        "-Xir-produce-js", "-Xes-classes", "-Xpartial-linkage=enable",
                        "-Xinclude=$klibPath",
                        "-libraries=${JS_LIBRARY}",
                        "-ir-output-dir=${outputPath.absolutePath}",
                        "-target=es2015",
                        "-Xir-per-module",
                        "-module-kind=es",
                        "-ir-output-name=$moduleName",
                    )
                )
            ) {
                val converted = File(outputPath, moduleName + ".mjs").readText()
                println(" before import demangeling --- ")
                println(converted)
                println(" --- ")
                val deconverted = transformImports(converted)
                println(" after import demangeling --- ")
                println(deconverted)
                println(" --- ")
                return deconverted
            }
        }

        return null

    }


    fun CLICompiler<*>.tryCompilation(
        arguments: List<String>,
    ): Boolean {
        val exitCode = CLICompiler.doMainNoExit(this, arguments.toTypedArray(), object : MessageRenderer {
            override fun renderPreamble(): String = ""

            override fun render(
                severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?
            ): String {
                println("compiler has message - $severity: $message @ $location")
                return ""
            }

            override fun renderUsage(usage: String): String =
                render(CompilerMessageSeverity.STRONG_WARNING, usage, null)

            override fun renderConclusion(): String = ""

            override fun getName(): String = "Redirector"
        })

        println(" ran and got exit code ${exitCode.code}")



        return exitCode.code == 0
    }


    fun transformImports(source: String): String {
        // Only gobbles up the single import block, never spans past its first '}'
        val afieldBlock = Regex(
            pattern = """import\s*\{\s*([^}]*?)\s*}\s*from\s*['"]\./afield\.mjs['"];""",
            options = setOf(RegexOption.MULTILINE)
        )
        val helperFns = mutableListOf<String>()

        val cleanedSource = afieldBlock.replace(source) { match ->
            val body = match.groupValues[1]
            val entries = body
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { entry ->
                    val parts = entry.split(Regex("""\s+as\s+"""))
                    if (parts.size != 2) return@map entry
                    val origWithHash = parts[0]
                    val alias        = parts[1]

                    // strip any trailing 13-char hash
                    val stripped = origWithHash.replace(Regex("[a-z0-9]{13}$"), "")

                    if (alias.startsWith("get_")) {
                        val base = alias.removePrefix("get_")
                        helperFns += "function get_$base() { return $base.get() }"
                        "$base as $base"
                    } else {
                        "$stripped as $alias"
                    }
                }

            // rebuild neatly
            """
        import {
          ${entries.joinToString(",\n  ")}
        } from './afield.mjs';
        """.trimIndent()
        }

        return if (helperFns.isEmpty()) {
            cleanedSource
        } else {
            cleanedSource + "\n\n" + helperFns.joinToString("\n")
        }
    }


}





