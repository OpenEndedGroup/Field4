package afield.ml

import afield.Box
import afield.HasOutput
import afield.ai.KotlinSourceIndex
import afield.graphics.FLine
import afield.graphics.lines
import afield.ml.core.FieldMLHostBridge
import afield.ml.core.FieldMLValue
import afield.ml.core.applyFieldMLFunction
import afield.ml.core.fieldMLValueToHost
import afield.ml.core.hostToFieldMLValue
import afield.ml.core.renderFieldMLValue
import afield.ml.core.vectorComponents
import afield.ml.core.vectorValue
import afield.ml.type.FieldMLHostTypes
import afield.ml.type.THost
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.inheritedPropertyRaw
import afield.stdlib.pointSize
import afield.stdlib.pointed
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile

object FieldMLRuntimePrelude {
    fun values(output: HasOutput? = null, box: Box? = null): Map<String, FieldMLValue> =
        linkedMapOf(
            "Unit" to FieldMLValue.UnitValue,
            "thisBox" to FieldMLValue.HostValue(box),
            "print" to native1("print") { value ->
                output?.print(renderFieldMLValue(value))
                value
            },
            "println" to native1("println") { value ->
                output?.println(renderFieldMLValue(value))
                value
            },
            "display" to native2("display") { channel, value ->
                output?.display(channel.asString("display channel"), outputPayload(value))
                value
            },
            "show" to native1("show") { value -> FieldMLValue.StringValue(renderFieldMLValue(value)) },
            "tap" to native2("tap") { effect, value ->
                applyFieldMLFunction(effect, value)
                value
            },

            "vec2" to native2("vec2") { x, y -> FieldMLValue.HostValue(Vector2f(x.asFloat("x"), y.asFloat("y"))) },
            "vec3" to native3("vec3") { x, y, z ->
                FieldMLValue.HostValue(Vector3f(x.asFloat("x"), y.asFloat("y"), z.asFloat("z")))
            },
            "vec4" to native4("vec4") { x, y, z, w ->
                FieldMLValue.HostValue(Vector4f(x.asFloat("x"), y.asFloat("y"), z.asFloat("z"), w.asFloat("w")))
            },

            "fline" to FieldMLValue.FactoryValue("fline") { FieldMLValue.HostValue(FLine()) },
            "moveTo" to native3("moveTo") { x, y, line ->
                FieldMLValue.HostValue(line.asFLine("moveTo").moveTo(x.asDouble("x"), y.asDouble("y")))
            },
            "moveTo3" to native4("moveTo3") { x, y, z, line ->
                FieldMLValue.HostValue(line.asFLine("moveTo3").moveTo(x.asDouble("x"), y.asDouble("y"), z.asDouble("z")))
            },
            "lineTo" to native3("lineTo") { x, y, line ->
                FieldMLValue.HostValue(line.asFLine("lineTo").lineTo(x.asDouble("x"), y.asDouble("y")))
            },
            "lineTo3" to native4("lineTo3") { x, y, z, line ->
                FieldMLValue.HostValue(line.asFLine("lineTo3").lineTo(x.asDouble("x"), y.asDouble("y"), z.asDouble("z")))
            },
            "moveToV" to native2("moveToV") { point, line ->
                FieldMLValue.HostValue(line.asFLine("moveToV").moveTo(point.asVec2("point")))
            },
            "lineToV" to native2("lineToV") { point, line ->
                FieldMLValue.HostValue(line.asFLine("lineToV").lineTo(point.asVec2("point")))
            },
            "cubicTo" to native7("cubicTo") { x0, y0, x1, y1, x, y, line ->
                FieldMLValue.HostValue(
                    line.asFLine("cubicTo").cubicTo(
                        x0.asDouble("x0"),
                        y0.asDouble("y0"),
                        x1.asDouble("x1"),
                        y1.asDouble("y1"),
                        x.asDouble("x"),
                        y.asDouble("y"),
                    ),
                )
            },
            "close" to native1("close") { line -> FieldMLValue.HostValue(line.asFLine("close").close()) },
            "thickness" to native2("thickness") { amount, line ->
                FieldMLValue.HostValue(line.asFLine("thickness").also { it.thickness = amount.asDouble("amount") })
            },
            "color" to native2("color") { color, line ->
                FieldMLValue.HostValue(line.asFLine("color").also { it.color = Vector4f(color.asVec4("color")) })
            },
            "filled" to native2("filled") { enabled, line ->
                FieldMLValue.HostValue(line.asFLine("filled").also { it.filled = enabled.asBool("enabled") })
            },
            "stroked" to native2("stroked") { enabled, line ->
                FieldMLValue.HostValue(line.asFLine("stroked").also { it.stroked = enabled.asBool("enabled") })
            },
            "pointed" to native2("pointed") { enabled, line ->
                FieldMLValue.HostValue(line.asFLine("pointed").also { it.pointed = enabled.asBool("enabled") })
            },
            "pointSize" to native2("pointSize") { amount, line ->
                FieldMLValue.HostValue(line.asFLine("pointSize").also { it.pointSize = amount.asDouble("amount") })
            },

            "pi" to FieldMLValue.FloatValue(Math.PI),
            "tau" to FieldMLValue.FloatValue(Math.PI * 2),
            "sin" to float1("sin", Math::sin),
            "cos" to float1("cos", Math::cos),
            "tan" to float1("tan", Math::tan),
            "asin" to float1("asin", Math::asin),
            "acos" to float1("acos", Math::acos),
            "atan" to float1("atan", Math::atan),
            "sqrt" to float1("sqrt", Math::sqrt),
            "exp" to float1("exp", Math::exp),
            "log" to float1("log", Math::log),
            "floor" to float1("floor", Math::floor),
            "ceil" to float1("ceil", Math::ceil),
            "round" to float1("round", { value -> Math.round(value).toDouble() }),
            "atan2" to native2("atan2") { y, x ->
                FieldMLValue.FloatValue(Math.atan2(y.asDouble("y"), x.asDouble("x")))
            },
            "pow" to native2("pow") { exponent, value ->
                FieldMLValue.FloatValue(Math.pow(value.asDouble("value"), exponent.asDouble("exponent")))
            },
            "fmod" to native2("fmod") { divisor, value ->
                FieldMLValue.FloatValue(value.asDouble("value").mod(divisor.asDouble("divisor")))
            },
            "mod" to native2("mod") { divisor, value ->
                FieldMLValue.IntValue(value.asLong("value").mod(divisor.asLong("divisor")))
            },
            "idiv" to native2("idiv") { divisor, value ->
                FieldMLValue.IntValue(value.asLong("value") / divisor.asLong("divisor"))
            },
            "lerp" to native3("lerp") { from, to, amount ->
                val t = amount.asDouble("amount")
                FieldMLValue.FloatValue(from.asDouble("from") * (1 - t) + to.asDouble("to") * t)
            },
            "abs" to native1("abs") { value -> value.mapNumber("abs", Math::abs, Math::abs) },
            "sign" to native1("sign") { value ->
                value.mapNumber("sign", { it.compareTo(0L).toLong() }, { Math.signum(it) })
            },
            "min" to native2("min") { a, b -> numeric2("min", a, b, Math::min, Math::min) },
            "max" to native2("max") { a, b -> numeric2("max", a, b, Math::max, Math::max) },
            "clamp" to native3("clamp") { low, high, value ->
                numeric2("clamp", numeric2("clamp", value, low, Math::max, Math::max), high, Math::min, Math::min)
            },
            "toFloat" to native1("toFloat") { value -> FieldMLValue.FloatValue(value.asLong("value").toDouble()) },
            "toInt" to native1("toInt") { value -> FieldMLValue.IntValue(value.asDouble("value").toLong()) },
            "not" to native1("not") { value -> FieldMLValue.BoolValue(!value.asBool("value")) },

            "range" to native2("range") { start, end ->
                val from = start.asLong("start")
                val until = end.asLong("end")
                FieldMLValue.ListValue((from until until).map { FieldMLValue.IntValue(it) })
            },
            "linspace" to native3("linspace") { start, end, count ->
                val from = start.asDouble("start")
                val to = end.asDouble("end")
                val steps = count.asLong("count").toInt()
                if (steps <= 0) {
                    FieldMLValue.ListValue(emptyList())
                } else if (steps == 1) {
                    FieldMLValue.ListValue(listOf(FieldMLValue.FloatValue(from)))
                } else {
                    FieldMLValue.ListValue(
                        (0 until steps).map { index ->
                            FieldMLValue.FloatValue(from + (to - from) * index / (steps - 1))
                        },
                    )
                }
            },
            "tabulate" to native2("tabulate") { function, count ->
                FieldMLValue.ListValue(
                    (0 until count.asLong("count").toInt()).map { index ->
                        applyFieldMLFunction(function, FieldMLValue.IntValue(index.toLong()))
                    },
                )
            },
            "map" to native2("map") { function, list ->
                FieldMLValue.ListValue(list.asList("map").map { applyFieldMLFunction(function, it) })
            },
            "mapIndexed" to native2("mapIndexed") { function, list ->
                FieldMLValue.ListValue(
                    list.asList("mapIndexed").mapIndexed { index, element ->
                        applyFieldMLFunction(applyFieldMLFunction(function, FieldMLValue.IntValue(index.toLong())), element)
                    },
                )
            },
            "filter" to native2("filter") { predicate, list ->
                FieldMLValue.ListValue(
                    list.asList("filter").filter { element ->
                        (applyFieldMLFunction(predicate, element) as? FieldMLValue.BoolValue)?.value
                            ?: error("filter expects a function returning Bool")
                    },
                )
            },
            "fold" to native3("fold") { function, initial, list ->
                list.asList("fold").fold(initial) { accumulator, element ->
                    applyFieldMLFunction(applyFieldMLFunction(function, accumulator), element)
                }
            },
            "length" to native1("length") { list -> FieldMLValue.IntValue(list.asList("length").size.toLong()) },
            "isEmpty" to native1("isEmpty") { list -> FieldMLValue.BoolValue(list.asList("isEmpty").isEmpty()) },
            "head" to native1("head") { list ->
                list.asList("head").firstOrNull() ?: error("head of an empty list")
            },
            "tail" to native1("tail") { list ->
                val elements = list.asList("tail")
                if (elements.isEmpty()) error("tail of an empty list")
                FieldMLValue.ListValue(elements.drop(1))
            },
            "nth" to native2("nth") { index, list ->
                val elements = list.asList("nth")
                val at = index.asLong("index").toInt()
                elements.getOrNull(at) ?: error("nth $at is outside a list of ${elements.size}")
            },
            "take" to native2("take") { count, list ->
                FieldMLValue.ListValue(list.asList("take").take(count.asLong("count").toInt().coerceAtLeast(0)))
            },
            "drop" to native2("drop") { count, list ->
                FieldMLValue.ListValue(list.asList("drop").drop(count.asLong("count").toInt().coerceAtLeast(0)))
            },
            "reverse" to native1("reverse") { list -> FieldMLValue.ListValue(list.asList("reverse").reversed()) },
            "append" to native2("append") { suffix, list ->
                FieldMLValue.ListValue(list.asList("append") + suffix.asList("suffix"))
            },
            "concat" to native1("concat") { list ->
                FieldMLValue.ListValue(list.asList("concat").flatMap { it.asList("concat element") })
            },
            "zip" to native2("zip") { other, list ->
                val right = other.asList("zip other")
                FieldMLValue.ListValue(
                    list.asList("zip").zip(right).map { (a, b) -> FieldMLValue.TupleValue(listOf(a, b)) },
                )
            },
            "sortBy" to native2("sortBy") { key, list ->
                FieldMLValue.ListValue(
                    list.asList("sortBy").sortedBy { element ->
                        applyFieldMLFunction(key, element).asDouble("sortBy key")
                    },
                )
            },
            "sum" to native1("sum") { list ->
                FieldMLValue.FloatValue(list.asList("sum").sumOf { it.asDouble("sum element") })
            },

            "dot" to native2("dot") { a, b ->
                val left = a.asVector("dot")
                val right = b.asVector("dot")
                requireSameWidth("dot", left, right)
                FieldMLValue.FloatValue(left.indices.sumOf { (left[it] * right[it]).toDouble() })
            },
            "vlength" to native1("vlength") { value ->
                FieldMLValue.FloatValue(magnitude(value.asVector("vlength")))
            },
            "distance" to native2("distance") { a, b ->
                val left = a.asVector("distance")
                val right = b.asVector("distance")
                requireSameWidth("distance", left, right)
                FieldMLValue.FloatValue(magnitude(FloatArray(left.size) { left[it] - right[it] }))
            },
            "normalize" to native1("normalize") { value ->
                val parts = value.asVector("normalize")
                val scale = magnitude(parts)
                if (scale == 0.0) value else vectorValue(FloatArray(parts.size) { (parts[it] / scale).toFloat() })
            },
            "mix" to native3("mix") { a, b, amount ->
                val left = a.asVector("mix")
                val right = b.asVector("mix")
                requireSameWidth("mix", left, right)
                val t = amount.asFloat("amount")
                vectorValue(FloatArray(left.size) { left[it] * (1 - t) + right[it] * t })
            },
            "cross" to native2("cross") { a, b ->
                val left = a.asVector("cross")
                val right = b.asVector("cross")
                if (left.size != 3 || right.size != 3) error("cross expects two Vec3 values")
                vectorValue(
                    floatArrayOf(
                        left[1] * right[2] - left[2] * right[1],
                        left[2] * right[0] - left[0] * right[2],
                        left[0] * right[1] - left[1] * right[0],
                    ),
                )
            },
            "polar" to native2("polar") { angle, radius ->
                val theta = angle.asDouble("angle")
                val r = radius.asDouble("radius")
                FieldMLValue.HostValue(Vector2f((Math.cos(theta) * r).toFloat(), (Math.sin(theta) * r).toFloat()))
            },

            "draw" to native2("draw") { channel, line ->
                val target = line.asFLine("draw")
                requireBox(box, "draw").lines(listOf(target), channel = channel.asString("channel"))
                FieldMLValue.HostValue(target)
            },
            "drawSized" to native4("drawSized") { channel, width, height, line ->
                val target = line.asFLine("drawSized")
                requireBox(box, "drawSized").lines(
                    listOf(target),
                    channel = channel.asString("channel"),
                    w = width.asDouble("width"),
                    h = height.asDouble("height"),
                )
                FieldMLValue.HostValue(target)
            },
            "animate" to native1("animate") { function -> FieldMLValue.HostValue(frameSequence(function, null)) },
            "animateFor" to native2("animateFor") { duration, function ->
                FieldMLValue.HostValue(frameSequence(function, duration.asDouble("duration")))
            },

            "hostInput" to FieldMLValue.HostValue(HostInputPlaceholder),
            // JVM `null`, which FieldML has no literal for: `Unit` is a value, not an absence, and
            // Kotlin default arguments are invisible to a reflective call, so an optional
            // parameter has to be spelled out.
            "hostNull" to FieldMLValue.HostValue(null),
            "hostClass" to native1("hostClass") { name -> FieldMLValue.HostValue(hostClass(name.asString("class name"))) },
            // A **record** of arguments means a named call, which is also the only way to let
            // Kotlin fill in a default (FieldMLHostBridge.callMethodNamed says why the two are one
            // feature). A tuple or a list still spreads positionally, and `hostCall1` — which never
            // spreads at all — is the way to pass one record *as* one argument.
            "hostNew" to native2("hostNew") { arguments, klass ->
                if (arguments is FieldMLValue.RecordValue) {
                    FieldMLHostBridge.constructNamed(klass.asClass("hostNew"), arguments.hostFields())
                } else {
                    FieldMLHostBridge.construct(klass.asClass("hostNew"), FieldMLHostBridge.spreadArguments(arguments))
                }
            },
            "hostNew0" to native1("hostNew0") { klass ->
                FieldMLHostBridge.construct(klass.asClass("hostNew0"), emptyList())
            },
            "hostCall" to native3("hostCall") { name, arguments, target ->
                if (arguments is FieldMLValue.RecordValue) {
                    FieldMLHostBridge.callMethodNamed(
                        name.asString("method name"),
                        target.asHost("target"),
                        arguments.hostFields(),
                    )
                } else {
                    FieldMLHostBridge.callMethod(
                        name.asString("method name"),
                        target.asHost("target"),
                        FieldMLHostBridge.spreadArguments(arguments),
                    )
                }
            },
            "hostCall0" to native2("hostCall0") { name, target ->
                FieldMLHostBridge.callMethod(name.asString("method name"), target.asHost("target"), emptyList())
            },
            "hostCall1" to native3("hostCall1") { name, argument, target ->
                FieldMLHostBridge.callMethod(
                    name.asString("method name"),
                    target.asHost("target"),
                    listOf(fieldMLValueToHost(argument)),
                )
            },
            "hostCall2" to native4("hostCall2") { name, a, b, target ->
                FieldMLHostBridge.callMethod(
                    name.asString("method name"),
                    target.asHost("target"),
                    listOf(fieldMLValueToHost(a), fieldMLValueToHost(b)),
                )
            },
            "hostStaticCall" to native3("hostStaticCall") { name, arguments, klass ->
                if (arguments is FieldMLValue.RecordValue) {
                    FieldMLHostBridge.callStaticMethodNamed(
                        name.asString("method name"),
                        klass.asClass("hostStaticCall class"),
                        extensionReceiver = null,
                        arguments = arguments.hostFields(),
                    )
                } else {
                    FieldMLHostBridge.callStaticMethod(
                        name.asString("method name"),
                        klass.asClass("hostStaticCall class"),
                        FieldMLHostBridge.spreadArguments(arguments),
                    )
                }
            },
            // Kotlin extensions compile to statics whose first parameter is the receiver, and Field's
            // Kotlin surface is mostly extensions — so the pipe-shaped form of a static call, with the
            // piped value going in front of the written arguments, is the one that actually gets used.
            "hostExt" to native4("hostExt") { klass, name, arguments, receiver ->
                if (arguments is FieldMLValue.RecordValue) {
                    // The piped value is the *extension receiver*, which has no name Kotlin will
                    // accept in a `callBy` map, so it is bound by kind rather than by name.
                    FieldMLHostBridge.callStaticMethodNamed(
                        name.asString("method name"),
                        klass.asClass("hostExt class"),
                        extensionReceiver = fieldMLValueToHost(receiver),
                        arguments = arguments.hostFields(),
                    )
                } else {
                FieldMLHostBridge.callStaticMethod(
                    name.asString("method name"),
                    klass.asClass("hostExt class"),
                    listOf(fieldMLValueToHost(receiver)) + FieldMLHostBridge.spreadArguments(arguments),
                )
                }
            },
            "hostStaticTap" to native4("hostStaticTap") { name, arguments, klass, input ->
                FieldMLHostBridge.callStaticMethod(
                    name.asString("method name"),
                    klass.asClass("hostStaticTap class"),
                    FieldMLHostBridge.spreadArguments(arguments.substituteHostInput(input)),
                )
                input
            },
            "hostTap" to native3("hostTap") { target, arguments, input ->
                callIndexedHostTap(target.asString("hostTap target"), arguments.asRecord("hostTap arguments"), input, box)
                input
            },
            "hostGet" to native2("hostGet") { name, target ->
                FieldMLHostBridge.readMember(name.asString("member name"), target.asHost("target"))
            },
            "hostSet" to native3("hostSet") { name, value, target ->
                val host = target.asHost("target")
                FieldMLHostBridge.writeMember(name.asString("member name"), host, fieldMLValueToHost(value))
                FieldMLValue.HostValue(host)
            },

            // Erasure escapes. A member reflection can only see as `Object` stays typed `Host`, which
            // is honest but unusable downstream; these are the author saying what it really is.
            //
            // `hostAs` is the general one, and it is also how FieldML *names* a JVM class: a value
            // arriving from a generic signature, or a parameter of a lambda the JVM handed us, gets a
            // real type from here and everything after it — member access, completion, arithmetic —
            // works as if reflection had known all along. The cast is checked, so a wrong name is a
            // run-time error at the point of the claim rather than a confusing failure later.
            "hostAs" to native2("hostAs") { name, value ->
                val requested = name.asString("class name")
                val klass = FieldMLHostTypes.jvmClassOf(THost(requested))
                    ?: error("hostAs does not know the class '$requested'")
                val host = value.asHost("hostAs value")
                if (!klass.isInstance(host)) {
                    error("hostAs \"$requested\" was given a ${host.javaClass.name}")
                }
                FieldMLValue.HostValue(host)
            },
            "hostAsInt" to native1("hostAsInt") { value -> FieldMLValue.IntValue(value.coerceToLong("hostAsInt")) },
            "hostAsFloat" to native1("hostAsFloat") { value -> FieldMLValue.FloatValue(value.coerceToDouble("hostAsFloat")) },
            "hostAsBool" to native1("hostAsBool") { value -> FieldMLValue.BoolValue(value.coerceToBool("hostAsBool")) },
            "hostAsString" to native1("hostAsString") { value -> FieldMLValue.StringValue(value.coerceToString()) },
            // A JVM collection that is not a `List` — `Box.parents` is a `Set` — has no FieldML shape,
            // because `hostToFieldMLValue` only unwraps lists. This is the way in for the rest.
            "hostAsList" to native1("hostAsList") { value -> FieldMLValue.ListValue(value.coerceToElements()) },

            // ---- source macros -------------------------------------------------------------------
            //
            // Mechanism A. A Field source macro is a rewrite over Kotlin *text*, and FieldML has no
            // Kotlin compiler — but the call-shaped macros rewrite to an ordinary `Box` extension
            // function, so what is actually needed is the *expansion* (for its side effects on the box)
            // plus a reflective call. See FieldMLMacroLayer for the argument, and for what is left.
            "hostMacroExpand" to native2("hostMacroExpand") { name, argumentTexts ->
                val insert = FieldMLMacroLayer.expand(
                    requireBox(box, "hostMacroExpand"),
                    name.asString("macro name"),
                    argumentTexts.asMacroArgumentTexts(),
                )
                FieldMLValue.RecordValue(
                    linkedMapOf(
                        "call" to FieldMLValue.StringValue(FieldMLMacroLayer.calleeOf(insert.here) ?: ""),
                        "arguments" to FieldMLValue.StringValue(FieldMLMacroLayer.argumentTextOf(insert.here)),
                        "here" to FieldMLValue.StringValue(insert.here),
                        "preamble" to FieldMLValue.StringValue(insert.preamble),
                        "declarations" to FieldMLValue.StringValue(insert.preambleAfterImports),
                        "start" to FieldMLValue.StringValue(insert.start),
                        "end" to FieldMLValue.StringValue(insert.end),
                    ),
                )
            },
            // A **record** of arguments is a named call, and a named call is the only shape that can
            // leave a Kotlin default unwritten — which matters more here than anywhere else, because a
            // macro's runtime function is usually all-defaults (`_fieldStatsHere` declares seven).
            "hostMacro" to native3("hostMacro") { name, argumentTexts, arguments ->
                val target = requireBox(box, "hostMacro")
                val macro = name.asString("macro name")
                val texts = argumentTexts.asMacroArgumentTexts()
                if (arguments is FieldMLValue.RecordValue) {
                    FieldMLMacroLayer.invokeNamed(target, macro, texts, arguments.hostFields())
                } else {
                    FieldMLMacroLayer.invoke(target, macro, texts, FieldMLHostBridge.spreadArguments(arguments))
                }
            },

            // The `prop` family, native. `prop<T>(name)` generates a delegate *declaration* rather than
            // a call, so there is nothing for `hostMacro` to invoke — but the delegate is
            // `afield.stdlib.inheritedPropertyRaw`, and calling that *is* reading the slot the generated
            // Kotlin would have read. A Kotlin box's `speed` and a FieldML box's `prop "speed"` are one
            // property, not two.
            "prop" to native1("prop") { name ->
                val slot = name.asString("property name")
                val target = requireBox(box, "prop")
                hostToFieldMLValue(inheritedPropertyRaw(target, slot) ?: error(missingPropMessage(target, slot)))
            },
            "propOr" to native2("propOr") { name, fallback ->
                val slot = name.asString("property name")
                inheritedPropertyRaw(requireBox(box, "propOr"), slot)
                    ?.let { hostToFieldMLValue(it) }
                    ?: fallback
            },
            "setProp" to native2("setProp") { name, value ->
                writeProp(requireBox(box, "setProp"), name.asString("property name"), value)
            },
            // `rootProp` differs from `prop` only in where the slot lives: on the document root, so every
            // box in the document shares the one value wherever it is read from (props.kt:88).
            "rootProp" to native1("rootProp") { name ->
                val slot = name.asString("property name")
                val target = requireBox(box, "rootProp").root
                hostToFieldMLValue(inheritedPropertyRaw(target, slot) ?: error(missingPropMessage(target, slot)))
            },
            "setRootProp" to native2("setRootProp") { name, value ->
                writeProp(requireBox(box, "setRootProp").root, name.asString("property name"), value)
            },
            // `once.name { … }` is `Onceing.getValue` (scratch.kt:26): read the slot, and only if it is
            // null run the computation and store it. `null` is the empty sentinel rather than a cached
            // value, which is why a computation that yields nothing can run again.
            "once" to native2("once") { name, compute ->
                val slot = name.asString("property name")
                val target = requireBox(box, "once")
                val existing = target.getPropertyRaw(slot)
                if (existing != null) {
                    hostToFieldMLValue(existing)
                } else {
                    val made = applyFieldMLFunction(compute, FieldMLValue.UnitValue)
                    fieldMLValueToHost(made)?.let { target.setPropertyRaw(slot, it, true) }
                    made
                }
            },
        )

