package afield.server

import afield.*
import afield.ai.core.BlockBoundary
import afield.ai.core.LogDispatcher
import afield.ai.core.LogEvent
import afield.ai.core.LogSink
import io.ktor.websocket.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Capture all log events (while still emitting to stdout) and expose a lightweight streaming
 * API similar to browsing.kt. Designed for an iframe widget that can request subsets of the log
 * archive and stream future entries live.
 */
object LogStream : LogSink {
    private const val MAX_RECORDS = 20_000
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // include "type" and other defaulted fields
    }
    private val seq = AtomicLong(0)
    private val records = ConcurrentLinkedDeque<Stored>()
    private val subscribers = ConcurrentHashMap<String, MutableSet<Subscription>>() // subId -> subs
    private val regexCache = ConcurrentHashMap<String, Regex>()
    private val added = AtomicBoolean(false)
    init { ensureSinkRegistered() }

    data class Stored(val seq: Long, val event: LogEvent)

    @Serializable
    data class Filter(
        val channels: List<String>? = null,            // matches if any token is contained in event.channel
        val messageContains: String? = null,           // case-insensitive substring
        val messageRegex: String? = null,              // optional regex match
        val blockId: String? = null,                   // match specific block id
        val includeBlockChildren: Boolean = false,     // stream the whole block once it matches
        val sinceEpochMs: Long? = null,                // only replay events after this timestamp
        val afterSeq: Long? = null,                    // only replay events after this seq
        val limit: Int = 500,                          // max replayed items
        val tail: Boolean = true                       // keep streaming future matches
    )

    @Serializable
    @SerialName("logs/subscribe")
    data class Subscribe(val subId: String, val filter: Filter)

    @Serializable
    @SerialName("logs/unsubscribe")
    data class Unsubscribe(val subId: String)

    @Serializable
    @SerialName("logs/ping")
    data class Ping(val subId: String? = null)

    @Serializable
    data class Ready(
        val type: String = "logs/ready",
        val subId: String,
        val replayed: Int,
        val nextSeq: Long
    )

    @Serializable
    data class LogPush(
        val type: String = "logs/event",
        val subId: String,
        val seq: Long,
        val channel: String,
        val message: String,
        val timestampEpochMs: Long,
        val timestampIso: String,
        val indent: String,
        val blockId: String? = null,
        val boundary: String? = null
    )

    @Serializable
    data class ErrorPush(
        val type: String = "logs/error",
        val subId: String? = null,
        val message: String
    )

    private data class Subscription(
        val subId: String,
        val filter: Filter,
        val send: (String) -> Unit,
        val activeBlocks: MutableSet<String> = mutableSetOf()
    )

    override fun log(event: LogEvent) {
        val stored = Stored(seq.incrementAndGet(), event)
        records.addLast(stored)
        while (records.size > MAX_RECORDS) records.pollFirst()
        pushToSubscribers(stored)
    }

    private fun pushToSubscribers(stored: Stored) {
        val boundaryStr = stored.event.boundary?.name
        subscribers.values.forEach { subs ->
            subs.forEach { sub ->
                val matches = matches(stored, sub.filter)
                val inBlock = sub.filter.includeBlockChildren && stored.event.blockId != null && sub.activeBlocks.contains(stored.event.blockId)

                if (matches) {
                    if (sub.filter.includeBlockChildren && stored.event.blockId != null) {
                        sub.activeBlocks.add(stored.event.blockId)
                    }
                    val payload = eventToJson(sub, stored)
                    sub.send(payload)
                } else if (inBlock) {
                    val payload = eventToJson(sub, stored)
                    sub.send(payload)
                }

                if (sub.filter.includeBlockChildren && stored.event.blockId != null && stored.event.boundary == BlockBoundary.END) {
                    sub.activeBlocks.remove(stored.event.blockId)
                }
            }
        }
    }

    private fun matches(stored: Stored, filter: Filter): Boolean {
        val e = stored.event

        if (filter.afterSeq != null && stored.seq <= filter.afterSeq) return false
        if (filter.sinceEpochMs != null && e.timestamp.toEpochMilli() < filter.sinceEpochMs) return false
        if (filter.blockId != null && filter.blockId != e.blockId) return false

        filter.channels?.let { wanted ->
            if (!wanted.any { token -> e.channel.contains(token, ignoreCase = true) }) return false
        }

        filter.messageContains?.let {
            if (!e.message.contains(it, ignoreCase = true)) return false
        }

        filter.messageRegex?.let { pattern ->
            val re = regexCache.computeIfAbsent(pattern) { Regex(it, RegexOption.IGNORE_CASE) }
            if (!re.containsMatchIn(e.message)) return false
        }

        return true
    }

    private fun eventToJson(sub: Subscription, stored: Stored): String {
        val e = stored.event
        val iso = DateTimeFormatter.ISO_INSTANT.format(e.timestamp)
        val dto = LogPush(
            subId = sub.subId,
            seq = stored.seq,
            channel = e.channel,
            message = e.message,
            timestampEpochMs = e.timestamp.toEpochMilli(),
            timestampIso = iso,
            indent = e.indent,
            blockId = e.blockId,
            boundary = e.boundary?.name
        )
        return json.encodeToString(LogPush.serializer(), dto)
    }

    fun addSubscription(subId: String, filter: Filter, sender: (String) -> Unit) {
        val safeSender: (String) -> Unit = { payload ->
            runCatching { sender(payload) }.onFailure { unsubscribe(subId) }
        }
        val sub = Subscription(subId, filter, safeSender)
        subscribers.getOrPut(subId) { CopyOnWriteArraySet() }.add(sub)
        replay(subId, sub)
        LogDispatcher.log(
            channel = "info,logstream",
            message = "subscription $subId active (tail=${filter.tail}, limit=${filter.limit})"
        )
    }

    private fun replay(subId: String, sub: Subscription) {
        val matched = mutableListOf<Stored>()
        val activeBlocks = mutableSetOf<String>()
        records.asSequence()
            .sortedBy { it.seq }
            .forEach { rec ->
                val hit = matches(rec, sub.filter)
                val inBlock = sub.filter.includeBlockChildren && rec.event.blockId != null && activeBlocks.contains(rec.event.blockId)
                if (hit) {
                    if (sub.filter.includeBlockChildren && rec.event.blockId != null) {
                        activeBlocks.add(rec.event.blockId)
                    }
                    matched += rec
                } else if (inBlock) {
                    matched += rec
                }
                if (sub.filter.includeBlockChildren && rec.event.blockId != null && rec.event.boundary == BlockBoundary.END) {
                    activeBlocks.remove(rec.event.blockId)
                }
            }
        val tail = matched.takeLast(sub.filter.limit.coerceAtLeast(1))
        tail.forEach { sub.send(eventToJson(sub, it)) }
        val ready = Ready(subId = subId, replayed = tail.size, nextSeq = seq.get())
        LogDispatcher.log("debug,logstream", "ready ${sub.subId} replayed=${tail.size}")
        sub.send(json.encodeToString(Ready.serializer(), ready))
        if (!sub.filter.tail) {
            // If not tailing, drop subscription after replay
            subscribers[subId]?.remove(sub)
        }
    }

    fun unsubscribe(subId: String) {
        subscribers.remove(subId)
    }

    fun ensureSinkRegistered() {
        if (added.compareAndSet(false, true)) {
            LogDispatcher.addSink(this)
        }
    }
}

