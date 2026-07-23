package afield.examples

import afield.graphics.RemoteArKotlinJs

/*
FIELD_TEST
id: remotejs.kotlinjs-binding-parity
runner: jvm-main
entrypoint: afield.examples.KotlinJsBindingParitySmokeTest
tags: [smoke, remotejs, kotlinjs]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinJsBindingParitySmokeTest ok"
*/

/**
 * Guards the invariants that motivated generating both façades from one binding model
 * ([afield.graphics.KotlinJsBinding]): the compiler declarations and the completion mirror must agree.
 * These are exactly the two discrepancies that hand-maintenance had produced.
 */
object KotlinJsBindingParitySmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val compiler = RemoteArKotlinJs.declarations
        val completion = RemoteArKotlinJs.completionDeclarations

        // Structural sanity: compiler is `external`, completion is plain.
        require(compiler.contains("external object THREE")) { "compiler THREE must be external" }
        require(completion.contains("object THREE") && !completion.contains("external")) {
            "completion mirror must be plain (no `external`)"
        }

        // Parity fix 1 — Vector2/3 and Euler components are MUTABLE in BOTH renderings (were `var` in
        // the compiler but `val` ctor params in the old completion mirror).
        for (name in listOf("var x: Double", "var y: Double", "var z: Double")) {
            require(compiler.contains(name)) { "compiler missing `$name`" }
            require(completion.contains(name)) { "completion missing `$name` (mutability parity)" }
        }
        require(!completion.contains("val x: Double")) { "completion still declares Vector.x as `val` — parity regressed" }

        // Parity fix 2 — Mesh.geometry / Mesh.material are PROPERTIES in BOTH (the old compiler decl
        // had only constructor parameters, so completion offered members compilation rejected).
        for (member in listOf("var geometry: BufferGeometry", "var material: Material")) {
            require(compiler.contains(member)) { "compiler Mesh missing property `$member`" }
            require(completion.contains(member)) { "completion Mesh missing property `$member`" }
        }

        // dynamic ↔ Any? split is applied.
        require(compiler.contains("val stage: dynamic")) { "compiler stage must be `dynamic`" }
        require(completion.contains("val stage: Any?")) { "completion stage must be `Any?`" }

        // Compiler-only glue must not leak into the completion surface.
        require(compiler.contains("__remoteArLaunch")) { "compiler missing launch glue" }
        require(!completion.contains("__remoteArLaunch")) { "completion must not carry compiler-only glue" }

        println("compiler=${compiler.length} chars, completion=${completion.length} chars; parity invariants hold")
        println("KotlinJsBindingParitySmokeTest ok")
    }
}
