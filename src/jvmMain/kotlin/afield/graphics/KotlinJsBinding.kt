package afield.graphics

/**
 * One source of truth for a Kotlin/JS façade surface.
 *
 * A KotlinJS head profile needs the *same* API described twice: once as compiler-facing `external`
 * declarations (the JS target the box compiles against) and once as a JVM-analysis-safe mirror (what
 * the out-of-process K2 Analysis-API completion sidecar analyzes — it has no `dynamic`, and `external`
 * declarations behave differently there). Maintaining those two by hand drifts — e.g. a property
 * that's `var` in one and `val` in the other, or a member completion offers that compilation rejects.
 *
 * So describe the surface ONCE with [jsBinding] and let [JsBindingModel.compilerDeclarations] and
 * [JsBindingModel.completionDeclarations] render both. The two rendered forms differ only mechanically:
 *
 * | aspect            | compiler (`external`)      | completion (plain, JVM-analysis) |
 * |-------------------|----------------------------|----------------------------------|
 * | declaration       | `external object/class`    | plain `object/class`             |
 * | member fun body   | none                       | `= TODO()`                       |
 * | property init     | none                       | `= <init>` (default `TODO()`)    |
 * | ctor param default| `= definedExternally`      | `= <real default>`               |
 * | supertype         | `: Base`                   | `: Base()`                       |
 * | `dynamic`         | `dynamic`                  | `Any?`                           |
 * | callback type     | `() -> Unit`               | `Function0<Unit>`                |
 * | operator/fun body | real body                  | `= TODO()`                       |
 *
 * A field that is both a constructor argument and a mutable property (THREE's `Vector3(x,y,z)` with
 * mutable `.x/.y/.z`, or `Mesh(geometry, material)` with mutable `.geometry/.material`) is one
 * [TypeBuilder.field] call — it emits a plain ctor param plus a `var` property in BOTH renderings,
 * so the two can never disagree on mutability or on whether the property exists.
 */

/** A type reference that may render differently for the JS compiler vs the JVM completion session. */
class JsType(val compiler: String, val completion: String = compiler) {
    companion object {
        val DYNAMIC = JsType("dynamic", "Any?")
        /** A no-arg callback — `() -> Unit` compiles for JS; the AA session prefers the `Function0` form. */
        val CALLBACK = JsType("() -> Unit", "Function0<Unit>")
        fun of(name: String) = JsType(name)
    }
}

class JsParam(
    val name: String,
    val type: JsType,
    /** Completion/real default; the compiler uses `definedExternally` inside `external` declarations. */
    val default: String? = null,
    val vararg: Boolean = false,
)

class JsProp(val name: String, val type: JsType, val mutable: Boolean, val completionInit: String = "TODO()")

class JsFun(
    val name: String,
    val params: List<JsParam>,
    val ret: JsType,
    /** Real body for top-level funs / operators; member funs of external classes carry none. */
    val compilerBody: String? = null,
    /** A top-level installed global (`external fun`, no body) rather than a wrapper with a body. */
    val external: Boolean = false,
    /** Member `operator fun` (e.g. a stage `get`/`set` indexer). */
    val operator: Boolean = false,
)

class JsTypeDecl(
    val name: String,
    val isObject: Boolean,
    val open: Boolean,
    val superType: String?,
    val ctor: List<JsParam>,
    val props: List<JsProp>,
    val funs: List<JsFun>,
    val nested: List<JsTypeDecl>,
    /** Emitted only into the completion mirror — lets a value be `dynamic` for the JS compiler
     *  (maximally permissive) yet a real type for completion (so its members are suggested). */
    val completionOnly: Boolean = false,
)

class JsTopVal(
    val name: String,
    val type: JsType,
    val mutable: Boolean,
    /** `external val` (compiler) vs a computed `get() = <compilerGetter>` when [compilerGetter] is set. */
    val compilerGetter: String? = null,
)

class JsExtFun(
    val receiver: String,
    val operator: Boolean,
    val name: String,
    val params: List<JsParam>,
    val ret: JsType,
    val compilerBody: String,
)

class JsBindingModel(
    val types: List<JsTypeDecl>,
    val topVals: List<JsTopVal>,
    val topFuns: List<JsFun>,
    val extFuns: List<JsExtFun>,
    /** Compiler-only glue emitted verbatim (private `@JsName` externals the public funs delegate to). */
    val rawCompiler: String,
) {
    val compilerDeclarations: String get() = JsBindingRenderer(external = true).render(this)
    val completionDeclarations: String get() = JsBindingRenderer(external = false).render(this)
}

// ---------------------------------------------------------------------------------------------------
// DSL builders
// ---------------------------------------------------------------------------------------------------

fun jsBinding(build: BindingBuilder.() -> Unit): JsBindingModel = BindingBuilder().apply(build).build()

class BindingBuilder {
    private val types = mutableListOf<JsTypeDecl>()
    private val topVals = mutableListOf<JsTopVal>()
    private val topFuns = mutableListOf<JsFun>()
    private val extFuns = mutableListOf<JsExtFun>()
    private var raw = ""

