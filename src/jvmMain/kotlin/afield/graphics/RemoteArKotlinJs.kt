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
                fn("add", "Object3D") { vararg("objects", "Object3D") }
                fn("remove", "Object3D") { vararg("objects", "Object3D") }
            }

            cls("Scene") { extends("Object3D") }
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

            cls("Material") {
                open()
                varProp("transparent", "Boolean", completionInit = "false")
                varProp("opacity", "Double", completionInit = "1.0")
            }

            cls("MeshBasicMaterial") {
                extends("Material")
                ctor { param("parameters", JsType.DYNAMIC, "null") }
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
        }

        cls("FLine") {
            varProp("stroked", "Boolean", completionInit = "true")
            varProp("filled", "Boolean", completionInit = "false")
            fn("moveTo", "FLine") { param("x", "Double"); param("y", "Double"); param("z", "Double", "0.0") }
            fn("moveTo", "FLine") { param("point", "THREE.Vector3") }
            fn("lineTo", "FLine") { param("x", "Double"); param("y", "Double"); param("z", "Double", "0.0") }
            fn("lineTo", "FLine") { param("point", "THREE.Vector3") }
            fn("cubicTo", "FLine") { param("a", "THREE.Vector3"); param("b", "THREE.Vector3"); param("c", "THREE.Vector3") }
            fn("uv", "FLine") { param("u", "Double"); param("v", "Double") }
            fn("vcolor", "FLine") { param("r", "Double"); param("g", "Double"); param("b", "Double"); param("a", "Double") }
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

        function("onFrame", ret = JsType.DYNAMIC, compilerBody = "__remoteArLaunch(callback, name, false)") {
            param("name", "String", "\"kotlinjs\"")
            param("callback", JsType.CALLBACK)
        }
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

        rawCompiler(
            """
            @kotlin.js.JsName("launch")
            private external fun __remoteArLaunch(
                callback: () -> Unit,
                name: String = definedExternally,
                oneShotByDefault: Boolean = definedExternally,
            ): dynamic

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
