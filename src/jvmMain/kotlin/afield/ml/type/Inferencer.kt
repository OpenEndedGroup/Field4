package afield.ml.type

import afield.ml.ast.BinaryOp
import afield.ml.ast.EBinary
import afield.ml.ast.EBool
import afield.ml.ast.ECall
import afield.ml.ast.EField
import afield.ml.ast.EFloat
import afield.ml.ast.EIf
import afield.ml.ast.EInt
import afield.ml.ast.ELambda
import afield.ml.ast.ELet
import afield.ml.ast.EList
import afield.ml.ast.EMatch
import afield.ml.ast.ENegate
import afield.ml.ast.ERecord
import afield.ml.ast.EString
import afield.ml.ast.ETuple
import afield.ml.ast.ETypeDecl
import afield.ml.ast.EVar
import afield.ml.ast.Expr
import afield.ml.ast.PBool
import afield.ml.ast.PCons
import afield.ml.ast.PConstructor
import afield.ml.ast.PInt
import afield.ml.ast.PList
import afield.ml.ast.PString
import afield.ml.ast.PTuple
import afield.ml.ast.PVar
import afield.ml.ast.PWildcard
import afield.ml.ast.Pattern
import afield.ml.ast.TypeDecl
import afield.ml.ast.TypeRef
import afield.ml.ast.TypeRefName
import afield.ml.diag.CompileAbort
import afield.ml.diag.Diagnostic
import afield.ml.diag.Severity
import afield.ml.diag.SourceRange

data class InferResult(
    val typed: TExpr?,
    val diagnostics: List<Diagnostic>,
)

class Inferencer {
    private var nextTypeVar = 1
    private val substitution = mutableMapOf<Int, Type>()
    private val diagnostics = mutableListOf<Diagnostic>()
    private val numericVariables = mutableSetOf<Int>()
    private val vectorVariables = mutableSetOf<Int>()

    fun infer(expr: Expr): InferResult =
        try {
            val inferred = inferExpr(expr, TypeEnv(FieldMLTypePrelude.schemes))
            InferResult(finalize(inferred), diagnostics)
        } catch (abort: CompileAbort) {
            diagnostics += abort.diagnostic
            InferResult(null, diagnostics)
        }

    private fun inferExpr(expr: Expr, env: TypeEnv): TExpr =
        when (expr) {
            is EInt -> TExprInt(expr.value, expr.range, TInt)
            is EFloat -> TExprFloat(expr.value, expr.range, TFloat)
            is EBool -> TExprBool(expr.value, expr.range, TBool)
            is EString -> TExprString(expr.value, expr.range, TString)
            is EVar -> {
                val scheme = env[expr.name] ?: abort("Unknown name '${expr.name}'", expr.range)
                TExprVar(expr.name, expr.range, instantiate(scheme))
            }
            is ETuple -> {
                val elements = expr.elements.map { inferExpr(it, env) }
                TExprTuple(elements, expr.range, TTuple(elements.map { it.type }))
            }
            is EList -> {
                val elements = expr.elements.map { inferExpr(it, env) }
                val elementType = elements.firstOrNull()?.type ?: fresh()
                elements.drop(1).forEach { unify(elementType, it.type, it.range) }
                TExprList(elements, expr.range, TList(elementType))
            }
            is ETypeDecl -> inferTypeDecl(expr, env)
            is ELambda -> inferLambda(expr.parameters, expr.body, expr.range, env)
            is ECall -> {
                val function = inferExpr(expr.function, env)
                val argument = inferExpr(expr.argument, env)
                val resultType = fresh()
                unify(function.type, TFun(argument.type, resultType), expr.range)
                val call = TExprCall(function, argument, expr.range, resultType)
                // The refinement *replaces* the result type rather than unifying with it: the prelude
                // already unified `resultType` with the scheme's `Host`, and `Host` unifies with
                // everything, so a unification here would quietly discard what reflection found.
                refineHostIntrinsic(call, env)?.let { call.copy(type = it) } ?: call
            }
            is ELet -> inferLet(expr, env)
            is EIf -> {
                val condition = inferExpr(expr.condition, env)
                unify(condition.type, TBool, expr.condition.range)
                val thenBranch = inferExpr(expr.thenBranch, env)
                val elseBranch = inferExpr(expr.elseBranch, env)
                unify(thenBranch.type, elseBranch.type, expr.range)
                TExprIf(condition, thenBranch, elseBranch, expr.range, thenBranch.type)
            }
            is EMatch -> inferMatch(expr, env)
            is ERecord -> {
                val fields = expr.fields.map { it.name to inferExpr(it.value, env) }
                TExprRecord(fields, expr.range, TRecord(fields.associate { it.first to it.second.type }))
            }
            is EField -> {
                val target = inferExpr(expr.target, env)
                val fieldType = fresh()
                val resolvedFieldType = when (val targetType = prune(target.type)) {
                    is TRecord -> targetType.fields[expr.field]
                        ?: abort("Record is missing field '${expr.field}'", expr.range)
                    is TVar -> {
                        unify(targetType, TRecord(mapOf(expr.field to fieldType)), expr.range)
                        fieldType
                    }
                    is THost -> {
                        val components = FieldMLTypePrelude.vectorComponents(targetType)
                        when {
                            expr.field in components -> TFloat
                            components.isNotEmpty() -> abort(
                                "${TypePrinter().print(targetType)} has no field '${expr.field}'" +
                                    " (expected one of ${components.joinToString(", ")})",
                                expr.range,
                            )
                            // Dot access on a typed host value reads a JVM member, so `img.width`
                            // works and is checked. An opaque `Host` resolves to Unknown and stays
                            // dynamic, which is the behaviour a program written before this had.
                            else -> resolved(FieldMLHostTypes.memberType(targetType, expr.field), expr.range)
                                ?: FieldMLTypePrelude.host
                        }
                    }
                    else -> abort("Field access requires a record, found ${TypePrinter().print(normalize(targetType))}", expr.range)
                }
                TExprField(target, expr.field, expr.range, resolvedFieldType)
            }
            is EBinary -> inferBinary(expr, env)
            is ENegate -> {
                val value = inferExpr(expr.value, env)
                val pruned = prune(value.type)
                if (!FieldMLTypePrelude.isVectorType(pruned)) requireNumeric(pruned, expr.range)
                TExprNegate(value, expr.range, prune(value.type))
            }
        }

