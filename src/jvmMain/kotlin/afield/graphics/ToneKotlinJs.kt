package afield.graphics

/**
 * Typed Kotlin/JS view of the Tone.js computer-music head — the `// {{ tone }}` profile.
 *
 * Described ONCE with [jsBinding]; the compiler-facing `external` declarations and the JVM-analysis
 * completion mirror are both generated from it (see [KotlinJsBinding]) so they cannot drift, exactly
 * like [RemoteArKotlinJs]. Adding a Tone class/helper is one DSL call.
 *
 * **Grounding (the roadmap's hard rule).** Every name bound here is confirmed present in the vendored
 * bundle `extensions/remote_ar/vendor/tone/15.1.22/Tone.js`, which the RemoteAR head installs as the
 * global `Tone` (`index.html` loads it; `index_vr.html` redirects there). The 184 module exports were
 * enumerated and each top-level name below matched against them; each member/property was matched as a
 * real identifier in the bundle. The one plausible name that is NOT present is `amount` (Distortion's
 * ctor arg) — so `Distortion` takes a permissive `dynamic` options arg and exposes the real
 * `distortion` property instead. `external object Tone` maps to the global `Tone`; `Tone.Synth()`
 * compiles to `new Tone.Synth()`, `Tone.now()` to `Tone.now()` — the same mapping THREE uses.
 *
 * The surface is a genuinely useful synthesis slice: the synth family, sources, the core effects, the
 * `Param`/`Signal` value type, `Transport` scheduling, and `Loop`/`Sequence`. Audio *playback* needs a
 * user gesture in a real browser head and is not headlessly verifiable; what IS verified is that every
 * name compiles Kotlin → JS against these generated declarations (see KotlinJsToneSurfaceSmokeTest).
 */
object ToneKotlinJs {
    const val surfaceId: String = "tone"

