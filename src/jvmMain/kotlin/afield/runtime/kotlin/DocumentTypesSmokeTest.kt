package afield.runtime.kotlin

import afield.app.server.SessionInformation
import afield.app.settings.needsRerun
import afield.document.graph.Box
import afield.document.graph.intro
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic

/*
FIELD_TEST
id: kotlin.document-types.smoke
runner: jvm-main
entrypoint: afield.runtime.kotlin.DocumentTypesSmokeTest
tags: [smoke, kotlin, repl, exported]
timeoutSec: 420
expect:
  exitCode: 0
  stdoutContains:
    - "Document types smoke passed"
*/
/**
 * Shared Kotlin boxes against real K2 REPL engines, as Field builds them: a shared box compiles
 * with the ordinary intro imports, other boxes' warm engines see its classes without being rebuilt,
 * and every box sees the one `Class` the shared box itself made.
 */
object DocumentTypesSmokeTest {
    private fun session(name: String): SessionInformation {
        val root = Box("root-$name")
        val si = SessionInformation(Files.createTempDirectory("field-doc-types-$name").toString(), name, root = root)
        root.inside = si
        si.boxes[root.id] = root
        return si
    }

    private fun box(si: SessionInformation, id: String, shared: Boolean, code: String = ""): Box = Box(id).also {
        it.inside = si
        if (shared) it.setPropertyRaw("box_kind", SHARED_KOTLIN_BOX, doResync = false)
        it.setPropertyRaw("code", code, doResync = false)
        si.boxes[id] = it
    }

    private suspend fun engine(si: SessionInformation, box: Box): KotlinInterface2 {
        val shared = si.documentTypes.isShared(box)
        val ki = KotlinInterface2(
            implicitReceivers = listOf(KotlinType(Box::class)),
            classLoader = si.documentTypes.loader,
            filePrefix = if (shared) si.documentTypes.publisherPrefix(box) else box.id,
            publishesToDocument = shared,
        )
        ki.associatedBox = box
        withTimeout(180_000) { ki.bootstrap(intro, implicits = listOf(box)) }
        return ki
    }

    /** Run [code] in [box]'s [ki] as a run of the box would; the errors it reported, if any. */
    private suspend fun run(si: SessionInformation, box: Box, ki: KotlinInterface2, code: String): Pair<Any?, List<String>> {
        si.documentTypes.beforeRun(box)
        val errors = mutableListOf<String>()
        val report = { d: List<ScriptDiagnostic> -> d.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.forEach { errors += it.message } }
        val r = withTimeout(180_000) { ki.exec(code, report, report, { errors += it.toString() }, implicits = listOf(box)) }
        return (r as? ResultValue.Value)?.value to errors
    }