    /**
     * The typed half of the JVM bridge: when a call is a *saturated* application of one of the
     * `host…` intrinsics, ask reflection what it actually returns, and refuse it here if the member
     * does not exist.
     *
     * Doing this in the inferencer rather than in the prelude's type schemes is what makes it
     * possible at all — a scheme is one fixed type, but `hostCall "moveTo" (x, y) line` has a result
     * that depends on the *value* of a string and the *type* of a receiver. The rules are:
     *
     * - only when the intrinsic's name still resolves to the prelude binding, so a program that
     *   shadows `hostGet` with its own function is left alone;
     * - only when the method name is a literal, since a computed name cannot be looked up;
     * - [FieldMLHostTypes.Resolution.Unknown] means the receiver's class is not known, and then
     *   nothing is claimed and nothing is refused — the call stays as dynamic as it was before;
     * - [FieldMLHostTypes.Resolution.Refused] is a genuine type error, and it is reported where the
     *   name was written rather than as a crash on the first run.
     *
     * Returns null when the type should be left as the scheme gave it.
     */
    private fun refineHostIntrinsic(call: TExprCall, env: TypeEnv): Type? {
        val spine = hostSpine(call) ?: return null
        val (name, arguments) = spine
        if (env[name] !== FieldMLTypePrelude.schemes[name]) return null
        return when (name) {
            "hostClass" -> arguments.singleOrNull()?.let { argument ->
                val className = literalString(argument) ?: return null
                if (FieldMLHostTypes.loadClass(className) == null &&
                    FieldMLHostTypes.loadClass(nestedSpelling(className)) == null
                ) {
                    abort("Unknown JVM class '$className'", argument.range)
                }
                FieldMLHostTypes.classHandleType(
                    if (FieldMLHostTypes.loadClass(className) != null) className else nestedSpelling(className),
                )
            }
            // `hostAs "Vec3" p` is both a checked cast and the way FieldML names a class: it is what
            // gives a type to a value that arrived through an erased signature or as the parameter of
            // a lambda the JVM called us with, and everything after it is checked normally.
            "hostAs" -> arguments.takeIf { it.size == 2 }?.let { (className, _) ->
                val requested = literalString(className) ?: return null
                if (FieldMLHostTypes.jvmClassOf(THost(requested)) == null) {
                    abort("hostAs does not know the class '$requested'", className.range)
                }
                FieldMLHostTypes.valueType(requested)
            }
            "hostNew0" -> arguments.singleOrNull()?.let { klass ->
                resolved(FieldMLHostTypes.constructorResultType(pruned(klass), emptyList()), call.range)
            }
            "hostNew" -> arguments.takeIf { it.size == 2 }?.let { (argumentList, klass) ->
                val argumentTypes = spreadTypes(argumentList) ?: return null
                resolved(FieldMLHostTypes.constructorResultType(pruned(klass), argumentTypes), call.range)
            }
            "hostCall" -> arguments.takeIf { it.size == 3 }?.let { (method, argumentList, target) ->
                val methodName = literalString(method) ?: return null
                val argumentTypes = spreadTypes(argumentList) ?: return null
                resolved(FieldMLHostTypes.methodResultType(pruned(target), methodName, argumentTypes), method.range)
            }
            "hostCall0" -> arguments.takeIf { it.size == 2 }?.let { (method, target) ->
                val methodName = literalString(method) ?: return null
                resolved(FieldMLHostTypes.methodResultType(pruned(target), methodName, emptyList()), method.range)
            }
            "hostCall1" -> arguments.takeIf { it.size == 3 }?.let { (method, argument, target) ->
                val methodName = literalString(method) ?: return null
                resolved(
                    FieldMLHostTypes.methodResultType(pruned(target), methodName, listOf(pruned(argument))),
                    method.range,
                )
            }
            "hostCall2" -> arguments.takeIf { it.size == 4 }?.let { (method, first, second, target) ->
                val methodName = literalString(method) ?: return null
                resolved(
                    FieldMLHostTypes.methodResultType(pruned(target), methodName, listOf(pruned(first), pruned(second))),
                    method.range,
                )
            }
            "hostExt" -> arguments.takeIf { it.size == 4 }?.let { (klass, method, argumentList, receiver) ->
                val methodName = literalString(method) ?: return null
                val argumentTypes = spreadTypes(argumentList) ?: return null
                resolved(
                    FieldMLHostTypes.staticMethodResultType(
                        pruned(klass),
                        methodName,
                        listOf(pruned(receiver)) + argumentTypes,
                    ),
                    method.range,
                )
            }
            "hostStaticCall" -> arguments.takeIf { it.size == 3 }?.let { (method, argumentList, klass) ->
                val methodName = literalString(method) ?: return null
                val argumentTypes = spreadTypes(argumentList) ?: return null
                resolved(
                    FieldMLHostTypes.staticMethodResultType(pruned(klass), methodName, argumentTypes),
                    method.range,
                )
            }
            "hostGet" -> arguments.takeIf { it.size == 2 }?.let { (member, target) ->
                val memberName = literalString(member) ?: return null
                resolved(FieldMLHostTypes.memberType(pruned(target), memberName), member.range)
            }
            // `hostSet` returns its receiver so it chains; the member itself is deliberately not
            // checked, because a Kotlin `var` with only a setter visible to reflection would
            // otherwise be reported as missing.
            "hostSet" -> arguments.takeIf { it.size == 3 }?.let { (_, _, target) -> pruned(target) }
            else -> null
        }
    }