    /** `external object Name { … }` — a namespace like `THREE`. */
    fun obj(name: String, build: TypeBuilder.() -> Unit) {
        types += TypeBuilder(name, isObject = true).apply(build).build()
    }

    /** A top-level `external class Name { … }` (e.g. `FLine`). */
    fun cls(name: String, build: TypeBuilder.() -> Unit) {
        types += TypeBuilder(name, isObject = false).apply(build).build()
    }

    /** A class emitted ONLY into the completion mirror — used to give a `dynamic` compiler value
     *  (e.g. the `stage` proxy) real, suggestable members for completion without constraining the
     *  permissive JS-target type. */
    fun completionCls(name: String, build: TypeBuilder.() -> Unit) {
        types += TypeBuilder(name, isObject = false, completionOnly = true).apply(build).build()
    }

    /** An installed JS global function (`external fun`, no body). */
    fun externalFun(name: String, ret: JsType = JsType.of("Unit"), build: ParamsBuilder.() -> Unit = {}) {
        topFuns += JsFun(name, ParamsBuilder().apply(build).params, ret, external = true)
    }

    fun externalFun(name: String, ret: String, build: ParamsBuilder.() -> Unit = {}) =
        externalFun(name, JsType.of(ret), build)

    fun externalVal(name: String, type: JsType, mutable: Boolean = false) {
        topVals += JsTopVal(name, type, mutable)
    }

    fun externalVal(name: String, type: String, mutable: Boolean = false) = externalVal(name, JsType.of(type), mutable)

    /** A read-only alias resolved to [compiler] in the JS target (`get() = compiler`). */
    fun computedVal(name: String, type: String, compiler: String) {
        topVals += JsTopVal(name, JsType.of(type), mutable = false, compilerGetter = compiler)
    }

    fun function(name: String, ret: JsType = JsType.of("Unit"), compilerBody: String, build: ParamsBuilder.() -> Unit = {}) {
        topFuns += JsFun(name, ParamsBuilder().apply(build).params, ret, compilerBody)
    }

    fun operator(receiver: String, name: String, ret: String, compilerBody: String, build: ParamsBuilder.() -> Unit) {
        extFuns += JsExtFun(receiver, operator = true, name, ParamsBuilder().apply(build).params, JsType.of(ret), compilerBody)
    }

    /** Compiler-only glue (private `@JsName` externals). Not part of the completion surface. */
    fun rawCompiler(text: String) { raw = text.trimIndent() }

    fun build() = JsBindingModel(types, topVals, topFuns, extFuns, raw)
}

class TypeBuilder(
    private val name: String,
    private val isObject: Boolean = false,
    private val completionOnly: Boolean = false,
) {
    private var open = false
    private var superType: String? = null
    private val ctor = mutableListOf<JsParam>()
    private val props = mutableListOf<JsProp>()
    private val funs = mutableListOf<JsFun>()
    private val nested = mutableListOf<JsTypeDecl>()

    fun open() { open = true }
    fun extends(base: String) { superType = base }
    fun ctor(build: ParamsBuilder.() -> Unit) { ctor += ParamsBuilder().apply(build).params }

    fun varProp(name: String, type: String, completionInit: String = "TODO()") {
        props += JsProp(name, JsType.of(type), mutable = true, completionInit)
    }
    fun valProp(name: String, type: String, completionInit: String = "TODO()") {
        props += JsProp(name, JsType.of(type), mutable = false, completionInit)
    }
    /** Property whose compiler/completion types differ (e.g. `dynamic` proxy ↔ typed indexer). */
    fun valProp(name: String, type: JsType, completionInit: String = "TODO()") {
        props += JsProp(name, type, mutable = false, completionInit)
    }

    /** A mutable field that is BOTH a ctor argument and a property — the single call keeps the two
     *  renderings in lockstep (plain ctor param + `var` property in each). */
    fun field(name: String, type: String, default: String? = null) {
        ctor += JsParam(name, JsType.of(type), default)
        props += JsProp(name, JsType.of(type), mutable = true)
    }

    fun fn(name: String, ret: String = "Unit", build: ParamsBuilder.() -> Unit = {}) {
        funs += JsFun(name, ParamsBuilder().apply(build).params, JsType.of(ret))
    }

    /** A member `operator fun` (e.g. `get`/`set` indexers). Param/return types may be [JsType]. */
    fun operatorFn(name: String, ret: JsType, build: ParamsBuilder.() -> Unit = {}) {
        funs += JsFun(name, ParamsBuilder().apply(build).params, ret, operator = true)
    }

    /** Nested class inside this object/class. */
    fun cls(name: String, build: TypeBuilder.() -> Unit = {}) {
        nested += TypeBuilder(name).apply(build).build()
    }

    fun build() = JsTypeDecl(name, isObject, open, superType, ctor, props, funs, nested, completionOnly)
}