    /**
     * A macro's arguments as the Kotlin pass would have handed them over: source *text*, one string per
     * PSI sibling after the callee — a type-argument group like `<Int>` and/or an argument list like
     * `(4096)` or `{ vertex = … }`.
     *
     * A bare string is the single-argument case, which is what nearly every call site looks like; a
     * tuple is how the `prop<Int>(speed)` shape is written, as `("<Int>", "(speed)")`.
     */
    private fun FieldMLValue.asMacroArgumentTexts(): List<String> =
        when (this) {
            is FieldMLValue.StringValue -> listOf(value)
            is FieldMLValue.TupleValue -> elements.map { it.coerceToString() }
            is FieldMLValue.ListValue -> elements.map { it.coerceToString() }
            FieldMLValue.UnitValue -> emptyList()
            else -> error(
                "A macro's arguments are source text: expected a String or a tuple of Strings, " +
                    "found ${renderFieldMLValue(this)}",
            )
        }

    /**
     * The write half of `prop`, which is always **local**: it shadows an ancestor's value for this box
     * and everything below it rather than changing theirs (`Inheritance.setValue`, `stdlib.kt:307`).
     *
     * `doResync = true` is the difference between a slot and a *visible* slot — it is what queues the
     * property for the next UI resync, so the inspector shows what a FieldML box wrote.
     */
    private fun writeProp(target: Box, name: String, value: FieldMLValue): FieldMLValue {
        val host = fieldMLValueToHost(value)
            ?: error("setProp cannot store Unit in '$name' — an absent slot is how `prop` and `once` spell 'not set yet'")
        target.setPropertyRaw(name, narrowToTheWidthAlreadyThere(inheritedPropertyRaw(target, name), host), true)
        return value
    }

