package afield.runtime.bytecode

import afield.audio.synth.SineOsc
import afield.audio.synth.Signal
import afield.audio.synth.SynthContext
import afield.runtime.lifecycle.BoxInterrupt
import afield.runtime.lifecycle.Runloop
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Library-side targets for [ThreadOwnershipPassTest], shaped like the real owned API: a Runloop-only
 * call, an audio call that takes its context last, and the shapes whose last argument the pass must
 * refuse to `DUP` (a wide double, a wide long, a default-arguments stub).
 */

/** When set, the targets and the stand-in anchor record the order things ran in. */
var guardEvents: MutableList<String>? = null

@ThreadOwned(ThreadOwner.RUNLOOP)
fun frameOnly(x: Int): Int {
    guardEvents?.add("call")
    return x + 1
}

@ThreadOwned(ThreadOwner.AUDIO, checkLastArgument = true)
fun renderWith(buf: FloatArray, ctx: ThreadOwnedContext?): Int = buf.size

@ThreadOwned(ThreadOwner.RUNLOOP, checkLastArgument = true)
fun wideLast(a: Int, d: Double): Double = d * a

@ThreadOwned(ThreadOwner.RUNLOOP, checkLastArgument = true)
fun longLast(l: Long): Long = l + 1

@ThreadOwned(ThreadOwner.RUNLOOP, checkLastArgument = true)
fun withDefault(ctx: ThreadOwnedContext?, n: Int = 2): Int = n

/** A builder: callable from anywhere, so not annotated, so never guarded. */
fun builder(x: Int): Int = x + 1

/** A context owned by whichever thread the test says. */
class TestContext(@Volatile var owner: Thread?) : ThreadOwnedContext {
    override val ownerThread: Thread? get() = owner
}

/**
 * Stand-in for the call-site anchor pass (not in this tree): `LDC 0; INVOKESTATIC StandInAnchor.arm(I)V`
 * before every [ThreadOwned] call, chained after the guard as [BoxBytecode.passes] orders them.
 */
object StandInAnchor : BoxBytecodePass {
    override val id = "standin-anchor"
    override val inserts = setOf("afield/runtime/bytecode/StandInAnchor")
    override fun wants(origin: SnippetOrigin) = true

    @JvmStatic
    fun arm(site: Int) {
        guardEvents?.add("arm")
    }

    override fun method(cls: ClassScope, m: MethodScope, next: MethodVisitor): MethodVisitor {
        val loader = cls.origin.classLoader ?: return next
        return object : MethodVisitor(Opcodes.API_VERSION, next) {
            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                if (!cls.isInserted(owner) && CallTargets.annotation(loader, owner, name, descriptor, ThreadOwnershipPass.THREAD_OWNED) != null) {
                    super.visitLdcInsn(0)
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "afield/runtime/bytecode/StandInAnchor", "arm", "(I)V", false)
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }
    }
}

/**
 * Box code for [ThreadOwnershipPassTest]: compiled by the test compiler, rewritten and reloaded (which
 * links it, so the verifier checks every inserted `DUP` and call). Each interesting call is on a line
 * tagged with a comment the test finds by text.
 */
class GuardBoxCode {
    fun runloopCall(): Int = frameOnly(1) // runloop-call

    fun audioCall(ctx: ThreadOwnedContext?): Int = renderWith(FloatArray(4), ctx) // audio-call

    fun wide(): Double = wideLast(2, 1.5) // wide-call

    fun long(): Long = longLast(3L) // long-call

    fun defaulted(ctx: ThreadOwnedContext?): Int = withDefault(ctx) // default-call

    fun builderOnly(): Int = builder(3) // builder-call

    fun realSignal(s: Signal, ctx: SynthContext): Float {
        val b = FloatArray(8)
        s.render(b, 8, ctx) // real-signal
        return b[0]
    }

    fun guardedLoop(n: Int): Int {
        var s = 0
        for (i in 0 until n) s += frameOnly(i) // guarded-loop
        return s
    }
}

class ThreadOwnershipPassTest {

    private val path = "afield/runtime/bytecode/ThreadOwnershipPassTest.kt"
    private val source = BoxBytecodeTestSupport.testSource(path) ?: error("no test source at $path")
    private val origin = SnippetOrigin(null, "code", "'guards' (test)", source, GuardBoxCode::class.java.classLoader) { source }

    private var savedRunloop: Thread? = null
    private val parked = CountDownLatch(1)
    private val others = ArrayList<Thread>()

    /** A live thread that is not this one, to stand for the Runloop or the audio thread. */
    private fun otherThread(name: String): Thread = thread(name = name, isDaemon = true) { parked.await() }.also { others += it }

    /** Make [t] the Runloop thread, and forget the guard's cached copy of the old one. */
    private fun runloopIs(t: Thread?) {
        Runloop.mainThread = t
        ThreadOwnership.runloop = null
    }

    @BeforeTest
    fun setUp() {
        savedRunloop = Runloop.mainThread
        ThreadOwnership.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        Runloop.mainThread = savedRunloop
        guardEvents = null
        parked.countDown()
        others.forEach { it.join(1000) }
        ThreadOwnership.resetForTests()
    }