    private val binding = jsBinding {
        obj("Tone") {
            // `Tone.version` — the vendored bundle's version string (grounded: `version` export).
            valProp("version", "String")

            // Marker base so `connect`/`chain` params accept any node. Open + empty: subclasses may
            // declare their own `toDestination()`/`connect()` returning their own type with no override.
            cls("ToneAudioNode") { open() }

            // A Param/Signal value (`.value`, ramps). Used for volume/frequency/gain/bpm/wet/etc.
            cls("Param") {
                varProp("value", "Double", completionInit = "0.0")
                fn("rampTo", "Param") { param("value", "Double"); param("rampTime", "Double", "0.1"); param("startTime", "Double", "0.0") }
                fn("linearRampTo", "Param") { param("value", "Double"); param("rampTime", "Double", "0.1"); param("startTime", "Double", "0.0") }
                fn("setValueAtTime", "Param") { param("value", "Double"); param("time", "Double") }
            }

            // ---- synth family (extend Instrument-shaped: audio node + note triggers + volume) ----
            instrument("Synth") { frequencyLike() }
            instrument("PolySynth") { polyTriggers("PolySynth") }
            instrument("MonoSynth") { frequencyLike() }
            instrument("AMSynth") { frequencyLike() }
            instrument("FMSynth") { frequencyLike() }
            instrument("DuoSynth") { frequencyLike() }
            instrument("MembraneSynth") { frequencyLike() }
            instrument("MetalSynth") {}
            instrument("PluckSynth") {}
            instrument("NoiseSynth") {}

            // ---- sources (audio node + start/stop) ----
            source("Oscillator") {
                ctor { param("frequency", JsType.DYNAMIC, "null"); param("type", "String", "\"sine\"") }
                varProp("type", "String", completionInit = "\"sine\"")
                valProp("frequency", "Param")
                valProp("detune", "Param")
            }
            source("Noise") {
                ctor { param("type", "String", "\"white\"") }
                varProp("type", "String", completionInit = "\"white\"")
            }
            source("Player") {
                ctor { param("url", JsType.DYNAMIC, "null") }
                varProp("autostart", "Boolean", completionInit = "false")
                varProp("loop", "Boolean", completionInit = "false")
                varProp("playbackRate", "Double", completionInit = "1.0")
            }

            // ---- effects (audio node; wet + effect-specific params) ----
            effect("Gain") {
                ctor { param("gain", "Double", "1.0") }
                valProp("gain", "Param")
            }
            effect("Volume") {
                ctor { param("volume", "Double", "0.0") }
                valProp("volume", "Param")
                varProp("mute", "Boolean", completionInit = "false")
            }
            effect("Filter") {
                ctor { param("frequency", JsType.DYNAMIC, "null"); param("type", "String", "\"lowpass\"") }
                varProp("type", "String", completionInit = "\"lowpass\"")
                valProp("frequency", "Param")
                valProp("Q", "Param")
            }
            effect("Reverb") {
                ctor { param("decay", "Double", "1.5") }
                varProp("decay", "Double", completionInit = "1.5")
                valProp("wet", "Param")
            }
            effect("FeedbackDelay") {
                ctor { param("delayTime", JsType.DYNAMIC, "null"); param("feedback", "Double", "0.5") }
                valProp("delayTime", "Param")
                valProp("feedback", "Param")
                valProp("wet", "Param")
            }
            effect("PingPongDelay") {
                ctor { param("delayTime", JsType.DYNAMIC, "null"); param("feedback", "Double", "0.5") }
                valProp("delayTime", "Param")
                valProp("feedback", "Param")
                valProp("wet", "Param")
            }
            effect("Distortion") {
                // Bundle has no `amount` identifier — take permissive options, expose the real `distortion`.
                ctor { param("options", JsType.DYNAMIC, "null") }
                varProp("distortion", "Double", completionInit = "0.4")
                valProp("wet", "Param")
            }
            effect("Chorus") {
                ctor { param("frequency", JsType.DYNAMIC, "null"); param("delayTime", "Double", "3.5"); param("depth", "Double", "0.7") }
                varProp("depth", "Double", completionInit = "0.7")
                valProp("wet", "Param")
            }

            // ---- global master output (getDestination()) ----
            cls("Destination") {
                extends("ToneAudioNode")
                valProp("volume", "Param")
                varProp("mute", "Boolean", completionInit = "false")
            }

            // ---- Transport: the global timeline (getTransport()) ----
            cls("Transport") {
                valProp("bpm", "Param")
                varProp("position", JsType("dynamic", "Any?"), completionInit = "\"0:0:0\"")
                varProp("seconds", "Double", completionInit = "0.0")
                varProp("swing", "Double", completionInit = "0.0")
                varProp("loop", "Boolean", completionInit = "false")
                valProp("state", "String")
                fn("start", "Transport") { param("time", "Double", "0.0"); param("offset", "Double", "0.0") }
                fn("stop", "Transport") { param("time", "Double", "0.0") }
                fn("pause", "Transport") { param("time", "Double", "0.0") }
                fn("cancel", "Transport") { param("after", "Double", "0.0") }
                // scheduleRepeat/scheduleOnce return a numeric event id; `clear(id)` cancels it.
                fn("scheduleRepeat", "Double") {
                    param("callback", timeCallback); param("interval", "String"); param("startTime", "Double", "0.0")
                }
                fn("scheduleOnce", "Double") { param("callback", timeCallback); param("time", "String") }
                fn("clear", "Transport") { param("id", "Double") }
            }

            // ---- scheduling primitives ----
            cls("Loop") {
                extends("ToneAudioNode")
                ctor { param("callback", timeCallback); param("interval", JsType("dynamic", "Any?"), "null") }
                varProp("mute", "Boolean", completionInit = "false")
                varProp("iterations", "Double", completionInit = "0.0")
                varProp("playbackRate", "Double", completionInit = "1.0")
                fn("start", "Loop") { param("time", "Double", "0.0") }
                fn("stop", "Loop") { param("time", "Double", "0.0") }
                fn("dispose", "Loop")
            }
            cls("Sequence") {
                extends("ToneAudioNode")
                ctor {
                    param("callback", sequenceCallback)
                    param("events", "Array<Any?>", "null")
                    param("subdivision", "String", "\"8n\"")
                }
                varProp("mute", "Boolean", completionInit = "false")
                varProp("loop", "Boolean", completionInit = "true")
                varProp("playbackRate", "Double", completionInit = "1.0")
                fn("start", "Sequence") { param("time", "Double", "0.0") }
                fn("stop", "Sequence") { param("time", "Double", "0.0") }
                fn("dispose", "Sequence")
            }
            cls("Part") {
                extends("ToneAudioNode")
                ctor { param("callback", sequenceCallback); param("events", "Array<Any?>", "null") }
                varProp("loop", "Boolean", completionInit = "false")
                fn("start", "Part") { param("time", "Double", "0.0") }
                fn("stop", "Part") { param("time", "Double", "0.0") }
                fn("dispose", "Part")
            }

            // ---- top-level Tone.* helpers (all confirmed exports) ----
            fn("now", "Double")
            fn("immediate", "Double")
            fn("start", JsType.DYNAMIC)                    // resumes the AudioContext; returns a Promise
            fn("loaded", JsType.DYNAMIC)                   // resolves when all buffers are loaded
            fn("getTransport", "Transport")
            fn("getDestination", "Destination")
            fn("getContext", JsType.DYNAMIC)
            fn("dbToGain", "Double") { param("db", "Double") }
            fn("gainToDb", "Double") { param("gain", "Double") }
            fn("ftom", "Double") { param("frequency", "Double") }
            fn("mtof", "Double") { param("midi", "Double") }
            fn("Frequency", JsType.DYNAMIC) { param("value", JsType.DYNAMIC) }
            fn("Time", JsType.DYNAMIC) { param("value", JsType.DYNAMIC) }
            fn("Midi", JsType.DYNAMIC) { param("value", JsType.DYNAMIC) }
        }

        // Field-native convenience: `log(value)` (mirrors the RemoteAR head's log helper).
        function("log", ret = JsType.of("Unit"), compilerBody = "__toneLog(value)") {
            param("value", "Any?")
        }

        rawCompiler(
            """
            @kotlin.js.JsName("log")
            private external fun __toneLog(value: Any?)
            """.trimIndent(),
        )
    }