    /**
     * Keep a shared slot at the width the *Kotlin* side declared.
     *
     * This is the sharp edge of sharing a `prop` between a Kotlin box and a FieldML one, and it does not
     * fail where you would look. FieldML has one integer type and one float type, so its `5` is a JVM
     * `Long` and its `0.5` a `Double`. A Kotlin box that wrote `prop<Int>(depth)` reads the slot through
     * `Inheritance<Int>`, whose `getValue` returns an erased `T` — so the *call site* inserts a checkcast
     * to `Integer` and a `Long` in the slot makes the **Kotlin** box throw `ClassCastException`, on a
     * line that mentions neither FieldML nor the write.
     *
     * There is nothing in the slot that says what type was declared, but there is something better: what
     * is already in it. If an ancestor's `prop<Int>` seeded the slot with an `Int`, an incoming FieldML
     * integer is stored as an `Int` and the Kotlin reader keeps working. An empty slot has no width to
     * preserve, and then FieldML's own widths are the right answer.
     *
     * Deliberately only narrowing, and only between the JVM widths of one FieldML type: this never turns
     * a Float into an Int or a number into something else.
     */
    private fun narrowToTheWidthAlreadyThere(existing: Any?, incoming: Any): Any =
        when {
            incoming is Long && existing is Int -> incoming.toInt()
            incoming is Long && existing is Short -> incoming.toShort()
            incoming is Long && existing is Byte -> incoming.toByte()
            incoming is Double && existing is Float -> incoming.toFloat()
            else -> incoming
        }

