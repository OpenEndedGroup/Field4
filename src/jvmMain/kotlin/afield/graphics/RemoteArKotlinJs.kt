package afield.graphics

/**
 * Typed Kotlin/JS view of the stable, useful core of the browser RemoteAR head.
 *
 * The surface is described ONCE via [jsBinding]; the compiler-facing `external` declarations and the
 * JVM-analysis-safe completion mirror are both generated from it (see [KotlinJsBinding]), so they
 * cannot drift (no more `var` vs `val` mismatches or completion offering members compilation rejects).
 * Add THREE classes / helpers here without touching marker parsing, routing, or compilation.
 *
 * Deliberately smaller than the complete THREE universe — these are the globals Field actually
 * installs in `extensions/remote_ar` (scene objects, vectors, FLine construction, the stage proxy,
 * and the animation task loop). The richer real head (transforms, scene search, object-creation
 * helpers, XR) is bound incrementally on top of this spine.
 */
object RemoteArKotlinJs {
    const val surfaceId: String = "remoteAR"

    private val binding = jsBinding {
        obj("THREE") {
            valProp("REVISION", "String")

            cls("Object3D") {
                open()
                varProp("name", "String")
                valProp("position", "Vector3")
                valProp("rotation", "Euler")
                valProp("scale", "Vector3")
                valProp("children", "Array<Object3D>")
                // ---- richer standard Object3D surface (three.js Object3D) ----
                valProp("up", "Vector3")
                valProp("quaternion", "Quaternion")
                valProp("matrix", "Matrix4")
                varProp("visible", "Boolean", completionInit = "true")
                varProp("castShadow", "Boolean", completionInit = "false")
                varProp("receiveShadow", "Boolean", completionInit = "false")
                varProp("renderOrder", "Double", completionInit = "0.0")
                varProp("frustumCulled", "Boolean", completionInit = "true")
                varProp("userData", JsType.DYNAMIC)
                varProp("parent", JsType("dynamic", "Object3D?"))
                fn("add", "Object3D") { vararg("objects", "Object3D") }
                fn("remove", "Object3D") { vararg("objects", "Object3D") }
                fn("lookAt", "Unit") { param("x", "Double"); param("y", "Double"); param("z", "Double") }
                fn("lookAt", "Unit") { param("target", "Vector3") }
                fn("applyMatrix4", "Unit") { param("matrix", "Matrix4") }
                fn("applyQuaternion", "Object3D") { param("q", "Quaternion") }
                fn("translateX", "Object3D") { param("distance", "Double") }
                fn("translateY", "Object3D") { param("distance", "Double") }
                fn("translateZ", "Object3D") { param("distance", "Double") }
                fn("rotateX", "Object3D") { param("angle", "Double") }
                fn("rotateY", "Object3D") { param("angle", "Double") }
                fn("rotateZ", "Object3D") { param("angle", "Double") }
                fn("rotateOnAxis", "Object3D") { param("axis", "Vector3"); param("angle", "Double") }
                fn("getObjectByName", "Object3D?") { param("name", "String") }
                fn("traverse", "Unit") { param("callback", JsType("(o: dynamic) -> Unit", "(o: Object3D) -> Unit")) }
                fn("updateMatrix", "Unit")
                fn("updateMatrixWorld", "Unit") { param("force", "Boolean", "false") }
                fn("clone", "Object3D") { param("recursive", "Boolean", "true") }
            }

            cls("Scene") {
                extends("Object3D")
                // Field replaces three.js's `attach(object)` method with a named-index proxy
                // (graphics.js): `SCENE.attach[name] = obj`. dynamic for the compiler, indexer for completion.
                valProp("attach", JsType("dynamic", "AttachProxy"))
            }
            cls("Camera") { open(); extends("Object3D") }

            cls("Vector2") {
                field("x", "Double", "0.0")
                field("y", "Double", "0.0")
                fn("set", "Vector2") { param("x", "Double"); param("y", "Double") }
                fn("clone", "Vector2")
                fn("add", "Vector2") { param("other", "Vector2") }
                fn("sub", "Vector2") { param("other", "Vector2") }
                fn("multiplyScalar", "Vector2") { param("value", "Double") }
            }

            cls("Vector3") {
                field("x", "Double", "0.0")
                field("y", "Double", "0.0")
                field("z", "Double", "0.0")
                fn("set", "Vector3") { param("x", "Double"); param("y", "Double"); param("z", "Double") }
                fn("clone", "Vector3")
                fn("add", "Vector3") { param("other", "Vector3") }
                fn("sub", "Vector3") { param("other", "Vector3") }
                fn("multiplyScalar", "Vector3") { param("value", "Double") }
                fn("normalize", "Vector3")
                fn("length", "Double")
            }

            cls("Euler") {
                field("x", "Double", "0.0")
                field("y", "Double", "0.0")
                field("z", "Double", "0.0")
                field("order", "String", "\"XYZ\"")
                fn("set", "Euler") {
                    param("x", "Double"); param("y", "Double"); param("z", "Double")
                    param("order", "String", "\"XYZ\"")
                }
            }

            cls("BufferGeometry") { open() }
            cls("BoxGeometry") {
                extends("BufferGeometry")
                ctor { param("width", "Double"); param("height", "Double"); param("depth", "Double") }
            }
            cls("SphereGeometry") {
                extends("BufferGeometry")
                ctor { param("radius", "Double"); param("widthSegments", "Int", "32"); param("heightSegments", "Int", "16") }
            }
            // ---- richer geometries (super-three r0.184.0; all re-exported on the THREE global) ----
            cls("PlaneGeometry") {
                extends("BufferGeometry")
                ctor {
                    param("width", "Double", "1.0"); param("height", "Double", "1.0")
                    param("widthSegments", "Int", "1"); param("heightSegments", "Int", "1")
                }
            }
            cls("CylinderGeometry") {
                extends("BufferGeometry")
                ctor {
                    param("radiusTop", "Double", "1.0"); param("radiusBottom", "Double", "1.0")
                    param("height", "Double", "1.0"); param("radialSegments", "Int", "32")
                }
            }
            cls("ConeGeometry") {
                extends("BufferGeometry")
                ctor { param("radius", "Double", "1.0"); param("height", "Double", "1.0"); param("radialSegments", "Int", "32") }
            }
            cls("TorusGeometry") {
                extends("BufferGeometry")
                ctor {
                    param("radius", "Double", "1.0"); param("tube", "Double", "0.4")
                    param("radialSegments", "Int", "12"); param("tubularSegments", "Int", "48")
                }
            }
            cls("TorusKnotGeometry") {
                extends("BufferGeometry")
                ctor {
                    param("radius", "Double", "1.0"); param("tube", "Double", "0.4")
                    param("tubularSegments", "Int", "64"); param("radialSegments", "Int", "8")
                }
            }
            cls("CircleGeometry") {
                extends("BufferGeometry")
                ctor { param("radius", "Double", "1.0"); param("segments", "Int", "32") }
            }
            cls("RingGeometry") {
                extends("BufferGeometry")
                ctor { param("innerRadius", "Double", "0.5"); param("outerRadius", "Double", "1.0"); param("thetaSegments", "Int", "32") }
            }

            cls("Material") {
                open()
                varProp("transparent", "Boolean", completionInit = "false")
                varProp("opacity", "Double", completionInit = "1.0")
                varProp("visible", "Boolean", completionInit = "true")
                varProp("side", "Int", completionInit = "0")
                varProp("depthTest", "Boolean", completionInit = "true")
                varProp("depthWrite", "Boolean", completionInit = "true")
                varProp("needsUpdate", "Boolean", completionInit = "false")
            }

            cls("MeshBasicMaterial") {
                extends("Material")
                ctor { param("parameters", JsType.DYNAMIC, "null") }
                valProp("color", "Color")
                varProp("map", JsType("dynamic", "Texture?"))
                varProp("wireframe", "Boolean", completionInit = "false")
            }
            // ---- lit materials (three.js; used by addPlane/addBox/loaders — all on the THREE global) ----
            cls("MeshStandardMaterial") {
                extends("Material")
                ctor { param("parameters", JsType.DYNAMIC, "null") }
                valProp("color", "Color")
                valProp("emissive", "Color")
                varProp("roughness", "Double", completionInit = "1.0")
                varProp("metalness", "Double", completionInit = "0.0")
                varProp("wireframe", "Boolean", completionInit = "false")
                varProp("map", JsType("dynamic", "Texture?"))
            }
            cls("MeshLambertMaterial") {
                extends("Material")
                ctor { param("parameters", JsType.DYNAMIC, "null") }
                valProp("color", "Color")
                valProp("emissive", "Color")
                varProp("wireframe", "Boolean", completionInit = "false")
                varProp("map", JsType("dynamic", "Texture?"))
            }
            cls("MeshPhongMaterial") {
                extends("Material")
                ctor { param("parameters", JsType.DYNAMIC, "null") }
                valProp("color", "Color")
                valProp("emissive", "Color")
                valProp("specular", "Color")
                varProp("shininess", "Double", completionInit = "30.0")
                varProp("wireframe", "Boolean", completionInit = "false")
                varProp("map", JsType("dynamic", "Texture?"))
            }
            cls("MeshNormalMaterial") {
                extends("Material")
                ctor { param("parameters", JsType.DYNAMIC, "null") }
                varProp("wireframe", "Boolean", completionInit = "false")
            }
            cls("PointsMaterial") {
                extends("Material")
                ctor { param("parameters", JsType.DYNAMIC, "null") }
                valProp("color", "Color")
                varProp("size", "Double", completionInit = "1.0")
                varProp("sizeAttenuation", "Boolean", completionInit = "true")
                varProp("vertexColors", "Boolean", completionInit = "false")
                varProp("map", JsType("dynamic", "Texture?"))
            }
            cls("LineBasicMaterial") {
                extends("Material")
                ctor { param("parameters", JsType.DYNAMIC, "null") }
                valProp("color", "Color")
                varProp("linewidth", "Double", completionInit = "1.0")
            }
            cls("Mesh") {
                extends("Object3D")
                // ctor args that are also mutable properties → present + mutable in BOTH renderings.
                field("geometry", "BufferGeometry")
                field("material", "Material")
            }

            // ---- core THREE math types (standard three.js) ----
            cls("Vector4") {
                field("x", "Double", "0.0"); field("y", "Double", "0.0")
                field("z", "Double", "0.0"); field("w", "Double", "0.0")
                fn("set", "Vector4") { param("x", "Double"); param("y", "Double"); param("z", "Double"); param("w", "Double") }
                fn("clone", "Vector4")
                fn("add", "Vector4") { param("other", "Vector4") }
                fn("sub", "Vector4") { param("other", "Vector4") }
                fn("multiplyScalar", "Vector4") { param("value", "Double") }
                fn("length", "Double")
            }

            cls("Quaternion") {
                field("x", "Double", "0.0"); field("y", "Double", "0.0")
                field("z", "Double", "0.0"); field("w", "Double", "1.0")
                fn("set", "Quaternion") { param("x", "Double"); param("y", "Double"); param("z", "Double"); param("w", "Double") }
                fn("setFromEuler", "Quaternion") { param("euler", "Euler") }
                fn("setFromAxisAngle", "Quaternion") { param("axis", "Vector3"); param("angle", "Double") }
                fn("multiply", "Quaternion") { param("other", "Quaternion") }
                fn("normalize", "Quaternion")
                fn("clone", "Quaternion")
            }

            cls("Matrix4") {
                fn("identity", "Matrix4")
                fn("clone", "Matrix4")
                fn("multiply", "Matrix4") { param("other", "Matrix4") }
                fn("premultiply", "Matrix4") { param("other", "Matrix4") }
                fn("makeTranslation", "Matrix4") { param("x", "Double"); param("y", "Double"); param("z", "Double") }
                fn("makeScale", "Matrix4") { param("x", "Double"); param("y", "Double"); param("z", "Double") }
                fn("makeRotationFromQuaternion", "Matrix4") { param("q", "Quaternion") }
            }

            cls("Color") {
                // `new THREE.Color(r, g, b)` — components are 0..1 and mutable.
                field("r", "Double", "1.0"); field("g", "Double", "1.0"); field("b", "Double", "1.0")
                fn("set", "Color") { param("value", "Int") }
                fn("setRGB", "Color") { param("r", "Double"); param("g", "Double"); param("b", "Double") }
                fn("setHex", "Color") { param("hex", "Int") }
                fn("getHex", "Int")
                fn("clone", "Color")
            }

            cls("PointLight") {
                extends("Object3D")
                ctor { param("color", "Int", "0xffffff"); param("intensity", "Double", "1.0"); param("distance", "Double", "0.0") }
                valProp("color", "Color")
                varProp("intensity", "Double")
                varProp("distance", "Double")
            }
            // ---- more lights (three.js; on the THREE global) ----
            cls("DirectionalLight") {
                extends("Object3D")
                ctor { param("color", "Int", "0xffffff"); param("intensity", "Double", "1.0") }
                valProp("color", "Color")
                varProp("intensity", "Double")
                valProp("target", "Object3D")
            }
            cls("AmbientLight") {
                extends("Object3D")
                ctor { param("color", "Int", "0xffffff"); param("intensity", "Double", "1.0") }
                valProp("color", "Color")
                varProp("intensity", "Double")
            }
            cls("HemisphereLight") {
                extends("Object3D")
                ctor { param("skyColor", "Int", "0xffffff"); param("groundColor", "Int", "0xffffff"); param("intensity", "Double", "1.0") }
                valProp("color", "Color")
                varProp("intensity", "Double")
            }
            cls("SpotLight") {
                extends("Object3D")
                ctor {
                    param("color", "Int", "0xffffff"); param("intensity", "Double", "1.0")
                    param("distance", "Double", "0.0"); param("angle", "Double", "1.047")
                    param("penumbra", "Double", "0.0")
                }
                valProp("color", "Color")
                varProp("intensity", "Double")
                varProp("distance", "Double")
                varProp("angle", "Double")
                varProp("penumbra", "Double")
                valProp("target", "Object3D")
            }

            // ---- scene-graph containers + renderable object types ----
            cls("Group") { extends("Object3D") }
            cls("Points") {
                extends("Object3D")
                field("geometry", "BufferGeometry")
                field("material", "Material")
            }
            cls("Line") {
                extends("Object3D")
                field("geometry", "BufferGeometry")
                field("material", "Material")
            }

            // ---- textures (loadTexture / addImagePlane return these) ----
            cls("Texture") {
                open()
                varProp("needsUpdate", "Boolean", completionInit = "false")
                varProp("wrapS", "Int", completionInit = "1000")
                varProp("wrapT", "Int", completionInit = "1000")
                valProp("repeat", "Vector2")
                valProp("offset", "Vector2")
                varProp("flipY", "Boolean", completionInit = "true")
            }
            cls("TextureLoader") {
                fn("load", "Texture") { param("url", "String") }
            }
        }

        cls("FLine") {
            varProp("stroked", "Boolean", completionInit = "true")
            varProp("filled", "Boolean", completionInit = "false")
            varProp("pointed", "Boolean", completionInit = "false")
            fn("moveTo", "FLine") { param("x", "Double"); param("y", "Double"); param("z", "Double", "0.0") }
            fn("moveTo", "FLine") { param("point", "THREE.Vector3") }
            fn("moveTo", "FLine") { param("point", "THREE.Vector2") }
            fn("lineTo", "FLine") { param("x", "Double"); param("y", "Double"); param("z", "Double", "0.0") }
            fn("lineTo", "FLine") { param("point", "THREE.Vector3") }
            fn("lineTo", "FLine") { param("point", "THREE.Vector2") }
            // cubicTo: 3 control vectors, 6 numbers (2D), or 9 numbers (3D).
            fn("cubicTo", "FLine") { param("a", "THREE.Vector3"); param("b", "THREE.Vector3"); param("c", "THREE.Vector3") }
            fn("cubicTo", "FLine") {
                param("c1x", "Double"); param("c1y", "Double"); param("c2x", "Double"); param("c2y", "Double")
                param("x", "Double"); param("y", "Double")
            }
            fn("cubicTo", "FLine") {
                param("c1x", "Double"); param("c1y", "Double"); param("c1z", "Double")
                param("c2x", "Double"); param("c2y", "Double"); param("c2z", "Double")
                param("x", "Double"); param("y", "Double"); param("z", "Double")
            }
            fn("uv", "FLine") { param("u", "Double"); param("v", "Double") }
            fn("vcolor", "FLine") { param("r", "Double"); param("g", "Double"); param("b", "Double"); param("a", "Double") }
            // Public transform: apply a per-point map `(x,y,z) -> [x,y,z]`, returning a new FLine
            // (fline.js:458). The callback returns a 3-element array of coordinates.
            fn(
                "byTransforming", "FLine",
            ) {
                param(
                    "transform",
                    JsType(
                        "(x: Double, y: Double, z: Double) -> Array<Double>",
                        "(x: Double, y: Double, z: Double) -> Array<Double>",
                    ),
                )
            }
            // Raw runtime transform ops (fline.js:492–578) — compiler-only; the public `+`/`-`/`*`/`/`
            // operator extensions below delegate to these, so they stay OUT of completion.
            compilerFn("__PLUS__", "FLine") { param("v", JsType.DYNAMIC) }
            compilerFn("__MINUS__", "FLine") { param("v", JsType.DYNAMIC) }
            compilerFn("__MULTIPLY__", "FLine") { param("v", JsType.DYNAMIC) }
            compilerFn("__DIVIDE__", "FLine") { param("v", JsType.DYNAMIC) }
            // Pointer-event registries: `fline.mouseDown[name] = { event -> … }` (Proxy-over-Map).
            valProp("mouseDown", JsType("dynamic", "PointerCallbacks"))
            valProp("mouseUp", JsType("dynamic", "PointerCallbacks"))
            valProp("mouseMove", JsType("dynamic", "PointerCallbacks"))
            valProp("dblClick", JsType("dynamic", "PointerCallbacks"))
        }

        // The runtime `stage` is a dynamic Map proxy (`fline.js`): `stage[name] = fline`,
        // `stage.name = fline`, `stage.add/remove`. Typing it as `dynamic` for the compiler keeps all
        // of that permissive; a completion-only `Stage` type gives `stage.add|` / `stage.remove|` and
        // `stage["…"]` completion without constraining the JS target. (To clear the scene use the free
        // `clearAll()` — the proxy's Map.clear would bypass scene removal.)
        completionCls("Stage") {
            fn("add", "Unit") { param("item", "Any?") }
            fn("remove", "Boolean") { param("item", "Any?") }
            operatorFn("get", JsType.of("Any?")) { param("name", "String") }
            operatorFn("set", JsType.of("Unit")) { param("name", "String"); param("value", "Any?") }
        }

        // `SCENE.attach[name] = obj` — Field's named-index proxy (compiler sees `dynamic`).
        completionCls("AttachProxy") {
            operatorFn("get", JsType.of("THREE.Object3D?")) { param("name", "String") }
            operatorFn("set", JsType.of("Unit")) { param("name", "String"); param("value", "Any?") }
        }

        // Completion shapes for the dynamic result objects the helpers return (compiler sees `dynamic`).
        completionCls("CameraPose") {
            valProp("position", "THREE.Vector3"); valProp("forward", "THREE.Vector3"); valProp("up", "THREE.Vector3")
        }
        completionCls("PlacedMesh") {
            valProp("mesh", "THREE.Mesh"); valProp("material", "THREE.Material"); valProp("mouseDown", "PointerCallbacks")
        }
        completionCls("ImagePlane") {
            valProp("mesh", "THREE.Mesh"); valProp("material", "THREE.Material")
            valProp("texture", "Any?"); valProp("mouseDown", "PointerCallbacks")
        }
        completionCls("VideoPlane") {
            valProp("mesh", "THREE.Mesh"); valProp("material", "THREE.Material")
            valProp("video", "Any?"); valProp("texture", "Any?"); valProp("mouseDown", "PointerCallbacks")
        }

        // ---- asset-loader callback payloads (files.js) — the loader hands each `callback` a runtime
        // object; `dynamic` for the compiler, these typed shapes for completion inside the lambda. The
        // animation methods (`play`/`stop`/`apply`) drive the auto-created XR AnimationMixer via
        // `mixer.clipAction(clip)` under the hood (files.js:396+, webxr.js:76). ----
        completionCls("GltfAsset") {
            // Array-valued fields are raw JS arrays (the payload is `dynamic` for the compiler, so member
            // names are emitted verbatim — a JS array's length is `.length`, not Kotlin's `.size`); keep
            // them `Any?` so completion doesn't suggest a `.size` that resolves to `undefined` at runtime.
            valProp("meshes", "Any?")                // Array<THREE.Object3D> at runtime (use [i] / .length)
            valProp("animations", "Any?")            // Array<THREE.AnimationClip> at runtime
            valProp("scene", "THREE.Object3D")
            valProp("scenes", "Any?")
            valProp("cameras", "Any?")
            valProp("asset", "Any?")
            fn("play", "Unit")                        // clipAction(clip).play() on every clip
            fn("stop", "Unit")
            fn("apply", "Unit") { param("time", "Double") }   // scrub: seek every clip to `time`
        }
        completionCls("FbxAsset") {
            // NB: loadFBX THROWS unless a FBXLoader is vendored in the head (files.js:129); prefer glTF.
            valProp("animations", "Any?")            // raw JS array at runtime (use [i] / .length)
            valProp("objects", "Any?")               // raw JS array at runtime (use [i] / .length)
            fn("play", "Unit") { param("looping", "Boolean", "true") }
            fn("stop", "Unit")
            fn("pause", "Unit")
            fn("currentTime", "Double")
            fn("apply", "Unit") { param("time", "Double") }
        }
        completionCls("PlyAsset") {
            valProp("mesh", "THREE.Object3D")
        }

        // Pointer-event registry: `target.mouseDown[name] = { event -> … }`. dynamic for the compiler,
        // a named-index callback registry for completion. The event object is head-dependent (dynamic).
        completionCls("PointerCallbacks") {
            operatorFn("set", JsType.of("Unit")) { param("name", "String"); param("callback", JsType.of("(event: Any?) -> Unit")) }
            operatorFn("get", JsType.of("Any?")) { param("name", "String") }
        }

        // WebXR hit (position + world matrix), returned by `xrHit()` / passed to onXRHit callbacks.
        completionCls("XRHit") {
            valProp("position", "THREE.Vector3")
            valProp("matrix", "Array<Double>")
        }

        externalVal("SCENE", "THREE.Scene")
        externalVal("CAMERA", "THREE.Camera")
        externalVal("stage", JsType("dynamic", "Stage"))
        computedVal("scene", "THREE.Scene", compiler = "SCENE")
        computedVal("camera", "THREE.Camera", compiler = "CAMERA")

        // `vec(…)` builds a THREE vector sized by arity (fline.js); `clearAll()` empties the scene.
        externalFun("vec", "THREE.Vector2") { param("x", "Double"); param("y", "Double") }
        externalFun("vec", "THREE.Vector3") { param("x", "Double"); param("y", "Double"); param("z", "Double") }
        externalFun("vec", "THREE.Vector4") { param("x", "Double"); param("y", "Double"); param("z", "Double"); param("w", "Double") }
        externalFun("clearAll", "Unit")

        // ---- transforms (fline.js) — each builds a THREE.Matrix4 ----
        externalFun("rotate", "THREE.Matrix4") { param("angle", "Double") }
        externalFun("rotate", "THREE.Matrix4") { param("angle", "Double"); param("pivotX", "Double"); param("pivotY", "Double") }
        externalFun("scale", "THREE.Matrix4") { param("s", "Double") }
        externalFun("scale", "THREE.Matrix4") { param("x", "Double"); param("y", "Double"); param("z", "Double") }
        externalFun("translate", "THREE.Matrix4") { param("x", "Double", "0.0"); param("y", "Double", "0.0"); param("z", "Double", "0.0") }
        externalFun("rotate3", "THREE.Matrix4") { param("axis", "THREE.Vector3"); param("angle", "Double") }

        // ---- scene search + camera pose (fline.js) ----
        externalFun("findByName", "THREE.Object3D?") { param("name", "String") }
        externalFun("findBySearchingFor", "THREE.Object3D?") { param("name", "String") }
        externalFun("findBySearchingPathFor", "THREE.Object3D?") { param("name", "String") }
        externalFun("getCurrentCamera", JsType("dynamic", "CameraPose"))
        externalFun("addTextureTo", "Unit") { param("url", "String"); param("target", JsType.DYNAMIC) }

        // ---- object-creation helpers (files.js) — return dynamic result objects (typed for completion) ----
        externalFun("addBox", JsType("dynamic", "PlacedMesh")) { param("name", "String"); param("size", "Double", "1.0") }
        externalFun("addPlane", JsType("dynamic", "PlacedMesh")) { param("name", "String"); param("size", "Double", "1.0") }
        externalFun("addImagePlane", JsType("dynamic", "ImagePlane")) { param("url", "String"); param("name", "String"); param("size", "Double", "1.0") }
        externalFun("addVideoPlane", JsType("dynamic", "VideoPlane")) { param("url", "String"); param("name", "String"); param("size", "Double", "1.0") }
        externalFun("addPointLight", "THREE.PointLight") { param("name", "String") }

        // ---- asset & animation loaders (files.js) — async/callback-based; the callback receives a
        // runtime payload (dynamic for the compiler, typed for completion). The loaded object is also
        // installed as a head global under `name`. ----
        externalFun("loadGLTF", "Unit") {
            param("url", "String"); param("name", "String")
            param("callback", JsType("(loaded: dynamic) -> Unit", "(loaded: GltfAsset) -> Unit"))
        }
        externalFun("loadPointCloudPLY", "Unit") {
            param("url", "String"); param("name", "String")
            param("callback", JsType("(loaded: dynamic) -> Unit", "(loaded: PlyAsset) -> Unit"))
        }
        // loadFBX THROWS unless the head vendors a FBXLoader (files.js:129) — bound for completeness;
        // convert assets to glTF and use loadGLTF instead.
        externalFun("loadFBX", "Unit") {
            param("url", "String"); param("name", "String")
            param("callback", JsType("(loaded: dynamic) -> Unit", "(loaded: FbxAsset) -> Unit"))
        }
        externalFun("loadJSONScene", "Unit") { param("url", "String"); param("name", "String") }
        // A THREE.Texture (dynamic for the compiler; typed for completion).
        externalFun("loadTexture", JsType("dynamic", "THREE.Texture")) { param("url", "String") }
        // Expose an on-disk directory over the Field server: mapDirectory("/abs/assets", "/assets").
        externalFun("mapDirectory", "Unit") { param("onDiskPath", "String"); param("servedAs", "String") }

        // ---- WebXR interaction (webxr.js) — on* return an unsubscribe () -> Unit ----
        externalFun("onXRHit", JsType.CALLBACK) { param("callback", JsType("(hit: dynamic) -> Unit", "(hit: Any?) -> Unit")) }
        externalFun("onXRSelect", JsType.CALLBACK) { param("callback", JsType("(hit: dynamic) -> Unit", "(hit: Any?) -> Unit")) }
        externalFun("xrHit", JsType("dynamic", "XRHit"))

        function("log", ret = JsType.of("Unit"), compilerBody = "__remoteArLog(value)") {
            param("value", "Any?")
        }

        operator("THREE.Vector2", "plus", "THREE.Vector2", "clone().add(other)") { param("other", "THREE.Vector2") }
        operator("THREE.Vector2", "minus", "THREE.Vector2", "clone().sub(other)") { param("other", "THREE.Vector2") }
        operator("THREE.Vector2", "times", "THREE.Vector2", "clone().multiplyScalar(value)") { param("value", "Double") }
        operator("THREE.Vector3", "plus", "THREE.Vector3", "clone().add(other)") { param("other", "THREE.Vector3") }
        operator("THREE.Vector3", "minus", "THREE.Vector3", "clone().sub(other)") { param("other", "THREE.Vector3") }
        operator("THREE.Vector3", "times", "THREE.Vector3", "clone().multiplyScalar(value)") { param("value", "Double") }
        operator("THREE.Vector4", "plus", "THREE.Vector4", "clone().add(other)") { param("other", "THREE.Vector4") }
        operator("THREE.Vector4", "minus", "THREE.Vector4", "clone().sub(other)") { param("other", "THREE.Vector4") }
        operator("THREE.Vector4", "times", "THREE.Vector4", "clone().multiplyScalar(value)") { param("value", "Double") }

        // ---- FLine transform operators (fline.js:492–578) — `fline + rotate(0.5)`, `fline + vec(1,0,0)`,
        // `fline * 2.0`, `fline - translate(…)`. Each delegates to the compiler-only raw member above.
        // Grounded overloads: +/- take a Matrix4 or a promotable vector; */÷ also take a scalar.
        operator("FLine", "plus", "FLine", "__PLUS__(m)") { param("m", "THREE.Matrix4") }
        operator("FLine", "plus", "FLine", "__PLUS__(v)") { param("v", "THREE.Vector3") }
        operator("FLine", "plus", "FLine", "__PLUS__(v)") { param("v", "THREE.Vector2") }
        operator("FLine", "minus", "FLine", "__MINUS__(m)") { param("m", "THREE.Matrix4") }
        operator("FLine", "minus", "FLine", "__MINUS__(v)") { param("v", "THREE.Vector3") }
        operator("FLine", "minus", "FLine", "__MINUS__(v)") { param("v", "THREE.Vector2") }
        operator("FLine", "times", "FLine", "__MULTIPLY__(m)") { param("m", "THREE.Matrix4") }
        operator("FLine", "times", "FLine", "__MULTIPLY__(v)") { param("v", "THREE.Vector3") }
        operator("FLine", "times", "FLine", "__MULTIPLY__(s)") { param("s", "Double") }
        operator("FLine", "div", "FLine", "__DIVIDE__(m)") { param("m", "THREE.Matrix4") }
        operator("FLine", "div", "FLine", "__DIVIDE__(v)") { param("v", "THREE.Vector3") }
        operator("FLine", "div", "FLine", "__DIVIDE__(s)") { param("s", "Double") }

        rawCompiler(
            """
            @kotlin.js.JsName("log")
            private external fun __remoteArLog(value: Any?)
            """.trimIndent(),
        )
    }

    /** Compiler-facing `external` declarations, generated from [binding]. */
    val declarations: String = binding.compilerDeclarations

    /** JVM-analysis-safe model used by the out-of-process completion sidecar, generated from [binding]. */
    val completionDeclarations: String = binding.completionDeclarations
}
