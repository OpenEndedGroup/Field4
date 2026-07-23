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

        externalVal("SCENE", "THREE.Scene")
        externalVal("CAMERA", "THREE.Camera")
        externalVal("stage", JsType.DYNAMIC)
        computedVal("scene", "THREE.Scene", compiler = "SCENE")
        computedVal("camera", "THREE.Camera", compiler = "CAMERA")

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