    /**
     * The same diagnosis `MissingInheritedPropertyException` gives, in FieldML's voice: nothing up the
     * graph has the slot, which is nearly always a box that has not run yet rather than a typo.
     */
    private fun missingPropMessage(target: Box, name: String): String =
        "No value for inherited property '$name' in '${target.name.ifBlank { target.id }}' or any of its " +
            "${target.parents.size} parent boxes. Has the box that writes it run yet? " +
            "`propOr \"$name\" fallback` reads it without failing."

    /**
     * Class lookup with the two spellings a FieldML author is likely to try: the JVM binary name and
     * the Kotlin nested-class spelling. `Img.Companion` is `afield.stdlib.imaging.Img$Companion` to
     * the class loader, and nobody writes the `$` by hand.
     */
    private fun hostClass(name: String): Class<*> {
        runCatching { return Class.forName(name) }
        var candidate = name
        while (candidate.contains('.')) {
            candidate = candidate.replaceAfterLast('.', "").dropLast(1) + "$" + candidate.substringAfterLast('.')
            runCatching { return Class.forName(candidate) }
        }
        error("Unknown JVM class '$name'")
    }

    /**
     * One frame of animation per step of the returned sequence: Field's launch machinery advances a
     * returned `Sequence` once per run loop frame, so applying the FieldML function here is what makes
     * a box animate. The function receives the seconds elapsed since launch and runs for its effects
     * (`draw`, `display`, `hostTap`); its result is discarded. A null [durationSeconds] runs until the
     * box is stopped.
     */
    private fun frameSequence(function: FieldMLValue, durationSeconds: Double?): Sequence<Long> {
        val start = System.nanoTime()
        var frame = 0L
        return generateSequence {
            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
            if (durationSeconds != null && elapsed > durationSeconds) return@generateSequence null
            applyFieldMLFunction(function, FieldMLValue.FloatValue(elapsed))
            frame += 1
            frame
        }
    }

