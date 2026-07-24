package afield.examples

import afield.graphics.RemoteArKotlinJs
import afield.remotejs.KotlinJsHeadProfiles
import afield.remotejs.KotlinToJsCompiler

/*
FIELD_TEST
id: remotejs.kotlinjs-remotear-surface
runner: jvm-main
entrypoint: afield.examples.KotlinJsRemoteArSurfaceSmokeTest
tags: [smoke, remotejs, kotlinjs]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinJsRemoteArSurfaceSmokeTest ok"
*/

/**
 * Validates the expanded RemoteAR surface (math types + `vec`/`clearAll` + the dynamic-with-typed-
 * completion `stage`) by compiling a sample that exercises it against the GENERATED compiler
 * declarations — the real check that the binding model emits valid `external` Kotlin/JS for every
 * new name and that they type-check together. Also asserts the `dynamic` (compiler) vs `Stage`
 * (completion) split so `stage.foo = x` stays permissive while `stage.add|` gets completion.
 */
object KotlinJsRemoteArSurfaceSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val compiler = RemoteArKotlinJs.declarations
        val completion = RemoteArKotlinJs.completionDeclarations

        // dynamic-with-typed-completion split for `stage`.
        require(compiler.contains("external val stage: dynamic")) { "stage must be `dynamic` for the compiler" }
        require(completion.contains("val stage: Stage")) { "stage must be typed `Stage` for completion" }
        require(completion.contains("class Stage")) { "completion mirror must declare Stage" }
        require(!compiler.contains("class Stage")) { "Stage is completion-only; must not enter the JS target" }
        require(completion.contains("operator fun get(name: String)") && completion.contains("fun add(item: Any?)")) {
            "Stage must expose indexed get + add for completion"
        }

        // math types present in BOTH renderings.
        for (t in listOf("Vector4", "Quaternion", "Matrix4", "Color")) {
            require(compiler.contains("class $t")) { "compiler missing THREE.$t" }
            require(completion.contains("class $t")) { "completion missing THREE.$t" }
        }
        require(compiler.contains("external fun vec(")) { "vec must be an installed external global" }

        // transforms / scene / creation helpers are installed globals in the compiler façade.
        for (n in listOf("rotate", "scale", "translate", "rotate3", "findByName", "getCurrentCamera",
            "addTextureTo", "addBox", "addPlane", "addImagePlane", "addVideoPlane", "addPointLight")) {
            require(compiler.contains("external fun $n(")) { "compiler missing installed global `$n`" }
        }
        require(compiler.contains("class PointLight")) { "THREE.PointLight missing" }
        require(compiler.contains("val attach: dynamic")) { "Scene.attach must be `dynamic` for the compiler" }
        require(completion.contains("val attach: AttachProxy")) { "Scene.attach must be typed for completion" }

        // interaction + XR: pointer registries on FLine (dynamic/typed split) and the XR globals.
        require(compiler.contains("val mouseDown: dynamic")) { "FLine.mouseDown must be `dynamic` for the compiler" }
        require(completion.contains("val mouseDown: PointerCallbacks")) { "FLine.mouseDown must be typed for completion" }
        for (n in listOf("onXRHit", "onXRSelect", "xrHit")) {
            require(compiler.contains("external fun $n(")) { "compiler missing XR global `$n`" }
        }
        for (t in listOf("CameraPose", "PlacedMesh", "ImagePlane", "VideoPlane", "AttachProxy", "PointerCallbacks", "XRHit")) {
            require(completion.contains("class $t")) { "completion missing result type $t" }
            require(!compiler.contains("class $t")) { "$t must be completion-only" }
        }

        // ---- item 1: FLine transform operators. The raw runtime members (__PLUS__ etc.) are
        // compiler-only; the public `operator fun FLine.plus/minus/times/div` must be in BOTH façades. ----
        for (raw in listOf("__PLUS__", "__MINUS__", "__MULTIPLY__", "__DIVIDE__")) {
            require(compiler.contains("fun $raw(")) { "compiler FLine missing raw transform member `$raw`" }
            require(!completion.contains("$raw")) { "raw transform member `$raw` must be compiler-only (kept out of completion)" }
        }
        for (op in listOf("plus", "minus", "times", "div")) {
            require(compiler.contains("fun FLine.$op(")) { "compiler missing FLine.$op operator" }
            require(completion.contains("fun FLine.$op(")) { "completion missing FLine.$op operator" }
        }
        require(compiler.contains("fun byTransforming(")) { "compiler FLine missing byTransforming" }

        // ---- item 2: loaders are installed globals; their payload types are completion-only. ----
        for (n in listOf("loadGLTF", "loadPointCloudPLY", "loadFBX", "loadTexture", "loadJSONScene", "mapDirectory")) {
            require(compiler.contains("external fun $n(")) { "compiler missing installed loader global `$n`" }
        }
        for (t in listOf("GltfAsset", "FbxAsset", "PlyAsset")) {
            require(completion.contains("class $t")) { "completion missing loader payload type $t" }
            require(!compiler.contains("class $t")) { "$t must be completion-only" }
        }

        // ---- item 3: richer THREE materials / geometries / lights present in BOTH façades. ----
        for (t in listOf("MeshStandardMaterial", "MeshLambertMaterial", "MeshPhongMaterial", "PointsMaterial",
            "LineBasicMaterial", "PlaneGeometry", "CylinderGeometry", "ConeGeometry", "TorusGeometry",
            "TorusKnotGeometry", "CircleGeometry", "RingGeometry", "DirectionalLight", "AmbientLight",
            "SpotLight", "HemisphereLight", "Group", "Points", "Line", "Texture", "TextureLoader")) {
            require(compiler.contains("class $t")) { "compiler missing THREE.$t" }
            require(completion.contains("class $t")) { "completion missing THREE.$t" }
        }
        require(compiler.contains("var visible: Boolean")) { "Object3D.visible missing" }
        require(compiler.contains("fun lookAt(")) { "Object3D.lookAt missing" }
        require(compiler.contains("fun applyMatrix4(")) { "Object3D.applyMatrix4 missing" }

        // The whole new surface must type-check against the generated compiler declarations.
        val sample = """
            // {{ remoteAR }}
            val p = vec(1.0, 2.0, 3.0)
            val q = p + vec(4.0, 5.0, 6.0)
            val v4 = THREE.Vector4(1.0, 2.0, 3.0, 4.0) * 2.0
            val col = THREE.Color(1.0, 0.5, 0.0)
            col.setRGB(0.2, 0.4, 0.6)
            val quat = THREE.Quaternion().setFromAxisAngle(vec(0.0, 1.0, 0.0), 1.57)
            val mat = THREE.Matrix4().makeTranslation(q.x, q.y, q.z)
            val flat = vec(1.0, 2.0)
            val line = FLine().moveTo(0.0, 0.0).lineTo(1.0, 1.0)
            stage.add(line)
            stage["named"] = FLine()
            stage.dynamicNamed = FLine()
            val box = addBox("crate", 0.5)
            box.mesh.position.set(1.0, 0.0, 0.0)
            val plane = addPlane("floor")
            val pic = addImagePlane("u.png", "pic")
            val movie = addVideoPlane("v.mp4", "film")
            val light = addPointLight("key")
            light.intensity = 2.0
            light.position.set(0.0, 3.0, 0.0)
            val mR = rotate(0.5)
            val mS = scale(2.0)
            val mT = translate(1.0, 2.0, 3.0)
            val mA = rotate3(vec(0.0, 1.0, 0.0), 1.57)
            val found = findByName("crate")
            found?.position?.set(0.0, 0.0, 0.0)
            val cam = getCurrentCamera()
            addTextureTo("t.png", line)
            SCENE.attach["thing"] = box.mesh
            line.pointed = true
            line.mouseDown["tap"] = { event -> log("down " + event) }
            line.dblClick["dbl"] = { _ -> log("dbl") }
            val curve = FLine().moveTo(0.0, 0.0).cubicTo(0.1, 0.2, 0.3, 0.4, 1.0, 1.0).lineTo(vec(2.0, 2.0))
            val unsub = onXRHit { hit -> log("hit " + hit) }
            onXRSelect { _ -> log("select") }
            val h = xrHit()
            unsub()

            // item 1 — FLine transform operators (Matrix4 + vector + scalar) and byTransforming.
            val base = FLine().moveTo(0.0, 0.0).lineTo(1.0, 1.0)
            val rotated = base + rotate(0.5)
            val shifted = base + vec(1.0, 0.0, 0.0)
            val shifted2 = base + vec(1.0, 0.0)
            val shrunk = base * 0.5
            val back = rotated - translate(0.0, 1.0, 0.0)
            val halved = base / 2.0
            val warped = base.byTransforming { x, y, z -> arrayOf(x * 2.0, y, z) }
            stage.add(rotated); stage.add(shifted); stage.add(shifted2); stage.add(shrunk)
            stage.add(back); stage.add(halved); stage.add(warped)

            // item 3 — richer geometries / materials / lights / Object3D props.
            val std = THREE.MeshStandardMaterial()
            std.roughness = 0.3; std.metalness = 0.8; std.color.setRGB(0.9, 0.2, 0.1)
            val torus = THREE.Mesh(THREE.TorusGeometry(1.0, 0.4, 16, 64), std)
            torus.visible = true
            torus.castShadow = true
            torus.lookAt(0.0, 1.0, 0.0)
            torus.quaternion.setFromEuler(torus.rotation)
            torus.applyMatrix4(rotate(0.25))
            torus.rotateY(0.1)
            val cyl = THREE.Mesh(THREE.CylinderGeometry(0.5, 0.5, 2.0, 24), THREE.MeshLambertMaterial())
            val group = THREE.Group()
            group.add(torus, cyl)
            group.name = "assembly"
            SCENE.add(group)
            val sun = THREE.DirectionalLight(0xffffff, 1.5)
            sun.position.set(3.0, 5.0, 2.0)
            SCENE.add(sun)
            SCENE.add(THREE.AmbientLight(0x404040, 0.6))
            val tex = loadTexture("wood.png")
            tex.needsUpdate = true
            std.map = tex
            val found2 = SCENE.getObjectByName("assembly")

            // item 2 — asset & animation loaders + the mixer-backed play/apply on the payload.
            mapDirectory("/abs/assets", "/assets")
            loadGLTF("/assets/robot.glb", "robot") { g ->
                g.play()
                g.apply(0.5)
                g.stop()
                log("gltf meshes=" + g.meshes + " anims=" + g.animations)
                g.scene.position.set(0.0, 0.0, -2.0)
            }
            loadPointCloudPLY("/assets/scan.ply", "scan") { p ->
                p.mesh.name = "cloud"
                SCENE.add(p.mesh)
            }
            loadFBX("/assets/legacy.fbx", "legacy") { f -> f.play(true); log("fbx t=" + f.currentTime()) }
            loadJSONScene("/assets/room.json", "room")

            clearAll()
            log("surface ok " + col.getHex() + " " + v4.w + " " + flat.x + " " + quat.w + " " + mat +
                " " + light.intensity + " " + mR + mS + mT + mA + " " + plane + pic + movie + " " + cam +
                " " + curve + h + " " + shifted2 + " " + shrunk + " " + back + " " + halved + " " + warped +
                " " + cyl + " " + found2 + " " + tex.flipY)
        """.trimIndent()

        val resolved = KotlinJsHeadProfiles.resolve(sample)
        require(resolved.profile === KotlinJsHeadProfiles.remoteAr) { "sample must select the remoteAR head" }
        val prepared = resolved.profile.prepareSource(resolved.source)
        val result = KotlinToJsCompiler.compile(prepared, moduleName = "remotear_surface", wrapImplicitMain = false)
        require(result.success && result.js != null) {
            "expanded RemoteAR surface did not compile:\n${result.rawLog.takeLast(2000)}"
        }
        println("expanded surface compiled: ${prepared.length} chars Kotlin -> ${result.js.length} chars JS")

        // The user's ask: `stage.add|` completes even though `stage` is `dynamic` for the compiler.
        // Skip-tolerant — needs the AA sidecar (feeds `completionDeclarations` as file-scope context).
        System.getProperty("afield.completion.sidecarUrl")
            ?: System.getenv("AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL")?.let { System.setProperty("afield.completion.sidecarUrl", it) }
        val stageDot = complete("stage.")
        if (stageDot == null) {
            println("no AA sidecar reachable; façade completion checks skipped")
        } else {
            require(stageDot.any { it.startsWith("add") } && stageDot.any { it.startsWith("remove") }) {
                "typed `stage.` completion missing add/remove: ${stageDot.take(20)}"
            }
            val threeCo = complete("THREE.Co") ?: emptyList()
            require(threeCo.any { it.startsWith("Color") }) { "THREE.Co did not complete Color: ${threeCo.take(20)}" }
            println("completion: stage. → ${stageDot.filter { it.startsWith("add") || it.startsWith("remove") }}; THREE.Co → ${threeCo.filter { it.startsWith("Color") }}")
            // FLine members (incl. the pointer registries) complete on a fresh FLine receiver.
            val flineDot = complete("FLine().") ?: emptyList()
            require(flineDot.any { it.startsWith("mouseDown") } && flineDot.any { it.startsWith("pointed") }) {
                "FLine(). completion missing mouseDown/pointed: ${flineDot.take(25)}"
            }
            val camDot = complete("getCurrentCamera().") ?: emptyList()
            val boxDot = complete("addBox(\"x\").") ?: emptyList()
            val xrDot = complete("xrHit().") ?: emptyList()
            require(camDot.any { it.startsWith("position") } && camDot.any { it.startsWith("forward") }) {
                "getCurrentCamera(). completion missing typed CameraPose members: ${camDot.take(25)}"
            }
            require(boxDot.any { it.startsWith("mesh") } && boxDot.any { it.startsWith("material") }) {
                "addBox(). completion missing typed PlacedMesh members: ${boxDot.take(25)}"
            }
            require(xrDot.any { it.startsWith("position") } && xrDot.any { it.startsWith("matrix") }) {
                "xrHit(). completion missing typed XRHit members: ${xrDot.take(25)}"
            }
            println("completion: FLine(). → ${flineDot.filter { it.startsWith("mouseDown") || it.startsWith("pointed") || it.startsWith("dblClick") }}")
            println("completion: getCurrentCamera(). → ${camDot.filter { it.startsWith("position") || it.startsWith("forward") || it.startsWith("up") }}; addBox(). → ${boxDot.filter { it.startsWith("mesh") || it.startsWith("material") }}; xrHit(). → ${xrDot.filter { it.startsWith("position") || it.startsWith("matrix") }}")
        }

        println("KotlinJsRemoteArSurfaceSmokeTest ok")
    }

    /** Complete against the generated completion mirror; null if no sidecar answered (→ skip). */
    private fun complete(snippet: String): List<String>? {
        val decl = RemoteArKotlinJs.completionDeclarations
        val roots = afield.AaCompletionClient.kotlinStdlibRoots()
        var unreachable = true
        repeat(8) {
            val o = afield.AaCompletionClient.complete(
                snippet = snippet, cursor = snippet.length, preamble = "",
                declarations = decl, implicitReceivers = emptyList(), classpathRootsOverride = roots,
                jsTarget = true,
            )
            if (o.detail.let { !it.startsWith("no-sidecar") && !it.startsWith("error") && !it.startsWith("http") }) unreachable = false
            (o.result as? afield.CompletionResult.Success)?.let { return it.metadata.map { m -> m.text } }
            Thread.sleep(400)
        }
        return if (unreachable) null else emptyList()
    }
}
