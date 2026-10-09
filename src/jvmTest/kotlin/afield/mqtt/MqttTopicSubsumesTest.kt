package afield.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals

class MqttTopicSubsumesTest {
    private val filters = listOf(
        "#", "+", "+/#", "+/+", "a", "a/#", "a/+", "a/b", "a/b/c", "a/+/c", "a/b/+", "a/+/+", "+/b", "a/b/#",
        "\$SYS/#", "\$SYS/+", "\$SYS", "/", "/#", "+/", "a/", "", // "" is never passed (invalid); kept out below
    ).filter { it.isNotEmpty() }

    private val topics = listOf(
        "a", "b", "a/b", "a/c", "a/b/c", "a/x/c", "a/b/d", "a/b/c/d", "x/b", "\$SYS", "\$SYS/x", "\$SYS/x/y",
        "/", "/a", "a/", "//", "a//c", "x", "x/y/z",
    )

    /** Ground truth: subsumes(f, g) iff every probe topic g matches, f matches. The probe set is rich enough for these filters. */
    @Test
    fun matrixAgreesWithMatching() {
        for (f in filters) for (g in filters) {
            val truth = topics.all { t -> !MqttTopic.matches(g, t) || MqttTopic.matches(f, t) }
            assertEquals(truth, MqttTopic.subsumes(f, g), "subsumes($f, $g)")
        }
    }

    @Test
    fun namedCases() {
        assertEquals(true, MqttTopic.subsumes("a/#", "a/+"))
        assertEquals(true, MqttTopic.subsumes("a/+", "a/b"))
        assertEquals(true, MqttTopic.subsumes("a/#", "a"))
        assertEquals(false, MqttTopic.subsumes("a/+", "a/#"))
        assertEquals(false, MqttTopic.subsumes("a/+/c", "a/b/+"))
        assertEquals(false, MqttTopic.subsumes("a/b/+", "a/+/c"))
        assertEquals(true, MqttTopic.subsumes("#", "+/#"))
        assertEquals(true, MqttTopic.subsumes("+/#", "#"))
        assertEquals(false, MqttTopic.subsumes("#", "\$SYS/#"))
        assertEquals(false, MqttTopic.subsumes("+/#", "\$SYS/x"))
        assertEquals(true, MqttTopic.subsumes("\$SYS/#", "\$SYS/+"))
    }

    @Test
    fun cover() {
        val q0 = QoS.AT_MOST_ONCE; val q1 = QoS.AT_LEAST_ONCE
        assertEquals(mapOf("a/#" to q1), MqttTopic.cover(mapOf("a/+" to q0, "a/#" to q0, "a/b" to q1)))
        assertEquals(mapOf("a/+/c" to q0, "a/b/+" to q0), MqttTopic.cover(mapOf("a/+/c" to q0, "a/b/+" to q0)))
        assertEquals(setOf("#"), MqttTopic.cover(mapOf("#" to q0, "+/#" to q0)).keys)
        assertEquals(setOf("#", "\$SYS/#"), MqttTopic.cover(mapOf("#" to q0, "\$SYS/#" to q0, "a" to q0)).keys)
    }
}