    private fun requireBox(box: Box?, name: String): Box =
        box ?: error("'$name' needs a current Field box")

    private fun magnitude(parts: FloatArray): Double =
        Math.sqrt(parts.sumOf { (it * it).toDouble() })

    private fun requireSameWidth(name: String, left: FloatArray, right: FloatArray) {
        if (left.size != right.size) error("$name expects two vectors of the same width, found Vec${left.size} and Vec${right.size}")
    }

    private fun float1(name: String, block: (Double) -> Double): FieldMLValue.NativeFunctionValue =
        native1(name) { value -> FieldMLValue.FloatValue(block(value.asDouble("value"))) }

    /** Applies whichever of [ints]/[floats] matches the runtime number, preserving Int vs Float. */
    private fun FieldMLValue.mapNumber(name: String, ints: (Long) -> Long, floats: (Double) -> Double): FieldMLValue =
        when (this) {
            is FieldMLValue.IntValue -> FieldMLValue.IntValue(ints(value))
            is FieldMLValue.FloatValue -> FieldMLValue.FloatValue(floats(value))
            else -> error("Expected numeric $name, found ${renderFieldMLValue(this)}")
        }

    private fun numeric2(
        name: String,
        left: FieldMLValue,
        right: FieldMLValue,
        ints: (Long, Long) -> Long,
        floats: (Double, Double) -> Double,
    ): FieldMLValue =
        when {
            left is FieldMLValue.IntValue && right is FieldMLValue.IntValue ->
                FieldMLValue.IntValue(ints(left.value, right.value))
            left is FieldMLValue.FloatValue && right is FieldMLValue.FloatValue ->
                FieldMLValue.FloatValue(floats(left.value, right.value))
            else -> error("$name expects two Ints or two Floats, found ${renderFieldMLValue(left)} and ${renderFieldMLValue(right)}")
        }