    private fun bytes(passes: List<BoxBytecodePass>): ByteArray =
        assertNotNull(BoxBytecode.rewriteClass(BoxBytecodeTestSupport.bytesOf(GuardBoxCode::class.java), origin, passes))

    private fun rewritten(passes: List<BoxBytecodePass> = listOf(ThreadOwnershipPass)): Any =
        BoxBytecodeTestSupport.load(GuardBoxCode::class.java.name, bytes(passes), GuardBoxCode::class.java.classLoader)
            .getDeclaredConstructor().newInstance()

    private fun <T> call(o: Any, name: String, vararg args: Any?): T {
        val m = o.javaClass.methods.first { it.name == name }
        try {
            @Suppress("UNCHECKED_CAST")
            return m.invoke(o, *args) as T
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun tagged(tag: String): Int {
        val i = source.split("\n").indexOfFirst { it.trimEnd().endsWith("// $tag") }
        check(i >= 0) { "no line tagged $tag" }
        return i + 1
    }

    private fun reportsFor(target: String) = ThreadOwnership.recentReports().filter { it.target.contains(target) }

    /** The instructions of [method] in [classBytes], as `DUP`, `LDC 3`, `INVOKESTATIC owner.name desc`. */
    private fun instructions(classBytes: ByteArray, method: String): List<String> {
        val out = ArrayList<String>()
        ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.API_VERSION) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (name != method) return null
                return object : MethodVisitor(Opcodes.API_VERSION) {
                    override fun visitInsn(opcode: Int) { out += if (opcode == Opcodes.DUP) "DUP" else "insn$opcode" }
                    override fun visitLdcInsn(value: Any?) { out += "LDC" }
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        out += "CALL ${owner.substringAfterLast('/')}.$name$descriptor"
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return out
    }

    private fun guardSites(method: String) = (0 until SnippetSites.size).mapNotNull { SnippetSites[it] }
        .filter { it.kind == "thread-guard" && it.className == "afield/runtime/bytecode/GuardBoxCode" && it.methodName == method }

    @Test
    fun aRunloopCallOffTheRunloopIsReportedOnceNamingItsLine() {
        val code = rewritten()
        runloopIs(Thread.currentThread())
        assertEquals(2, call<Int>(code, "runloopCall"))
        assertTrue(reportsFor("frameOnly").isEmpty(), "on the Runloop: nothing to report")

        runloopIs(otherThread("fake-runloop"))
        val before = ThreadOwnership.violations
        repeat(3) { assertEquals(2, call<Int>(code, "runloopCall")) }
        val r = reportsFor("frameOnly").single()
        assertEquals(ThreadOwner.RUNLOOP, r.owner)
        assertEquals(tagged("runloop-call"), r.box?.line, r.message)
        assertTrue(r.message.contains("'fake-runloop'"), r.message)
        assertEquals(before + 3, ThreadOwnership.violations, "every violation counts; one report")

        // No Runloop at all (a jvm-main entry): nothing owns the frame, nothing is checked.
        runloopIs(null)
        ThreadOwnership.resetForTests()
        call<Int>(code, "runloopCall")
        assertTrue(reportsFor("frameOnly").isEmpty())
    }

    @Test
    fun anAudioCallSeesItsContextThroughADupOfTheLastArgument() {
        val out = bytes(listOf(ThreadOwnershipPass))
        val ins = instructions(out, "audioCall")
        val at = ins.indexOfFirst { it.startsWith("CALL ThreadOwnershipPassTestKt.renderWith") }
        assertEquals(
            listOf("DUP", "LDC", "CALL ThreadOwnership.check(Ljava/lang/Object;I)V"),
            ins.subList(at - 3, at), ins.toString(),
        )
        val code = BoxBytecodeTestSupport.load(GuardBoxCode::class.java.name, out, GuardBoxCode::class.java.classLoader).getDeclaredConstructor().newInstance()

        val audio = otherThread("test-audio")
        assertEquals(4, call<Int>(code, "audioCall", TestContext(Thread.currentThread())))
        assertEquals(4, call<Int>(code, "audioCall", TestContext(null)))
        assertEquals(4, call<Int>(code, "audioCall", null))
        assertTrue(reportsFor("renderWith").isEmpty(), "own context, unowned context, no context: all fine")

        assertEquals(4, call<Int>(code, "audioCall", TestContext(audio)))
        val r = reportsFor("renderWith").single()
        assertEquals(ThreadOwner.AUDIO, r.owner)
        assertEquals(tagged("audio-call"), r.box?.line)
        assertEquals("'test-audio'", r.ownerThread)
        val g = guardSites("audioCall").last().detail as ThreadGuard
        assertTrue(g.lastArgument)
        assertNull(g.refused)
    }

    @Test
    fun aWideOrPrimitiveLastArgumentOrADefaultStubIsRefusedAtRewriteTime() {
        val out = bytes(listOf(ThreadOwnershipPass))
        for ((method, target) in listOf("wide" to "wideLast", "long" to "longLast", "defaulted" to "withDefault\$default")) {
            val ins = instructions(out, method)
            val at = ins.indexOfFirst { it.startsWith("CALL ThreadOwnershipPassTestKt.$target(") }
            assertTrue(at >= 2, ins.toString())
            assertEquals(listOf("LDC", "CALL ThreadOwnership.check(I)V"), ins.subList(at - 2, at), "$method: $ins")
            assertTrue("DUP" !in ins, "$method must not DUP: $ins")
        }
        assertEquals("wide", (guardSites("wide").last().detail as ThreadGuard).refused)
        assertEquals("wide", (guardSites("long").last().detail as ThreadGuard).refused)
        assertEquals("default-stub", (guardSites("defaulted").last().detail as ThreadGuard).refused)

        // It loaded and linked (the verifier ran), and the owner-only guard still guards.
        val code = BoxBytecodeTestSupport.load(GuardBoxCode::class.java.name, out, GuardBoxCode::class.java.classLoader).getDeclaredConstructor().newInstance()
        runloopIs(otherThread("fake-runloop"))
        assertEquals(3.0, call<Double>(code, "wide"))
        assertEquals(4L, call<Long>(code, "long"))
        assertEquals(2, call<Int>(code, "defaulted", null))
        assertEquals(tagged("wide-call"), reportsFor("wideLast").single().box?.line)
        assertEquals(tagged("long-call"), reportsFor("longLast").single().box?.line)
        assertEquals(tagged("default-call"), reportsFor("withDefault").single().box?.line)
    }

    @Test
    fun buildersAreNotGuarded() {
        val ins = instructions(bytes(listOf(ThreadOwnershipPass)), "builderOnly")
        assertTrue(ins.none { it.startsWith("CALL ThreadOwnership.check") }, ins.toString())
        assertTrue(guardSites("builderOnly").isEmpty())
    }

    @Test
    fun theGuardChecksBeforeTheAnchorArmsBeforeTheCallIsEntered() {
        val code = rewritten(listOf(BoxInterrupt.PollPass, ThreadOwnershipPass, StandInAnchor))
        val ins = instructions(bytes(listOf(BoxInterrupt.PollPass, ThreadOwnershipPass, StandInAnchor)), "runloopCall")
        val at = ins.indexOfFirst { it.startsWith("CALL ThreadOwnershipPassTestKt.frameOnly") }
        assertEquals(listOf("LDC", "CALL ThreadOwnership.check(I)V", "LDC", "CALL StandInAnchor.arm(I)V"), ins.subList(at - 4, at), ins.toString())

        runloopIs(otherThread("fake-runloop"))
        val events = ArrayList<String>()
        val listener: (ThreadOwnershipReport) -> Unit = { events += "check" }
        ThreadOwnership.onReport += listener
        try {
            guardEvents = events
            call<Int>(code, "runloopCall")
        } finally {
            ThreadOwnership.onReport -= listener
            guardEvents = null
        }
        assertEquals(listOf("check", "arm", "call"), events)
    }

    @Test
    fun theRealSignalRenderIsGuardedAndReportedOnceWithTheLibraryCheck() {
        val code = rewritten()
        assertTrue(guardSites("realSignal").any { (it.detail as ThreadGuard).target == "Signal.render" },
            "Signal.render carries @ThreadOwned, found through the class file of a subclass's call")
        val ctx = SynthContext(48_000f, 8)
        call<Float>(code, "realSignal", SineOsc(), ctx)
        assertTrue(reportsFor("render").isEmpty(), "an unowned (offline) context")

        val audio = otherThread("test-audio")
        ctx.claimOwnership(audio)
        try {
            call<Float>(code, "realSignal", SineOsc(), ctx)
        } finally {
            ctx.claimOwnership(null)
        }
        // The pass's check and the library's cache-miss check both saw it; the line is reported once.
        val r = reportsFor("render").single()
        assertEquals(tagged("real-signal"), r.box?.line, r.message)
        assertEquals("'test-audio'", r.ownerThread)
    }

    @Test
    fun guardOverheadOnAnOnThreadCall() {
        val code = rewritten()
        runloopIs(Thread.currentThread())
        val plain = GuardBoxCode()
        val n = 5_000_000
        fun time(block: () -> Int): Long {
            val t0 = System.nanoTime()
            block()
            return System.nanoTime() - t0
        }
        var best = Long.MAX_VALUE
        var bestPlain = Long.MAX_VALUE
        repeat(5) {
            bestPlain = minOf(bestPlain, time { plain.guardedLoop(n) })
            best = minOf(best, time { call<Int>(code, "guardedLoop", n) })
        }
        println("thread guard: ${"%.2f".format(best.toDouble() / n)} ns/call guarded, ${"%.2f".format(bestPlain.toDouble() / n)} ns/call unguarded ($n calls, best of 5)")
        assertTrue(reportsFor("frameOnly").isEmpty())
    }
}
