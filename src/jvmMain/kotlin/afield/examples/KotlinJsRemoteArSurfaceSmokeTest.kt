package afield.examples

import afield.graphics.RemoteArKotlinJs
import afield.remotejs.KotlinJsHeadProfiles
import afield.remotejs.KotlinToJsCompiler

/*
FIELD_TEST
id: remotejs.kotlinjs-remotear-surface
runner: jvm-main
entrypoint: afield.examples.KotlinJsRemoteArSurfaceSmokeTest
tags: [smoke, remotejs, kotlinjs]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinJsRemoteArSurfaceSmokeTest ok"
*/

/**
 * Validates the expanded RemoteAR surface (math types + `vec`/`clearAll` + the dynamic-with-typed-
 * completion `stage`) by compiling a sample that exercises it against the GENERATED compiler
 * declarations — the real check that the binding model emits valid `external` Kotlin/JS for every
 * new name and that they type-check together. Also asserts the `dynamic` (compiler) vs `Stage`
 * (completion) split so `stage.foo = x` stays permissive while `stage.add|` gets completion.
 */
object KotlinJsRemoteArSurfaceSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val compiler = RemoteArKotlinJs.declarations
        val completion = RemoteArKotlinJs.completionDeclarations

        // dynamic-with-typed-completion split for `stage`.
        require(compiler.contains("external val stage: dynamic")) { "stage must be `dynamic` for the compiler" }
        require(completion.contains("val stage: Stage")) { "stage must be typed `Stage` for completion" }
        require(completion.contains("class Stage")) { "completion mirror must declare Stage" }
        require(!compiler.contains("class Stage")) { "Stage is completion-only; must not enter the JS target" }
        require(completion.contains("operator fun get(name: String)") && completion.contains("fun add(item: Any?)")) {
            "Stage must expose indexed get + add for completion"
        }

        // math types present in BOTH renderings.
        for (t in listOf("Vector4", "Quaternion", "Matrix4", "Color")) {
            require(compiler.contains("class $t")) { "compiler missing THREE.$t" }
            require(completion.contains("class $t")) { "completion missing THREE.$t" }
        }
        require(compiler.contains("external fun vec(")) { "vec must be an installed external global" }

        // The whole new surface must type-check against the generated compiler declarations.
        val sample = """
            // {{ remoteAR }}
            val p = vec(1.0, 2.0, 3.0)
            val q = p + vec(4.0, 5.0, 6.0)
            val v4 = THREE.Vector4(1.0, 2.0, 3.0, 4.0) * 2.0
            val col = THREE.Color(1.0, 0.5, 0.0)
            col.setRGB(0.2, 0.4, 0.6)
            val quat = THREE.Quaternion().setFromAxisAngle(vec(0.0, 1.0, 0.0), 1.57)
            val mat = THREE.Matrix4().makeTranslation(q.x, q.y, q.z)
            val flat = vec(1.0, 2.0)
            val line = FLine().moveTo(0.0, 0.0).lineTo(1.0, 1.0)
            stage.add(line)
            stage["named"] = FLine()
            stage.dynamicNamed = FLine()
            clearAll()
            log("surface ok " + col.getHex() + " " + v4.w + " " + flat.x + " " + quat.w + " " + mat)
        """.trimIndent()

        val resolved = KotlinJsHeadProfiles.resolve(sample)
        require(resolved.profile === KotlinJsHeadProfiles.remoteAr) { "sample must select the remoteAR head" }
        val prepared = resolved.profile.prepareSource(resolved.source)
        val result = KotlinToJsCompiler.compile(prepared, moduleName = "remotear_surface", wrapImplicitMain = false)
        require(result.success && result.js != null) {
            "expanded RemoteAR surface did not compile:\n${result.rawLog.takeLast(2000)}"
        }
        println("expanded surface compiled: ${prepared.length} chars Kotlin -> ${result.js!!.length} chars JS")

        // The user's ask: `stage.add|` completes even though `stage` is `dynamic` for the compiler.
        // Skip-tolerant — needs the AA sidecar (feeds `completionDeclarations` as file-scope context).
        System.getProperty("afield.completion.sidecarUrl")
            ?: System.getenv("AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL")?.let { System.setProperty("afield.completion.sidecarUrl", it) }
        val stageDot = complete("stage.")
        if (stageDot == null) {
            println("no AA sidecar reachable; façade completion checks skipped")
        } else {
            require(stageDot.any { it.startsWith("add") } && stageDot.any { it.startsWith("remove") }) {
                "typed `stage.` completion missing add/remove: ${stageDot.take(20)}"
            }
            val threeCo = complete("THREE.Co") ?: emptyList()
            require(threeCo.any { it.startsWith("Color") }) { "THREE.Co did not complete Color: ${threeCo.take(20)}" }
            println("completion: stage. → ${stageDot.filter { it.startsWith("add") || it.startsWith("remove") }}; THREE.Co → ${threeCo.filter { it.startsWith("Color") }}")
        }

        println("KotlinJsRemoteArSurfaceSmokeTest ok")
    }

    /** Complete against the generated completion mirror; null if no sidecar answered (→ skip). */
    private fun complete(snippet: String): List<String>? {
        val decl = RemoteArKotlinJs.completionDeclarations
        val roots = afield.AaCompletionClient.kotlinStdlibRoots()
        var unreachable = true
        repeat(8) {
            val o = afield.AaCompletionClient.complete(
                snippet = snippet, cursor = snippet.length, preamble = "",
                declarations = decl, implicitReceivers = emptyList(), classpathRootsOverride = roots,
            )
            if (o.detail.let { !it.startsWith("no-sidecar") && !it.startsWith("error") && !it.startsWith("http") }) unreachable = false
            (o.result as? afield.CompletionResult.Success)?.let { return it.metadata.map { m -> m.text } }
            Thread.sleep(400)
        }
        return if (unreachable) null else emptyList()
    }
}