@RunsAt(installHandlers)
fun installLogStreamHandler() {
    LogStream.ensureSinkRegistered()
    rawHandlers.getOrPut("__logs__") { mutableSetOf() }.add { text ->
        val session = thisSession.get()
        val payload = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return@add Handled.no
        val type = payload.jsonObject["type"]?.toString()?.trim('"') ?: return@add Handled.no
        when (type) {
            "logs/subscribe" -> {
                val msg = LogStream.json.decodeFromJsonElement<LogStream.Subscribe>(payload)
                LogDispatcher.log("info,logstream", "subscribe ${msg.subId} filter=${msg.filter}")
                inMain {
                    LogStream.addSubscription(msg.subId, msg.filter) { jsonText ->
                        inMain { session.send(jsonText) }
                    }
                }
                Handled.yes
            }
            "logs/unsubscribe" -> {
                val msg = LogStream.json.decodeFromJsonElement<LogStream.Unsubscribe>(payload)
                LogDispatcher.log("info,logstream", "unsubscribe ${msg.subId}")
                LogStream.unsubscribe(msg.subId)
                Handled.yes
            }
            "logs/ping" -> {
                val msg = LogStream.json.decodeFromJsonElement<LogStream.Ping>(payload)
                val ack = LogStream.ErrorPush(subId = msg.subId, message = "pong")
                inMain { session.send(Json.encodeToString(LogStream.ErrorPush.serializer(), ack)) }
                Handled.yes
            }
            else -> {
                LogDispatcher.log("warning,logstream", "unknown logs message type=$type")
                val err = LogStream.ErrorPush(message = "unknown logs type $type")
                inMain { session.send(Json.encodeToString(LogStream.ErrorPush.serializer(), err)) }
                Handled.no
            }
        }
    }
}