    private fun resolved(resolution: FieldMLHostTypes.Resolution, range: SourceRange): Type? =
        when (resolution) {
            is FieldMLHostTypes.Resolution.Found -> resolution.type
            is FieldMLHostTypes.Resolution.Refused -> abort(resolution.message, range)
            FieldMLHostTypes.Resolution.Unknown -> null
        }

    /** `a.b.Img.Companion` as the class loader spells it: `a.b.Img$Companion`. */
    private fun nestedSpelling(className: String): String {
        if (!className.contains('.')) return className
        val owner = className.substringBeforeLast('.')
        return "$owner$${className.substringAfterLast('.')}"
    }

    /**
     * The head name and arguments of a chain of applications, left to right. FieldML is curried and
     * `|>` desugars to application, so `line |> hostCall "moveTo" args` and
     * `hostCall "moveTo" args line` reach here as the same spine.
     */
    private fun hostSpine(call: TExprCall): Pair<String, List<TExpr>>? {
        val arguments = mutableListOf<TExpr>()
        var current: TExpr = call
        while (current is TExprCall) {
            arguments += current.argument
            current = current.function
        }
        val head = current as? TExprVar ?: return null
        if (!head.name.startsWith("host")) return null
        return head.name to arguments.asReversed()
    }

    /**
     * The argument types behind the tuple-or-list spread the `host…` family uses, or null when the
     * arity cannot be known statically — a list whose elements were not written out, say, which has
     * one FieldML type but any number of JVM arguments.
     */
    private fun spreadTypes(expr: TExpr): List<Type>? {
        if (expr is TExprList) return expr.elements.map { pruned(it) }
        return when (val type = pruned(expr)) {
            is TTuple -> type.elements.map { prune(it) }
            TUnit -> emptyList()
            is TList -> null
            else -> listOf(type)
        }
    }

    private fun pruned(expr: TExpr): Type = prune(expr.type)

    private fun literalString(expr: TExpr): String? = (expr as? TExprString)?.value

