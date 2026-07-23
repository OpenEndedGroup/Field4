package afield.examples

import afield.Box
import afield.SavingContext
import afield.SessionInformation
import afield.remotejs.KotlinJsExecution
import afield.remotejs.KotlinJsHeadProfiles
import afield.remotejs.KotlinToJsCompiler
import afield.remotejs.isKotlinJsRoot
import afield.remotejs.kotlinJsTopologyNeedsUpdating
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/*
FIELD_TEST
id: remotejs.kotlinjs-head-profiles
runner: jvm-main
entrypoint: afield.examples.KotlinJsHeadProfilesSmokeTest
tags: [smoke, remotejs, kotlinjs, visionos]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinJsHeadProfilesSmokeTest ok"
*/

/** Marker parsing, fragment inheritance, compilation, and runtime global-name linking. */
object KotlinJsHeadProfilesSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val visionSource = """
            // {{ visionmetal uniforms=phase,amount,tint }}
            uniform("amount", 0.025)
            uniform("tint", 0.1, 0.8, 1.0, 0.35)
            onFrame { frame ->
                uniform("phase", frame.time * 3.0 + frame.deltaTime)
                log("frame " + frame.predictedDisplayTime)
            }
        """.trimIndent()
        val vision = KotlinJsHeadProfiles.resolve(visionSource)
        require(vision.profile === KotlinJsHeadProfiles.visionMetal)
        require(vision.optionList("uniforms") == listOf("phase", "amount", "tint"))
        require(vision.source.lineSequence().first().isBlank()) { "marker line must be retained as blank" }

        val remoteSource = """
            // {{ remoteAR }}
            val origin = THREE.Vector3(1.0, 2.0, 3.0)
            val moved = origin + THREE.Vector3(10.0, 20.0, 30.0)
            val mesh = THREE.Mesh(THREE.BoxGeometry(1.0, 1.0, 1.0), THREE.MeshBasicMaterial())
            mesh.position.set(moved.x, moved.y, moved.z)
            scene.add(mesh)
            stage.profileLine = FLine().moveTo(0.0, 0.0).lineTo(1.0, 1.0)
            onFrame { mesh.rotation.y += 0.25 }
            log("remoteAR installed")
        """.trimIndent()
        val remote = KotlinJsHeadProfiles.resolve(remoteSource)
        require(remote.profile === KotlinJsHeadProfiles.remoteAr)

        val toneSource = """
            // {{ tone }}
            val synth = Tone.PolySynth()
            val reverb = Tone.Reverb(2.5)
            reverb.toDestination()
            synth.connect(reverb)
            val transport = Tone.getTransport()
            transport.bpm.value = 120.0
            transport.scheduleRepeat({ time -> synth.triggerAttackRelease("C4", "8n", time) }, "4n")
            Tone.start()
            transport.start()
            log("tone-runtime " + transport.bpm.value)
        """.trimIndent()
        val tone = KotlinJsHeadProfiles.resolve(toneSource)
        require(tone.profile === KotlinJsHeadProfiles.tone)

        val box = Box("head-profile-inheritance")
        box.setPropertyRaw("code", remoteSource, doResync = false)
        val fragment = KotlinJsHeadProfiles.resolveForBox(box, "log(\"fragment\")")
        require(fragment.profile === KotlinJsHeadProfiles.remoteAr && fragment.source == "log(\"fragment\")")

        require(runCatching { KotlinJsHeadProfiles.resolve("// {{ imaginary }}\nlog(1)") }.isFailure) {
            "unknown explicit markers must not silently use the default head"
        }
        require(runCatching { KotlinJsHeadProfiles.resolve("// {{ remoteAR uniforms=phase }}\nlog(1)") }.isFailure) {
            "profile options must not be silently ignored by the wrong head"
        }
        require(KotlinJsHeadProfiles.resolve("log(\"legacy\")").profile === KotlinJsHeadProfiles.remote) {
            "unmarked source must preserve legacy RemoteJS routing"
        }

        verifyRuntimeSwitch()

        val visionJs = compile(vision.profile.prepareSource(vision.source), "profile_vision")
        val remoteJs = compile(remote.profile.prepareSource(remote.source), "profile_remote_ar")
        val toneJs = compile(tone.profile.prepareSource(tone.source), "profile_tone")

        val node = which("node")
        if (node != null) {
            runNode(node, visionHost + "\n" + visionJs + "\n" + visionProbe, "vision-metal-profile-ok")
            runNode(node, remoteArHost + "\n" + remoteJs + "\n" + remoteArProbe, "remote-ar-profile-ok")
            runNode(node, toneHost + "\n" + toneJs + "\n" + toneProbe, "tone-profile-ok")
        } else {
            println("node not on PATH; all profile compiles passed, host-link execution skipped")
        }

        println("KotlinJsHeadProfilesSmokeTest ok")
    }

    private fun verifyRuntimeSwitch() {
        val workspace = Files.createTempDirectory("afield-kotlinjs-head-profile").toFile()
        val document = File(workspace, "doc").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")
        val documentRoot = Box("document-root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = documentRoot,
            ephemeral = true,
        )
        documentRoot.inside = session
        session.boxes[documentRoot.id] = documentRoot
        thisSessionInfo.set(session)
        try {
            val languageRoot = Box("kotlinjs-root").also {
                it.inside = session
                it.isKotlinJsRoot = true
                session.boxes[it.id] = it
            }
            val cell = Box("profile-cell").also {
                it.inside = session
                session.boxes[it.id] = it
            }
            languageRoot.connect(cell)
            kotlinJsTopologyNeedsUpdating(session.boxes.values)
            require(cell.ke is KotlinJsExecution) {
                "a child of the Kotlin/JS root must execute through KotlinJsExecution, got ${cell.ke::class.simpleName}"
            }
        } finally {
            thisSessionInfo.remove()
            workspace.deleteRecursively()
        }
    }

    private fun compile(source: String, module: String): String {
        val result = KotlinToJsCompiler.compile(source, moduleName = module, wrapImplicitMain = false)
        require(result.success && result.js != null) {
            "$module did not compile:\n${result.rawLog.takeLast(1800)}"
        }
        return result.js
    }

    private fun runNode(node: String, source: String, expected: String) {
        val script = Files.createTempFile("field-kotlinjs-head-profile", ".js").toFile()
        try {
            script.writeText(source)
            val process = ProcessBuilder(node, script.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            require(process.exitValue() == 0 && output.contains(expected)) { "$expected failed:\n$output" }
            println(output)
        } finally {
            script.delete()
        }
    }

    private val visionHost = """
        const __writes = [];
        let __frame = null;
        function uniform(name, x, y = 0, z = 0, w = 0) { __writes.push([name, x, y, z, w]); }
        function onFrame(callback) { __frame = callback; }
        function print(value) { console.log(value); }
    """.trimIndent()

    private val visionProbe = """
        if (typeof __frame !== "function") throw new Error("missing vision frame callback");
        __frame(2.0, 0.25, 2.5);
        const phase = __writes.find(entry => entry[0] === "phase");
        if (!phase || Math.abs(phase[1] - 6.25) > 1e-9) throw new Error(JSON.stringify(__writes));
        console.log("vision-metal-profile-ok");
    """.trimIndent()

    private val remoteArHost = """
        class Object3D {
            constructor() { this.name = ""; this.position = new Vector3(); this.rotation = {x:0,y:0,z:0}; this.scale = new Vector3(1,1,1); this.children = []; }
            add(...objects) { this.children.push(...objects); return this; }
            remove(...objects) { this.children = this.children.filter(x => !objects.includes(x)); return this; }
        }
        class Vector3 {
            constructor(x=0,y=0,z=0) { this.x=x; this.y=y; this.z=z; }
            set(x,y,z) { this.x=x; this.y=y; this.z=z; return this; }
            clone() { return new Vector3(this.x,this.y,this.z); }
            add(o) { this.x+=o.x; this.y+=o.y; this.z+=o.z; return this; }
            sub(o) { this.x-=o.x; this.y-=o.y; this.z-=o.z; return this; }
            multiplyScalar(v) { this.x*=v; this.y*=v; this.z*=v; return this; }
            normalize() { return this; }
            length() { return Math.hypot(this.x,this.y,this.z); }
        }
        class FLine {
            constructor() { this.stroked=true; this.filled=false; }
            moveTo() { return this; }
            lineTo() { return this; }
            cubicTo() { return this; }
            uv() { return this; }
            vcolor() { return this; }
        }
        const THREE = {
            REVISION:"test", Object3D, Scene:class extends Object3D {}, Camera:class extends Object3D {},
            Vector2:Vector3, Vector3, Euler:class {}, BufferGeometry:class {}, BoxGeometry:class {},
            SphereGeometry:class {}, Material:class {}, MeshBasicMaterial:class {},
            Mesh:class extends Object3D { constructor(g,m) { super(); this.geometry=g; this.material=m; } }
        };
        const SCENE = new THREE.Scene();
        const CAMERA = new THREE.Camera();
        const stage = {};
        let __remoteFrame = null;
        function __fieldOnStart(callback) { callback(); }
        function __fieldOnStop(_callback) {}
        function __fieldOnFrame(callback) { __remoteFrame = () => callback(0, 0, 0, null); }
        function __fieldKeepAlive() {}
        function log(value) { console.log(value); }
    """.trimIndent()

    private val remoteArProbe = """
        if (SCENE.children.length !== 1) throw new Error("mesh was not attached");
        if (!stage.profileLine) throw new Error("FLine was not staged");
        if (typeof __remoteFrame !== "function") throw new Error("missing RemoteAR frame callback");
        __remoteFrame();
        if (Math.abs(SCENE.children[0].rotation.y - 0.25) > 1e-9) throw new Error("frame did not run");
        console.log("remote-ar-profile-ok");
    """.trimIndent()

    // A mock `Tone` global recording constructor/method invocations, so the compiled Kotlin -> JS is
    // proven at RUNTIME to call the bound names (not just type-check). Mirrors the shape of the real
    // Tone.js API for the slice the source exercises.
    private val toneHost = """
        const __calls = [];
        function rec(name) { __calls.push(name); }
        class Param {
            constructor(v) { this._v = v || 0; }
            get value() { return this._v; }
            set value(x) { this._v = x; }
            rampTo() { return this; } linearRampTo() { return this; } setValueAtTime() { return this; }
        }
        class Node {
            connect() { rec("connect"); return this; }
            disconnect() { return this; }
            toDestination() { rec("toDestination"); return this; }
            chain() { rec("chain"); return this; }
            dispose() { return this; } sync() { return this; } unsync() { return this; }
        }
        class Instrument extends Node {
            constructor() { super(); this.volume = new Param(0); this.frequency = new Param(0); this.detune = new Param(0); }
            triggerAttackRelease() { rec("triggerAttackRelease"); }
            triggerAttack() { rec("triggerAttack"); }
            triggerRelease() {} releaseAll() { return this; }
        }
        class Transport {
            constructor() { this.bpm = new Param(120); this.position = "0:0:0"; this.seconds = 0; this.swing = 0; this.loop = false; this.state = "stopped"; }
            start() { rec("transport.start"); this.state = "started"; return this; }
            stop() { return this; } pause() { return this; } cancel() { return this; }
            scheduleRepeat(cb) { rec("scheduleRepeat"); this._cb = cb; return 1; }
            scheduleOnce() { return 2; } clear() { return this; }
        }
        const __transport = new Transport();
        const Tone = {
            version: "15.1.22",
            PolySynth: class extends Instrument {}, Synth: class extends Instrument {}, MonoSynth: class extends Instrument {},
            Reverb: class extends Node { constructor(d) { super(); this.decay = d; this.wet = new Param(1); } },
            Oscillator: class extends Node { constructor(f, t) { super(); this.frequency = new Param(f); this.detune = new Param(0); this.type = t; } start() { return this; } stop() { return this; } },
            Filter: class extends Node { constructor(f, t) { super(); this.frequency = new Param(f); this.Q = new Param(1); this.type = t; } },
            Gain: class extends Node { constructor(g) { super(); this.gain = new Param(g); } },
            FeedbackDelay: class extends Node { constructor(d, fb) { super(); this.delayTime = new Param(0); this.feedback = new Param(fb); this.wet = new Param(1); } },
            Noise: class extends Node { constructor(t) { super(); this.type = t; } start() { return this; } stop() { return this; } },
            Loop: class { constructor() { rec("Loop"); } start() { return this; } stop() { return this; } dispose() { return this; } },
            Sequence: class { constructor() { rec("Sequence"); } start() { return this; } stop() { return this; } dispose() { return this; } },
            now() { return 0; }, immediate() { return 0; },
            start() { rec("Tone.start"); return Promise.resolve(); }, loaded() { return Promise.resolve(); },
            getTransport() { return __transport; }, getDestination() { return new Node(); }, getContext() { return {}; },
            dbToGain(db) { return Math.pow(10, db / 20); }, gainToDb(g) { return 20 * Math.log10(g); },
            ftom() { return 69; }, mtof() { return 261.6; },
            Frequency(v) { return v; }, Time(v) { return v; }, Midi(v) { return v; },
        };
        function log(value) { console.log(value); }
    """.trimIndent()

    private val toneProbe = """
        for (const expected of ["toDestination", "connect", "scheduleRepeat", "Tone.start", "transport.start"]) {
            if (!__calls.includes(expected)) throw new Error("missing " + expected + " in " + JSON.stringify(__calls));
        }
        if (__transport.bpm.value !== 120) throw new Error("bpm not set: " + __transport.bpm.value);
        if (typeof __transport._cb !== "function") throw new Error("scheduleRepeat callback not registered");
        __transport._cb(0.5);
        if (!__calls.includes("triggerAttackRelease")) throw new Error("scheduled callback did not trigger the synth");
        console.log("tone-profile-ok");
    """.trimIndent()

    private fun which(executable: String): String? =
        (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .map { File(it, executable) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
}