class ParamsBuilder {
    val params = mutableListOf<JsParam>()
    fun param(name: String, type: String, default: String? = null) { params += JsParam(name, JsType.of(type), default) }
    fun param(name: String, type: JsType, default: String? = null) { params += JsParam(name, type, default) }
    fun vararg(name: String, type: String) { params += JsParam(name, JsType.of(type), vararg = true) }
}

// ---------------------------------------------------------------------------------------------------
// Renderer
// ---------------------------------------------------------------------------------------------------

private class JsBindingRenderer(private val external: Boolean) {
    fun render(model: JsBindingModel): String {
        val out = StringBuilder()
        model.types
            .filterNot { external && it.completionOnly } // completion-only types never enter the JS target
            .forEach { out.append(renderType(it, top = true, indent = "")).append("\n\n") }
        model.topVals.forEach { out.append(renderTopVal(it)).append('\n') }
        if (model.topVals.isNotEmpty()) out.append('\n')
        model.topFuns.forEach { out.append(renderTopFun(it)).append('\n') }
        if (model.topFuns.isNotEmpty()) out.append('\n')
        model.extFuns.forEach { out.append(renderExtFun(it)).append('\n') }
        if (external && model.rawCompiler.isNotBlank()) out.append('\n').append(model.rawCompiler).append('\n')
        return out.toString().trimEnd() + "\n"
    }

    private fun typeName(t: JsType) = if (external) t.compiler else t.completion

    private fun renderParam(p: JsParam): String {
        val prefix = if (p.vararg) "vararg " else ""
        val def = p.default?.let { if (external) " = definedExternally" else " = $it" } ?: ""
        return "$prefix${p.name}: ${typeName(p.type)}$def"
    }

    private fun renderType(t: JsTypeDecl, top: Boolean, indent: String): String {
        val sb = StringBuilder()
        val kw = buildString {
            // `external` is only written at the top level; nested declarations inherit it.
            if (external && top) append("external ")
            if (t.open) append("open ")
            append(if (t.isObject) "object " else "class ")
            append(t.name)
        }
        sb.append(indent).append(kw)
        if (!t.isObject && t.ctor.isNotEmpty()) {
            sb.append("(").append(t.ctor.joinToString(", ") { renderParam(it) }).append(")")
        }
        t.superType?.let { sb.append(" : ").append(it).append(if (external) "" else "()") }

        val bodyLines = buildList {
            t.props.forEach { add(renderProp(it)) }
            t.funs.forEach { add(renderMemberFun(it)) }
            t.nested.forEach { add(renderType(it, top = false, indent = "").trimEnd()) }
        }
        if (bodyLines.isEmpty()) return sb.toString()
        sb.append(" {\n")
        bodyLines.forEach { line ->
            line.split("\n").forEach { sb.append(indent).append("    ").append(it).append('\n') }
        }
        sb.append(indent).append("}")
        return sb.toString()
    }

    private fun renderProp(p: JsProp): String {
        val decl = "${if (p.mutable) "var" else "val"} ${p.name}: ${typeName(p.type)}"
        return if (external) decl else "$decl = ${p.completionInit}"
    }

    private fun renderMemberFun(f: JsFun): String {
        val op = if (f.operator) "operator " else ""
        val sig = "${op}fun ${f.name}(${f.params.joinToString(", ") { renderParam(it) }}): ${typeName(f.ret)}"
        return if (external) sig else "$sig = TODO()"
    }

    private fun renderTopVal(v: JsTopVal): String {
        val decl = "${if (v.mutable) "var" else "val"} ${v.name}: ${typeName(v.type)}"
        return when {
            !external -> "$decl = TODO()"
            v.compilerGetter != null -> "$decl get() = ${v.compilerGetter}"
            else -> "external $decl"
        }
    }

    private fun renderTopFun(f: JsFun): String {
        val params = f.params.joinToString(", ") { p ->
            // installed globals are `external fun` in the compiler → defaults are `definedExternally`.
            val def = p.default?.let { if (external && f.external) " = definedExternally" else " = $it" } ?: ""
            "${if (p.vararg) "vararg " else ""}${p.name}: ${typeName(p.type)}$def"
        }
        val sig = "fun ${f.name}($params): ${typeName(f.ret)}"
        return when {
            external && f.external -> "external $sig"                    // installed global, no body
            external -> "$sig = ${f.compilerBody ?: "TODO()"}"           // wrapper with a real body
            else -> "$sig = TODO()"                                       // completion mirror
        }
    }

    private fun renderExtFun(f: JsExtFun): String {
        val params = f.params.joinToString(", ") { p ->
            val def = p.default?.let { " = $it" } ?: ""
            "${p.name}: ${typeName(p.type)}$def"
        }
        val op = if (f.operator) "operator " else ""
        val sig = "${op}fun ${f.receiver}.${f.name}($params): ${typeName(f.ret)}"
        return "$sig = ${if (external) f.compilerBody else "TODO()"}"
    }
}