    private fun inferTypeDecl(expr: ETypeDecl, env: TypeEnv): TExprTypeDecl {
        val parameterTypes = expr.declaration.parameters.associateWith { fresh() as Type }
        val resultType = TAdt(expr.declaration.name, expr.declaration.parameters.map { parameterTypes.getValue(it) })
        var extended = env.bindType(expr.declaration.name, expr.declaration.parameters.size)
        val constructors = expr.declaration.constructors.map { constructor ->
            val argumentTypes = constructor.arguments.map {
                resolveTypeRef(it, expr.declaration, parameterTypes, resultType, extended)
            }
            val constructorType = argumentTypes.asReversed().fold(resultType as Type) { acc, argumentType ->
                TFun(argumentType, acc)
            }
            val quantified = parameterTypes.values.mapNotNullTo(mutableSetOf()) { (it as? TVar)?.id }
            val scheme = Scheme(quantified, constructorType)
            val info = ConstructorInfo(constructor.name, constructor.arguments.size, scheme)
            extended = extended.bindConstructor(info)
            TypedConstructor(constructor.name, constructor.arguments.size, scheme)
        }
        val body = inferExpr(expr.body, extended)
        return TExprTypeDecl(expr.declaration, constructors, body, expr.range, body.type)
    }

    private fun resolveTypeRef(
        ref: TypeRef,
        declaration: TypeDecl,
        parameters: Map<String, Type>,
        currentType: Type,
        env: TypeEnv,
    ): Type =
        when (ref) {
            is TypeRefName -> when (ref.name) {
                "Int" -> TInt
                "Float" -> TFloat
                "Bool" -> TBool
                "String" -> TString
                "Unit" -> TUnit
                "Host" -> FieldMLTypePrelude.host
                "Vec2" -> FieldMLTypePrelude.vec2
                "Vec3" -> FieldMLTypePrelude.vec3
                "Vec4" -> FieldMLTypePrelude.vec4
                "FLine" -> FieldMLTypePrelude.fline
                declaration.name -> currentType
                in parameters.keys -> parameters.getValue(ref.name)
                else -> {
                    if (!env.hasType(ref.name)) abort("Unknown type '${ref.name}'", ref.range)
                    TAdt(ref.name, emptyList())
                }
            }
        }

    private fun inferLambda(parameters: List<String>, body: Expr, range: SourceRange, env: TypeEnv): TExprLambda {
        var localEnv = env
        val typedParams = parameters.map { parameter ->
            val type = fresh()
            localEnv = localEnv.bind(parameter, Scheme(emptySet(), type))
            parameter to type
        }
        val typedBody = inferExpr(body, localEnv)
        val lambdaType = typedParams.asReversed().fold(typedBody.type) { acc, (_, parameterType) ->
            TFun(parameterType, acc)
        }
        return TExprLambda(typedParams, typedBody, range, lambdaType)
    }

    private fun inferLet(expr: ELet, env: TypeEnv): TExprLet {
        // `let f x = ...` is recursive: bind f monomorphically while inferring its own body. A plain
        // `let x = ...` is not, so `let x = x + 1` keeps shadowing an outer binding.
        val recursive = expr.parameters.isNotEmpty()
        val valueExpr = if (!recursive) {
            inferExpr(expr.value, env)
        } else {
            val selfType = fresh()
            val selfEnv = env.bind(expr.name, Scheme(emptySet(), selfType))
            val lambda = inferLambda(expr.parameters, expr.value, expr.value.range, selfEnv)
            unify(selfType, lambda.type, expr.value.range)
            lambda
        }
        val scheme = generalize(env, valueExpr.type)
        val bodyEnv = env.bind(expr.name, scheme)
        val body = inferExpr(expr.body, bodyEnv)
        val parameters = when (valueExpr) {
            is TExprLambda -> valueExpr.parameters
            else -> emptyList()
        }
        return TExprLet(expr.name, parameters, valueExpr, body, expr.range, body.type, recursive)
    }

    private fun inferMatch(expr: EMatch, env: TypeEnv): TExprMatch {
        val scrutinee = inferExpr(expr.scrutinee, env)
        val resultType = fresh()
        val cases = expr.cases.map { case ->
            val (pattern, patternEnv) = inferPattern(case.pattern, scrutinee.type, env)
            val body = inferExpr(case.body, patternEnv)
            unify(body.type, resultType, case.body.range)
            TMatchCase(pattern, body)
        }
        return TExprMatch(scrutinee, cases, expr.range, resultType)
    }