    private fun outputPayload(value: FieldMLValue): Any? =
        when (value) {
            is FieldMLValue.HostValue -> value.value
            is FieldMLValue.StringValue -> value.value
            else -> renderFieldMLValue(value)
        }

    private fun native1(name: String, block: (FieldMLValue) -> FieldMLValue): FieldMLValue.NativeFunctionValue =
        FieldMLValue.NativeFunctionValue(name, block)

    private fun native2(name: String, block: (FieldMLValue, FieldMLValue) -> FieldMLValue): FieldMLValue.NativeFunctionValue =
        native1(name) { a -> native1("$name/1") { b -> block(a, b) } }

    private fun native3(
        name: String,
        block: (FieldMLValue, FieldMLValue, FieldMLValue) -> FieldMLValue,
    ): FieldMLValue.NativeFunctionValue =
        native1(name) { a -> native2("$name/1") { b, c -> block(a, b, c) } }

    private fun native4(
        name: String,
        block: (FieldMLValue, FieldMLValue, FieldMLValue, FieldMLValue) -> FieldMLValue,
    ): FieldMLValue.NativeFunctionValue =
        native1(name) { a -> native3("$name/1") { b, c, d -> block(a, b, c, d) } }

    private fun native7(
        name: String,
        block: (FieldMLValue, FieldMLValue, FieldMLValue, FieldMLValue, FieldMLValue, FieldMLValue, FieldMLValue) -> FieldMLValue,
    ): FieldMLValue.NativeFunctionValue =
        native1(name) { a ->
            native1("$name/1") { b ->
                native1("$name/2") { c ->
                    native1("$name/3") { d ->
                        native1("$name/4") { e ->
                            native1("$name/5") { f ->
                                native1("$name/6") { g -> block(a, b, c, d, e, f, g) }
                            }
                        }
                    }
                }
            }
        }

    private fun FieldMLValue.asDouble(name: String): Double =
        when (this) {
            is FieldMLValue.IntValue -> value.toDouble()
            is FieldMLValue.FloatValue -> value
            else -> error("Expected numeric $name, found ${renderFieldMLValue(this)}")
        }

    private fun FieldMLValue.asFloat(name: String): Float = asDouble(name).toFloat()