    /** Compiler-facing `external` declarations, generated from [binding]. */
    val declarations: String = binding.compilerDeclarations

    /** JVM-analysis-safe model used by the out-of-process completion sidecar, generated from [binding]. */
    val completionDeclarations: String = binding.completionDeclarations
}

// --- callback param types (compiler `dynamic` ↔ completion `Any?` where a value is head-shaped) ---

private val timeCallback = JsType("(time: Double) -> Unit", "(time: Double) -> Unit")
private val sequenceCallback = JsType("(time: Double, value: dynamic) -> Unit", "(time: Double, value: Any?) -> Unit")

// --- DSL helpers: shared shapes so the two façades never disagree and the surface stays terse ---

/** The `ToneAudioNode` chaining methods, each returning [self] so `x.connect(y).toDestination()` works. */
private fun TypeBuilder.audioNode(self: String) {
    extends("ToneAudioNode")
    fn("connect", self) { param("destination", "ToneAudioNode") }
    fn("disconnect", self)
    fn("toDestination", self)
    fn("chain", self) { vararg("nodes", "ToneAudioNode") }
    fn("dispose", self)
    fn("sync", self)
    fn("unsync", self)
}

/** Monophonic note triggers (single note per call). */
private fun TypeBuilder.monoTriggers() {
    fn("triggerAttackRelease", "Unit") { param("note", "String"); param("duration", "String") }
    fn("triggerAttackRelease", "Unit") { param("note", "String"); param("duration", "String"); param("time", "Double") }
    fn("triggerAttackRelease", "Unit") { param("note", "String"); param("duration", "String"); param("time", "Double"); param("velocity", "Double") }
    fn("triggerAttack", "Unit") { param("note", "String") }
    fn("triggerAttack", "Unit") { param("note", "String"); param("time", "Double") }
    fn("triggerAttack", "Unit") { param("note", "String"); param("time", "Double"); param("velocity", "Double") }
    fn("triggerRelease", "Unit")
    fn("triggerRelease", "Unit") { param("time", "Double") }
}

/** Polyphonic note triggers — note arrays + `releaseAll` — on top of the mono forms. */
private fun TypeBuilder.polyTriggers(self: String) {
    monoTriggers()
    fn("triggerAttackRelease", "Unit") { param("notes", "Array<String>"); param("duration", "String") }
    fn("triggerAttackRelease", "Unit") { param("notes", "Array<String>"); param("duration", "String"); param("time", "Double") }
    fn("triggerAttack", "Unit") { param("notes", "Array<String>") }
    fn("triggerAttack", "Unit") { param("notes", "Array<String>"); param("time", "Double") }
    fn("releaseAll", self)
}

/** Common `frequency`/`detune` signals on the tonal synths. */
private fun TypeBuilder.frequencyLike() {
    valProp("frequency", "Param")
    valProp("detune", "Param")
}

/** An instrument: audio node + a default no-arg-or-options ctor + note triggers + `volume`.
 *  Called inside `obj("Tone") { … }`, so the enclosing receiver is the Tone [TypeBuilder] and
 *  [TypeBuilder.cls] nests the class inside the `Tone` object. */
private fun TypeBuilder.instrument(name: String, extra: TypeBuilder.() -> Unit) {
    cls(name) {
        audioNode(name)
        ctor { param("options", JsType.DYNAMIC, "null") }
        valProp("volume", "Param")
        if (name == "PolySynth") extra() else { monoTriggers(); extra() }
    }
}

/** A source: audio node + `start`/`stop`. Caller supplies the ctor + source-specific props. */
private fun TypeBuilder.source(name: String, extra: TypeBuilder.() -> Unit) {
    cls(name) {
        audioNode(name)
        fn("start", name) { param("time", "Double", "0.0") }
        fn("stop", name) { param("time", "Double", "0.0") }
        extra()
    }
}

/** An effect: audio node + `wet`-style params. Caller supplies the ctor + effect-specific props. */
private fun TypeBuilder.effect(name: String, extra: TypeBuilder.() -> Unit) {
    cls(name) {
        audioNode(name)
        extra()
    }
}