    private fun inferPattern(pattern: Pattern, expected: Type, env: TypeEnv): Pair<TPattern, TypeEnv> =
        when (pattern) {
            is PWildcard -> TPWildcard(pattern.range, expected) to env
            is PVar -> TPVar(pattern.name, pattern.range, expected) to env.bind(pattern.name, Scheme(emptySet(), expected))
            is PInt -> {
                unify(expected, TInt, pattern.range)
                TPInt(pattern.value, pattern.range, TInt) to env
            }
            is PBool -> {
                unify(expected, TBool, pattern.range)
                TPBool(pattern.value, pattern.range, TBool) to env
            }
            is PString -> {
                unify(expected, TString, pattern.range)
                TPString(pattern.value, pattern.range, TString) to env
            }
            is PTuple -> {
                val elementTypes = pattern.elements.map { fresh() as Type }
                unify(expected, TTuple(elementTypes), pattern.range)
                var patternEnv = env
                val typedElements = pattern.elements.mapIndexed { index, element ->
                    val (typed, nextEnv) = inferPattern(element, elementTypes[index], patternEnv)
                    patternEnv = nextEnv
                    typed
                }
                TPTuple(typedElements, pattern.range, TTuple(elementTypes)) to patternEnv
            }
            is PList -> {
                val elementType = fresh()
                unify(expected, TList(elementType), pattern.range)
                var patternEnv = env
                val typedElements = pattern.elements.map { element ->
                    val (typed, nextEnv) = inferPattern(element, elementType, patternEnv)
                    patternEnv = nextEnv
                    typed
                }
                TPList(typedElements, pattern.range, TList(elementType)) to patternEnv
            }
            is PConstructor -> inferConstructorPattern(pattern, expected, env)
            is PCons -> {
                val elementType = fresh()
                unify(expected, TList(elementType), pattern.range)
                val (head, headEnv) = inferPattern(pattern.head, elementType, env)
                val (tail, tailEnv) = inferPattern(pattern.tail, TList(elementType), headEnv)
                TPCons(head, tail, pattern.range, TList(elementType)) to tailEnv
            }
        }

    private fun inferConstructorPattern(pattern: PConstructor, expected: Type, env: TypeEnv): Pair<TPattern, TypeEnv> {
        val constructor = env.constructor(pattern.name) ?: abort("Unknown constructor '${pattern.name}'", pattern.range)
        if (constructor.arity != pattern.arguments.size) {
            abort(
                "Constructor '${pattern.name}' expects ${constructor.arity} pattern argument(s), found ${pattern.arguments.size}",
                pattern.range,
            )
        }
        val argumentTypes = mutableListOf<Type>()
        var cursor = instantiate(constructor.scheme)
        repeat(constructor.arity) {
            val function = prune(cursor) as? TFun
                ?: abort("Constructor '${pattern.name}' is not a function", pattern.range)
            argumentTypes += function.from
            cursor = function.to
        }
        unify(cursor, expected, pattern.range)
        var patternEnv = env
        val typedArguments = pattern.arguments.mapIndexed { index, argument ->
            val (typed, nextEnv) = inferPattern(argument, argumentTypes[index], patternEnv)
            patternEnv = nextEnv
            typed
        }
        return TPConstructor(pattern.name, typedArguments, pattern.range, cursor) to patternEnv
    }

    private fun inferBinary(expr: EBinary, env: TypeEnv): TExprBinary {
        val left = inferExpr(expr.left, env)
        val right = inferExpr(expr.right, env)
        val resultType = when (expr.op) {
            BinaryOp.ADD, BinaryOp.SUB -> {
                val vector = vectorOperand(left.type, right.type)
                if (vector != null) {
                    // Vectors add and subtract component by component, so both sides must match.
                    unify(left.type, right.type, expr.range)
                    vector
                } else {
                    unify(left.type, right.type, expr.range)
                    requireNumeric(left.type, expr.range)
                    left.type
                }
            }
            BinaryOp.MUL -> {
                val leftVector = FieldMLTypePrelude.isVectorType(prune(left.type))
                val rightVector = FieldMLTypePrelude.isVectorType(prune(right.type))
                when {
                    // Vector times vector multiplies component by component; vector times a Float scales.
                    leftVector && rightVector -> {
                        unify(left.type, right.type, expr.range)
                        prune(left.type)
                    }
                    leftVector -> {
                        unify(right.type, TFloat, expr.right.range)
                        prune(left.type)
                    }
                    rightVector -> {
                        unify(left.type, TFloat, expr.left.range)
                        prune(right.type)
                    }
                    else -> {
                        unify(left.type, right.type, expr.range)
                        requireNumeric(left.type, expr.range)
                        left.type
                    }
                }
            }
            BinaryOp.DIV -> {
                if (FieldMLTypePrelude.isVectorType(prune(left.type))) {
                    unify(right.type, TFloat, expr.right.range)
                    prune(left.type)
                } else {
                    unify(left.type, TFloat, expr.left.range)
                    unify(right.type, TFloat, expr.right.range)
                    TFloat
                }
            }
            BinaryOp.CONCAT -> {
                unify(left.type, TString, expr.left.range)
                unify(right.type, TString, expr.right.range)
                TString
            }
            BinaryOp.CONS -> {
                val element = fresh()
                unify(left.type, element, expr.left.range)
                unify(right.type, TList(element), expr.right.range)
                TList(element)
            }
            BinaryOp.LT, BinaryOp.LTE, BinaryOp.GT, BinaryOp.GTE -> {
                unify(left.type, right.type, expr.range)
                requireNumeric(left.type, expr.range)
                TBool
            }
            BinaryOp.EQ, BinaryOp.NEQ -> {
                unify(left.type, right.type, expr.range)
                requireEquality(left.type, expr.range)
                TBool
            }
            BinaryOp.AND, BinaryOp.OR -> {
                unify(left.type, TBool, expr.left.range)
                unify(right.type, TBool, expr.right.range)
                TBool
            }
        }
        return TExprBinary(expr.op, left, right, expr.range, resultType)
    }