    private suspend fun ok(si: SessionInformation, box: Box, ki: KotlinInterface2, code: String): Any? {
        val (value, errors) = run(si, box, ki, code)
        check(errors.isEmpty()) { "${box.id}: $code\n -> $errors" }
        return value
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val a = session("a")
        val types = a.documentTypes

        // The case that started this: code moved from an ordinary box, using the intro's Vector3f.
        val shared = box(a, "garden_types", shared = true)
        val sharedKi = engine(a, shared)
        ok(a, shared, sharedKi, "data class Banana(val x: Vector3f)\nfun helper(a: Int) = a + 1\nval sample = Banana(Vector3f(9f, 9f, 9f))")
        val first = checkNotNull(types.afterSuccessfulRun(shared))
        check(first.ok && types.published()[shared.id] == listOf("Banana")) { first.message }
        check(first.message.contains("Not shared") && first.message.contains("helper")) { first.message }
        val sample = checkNotNull(ok(a, shared, sharedKi, "sample"))

        // A consumer whose engine has already compiled sees the class, and it is the same Class.
        val user = box(a, "garden_view", shared = false, code = "Banana(Vector3f(1f, 2f, 3f))")
        val userKi = engine(a, user)
        user.replaceKi(userKi)
        ok(a, user, userKi, "1 + 1")
        val made = checkNotNull(ok(a, user, userKi, "Banana(Vector3f(1f, 2f, 3f)).copy(x = Vector3f(0f, 0f, 0f))"))
        check(made.javaClass === sample.javaClass) { "${made.javaClass} (${made.javaClass.classLoader}) vs ${sample.javaClass} (${sample.javaClass.classLoader})" }
        check(made.javaClass.classLoader === types.loader)
        check(ok(a, user, userKi, "Banana(Vector3f(1f, 2f, 3f)).component1().y").toString() == "2.0")

        // Changing the class: the same warm consumer engine sees the new one, and is told to re-run.
        user.needsRerun = null
        ok(a, shared, sharedKi, "data class Banana(val x: Vector3f) { val doubled get() = x.x * 2 }")
        check(checkNotNull(types.afterSuccessfulRun(shared)).ok)
        check(user.needsRerun != null) { "the box using Banana was not marked" }
        check(ok(a, user, userKi, "Banana(Vector3f(3f, 0f, 0f)).doubled").toString() == "6.0")

        // An old value is reported as old, by name, instead of failing a cast.
        val newer = checkNotNull(ok(a, user, userKi, "Banana(Vector3f(1f, 0f, 0f))"))
        check(types.sameDeclaration(made.javaClass, newer.javaClass) && made.javaClass !== newer.javaClass)
        val stale = runCatching { types.requireCurrent(made, user) }.exceptionOrNull()
        check(stale is IllegalStateException && stale.message.orEmpty().contains("earlier run")) { "$stale" }
        types.requireCurrent(newer, user)
        types.requireCurrent(listOf(newer), user)
        check(runCatching { types.requireCurrent(listOf(made), user) }.isFailure)

        // A shared box that fails to compile keeps its last good classes in force.
        val (_, broken) = run(a, shared, sharedKi, "data class Banana(")
        check(broken.isNotEmpty())
        check(ok(a, user, userKi, "Banana(Vector3f(4f, 0f, 0f)).doubled").toString() == "8.0")

        // Two shared boxes cannot share one name.
        val clash = box(a, "other_types", shared = true)
        val clashKi = engine(a, clash)
        ok(a, clash, clashKi, "data class Banana(val y: Int)")
        val refused = checkNotNull(types.afterSuccessfulRun(clash))
        check(!refused.ok && refused.message.contains("already shared")) { refused.message }

        // One shared box can use another's classes.
        val seeds = box(a, "seed_types", shared = true)
        val seedsKi = engine(a, seeds)
        ok(a, seeds, seedsKi, "data class Seed(val banana: Banana)")
        check(checkNotNull(types.afterSuccessfulRun(seeds)).ok)
        check(ok(a, user, userKi, "Seed(Banana(Vector3f(1f, 0f, 0f))).banana.doubled").toString() == "2.0")

        // Another document has its own classes under the same name.
        val b = session("b")
        val bShared = box(b, "b_types", shared = true)
        val bSharedKi = engine(b, bShared)
        ok(b, bShared, bSharedKi, "data class Banana(val label: String)")
        check(checkNotNull(b.documentTypes.afterSuccessfulRun(bShared)).ok)
        val bUser = box(b, "b_view", shared = false)
        val bUserKi = engine(b, bUser)
        val bValue = checkNotNull(ok(b, bUser, bUserKi, "Banana(\"b\")"))
        check(bValue.javaClass.classLoader === b.documentTypes.loader && b.documentTypes.loader !== types.loader)

        // Withdrawn classes are no longer offered to new compiles.
        types.withdraw(seeds)
        check(seeds.id !in types.published())
        val (_, gone) = run(a, user, userKi, "Seed(Banana(Vector3f(1f, 0f, 0f)))")
        check(gone.isNotEmpty()) { "Seed still resolved after its box was withdrawn" }

        listOf(sharedKi, userKi, clashKi, seedsKi, bSharedKi, bUserKi).forEach { it.dispose() }
        println("Document types smoke passed")
    }
}