    private fun FieldMLValue.asBool(name: String): Boolean =
        (this as? FieldMLValue.BoolValue)?.value ?: error("Expected Bool $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asString(name: String): String =
        (this as? FieldMLValue.StringValue)?.value ?: error("Expected String $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asLong(name: String): Long =
        when (this) {
            is FieldMLValue.IntValue -> value
            is FieldMLValue.FloatValue -> value.toLong()
            else -> error("Expected Int $name, found ${renderFieldMLValue(this)}")
        }

    private fun FieldMLValue.asList(name: String): List<FieldMLValue> =
        (this as? FieldMLValue.ListValue)?.elements ?: error("Expected a list for $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asVector(name: String): FloatArray =
        vectorComponents(this) ?: error("Expected Vec2, Vec3 or Vec4 for $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asHost(name: String): Any =
        (this as? FieldMLValue.HostValue)?.value ?: error("Expected host $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asClass(name: String): Class<*> =
        asHost(name) as? Class<*> ?: error("$name expects a class handle from hostClass, found ${renderFieldMLValue(this)}")

    /** The `hostAs…` family: reads a scalar out of whatever a `Host`-typed value turned out to be. */
    private fun FieldMLValue.coerceToDouble(name: String): Double =
        when (this) {
            is FieldMLValue.IntValue -> value.toDouble()
            is FieldMLValue.FloatValue -> value
            is FieldMLValue.StringValue -> value.toDoubleOrNull() ?: error("$name cannot read a number from \"$value\"")
            is FieldMLValue.HostValue -> (value as? Number)?.toDouble()
                ?: error("$name expects a number, found ${renderFieldMLValue(this)}")
            else -> error("$name expects a number, found ${renderFieldMLValue(this)}")
        }

    private fun FieldMLValue.coerceToLong(name: String): Long =
        when (this) {
            is FieldMLValue.IntValue -> value
            else -> coerceToDouble(name).toLong()
        }

    private fun FieldMLValue.coerceToBool(name: String): Boolean =
        when (this) {
            is FieldMLValue.BoolValue -> value
            is FieldMLValue.HostValue -> (value as? Boolean)
                ?: error("$name expects a Bool, found ${renderFieldMLValue(this)}")
            else -> error("$name expects a Bool, found ${renderFieldMLValue(this)}")
        }

    private fun FieldMLValue.coerceToElements(): List<FieldMLValue> =
        when (this) {
            is FieldMLValue.ListValue -> elements
            is FieldMLValue.TupleValue -> elements
            is FieldMLValue.HostValue -> when (val host = value) {
                is Iterable<*> -> host.map { hostToFieldMLValue(it) }
                is Array<*> -> host.map { hostToFieldMLValue(it) }
                is Map<*, *> -> host.entries.map { (key, entry) ->
                    FieldMLValue.TupleValue(listOf(hostToFieldMLValue(key), hostToFieldMLValue(entry)))
                }
                else -> error("hostAsList expects a collection, found ${host?.javaClass?.name}")
            }
            else -> error("hostAsList expects a collection, found ${renderFieldMLValue(this)}")
        }

    private fun FieldMLValue.coerceToString(): String =
        when (this) {
            is FieldMLValue.StringValue -> value
            is FieldMLValue.HostValue -> value?.toString() ?: "null"
            else -> renderFieldMLValue(this)
        }

    private fun FieldMLValue.asRecord(name: String): Map<String, FieldMLValue> =
        (this as? FieldMLValue.RecordValue)?.fields ?: error("Expected record $name, found ${renderFieldMLValue(this)}")

    /**
     * A record of named arguments, host-side. Functions stay [FieldMLValue]s for the same reason
     * [FieldMLHostBridge.spreadArguments] leaves them alone: `samProxy` has to be able to see them.
     */
    private fun FieldMLValue.RecordValue.hostFields(): Map<String, Any?> =
        fields.mapValues { fieldMLValueToHost(it.value) }

    private fun FieldMLValue.asHostArguments(name: String): List<Any?> =
        when (this) {
            is FieldMLValue.ListValue -> elements.map(::fieldMLValueToHost)
            is FieldMLValue.TupleValue -> elements.map(::fieldMLValueToHost)
            else -> error("Expected tuple or list $name, found ${renderFieldMLValue(this)}")
        }

    private fun FieldMLValue.substituteHostInput(input: FieldMLValue): FieldMLValue =
        when (this) {
            is FieldMLValue.HostValue -> if (value === HostInputPlaceholder) input else this
            is FieldMLValue.TupleValue -> copy(elements = elements.map { it.substituteHostInput(input) })
            is FieldMLValue.ListValue -> copy(elements = elements.map { it.substituteHostInput(input) })
            is FieldMLValue.RecordValue -> copy(fields = fields.mapValues { (_, value) -> value.substituteHostInput(input) })
            else -> this
        }

    private fun FieldMLValue.asFLine(name: String): FLine =
        asHost(name) as? FLine ?: error("Expected FLine for $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asVec2(name: String): Vector2f =
        asHost(name) as? Vector2f ?: error("Expected Vec2 for $name, found ${renderFieldMLValue(this)}")

    private fun FieldMLValue.asVec4(name: String): Vector4f =
        asHost(name) as? Vector4f ?: error("Expected Vec4 for $name, found ${renderFieldMLValue(this)}")

    private fun callIndexedHostTap(
        target: String,
        namedArguments: Map<String, FieldMLValue>,
        input: FieldMLValue,
        box: Box?,
    ) {
        val candidates = indexedHostFunctions(target)
        if (candidates.isEmpty()) error("No indexed Kotlin function found for '$target'")
        val errors = mutableListOf<String>()
        for (candidate in candidates) {
            val built = candidate.buildArguments(namedArguments, input, box)
            if (built != null) {
                val javaClass = Class.forName(candidate.ownerClassName)
                val result = runCatching { FieldMLHostBridge.callStaticMethod(candidate.name, javaClass, built) }
                if (result.isSuccess) return
                errors += "${candidate.ownerClassName}.${candidate.name}: ${result.exceptionOrNull()?.message}"
            }
        }
        error(
            "No compatible indexed hostTap target '$target'. Tried: " +
                (errors.ifEmpty { candidates.map { "${it.ownerClassName}.${it.name}" } }).joinToString("; "),
        )
    }

    private fun IndexedHostFunction.buildArguments(
        namedArguments: Map<String, FieldMLValue>,
        input: FieldMLValue,
        box: Box?,
    ): List<Any?>? {
        val remaining = namedArguments.keys.toMutableSet()
        val out = mutableListOf<Any?>()
        if (receiverType == "Box" || receiverType == "afield.Box") {
            out += box ?: error("hostTap target '$qualifiedName' needs a current Field box")
        } else if (receiverType != null) {
            return null
        }
        var usedInput = false
        for (parameter in parameters) {
            val value = namedArguments[parameter.name]
            if (value != null) {
                remaining -= parameter.name
                out += fieldMLValueToHost(value)
            } else if (!parameter.hasDefault) {
                if (usedInput) return null
                out += if (parameter.isVararg || parameter.type.startsWith("List<")) {
                    listOf(fieldMLValueToHost(input))
                } else {
                    fieldMLValueToHost(input)
                }
                usedInput = true
            } else {
                out += parameter.defaultValue ?: return null
            }
        }
        if (remaining.isNotEmpty()) return null
        return out.takeIf { usedInput }
    }

    private fun indexedHostFunctions(target: String): List<IndexedHostFunction> {
        val simpleName = target.substringAfterLast('.')
        val requestedPackage = target.substringBeforeLast('.', missingDelimiterValue = "")
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        val indexedFiles = (KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex())
            ?.definitions
            ?.filter { it.kind == "fun" && it.name.substringAfterLast('.') == simpleName }
            ?.map { root.resolve(it.filePath).normalize() }
            ?.distinct()
            .orEmpty()

        val sourceFiles = if (indexedFiles.isNotEmpty()) {
            indexedFiles
        } else {
            listOf(root.resolve("src/jvmMain/kotlin"), root.resolve("src/personal/kotlin"))
                .filter { Files.exists(it) }
                .flatMap { sourceRoot ->
                    Files.walk(sourceRoot).use { stream ->
                        stream.filter { it.isRegularFile() && it.toString().endsWith(".kt") }.toList()
                    }
                }
        }

        return sourceFiles.flatMap { file ->
            val text = runCatching { Files.readString(file) }.getOrNull() ?: return@flatMap emptyList()
            val pkg = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
            if (requestedPackage.isNotEmpty() && pkg != requestedPackage) return@flatMap emptyList()
            parseIndexedHostFunctions(text, file, pkg).filter { it.name == simpleName }
        }
    }

    private fun parseIndexedHostFunctions(text: String, file: Path, pkg: String): List<IndexedHostFunction> {
        val out = mutableListOf<IndexedHostFunction>()
        val regex = Regex("""(?m)(?:^|\n)\s*(?:public\s+|internal\s+)?fun\s+([A-Za-z_][A-Za-z0-9_.<>?]*)\.([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        for (match in regex.findAll(text)) {
            val receiver = match.groupValues[1].substringBefore("<").removeSuffix("?")
            val name = match.groupValues[2]
            val openParen = match.range.last
            val closeParen = findMatchingParen(text, openParen) ?: continue
            val parameters = parseIndexedParameters(text.substring(openParen + 1, closeParen))
            val owner = topLevelOwnerClassName(pkg, file)
            out += IndexedHostFunction(
                qualifiedName = listOf(pkg, name).filter { it.isNotBlank() }.joinToString("."),
                ownerClassName = owner,
                name = name,
                receiverType = receiver,
                parameters = parameters,
            )
        }
        return out
    }

    private fun parseIndexedParameters(text: String): List<IndexedHostParameter> =
        splitTopLevel(text, ',').mapNotNull { raw ->
            val segment = raw.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val withoutAnnotations = segment.replace(Regex("""^@[A-Za-z_][A-Za-z0-9_.]*(\([^)]*\))?\s*"""), "")
            val isVararg = withoutAnnotations.startsWith("vararg ")
            val cleaned = withoutAnnotations.removePrefix("vararg ").trim()
            val colon = cleaned.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = cleaned.substring(0, colon).trim()
            val afterColon = cleaned.substring(colon + 1).trim()
            val equals = topLevelIndexOf(afterColon, '=')
            val type = (if (equals >= 0) afterColon.substring(0, equals) else afterColon).trim()
            val defaultSource = if (equals >= 0) afterColon.substring(equals + 1).trim() else null
            IndexedHostParameter(
                name = name,
                type = type,
                isVararg = isVararg,
                hasDefault = defaultSource != null,
                defaultValue = defaultSource?.let(::parseHostDefaultLiteral),
            )
        }

    private fun parseHostDefaultLiteral(source: String): Any? {
        val s = source.trim().removeSuffix(",")
        return when {
            s == "true" -> true
            s == "false" -> false
            s.startsWith("\"") && s.endsWith("\"") -> s.removeSurrounding("\"")
            s.toIntOrNull() != null -> s.toInt()
            s.toDoubleOrNull() != null -> s.toDouble()
            else -> null
        }
    }

    private fun findMatchingParen(text: String, openIndex: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openIndex until text.length) {
            val ch = text[i]
            if (inString) {
                escaped = ch == '\\' && !escaped
                if (ch == '"' && !escaped) inString = false
                if (ch != '\\') escaped = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var paren = 0
        var angle = 0
        var bracket = 0
        var inString = false
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '"') inString = !inString
            if (!inString) {
                when (ch) {
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    '[' -> bracket++
                    ']' -> if (bracket > 0) bracket--
                    delimiter -> if (paren == 0 && angle == 0 && bracket == 0) {
                        parts += text.substring(start, i)
                        start = i + 1
                    }
                }
            }
        }
        parts += text.substring(start)
        return parts
    }

    private fun topLevelIndexOf(text: String, target: Char): Int {
        var paren = 0
        var angle = 0
        var inString = false
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '"') inString = !inString
            if (!inString) {
                when (ch) {
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    target -> if (paren == 0 && angle == 0) return i
                }
            }
        }
        return -1
    }

    private fun topLevelOwnerClassName(pkg: String, file: Path): String {
        val base = file.fileName.toString().removeSuffix(".kt")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } + "Kt"
        return if (pkg.isBlank()) base else "$pkg.$base"
    }

    private data class IndexedHostFunction(
        val qualifiedName: String,
        val ownerClassName: String,
        val name: String,
        val receiverType: String?,
        val parameters: List<IndexedHostParameter>,
    )

    private data class IndexedHostParameter(
        val name: String,
        val type: String,
        val isVararg: Boolean,
        val hasDefault: Boolean,
        val defaultValue: Any?,
    )

    private object HostInputPlaceholder {
        override fun toString(): String = "<hostInput>"
    }
}