    /** The vector type involved in an operand pair, or null when neither side is a vector. */
    private fun vectorOperand(left: Type, right: Type): Type? {
        val prunedLeft = prune(left)
        if (FieldMLTypePrelude.isVectorType(prunedLeft)) return prunedLeft
        val prunedRight = prune(right)
        return if (FieldMLTypePrelude.isVectorType(prunedRight)) prunedRight else null
    }

    private fun instantiate(scheme: Scheme): Type {
        val replacements = scheme.quantified.associateWith { fresh() as Type }
        for (numeric in scheme.numeric) {
            val replacement = replacements[numeric]
            if (replacement is TVar) numericVariables += replacement.id
        }
        for (vector in scheme.vector) {
            val replacement = replacements[vector]
            if (replacement is TVar) vectorVariables += replacement.id
        }
        fun walk(type: Type): Type =
            when (val pruned = prune(type)) {
                is TVar -> replacements[pruned.id] ?: pruned
                is TFun -> TFun(walk(pruned.from), walk(pruned.to))
                is TRecord -> TRecord(pruned.fields.mapValues { walk(it.value) })
                is TTuple -> TTuple(pruned.elements.map(::walk))
                is TList -> TList(walk(pruned.element))
                is TAdt -> TAdt(pruned.name, pruned.arguments.map(::walk))
                is THost -> pruned
                else -> pruned
            }
        return walk(scheme.body)
    }

    private fun generalize(env: TypeEnv, type: Type): Scheme {
        val quantified = freeVars(type) - env.freeVars()
        return Scheme(
            quantified,
            prune(type),
            numeric = quantified intersect numericVariables,
            vector = quantified intersect vectorVariables,
        )
    }

    private fun requireNumeric(type: Type, range: SourceRange) {
        when (val pruned = prune(type)) {
            TInt, TFloat -> Unit
            is TVar -> numericVariables += pruned.id
            // An opaque `Host` is dynamic in every other position, so it has to be dynamic here too;
            // refusing it would put a cast in front of every use of an erased host result.
            else -> if (!isDynamicHost(pruned)) {
                abort("Expected Int or Float, found ${TypePrinter().print(normalize(pruned))}", range)
            }
        }
    }

    private fun requireVector(type: Type, range: SourceRange) {
        val pruned = prune(type)
        when {
            FieldMLTypePrelude.isVectorType(pruned) -> Unit
            pruned is TVar -> vectorVariables += pruned.id
            isDynamicHost(pruned) -> Unit
            else -> abort("Expected Vec2, Vec3 or Vec4, found ${TypePrinter().print(normalize(pruned))}", range)
        }
    }

    private fun requireEquality(type: Type, range: SourceRange) {
        when (val pruned = prune(type)) {
            TInt, TFloat, TBool, TString -> Unit
            is TVar -> Unit
            else -> if (!isDynamicHost(pruned)) {
                abort("Equality is not defined for ${TypePrinter().print(normalize(pruned))}", range)
            }
        }
    }

    private fun isDynamicHost(type: Type): Boolean = type is THost && type.name == FieldMLHostTypes.OPAQUE

