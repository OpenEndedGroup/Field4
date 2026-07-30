package afield.examples

import afield.Box
import afield.SavingContext
import afield.SessionInformation
import afield.graphics.VisionMetalGeometryOrigin
import afield.graphics.msl
import afield.graphics.reloadVisionMetalShader
import afield.graphics.visionMetal
import afield.graphics.visionMetalShaderSource
import afield.stdlib.actions
import afield.stdlib.fragment
import afield.remotejs.KotlinJsExecution
import afield.remotejs.KotlinJsHeadProfiles
import afield.remotejs.KotlinToJsCompiler
import afield.remotejs.isKotlinJsRoot
import afield.remotejs.kotlinJsTopologyNeedsUpdating
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import afield.util.NodeTool

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
            onStart {
                geometry {
                    color(0.2, 0.8, 1.0)
                    cube(0.18, cz = -1.2)
                }
                uniform("amount", 0.025)
                uniform("tint", 0.1, 0.8, 1.0, 0.35)
                onFrame { frame ->
                    uniform("phase", frame.time * 3.0 + frame.deltaTime)
                    log("frame " + frame.predictedDisplayTime)
                }
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
            // item 1 — FLine transform operators (Matrix4 + vector + scalar)
            val line = FLine().moveTo(0.0, 0.0).lineTo(1.0, 1.0)
            val spun = (line + rotate(0.5)) + vec(1.0, 0.0, 0.0)
            val scaled = spun * 0.5
            stage.profileLine = scaled
            // item 3 — richer THREE materials / geometries / lights / Object3D props
            val std = THREE.MeshStandardMaterial()
            std.roughness = 0.4
            std.metalness = 0.9
            val torus = THREE.Mesh(THREE.TorusGeometry(1.0, 0.4, 16, 32), std)
            torus.visible = true
            torus.castShadow = true
            torus.lookAt(0.0, 1.0, 0.0)
            torus.rotateY(0.1)
            val group = THREE.Group()
            group.add(torus)
            group.name = "rig"
            scene.add(group)
            scene.add(THREE.DirectionalLight(0xffffff, 1.5))
            val tex = loadTexture("wood.png")
            tex.needsUpdate = true
            std.map = tex
            val found = scene.getObjectByName("rig")
            log("found " + found?.name)
            // item 2 — asset loader callback + the mixer-backed play/apply on the payload
            mapDirectory("/abs/assets", "/assets")
            loadGLTF("/assets/robot.glb", "robot") { g ->
                g.play()
                g.apply(0.25)
                g.scene.position.set(0.0, 0.0, -2.0)
                log("gltf loaded scene=" + g.scene.name)
            }
            onFrame { mesh.rotation.y += 0.25 }
            log("remoteAR installed")
        """.trimIndent()
        val remote = KotlinJsHeadProfiles.resolve(remoteSource)
        require(remote.profile === KotlinJsHeadProfiles.remoteAr)

        val toneSource = """
            // {{ tone }}
            onStart {
                val synth = Tone.PolySynth()
                val reverb = Tone.Reverb(2.5)
                reverb.toDestination()
                synth.connect(reverb)
                val transport = Tone.getTransport()
                transport.bpm.value = 120.0
                val event = transport.scheduleRepeat({ time -> synth.triggerAttackRelease("C4", "8n", time) }, "4n")
                Tone.start()
                transport.start()
                log("tone-runtime " + transport.bpm.value)
                onStop {
                    transport.clear(event)
                    transport.stop()
                    synth.dispose()
                    reverb.dispose()
                }
            }
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

        verifyMslPropertyPrecedence()
        verifyReloadActionsAreInstalled()
        verifyRuntimeSwitch()

        val visionJs = compile(vision.profile.prepareSource(vision.source), "profile_vision")
        val remoteJs = compile(remote.profile.prepareSource(remote.source), "profile_remote_ar")
        val toneJs = compile(tone.profile.prepareSource(tone.source), "profile_tone")

        val node = NodeTool.resolveNode()
        if (node != null) {
            runNode(node, visionHost + "\n" + visionJs + "\n" + visionProbe, "vision-metal-profile-ok")
            runNode(node, remoteArHost + "\n" + remoteJs + "\n" + remoteArProbe, "remote-ar-profile-ok")
            runNode(node, toneHost + "\n" + toneJs + "\n" + toneProbe, "tone-profile-ok")
        } else {
            println("skipping host-link execution: ${NodeTool.unavailableMessage()}; all profile compiles passed")
        }

        println("KotlinJsHeadProfilesSmokeTest ok")
    }

    /**
     * MSL belongs in `msl`, not in the GLSL `fragment` property the proof-of-concept borrowed. The
     * fallback stays so an existing PoC box keeps rendering, but `msl` has to win when both are set.
     */
    private fun verifyMslPropertyPrecedence() {
        val box = Box("msl-property-precedence")
        require(box.visionMetalShaderSource() == null) {
            "a box with no Metal source anywhere must report none, not an empty string"
        }

        box.fragment = "// legacy proof-of-concept source"
        require(box.visionMetalShaderSource() == "// legacy proof-of-concept source") {
            "the deprecated `fragment` fallback must keep the existing PoC box running"
        }

        box.msl = "// the real thing"
        require(box.visionMetalShaderSource() == "// the real thing") {
            "`msl` must win over `fragment` once it is set"
        }

        box.msl = "   "
        require(box.visionMetalShaderSource() == "// legacy proof-of-concept source") {
            "a blank `msl` must not shadow a populated `fragment`"
        }

        require("msl" in Box.ioSeparateProperties && Box.ioSeparateProperties["msl"] == "metal") {
            "the msl property must save beside the box as .metal, got ${Box.ioSeparateProperties["msl"]}"
        }
        // ioSeparateProperties only chooses *where* a property is written. Membership of
        // ioPersistentProperties is what decides whether it is written at all — registering one and
        // not the other silently drops the shader on save, which is exactly what happened first time.
        require(Box.ioPersistentProperties.any { it.toRegex().matches("msl") }) {
            "the msl property is not persistent, so a saved document would lose its Metal source"
        }
        // uiResyncProperties is already mapped to Regex at declaration; ioPersistentProperties is not.
        require(Box.uiResyncProperties.any { it.matches("msl") }) {
            "the msl property does not resync, so editor edits would not reach the box"
        }
        println("msl property precedence: msl > fragment > resident ✓ (persistent + resyncing)")
    }

    /**
     * Publishing a program has to leave the box carrying its explicit reload commands, so that a
     * shader edit is a deliberate act rather than an implicit side effect of rerunning the Kotlin.
     */
    private fun verifyReloadActionsAreInstalled() {
        val box = Box("vision-reload-actions")
        require(box.actions.keys.none { it.startsWith("visionMetal_") }) {
            "a box with no vision program should not advertise vision reload commands"
        }

        box.msl = "// entry points go here"
        val state = box.visionMetal(shader = box.msl!!, uniforms = listOf("phase"))

        // `visionMetal_reload` is also the id BootstrapMonacoActions.kt maps the `msl` property to,
        // which is what binds alt-r in the MSL editor pane to a headset recompile.
        val expected = listOf(
            "visionMetal_reload",
            "visionMetal_reload_uniforms",
            "visionMetal_reload_program",
            "visionMetal_geometry_builtin",
        )
        expected.forEach { id ->
            require(id in box.actions) { "missing vision reload command '$id'; have ${box.actions.keys}" }
        }
        require(box.actions["visionMetal_reload"]!!.desc == "Reload MSL|alt r") {
            "the MSL reload command lost its label/chord: ${box.actions["visionMetal_reload"]!!.desc}"
        }

        require(state.declaredUniforms == listOf("phase")) {
            "declared uniform slot order did not reach the program state: ${state.declaredUniforms}"
        }
        require(state.geometryOrigin == VisionMetalGeometryOrigin.SCRIPT) {
            "with no wire vertices, geometry belongs to the box's Kotlin/JS builder, got ${state.geometryOrigin}"
        }

        // A shader-only reload must advance the program revision without disturbing the other parts.
        val before = state.currentRevision
        box.msl = "// edited"
        require(box.reloadVisionMetalShader()) { "reloading MSL should succeed once a program is resident" }
        require(state.currentRevision > before) { "a shader reload must advance the program revision" }
        require(state.shaderSource == "// edited") { "the reload published stale MSL: ${state.shaderSource}" }
        require(state.declaredUniforms == listOf("phase")) {
            "a shader reload must not clear the declared uniforms, got ${state.declaredUniforms}"
        }
        require(state.geometryOrigin == VisionMetalGeometryOrigin.SCRIPT) {
            "a shader reload must not change geometry ownership, got ${state.geometryOrigin}"
        }

        // Asking for the built-in cube is likewise a single explicit part change.
        state.useBuiltinGeometry()
        require(state.geometryOrigin == VisionMetalGeometryOrigin.BUILTIN) {
            "the built-in-cube command should hand geometry back to the head"
        }
        require(state.shaderSource == "// edited") { "changing geometry must not disturb the shader" }
        println("vision reload commands: msl / uniforms / program / built-in cube, part-wise ✓")
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
        const __meshes = [];
        let __frame = null;
        function uniform(name, x, y = 0, z = 0, w = 0) { __writes.push([name, x, y, z, w]); }
        function __fieldSetGeometry(vertices, indices) { __meshes.push({ vertices, indices }); }
        function __fieldOnStart(callback) { callback(); }
        function __fieldOnStop(_callback) {}
        function __fieldOnFrame(callback) { __frame = callback; }
        function __fieldKeepAlive() {}
        function print(value) { console.log(value); }
    """.trimIndent()

    private val visionProbe = """
        if (typeof __frame !== "function") throw new Error("missing vision frame callback");
        __frame(2.0, 0.25, 7, 2.5);
        const phase = __writes.find(entry => entry[0] === "phase");
        if (!phase || Math.abs(phase[1] - 6.25) > 1e-9) throw new Error(JSON.stringify(__writes));
        // Geometry is built in the head, not shipped over the wire: the marker'd source has to reach
        // the same bridge call the native runtime exposes.
        if (__meshes.length !== 1) throw new Error("expected one geometry upload, got " + __meshes.length);
        if (!(__meshes[0].vertices instanceof Float32Array)) throw new Error("geometry did not arrive as a Float32Array");
        if (__meshes[0].vertices.length !== 24 * 16) throw new Error("cube floats: " + __meshes[0].vertices.length);
        if (__meshes[0].indices.length !== 36) throw new Error("cube indices: " + __meshes[0].indices.length);
        console.log("vision-metal-profile-ok");
    """.trimIndent()

    private val remoteArHost = """
        const __calls = [];
        function rec(name) { __calls.push(name); }
        // A minimal Matrix4-ish transform value: `rotate`/`translate`/`scale` return one; the FLine
        // transform operators just record which raw op fired.
        function mat(kind) { return { isMatrix4: true, kind }; }
        class Object3D {
            constructor() {
                this.name = ""; this.position = new Vector3(); this.rotation = {x:0,y:0,z:0};
                this.scale = new Vector3(1,1,1); this.children = []; this.up = new Vector3(0,1,0);
                this.quaternion = {x:0,y:0,z:0,w:1}; this.visible = true; this.castShadow = false;
                this.receiveShadow = false; this.renderOrder = 0; this.userData = {};
            }
            add(...objects) { this.children.push(...objects); return this; }
            remove(...objects) { this.children = this.children.filter(x => !objects.includes(x)); return this; }
            lookAt() { rec("lookAt"); }
            applyMatrix4() { rec("applyMatrix4"); }
            rotateX() { return this; } rotateY() { rec("rotateY"); return this; } rotateZ() { return this; }
            translateX() { return this; } translateY() { return this; } translateZ() { return this; }
            updateMatrix() {} updateMatrixWorld() {}
            getObjectByName(name) { if (this.name === name) return this; for (const c of this.children) { const r = c.getObjectByName && c.getObjectByName(name); if (r) return r; } return null; }
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
        class Color { constructor(r=1,g=1,b=1){ this.r=r; this.g=g; this.b=b; } set(){return this;} setRGB(r,g,b){this.r=r;this.g=g;this.b=b;return this;} setHex(){return this;} getHex(){return 0;} clone(){return new Color(this.r,this.g,this.b);} }
        class FLine {
            constructor() { this.stroked=true; this.filled=false; }
            moveTo() { return this; }
            lineTo() { return this; }
            cubicTo() { return this; }
            uv() { return this; }
            vcolor() { return this; }
            byTransforming() { rec("byTransforming"); return this; }
            __PLUS__(v) { rec("FLine.__PLUS__"); return this; }
            __MINUS__(v) { rec("FLine.__MINUS__"); return this; }
            __MULTIPLY__(v) { rec("FLine.__MULTIPLY__"); return this; }
            __DIVIDE__(v) { rec("FLine.__DIVIDE__"); return this; }
        }
        class Material { constructor(p) { this.transparent=false; this.opacity=1; this.color=new Color(); this.map=null; } }
        const THREE = {
            REVISION:"test", Object3D, Scene:class extends Object3D {}, Camera:class extends Object3D {},
            Vector2:Vector3, Vector3, Vector4:Vector3, Euler:class {}, Quaternion:class {}, Matrix4:class {}, Color,
            BufferGeometry:class {}, BoxGeometry:class {}, SphereGeometry:class {},
            PlaneGeometry:class {}, CylinderGeometry:class {}, ConeGeometry:class {},
            TorusGeometry:class { constructor() { rec("TorusGeometry"); } }, TorusKnotGeometry:class {},
            CircleGeometry:class {}, RingGeometry:class {},
            Material, MeshBasicMaterial:class extends Material {},
            MeshStandardMaterial:class extends Material { constructor(p) { super(p); this.roughness=1; this.metalness=0; this.emissive=new Color(); rec("MeshStandardMaterial"); } },
            MeshLambertMaterial:class extends Material {}, MeshPhongMaterial:class extends Material {},
            MeshNormalMaterial:class extends Material {}, PointsMaterial:class extends Material {}, LineBasicMaterial:class extends Material {},
            PointLight:class extends Object3D {}, DirectionalLight:class extends Object3D { constructor(c,i) { super(); this.color=new Color(); this.intensity=i; rec("DirectionalLight"); } },
            AmbientLight:class extends Object3D {}, HemisphereLight:class extends Object3D {}, SpotLight:class extends Object3D {},
            Group:class extends Object3D { constructor() { super(); rec("Group"); } },
            Points:class extends Object3D { constructor(g,m) { super(); this.geometry=g; this.material=m; } },
            Line:class extends Object3D { constructor(g,m) { super(); this.geometry=g; this.material=m; } },
            Texture:class { constructor() { this.needsUpdate=false; this.flipY=true; } },
            TextureLoader:class { load(url) { return new THREE.Texture(); } },
            Mesh:class extends Object3D { constructor(g,m) { super(); this.geometry=g; this.material=m; } }
        };
        const SCENE = new THREE.Scene();
        const CAMERA = new THREE.Camera();
        const stage = {};
        function rotate() { return mat("rotate"); }
        function translate() { return mat("translate"); }
        function scale() { return mat("scale"); }
        function vec(x=0,y=0,z,w) { return z===undefined ? new Vector3(x,y,0) : new Vector3(x,y,z); }
        function loadTexture(url) { rec("loadTexture"); return new THREE.Texture(); }
        function mapDirectory(a,b) { rec("mapDirectory"); }
        function loadGLTF(url, name, callback) {
            rec("loadGLTF");
            const loaded = {
                meshes: [new THREE.Object3D(), new THREE.Object3D()],
                animations: [{}, {}],
                scene: new THREE.Object3D(),
                scenes: [], cameras: [], asset: {},
                play() { rec("gltf.play"); }, stop() { rec("gltf.stop"); }, apply(t) { rec("gltf.apply"); },
            };
            if (callback) callback(loaded);
        }
        let __remoteFrame = null;
        function __fieldOnStart(callback) { callback(); }
        function __fieldOnStop(_callback) {}
        function __fieldOnFrame(callback) { __remoteFrame = () => callback(0, 0, 0, null); }
        function __fieldKeepAlive() {}
        function log(value) { console.log(value); }
    """.trimIndent()

    private val remoteArProbe = """
        // mesh + group + directional light attached to the scene
        if (SCENE.children.length !== 3) throw new Error("expected 3 scene children, got " + SCENE.children.length);
        if (!stage.profileLine) throw new Error("FLine was not staged");
        // item 1 — the FLine transform operators fired the raw runtime members
        for (const expected of ["FLine.__PLUS__", "FLine.__MULTIPLY__"]) {
            if (!__calls.includes(expected)) throw new Error("missing " + expected + " in " + JSON.stringify(__calls));
        }
        // item 3 — richer THREE constructed + Object3D methods called
        for (const expected of ["TorusGeometry", "MeshStandardMaterial", "Group", "DirectionalLight", "lookAt", "rotateY", "loadTexture"]) {
            if (!__calls.includes(expected)) throw new Error("missing " + expected + " in " + JSON.stringify(__calls));
        }
        const rig = SCENE.getObjectByName("rig");
        if (!rig || rig.name !== "rig") throw new Error("named group not found");
        if (rig.children.length !== 1 || !rig.children[0].visible) throw new Error("torus not grouped/visible");
        // item 2 — the loader callback ran and drove the (mixer-backed) play/apply/mapDirectory
        for (const expected of ["mapDirectory", "loadGLTF", "gltf.play", "gltf.apply"]) {
            if (!__calls.includes(expected)) throw new Error("missing " + expected + " in " + JSON.stringify(__calls));
        }
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
            dispose() { rec("dispose"); return this; } sync() { return this; } unsync() { return this; }
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
            stop() { rec("transport.stop"); return this; } pause() { return this; } cancel() { return this; }
            scheduleRepeat(cb) { rec("scheduleRepeat"); this._cb = cb; return 1; }
            scheduleOnce() { return 2; } clear() { rec("transport.clear"); return this; }
        }
        const __transport = new Transport();
        let __toneStop = null;
        function __fieldOnStart(callback) { callback(); }
        function __fieldOnStop(callback) { __toneStop = callback; }
        function __fieldOnFrame(_callback) {}
        function __fieldKeepAlive() {}
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
        if (typeof __toneStop !== "function") throw new Error("onStop cleanup not registered");
        __toneStop();
        for (const expected of ["transport.clear", "transport.stop"]) {
            if (!__calls.includes(expected)) throw new Error("missing cleanup " + expected + " in " + JSON.stringify(__calls));
        }
        if (__calls.filter(it => it === "dispose").length !== 2) {
            throw new Error("expected synth and reverb disposal: " + JSON.stringify(__calls));
        }
        console.log("tone-profile-ok");
    """.trimIndent()
}
