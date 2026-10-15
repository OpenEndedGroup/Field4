package afield.io.mqtt

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The subscription-cover behaviour of [MqttClient], written once and run against any broker at
 * [url]: [MqttInteropRegressionTest] uses [MqttBroker], [MqttMosquittoInteropTest] uses mosquitto
 * (which sends one copy per matching subscription, the case the cover exists for).
 */
class MqttCoverScenarios(val url: String) : AutoCloseable {
    private val owned = mutableListOf<AutoCloseable>()
    private var n = 0

    fun client(): MqttClient = MqttClient(url, clientId = "cover-${n++}-${System.nanoTime() % 100000}").also {
        owned += it; assertTrue(it.awaitConnected(), "did not connect to $url")
    }

    override fun close() = owned.asReversed().forEach { runCatching { it.close() } }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    /** A QoS 1 round trip on [c]'s own connection: every SUBSCRIBE/UNSUBSCRIBE before it has been processed. */
    private fun settle(c: MqttClient) = c.publish("zz-settle/${c.clientId}", "x", QoS.AT_LEAST_ONCE).get(5, TimeUnit.SECONDS)

    fun overlappingHandlersGetOneCopyEach() {
        val c = client(); val pub = client()
        val plus = CopyOnWriteArrayList<String>(); val hash = CopyOnWriteArrayList<String>(); val exact = CopyOnWriteArrayList<String>()
        c.subscribe("ov/+") { plus += it.text }
        c.subscribe("ov/#") { hash += it.text }
        c.subscribe("ov/b") { exact += it.text }
        settle(c)
        assertEquals(mapOf("ov/#" to QoS.AT_MOST_ONCE), c.brokerSubscriptions)
        pub.publish("ov/b", "1", QoS.AT_LEAST_ONCE).get(5, TimeUnit.SECONDS)
        pub.publish("ov/c/d", "2").get(5, TimeUnit.SECONDS)
        settle(pub); settle(c)
        assertTrue(waitFor { hash.size == 2 })
        Thread.sleep(200)
        assertEquals(listOf("1"), plus.toList())
        assertEquals(listOf("1", "2"), hash.toList())
        assertEquals(listOf("1"), exact.toList())
    }

    /** The old 250 ms dedupe swallowed these. */
    fun identicalRepeatsAllArrive() {
        val c = client(); val pub = client()
        val plus = CopyOnWriteArrayList<String>(); val hash = CopyOnWriteArrayList<String>()
        c.subscribe("rep/+") { plus += it.text }
        c.subscribe("rep/#") { hash += it.text }
        settle(c)
        repeat(10) { pub.publish("rep/x", "same").get(5, TimeUnit.SECONDS); Thread.sleep(10) }
        assertTrue(waitFor { hash.size >= 10 && plus.size >= 10 }, "plus=${plus.size} hash=${hash.size}")
        Thread.sleep(200)
        assertEquals(10, plus.size); assertEquals(10, hash.size)
    }

    fun retainedReplayToSubsumedLateSubscriber() {
        val pub = client()
        pub.publish("ret/a", "kept", QoS.AT_LEAST_ONCE, retain = true).get(5, TimeUnit.SECONDS)
        val c = client()
        val early = CopyOnWriteArrayList<MqttMessage>(); val late = CopyOnWriteArrayList<MqttMessage>()
        c.subscribe("ret/#") { early += it }
        assertTrue(waitFor { early.isNotEmpty() })
        c.subscribe("ret/+") { late += it } // subsumed: no SUBSCRIBE, so the replay comes from the cache
        assertTrue(waitFor { late.isNotEmpty() }, "no replay to the subsumed late subscriber")
        assertEquals("kept", late[0].text); assertTrue(late[0].retained)
        Thread.sleep(200)
        assertEquals(1, early.size, "the early subscriber must not get the replay again: $early")
        assertEquals(1, late.size)
        // cleared: no replay afterwards
        pub.publish("ret/a", ByteArray(0), QoS.AT_LEAST_ONCE, retain = true).get(5, TimeUnit.SECONDS)
        assertTrue(waitFor { early.size == 2 })
        val later = CopyOnWriteArrayList<MqttMessage>()
        c.subscribe("ret/a") { later += it }
        Thread.sleep(200)
        assertEquals(emptyList(), later.toList())
        pub.publish("ret/a", "", retain = true).get(5, TimeUnit.SECONDS) // leave the broker clean
    }

    /** Subscribing a cover and unsubscribing it swaps the broker set; a steady QoS 1 stream loses nothing. */
    fun coverSwapsWithoutLoss() {
        val c = client(); val pub = client()
        val got = CopyOnWriteArrayList<Int>()
        val b = c.subscribe("sw/b", QoS.AT_LEAST_ONCE) { got += it.text.toInt() }
        c.subscribe("sw/c", QoS.AT_LEAST_ONCE) {}
        settle(c)
        assertEquals(setOf("sw/b", "sw/c"), c.brokerSubscriptions.keys)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sender = Thread {
            var i = 0
            while (running.get()) { pub.publish("sw/b", "${i++}", QoS.AT_LEAST_ONCE).get(5, TimeUnit.SECONDS); Thread.sleep(2) }
            pub.publish("sw/b", "-1", QoS.AT_LEAST_ONCE).get(5, TimeUnit.SECONDS)
        }.apply { start() }
        Thread.sleep(50)
        val all = c.subscribe("sw/#", QoS.AT_LEAST_ONCE) {}
        settle(c)
        assertEquals(setOf("sw/#"), c.brokerSubscriptions.keys)
        Thread.sleep(50)
        all.close()
        settle(c)
        assertEquals(setOf("sw/b", "sw/c"), c.brokerSubscriptions.keys)
        Thread.sleep(50)
        running.set(false); sender.join(10_000)
        assertTrue(waitFor { -1 in got })
        val seen = got.filter { it >= 0 }.toSet()
        val max = seen.max()
        assertEquals((0..max).toSet(), seen, "lost during the swap: ${(0..max) - seen}")
        b.close(); settle(c)
        assertEquals(setOf("sw/c"), c.brokerSubscriptions.keys)
    }

    fun qosUpgradeResubscribesCover() {
        val c = client()
        c.subscribe("up/#") {}
        settle(c)
        c.subscribe("up/a", QoS.AT_LEAST_ONCE) {}
        settle(c)
        assertEquals(mapOf("up/#" to QoS.AT_LEAST_ONCE), c.brokerSubscriptions)
    }
}