    private fun unify(left: Type, right: Type, range: SourceRange) {
        val a = prune(left)
        val b = prune(right)
        if (a == b) return
        when {
            a is TVar -> bind(a, b, range)
            b is TVar -> bind(b, a, range)
            a is TFun && b is TFun -> {
                unify(a.from, b.from, range)
                unify(a.to, b.to, range)
            }
            a is TRecord && b is TRecord -> unifyRecords(a, b, range)
            a is TTuple && b is TTuple && a.elements.size == b.elements.size -> {
                a.elements.zip(b.elements).forEach { (leftElement, rightElement) -> unify(leftElement, rightElement, range) }
            }
            a is TTuple && b is TTuple -> abort("Tuple arity mismatch: ${a.elements.size} is not ${b.elements.size}", range)
            a is TList && b is TList -> unify(a.element, b.element, range)
            a is TAdt && b is TAdt && a.name == b.name && a.arguments.size == b.arguments.size -> {
                a.arguments.zip(b.arguments).forEach { (leftArg, rightArg) -> unify(leftArg, rightArg, range) }
            }
            a is THost && b is THost && a.name == b.name -> Unit
            // `Host` is FieldML's one dynamic type: the type of a JVM value whose class reflection
            // could not pin down. Refusing to unify it would mean that every erased host result had
            // to be threaded through a cast before it could be used at all, which is the state this
            // bridge exists to end. Everything *else* about host types is checked, and a value whose
            // class is known keeps its own name, so this escape is confined to the genuinely unknown.
            a is THost && a.name == FieldMLHostTypes.OPAQUE -> Unit
            b is THost && b.name == FieldMLHostTypes.OPAQUE -> Unit
            else -> abort(
                "Type mismatch: ${TypePrinter().print(normalize(a))} is not ${TypePrinter().print(normalize(b))}",
                range,
            )
        }
    }

    private fun unifyRecords(left: TRecord, right: TRecord, range: SourceRange) {
        val missingInLeft = right.fields.keys - left.fields.keys
        val missingInRight = left.fields.keys - right.fields.keys
        if (missingInLeft.isNotEmpty()) abort("Record is missing field '${missingInLeft.first()}'", range)
        if (missingInRight.isNotEmpty()) abort("Record has unexpected field '${missingInRight.first()}'", range)
        for (field in left.fields.keys) unify(left.fields.getValue(field), right.fields.getValue(field), range)
    }

    private fun bind(variable: TVar, type: Type, range: SourceRange) {
        val target = prune(type)
        if (target == variable) return
        if (occurs(variable.id, target)) abort("Infinite type involving ${TypePrinter().print(variable)}", range)
        if (variable.id in numericVariables) requireNumeric(target, range)
        if (variable.id in vectorVariables) requireVector(target, range)
        substitution[variable.id] = target
    }

    private fun prune(type: Type): Type {
        if (type !is TVar) return type
        val replacement = substitution[type.id] ?: return type
        val pruned = prune(replacement)
        substitution[type.id] = pruned
        return pruned
    }

    private fun occurs(variableId: Int, type: Type): Boolean =
        when (val pruned = prune(type)) {
            is TVar -> pruned.id == variableId
            is TFun -> occurs(variableId, pruned.from) || occurs(variableId, pruned.to)
            is TRecord -> pruned.fields.values.any { occurs(variableId, it) }
            is TTuple -> pruned.elements.any { occurs(variableId, it) }
            is TList -> occurs(variableId, pruned.element)
            is TAdt -> pruned.arguments.any { occurs(variableId, it) }
            is THost -> false
            else -> false
        }

    private fun normalize(type: Type): Type =
        when (val pruned = prune(type)) {
            is TFun -> TFun(normalize(pruned.from), normalize(pruned.to))
            is TRecord -> TRecord(pruned.fields.mapValues { normalize(it.value) })
            is TTuple -> TTuple(pruned.elements.map(::normalize))
            is TList -> TList(normalize(pruned.element))
            is TAdt -> TAdt(pruned.name, pruned.arguments.map(::normalize))
            is THost -> pruned
            else -> pruned
        }

    private fun finalize(expr: TExpr): TExpr {
        fun type(type: Type): Type = defaultedType(type)
        return when (expr) {
            is TExprInt -> expr.copy(type = type(expr.type))
            is TExprFloat -> expr.copy(type = type(expr.type))
            is TExprBool -> expr.copy(type = type(expr.type))
            is TExprString -> expr.copy(type = type(expr.type))
            is TExprVar -> expr.copy(type = type(expr.type))
            is TExprTuple -> expr.copy(elements = expr.elements.map(::finalize), type = type(expr.type))
            is TExprList -> expr.copy(elements = expr.elements.map(::finalize), type = type(expr.type))
            is TExprTypeDecl -> expr.copy(body = finalize(expr.body), type = type(expr.type))
            is TExprLet -> expr.copy(
                parameters = expr.parameters.map { it.first to type(it.second) },
                value = finalize(expr.value),
                body = finalize(expr.body),
                type = type(expr.type),
            )
            is TExprLambda -> expr.copy(
                parameters = expr.parameters.map { it.first to type(it.second) },
                body = finalize(expr.body),
                type = type(expr.type),
            )
            is TExprCall -> expr.copy(finalize(expr.function), finalize(expr.argument), type = type(expr.type))
            is TExprIf -> expr.copy(finalize(expr.condition), finalize(expr.thenBranch), finalize(expr.elseBranch), type = type(expr.type))
            is TExprMatch -> expr.copy(
                scrutinee = finalize(expr.scrutinee),
                cases = expr.cases.map { TMatchCase(finalizePattern(it.pattern), finalize(it.body)) },
                type = type(expr.type),
            )
            is TExprRecord -> expr.copy(fields = expr.fields.map { it.first to finalize(it.second) }, type = type(expr.type))
            is TExprField -> expr.copy(target = finalize(expr.target), type = type(expr.type))
            is TExprBinary -> expr.copy(left = finalize(expr.left), right = finalize(expr.right), type = type(expr.type))
            is TExprNegate -> expr.copy(value = finalize(expr.value), type = type(expr.type))
        }
    }

