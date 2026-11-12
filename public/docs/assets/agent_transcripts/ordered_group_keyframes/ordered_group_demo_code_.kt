// An inherited property stays dynamic after source-only rehydration.
prop<Double>(ruleOffset) = 0.1
val group =
    OrderedKeyframeGroup({ 5 }, sourceBoxId = thisBox.id) { resolved ->
            println("Applied: " + resolved.values("cooling"))
        }
        .lane("cooling", 0.0) { it in 0.0..1.0 }
val a = group.capture()
val cooling = sourceBlock { screenNo: Int ->
    val ramp = 0.04 * screenNo
    ruleOffset + ramp
}
group["cooling"] = cooling
val b = group.capture()
val directory = FileManager.current().path("%document/keys/ordered-demo")
b.save(directory.resolve("wave"))
val restored = group.scope.loadKey(directory.resolve("wave"))
group.apply(restored)
check(kotlin.math.abs(group.resolve()["cooling", 4] - .26) < 1e-9)
ruleOffset = .2
check(kotlin.math.abs(group.resolve()["cooling", 4] - .36) < 1e-9)

// Blend outputs at each index, not the syntax of their programs. Save the midpoint too.
val middle = group.blend(a, restored) { .5 }
middle.save(directory.resolve("midpoint"))
val reloadedMiddle = group.scope.loadKey(directory.resolve("midpoint"))
check(kotlin.math.abs(group.resolve(reloadedMiddle)["cooling", 4] - .18) < 1e-9)
val stagger = group.transition(a, b, seconds = .75, durationSeconds = 1.0, staggerSeconds = .25)
group.apply(stagger)
check(group.resolve()["cooling", 4] == 0.0)

// Each endpoint retains its own box's inherited context across a saved blend.
val other = SourceBackedFunction<Int, Double>(
    "ordered_group_other",
    "{ index: Int -> otherOffset + 0.02 * index }",
)
val crossContext = group.blend(restored, group.scope.Key("cooling" to other)) { .5 }
crossContext.save(directory.resolve("cross-context"))
check(
    kotlin.math.abs(group.resolve(group.scope.loadKey(directory.resolve("cross-context")))["cooling", 4] - .37) < 1e-9,
)

// All indices validate before any writes. Keep the previous rules on failure.
val previous = group.capture()
val failure = runCatching {
    group["cooling"] = sourceBlock { screenNo: Int -> if (screenNo == 4) Double.NaN else .2 }
}
        .exceptionOrNull()!!
check(failure.message!!.contains("index 4"))
check(group.capture().values == previous.values)
markdown(
    "result",
    """
### Ordered keyframes passed
Five members, typed function source, inherited property access, native save/reload,
a saved pointwise midpoint, stagger and rejection of a non-finite final member.
""",
)
