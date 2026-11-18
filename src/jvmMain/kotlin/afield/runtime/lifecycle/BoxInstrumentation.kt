package afield.runtime.lifecycle

import kotlinx.serialization.Serializable

/**
 * What Field does to the classes kotlinc emits for a Kotlin box, as one three-way switch.
 *
 * ```kotlin
 * BoxInstrumentation.mode = BoxInstrumentation.Mode.INSTRUMENT   // this session only
 * BoxInstrumentation.clearSession()                              // back to the welcome-page choice
 * ```
 *
 * - [Mode.WATCHDOG] (the default): new box code is instrumented, and the [BoxInterrupt.Watchdog]
 *   interrupts box code that has held the Runloop for [BoxInterrupt.Watchdog.interruptAfterMs].
 * - [Mode.INSTRUMENT]: new box code is instrumented, so "Interrupt Running Box", the agent's RunBox
 *   timeout and every feature that reads instrumented code keep working, but the watchdog only
 *   reports stalls and never interrupts on its own. For long synchronous computations you mean to run.
 * - [Mode.OFF]: box code is compiled unchanged. Nothing can interrupt it, a stall report cannot name
 *   the box, and features that rely on instrumentation fall back to their slower paths or stay off.
 *
 * A change applies to code compiled after it: re-run a box to pick it up. Code compiled while
 * instrumentation was on keeps its instrumentation until it is recompiled.
 *
 * Three places set the mode, strongest first: the `AFIELD_BOX_INSTRUMENTATION` environment variable
 * or `-Dafield.boxInstrumentation` system property (`watchdog`, `instrument`, `off`; the older
 * `AFIELD_BOX_INTERRUPT=0|1` / `-Dafield.boxInterrupt` still count, as `off` / `watchdog`); a
 * session value assigned to [mode] from code or `POST /api/box-instrumentation` with
 * `"persist": false`; and the persisted preference (the Runtime section of `/welcome`,
 * `FieldPreferences.boxInstrumentation`). [status] says which one is in effect.
 */
object BoxInstrumentation {

    /** The three settings; [key] is the spelling used by the preference, the environment and the HTTP API. */
    enum class Mode(val key: String) {
        /** Instrument new box code and let the watchdog interrupt a box that stalls the Runloop. */
        WATCHDOG("watchdog"),

        /** Instrument new box code; the watchdog reports stalls but never interrupts. */
        INSTRUMENT("instrument"),

        /** Compile box code unchanged. */
        OFF("off");

        companion object {
            /** The mode spelled [text] (its [key], or a few obvious synonyms), or null. */
            fun parse(text: String?): Mode? = when (text?.trim()?.lowercase()) {
                "watchdog", "on", "full", "1", "true" -> WATCHDOG
                "instrument", "instrumented", "no-watchdog", "nowatchdog" -> INSTRUMENT
                "off", "none", "0", "false" -> OFF
                else -> null
            }
        }
    }

    private const val ENV = "AFIELD_BOX_INSTRUMENTATION"
    private const val PROP = "afield.boxInstrumentation"
    private const val LEGACY_ENV = "AFIELD_BOX_INTERRUPT"
    private const val LEGACY_PROP = "afield.boxInterrupt"

    /** The environment/system-property override and the name it came from, read once at startup. */
    private val override: Pair<Mode, String>? = run {
        BoxInterrupt.setting(ENV, PROP)?.let { raw -> Mode.parse(raw)?.let { return@run it to overrideName(ENV, PROP) } }
        BoxInterrupt.setting(LEGACY_ENV, LEGACY_PROP)?.let { raw ->
            val on = raw != "0" && raw != "off" && raw != "false"
            (if (on) Mode.WATCHDOG else Mode.OFF) to overrideName(LEGACY_ENV, LEGACY_PROP)
        }
    }

    private fun overrideName(env: String, prop: String) = if (System.getProperty(prop) != null) "-D$prop" else env

    @Volatile private var preferred: Mode = Mode.WATCHDOG
    @Volatile private var session: Mode? = null

    /**
     * The mode in effect. Assigning sets it for this session (until restart or [clearSession]),
     * above the persisted preference; an environment override still wins, and assigning while one
     * is set changes nothing ([status] reports the override).
     */
    var mode: Mode
        get() = override?.first ?: session ?: preferred
        set(value) { session = value }

    /** Whether newly compiled box code is rewritten. */
    val instrumenting: Boolean get() = mode != Mode.OFF

    /** Whether the watchdog may interrupt box code that has stalled the Runloop. */
    val watchdogInterrupts: Boolean get() = mode == Mode.WATCHDOG

    /** Apply the persisted preference. Called at startup and whenever preferences are saved. */
    fun applyPreference(preference: Mode) {
        preferred = preference
    }

    /** Drop a session value set through [mode], so the preference applies again. */
    fun clearSession() {
        session = null
    }

    /** The mode in effect, where it came from, and what the watchdog will do: what `/welcome` shows. */
    fun status(): BoxInstrumentationStatus {
        val o = override
        return BoxInstrumentationStatus(
            mode = mode.key,
            preference = preferred.key,
            source = when {
                o != null -> "environment"
                session != null -> "session"
                else -> "preference"
            },
            overrideName = o?.second ?: "",
            watchdogRunning = BoxInterrupt.Watchdog.isRunning(),
            watchdogReportAfterMs = BoxInterrupt.Watchdog.reportAfterMs,
            watchdogInterruptAfterMs = BoxInterrupt.Watchdog.interruptAfterMs,
            instrumentedClasses = BoxInterrupt.instrumentedClassCount()
        )
    }
}

/** [BoxInstrumentation.status], as `/welcome` and `GET /api/box-instrumentation` report it. */
@Serializable
data class BoxInstrumentationStatus(
    /** The mode in effect: `watchdog`, `instrument` or `off`. */
    val mode: String = "watchdog",
    /** The persisted preference, which [mode] differs from when [source] is not `preference`. */
    val preference: String = "watchdog",
    /** `environment`, `session` or `preference`: what set [mode]. */
    val source: String = "preference",
    /** The variable or `-D` property that overrides the preference, when [source] is `environment`. */
    val overrideName: String = "",
    /** False when `AFIELD_RUNLOOP_WATCHDOG=0` kept the watchdog from starting. */
    val watchdogRunning: Boolean = false,
    val watchdogReportAfterMs: Long = 0,
    /** 0 means the watchdog never interrupts, whatever the mode. */
    val watchdogInterruptAfterMs: Long = 0,
    /** Snippet classes rewritten so far this session. */
    val instrumentedClasses: Int = 0
)

/** A request to `POST /api/box-instrumentation`. */
@Serializable
data class BoxInstrumentationRequest(
    /** `watchdog`, `instrument` or `off`; null or blank with `persist = false` clears the session value. */
    val mode: String? = null,
    /** True (the default) saves it as the preference, as `/welcome` does; false sets it for this session only. */
    val persist: Boolean = true
)