    private fun finalizePattern(pattern: TPattern): TPattern {
        fun type(type: Type): Type = defaultedType(type)
        return when (pattern) {
            is TPWildcard -> pattern.copy(type = type(pattern.type))
            is TPVar -> pattern.copy(type = type(pattern.type))
            is TPInt -> pattern.copy(type = type(pattern.type))
            is TPBool -> pattern.copy(type = type(pattern.type))
            is TPString -> pattern.copy(type = type(pattern.type))
            is TPTuple -> pattern.copy(elements = pattern.elements.map(::finalizePattern), type = type(pattern.type))
            is TPList -> pattern.copy(elements = pattern.elements.map(::finalizePattern), type = type(pattern.type))
            is TPConstructor -> pattern.copy(arguments = pattern.arguments.map(::finalizePattern), type = type(pattern.type))
            is TPCons -> pattern.copy(
                head = finalizePattern(pattern.head),
                tail = finalizePattern(pattern.tail),
                type = type(pattern.type),
            )
        }
    }

    /**
     * Normalizes a type for reporting, defaulting any still-unresolved constrained variable to the
     * concrete type its constraint implies: numeric variables become `Float`, vector ones `Vec2`.
     */
    private fun defaultedType(type: Type): Type {
        val normalized = normalize(type)
        if (normalized !is TVar) return normalized
        return when (normalized.id) {
            in numericVariables -> TFloat
            in vectorVariables -> FieldMLTypePrelude.vec2
            else -> normalized
        }
    }

    private fun fresh(): TVar = TVar(nextTypeVar++)

    private fun freeVars(type: Type): Set<Int> =
        when (val pruned = prune(type)) {
            is TVar -> setOf(pruned.id)
            is TFun -> freeVars(pruned.from) + freeVars(pruned.to)
            is TRecord -> pruned.fields.values.flatMapTo(mutableSetOf()) { freeVars(it) }
            is TTuple -> pruned.elements.flatMapTo(mutableSetOf()) { freeVars(it) }
            is TList -> freeVars(pruned.element)
            is TAdt -> pruned.arguments.flatMapTo(mutableSetOf()) { freeVars(it) }
            is THost -> emptySet()
            else -> emptySet()
        }

    private fun abort(message: String, range: SourceRange): Nothing {
        throw CompileAbort(Diagnostic(Severity.Error, message, range))
    }

    private data class ConstructorInfo(
        val name: String,
        val arity: Int,
        val scheme: Scheme,
    )

    private class TypeEnv(
        private val values: Map<String, Scheme> = emptyMap(),
        private val constructors: Map<String, ConstructorInfo> = emptyMap(),
        private val types: Map<String, Int> = emptyMap(),
    ) {
        operator fun get(name: String): Scheme? = values[name]
        fun bind(name: String, scheme: Scheme): TypeEnv = TypeEnv(values + (name to scheme), constructors, types)
        fun bindConstructor(info: ConstructorInfo): TypeEnv =
            TypeEnv(values + (info.name to info.scheme), constructors + (info.name to info), types)

        fun bindType(name: String, arity: Int): TypeEnv = TypeEnv(values, constructors, types + (name to arity))
        fun hasType(name: String): Boolean = types.containsKey(name)
        fun constructor(name: String): ConstructorInfo? = constructors[name]

        fun freeVars(): Set<Int> =
            values.values.flatMapTo(mutableSetOf()) { scheme ->
                freeVars(scheme.body) - scheme.quantified
            }

        private fun freeVars(type: Type): Set<Int> =
            when (type) {
                is TVar -> setOf(type.id)
                is TFun -> freeVars(type.from) + freeVars(type.to)
                is TRecord -> type.fields.values.flatMapTo(mutableSetOf()) { freeVars(it) }
                is TTuple -> type.elements.flatMapTo(mutableSetOf()) { freeVars(it) }
                is TList -> freeVars(type.element)
                is TAdt -> type.arguments.flatMapTo(mutableSetOf()) { freeVars(it) }
                is THost -> emptySet()
                else -> emptySet()
            }
    }
}
