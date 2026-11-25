package afield.runtime.kotlin

import afield.app.server.SessionInformation
import afield.app.settings.needsRerun
import afield.document.graph.Box
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import kotlin.script.experimental.api.ResultValue

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
/** Exercises real compilation, loader identity, replacement, and rollback in two documents. */
object DocumentTypesSmokeTest {
    private fun session(name: String): SessionInformation {
        val root = Box("root-$name")
        val si = SessionInformation(Files.createTempDirectory("field-doc-types-$name").toString(), name, root = root)
        root.inside = si
        si.boxes[root.id] = root
        return si
    }

    private fun box(si: SessionInformation, id: String, source: String): Box = Box(id).also {
        it.inside = si
        it.setPropertyRaw("box_kind", SHARED_KOTLIN_BOX, doResync = false)
        it.setPropertyRaw("code", source, doResync = false)
        si.boxes[id] = it
    }

    private suspend fun eval(loader: ClassLoader, source: String): String {
        val ki = KotlinInterface2(classLoader = loader, filePrefix = "document-types-test")
        return try { withTimeout(180_000) { ki.exec(source) }?.toString() ?: "<null>" } finally { ki.dispose() }
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val a = session("a")
        val b = session("b")
        val aBox = box(a, "definition-a", "data class Particle(val x: Int)")
        box(a, "definition-extra", "data class Seed(val particle: Particle)")
        box(b, "definition-b", "data class Particle(val x: Int) { val label get() = \"other\" }")
        check(a.documentTypes.publish().ok)
        check(b.documentTypes.publish().ok)
        val aLoader = checkNotNull(a.documentTypes.loader)
        val bLoader = checkNotNull(b.documentTypes.loader)
        val aType = aLoader.loadClass("Particle")
        val bType = bLoader.loadClass("Particle")
        check(aType !== bType)
        check(eval(aLoader, "Seed(Particle(2).copy(x = 7)).particle.x").contains("7"))
        check(eval(bLoader, "Particle(2).label").contains("other"))
        val old = aType.getConstructor(Int::class.javaPrimitiveType).newInstance(3)
        val callbackEngine = KotlinInterface2(classLoader = aLoader, filePrefix = "old-callback")
        val callback = (callbackEngine.exec("{ Particle(1) }") as ResultValue.Value).value
        callbackEngine.dispose()
        val before = a.documentTypes.publish()
        check(before.ok && !before.changed && a.documentTypes.loader === aLoader)
        aBox.setPropertyRaw("code", "data class Particle(val x: Int) { val doubled get() = x * 2 }", doResync = false)
        val pending = Box("initializing").also { it.inside = a; a.boxes[it.id] = it }
        val built = CountDownLatch(1)
        val finish = CountDownLatch(1)
        pending.kiBuilder = { _, _, _ ->
            KotlinInterface2(classLoader = aLoader, filePrefix = "pending-generation").also {
                built.countDown()
                check(finish.await(90, TimeUnit.SECONDS))
            }
        }
        val building = async(Dispatchers.Default) { pending.ki }
        check(withContext(Dispatchers.IO) { built.await(60, TimeUnit.SECONDS) })
        val cancelled = a.documentTypes.publish(accepted = { false })
        check(!cancelled.ok && a.documentTypes.loader === aLoader)
        check(a.documentTypes.publish().changed)
        finish.countDown()
        check(building.await().isDisposed && pending.liveKi == null)
        val newer = checkNotNull(a.documentTypes.loader)
        check(newer !== aLoader && newer.loadClass("Particle") !== aType)
        check(eval(newer, "Particle(3).doubled").contains("6"))
        val stale = runCatching { a.documentTypes.requireCurrent(old, aBox) }.exceptionOrNull()
        check(stale is IllegalStateException && stale.message.orEmpty().contains("re-run the box"))
        check(aBox.needsRerun != null)
        check(runCatching { a.documentTypes.requireCurrent(callback, aBox) }.isFailure)
        aBox.setPropertyRaw("code", "data class Particle(", doResync = false)
        val failed = a.documentTypes.publish()
        check(!failed.ok && failed.message.contains("definition-a"))
        check(a.documentTypes.loader === newer)
        check(eval(newer, "Particle(4).doubled").contains("8"))
        check(b.documentTypes.loader === bLoader)
        val bOld = bLoader.loadClass("Particle").getConstructor(Int::class.javaPrimitiveType).newInstance(1)
        val live = KotlinInterface2(classLoader = bLoader, filePrefix = "rebase-test")
        b.root.replaceKi(live)
        b.documentTypes.rebaseAfterPersonalReload()
        check(b.documentTypes.loader !== bLoader && b.root.liveKi == null && b.root.needsRerun != null)
        check(runCatching { b.documentTypes.requireCurrent(bOld, b.root) }.isFailure)
        check(eval(checkNotNull(b.documentTypes.loader), "Particle(2).label").contains("other"))
        println("Document types smoke passed")
    }
}
