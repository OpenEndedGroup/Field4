import afield.graphics.scene.video.StereoVideoGroupLooks
import afield.study.source.SourceBackedFunction
import afield.document.workspace.FileManager

displayClearAll()

// One pool for the whole ordered ring. Look up carousel and clock dynamically on reruns.
prop<StereoVideoGroupLooks>(groupLooks) =
    (thisBox.getPropertyRaw("groupLooks") as? StereoVideoGroupLooks)
        ?: StereoVideoGroupLooks(FileManager.current().path("%document/keys/ordered-looks"), thisBox.id,
            { carousel }, { carouselClock })
val pool = groupLooks
val group = pool.group
val scope = pool.scope

// Working rules survive a server restart; the native key format also retains expression source.
val workingFile = pool.path("working rules")
if (java.nio.file.Files.isRegularFile(workingFile)) scope.loadKey(workingFile)()
if (pool.names.isEmpty()) pool.store("initial ring")
fun keepWorking() { scope.capture().save(workingFile) }

// Rules are evaluated only on Apply/recall, never in the graphics callback. Plain lambdas are rejected.
// Example: group["input.shadowCooling"] = sourceBlock { screenNo: Int -> 0.1 + 0.04 * screenNo }
// Screen numbers here are 0..7; labels in the interface are 1..8.
var lookError = ""
var workingDirty = false
var lastWorkingChange = 0L
fun lookAction(action: () -> Unit) {
    runCatching { action(); workingDirty = true; lastWorkingChange = System.nanoTime(); lookError = "" }
        .onFailure { lookError = it.message ?: it.javaClass.simpleName }
}
val drafts = mutableMapOf<String, String>()
fun ruleText(value: Any?): String = when (value) {
    is SourceBackedFunction<*, *> -> value.source
    else -> value.toString()
}
fun parseRule(text: String): Any = text.trim().toDoubleOrNull()
    ?: SourceBackedFunction<Int, Double>(thisBox.id, text.trim())

controlGroup("whole-ring looks") {
    stringInput("Look name", initialValue = pool.selectedName,
        hoverText = "One shared look contains rules for the entire ordered ring, plus the single global playback speed. Existing names retain earlier versions.") { pool.selectedName = it }
    button("Store whole-ring look", hoverText = "Save authored constants and function source, not eight sampled copies. Speed is saved once. GPU accumulation pixels are not captured.") { down -> if (down) lookAction { pool.store() } }
    checkbox("Restore shared speed", initialValue = pool.restoreSpeed,
        hoverText = "Opt in to changing the one decoder's speed when recalling. Screen functions cannot create independent playback clocks.") { pool.restoreSpeed = it }
    slider("Transition seconds", min = 0.0, max = 30.0, step = .01, initialValue = pool.transitionSeconds,
        hoverText = "Pointwise blend of the two looks' evaluated values at each screen index. Zero makes a cut; source programs are never structurally interpolated.") { pool.transitionSeconds = it }
    slider("Stagger / screen (s)", min = 0.0, max = 5.0, step = .01, initialValue = pool.staggerSeconds,
        hoverText = "Screen index times this delay before its transition begins. The decoder speed, if enabled, changes once at recall start.") { pool.staggerSeconds = it }
    button("Recall whole-ring look") { down -> if (down) lookAction { pool.recall() } }
    button("Hold transition here", hoverText = "Stop the transition and retain its current blended rules. This intermediate look can be saved and restored.") { down -> if (down) lookAction { pool.cancel() } }
    button("Import manual screen edits", hoverText = "Replace the authored rules with the current renderer settings. Identical values become constants; differences become explicit indexed lookup functions. Use after tweaking the retained per-screen inspector sliders or a score.") { down -> if (down) lookAction { pool.importCurrentScreens(); showRules() } }
    button("Apply all rule drafts", hoverText = "Validate every expression at every index, including flash duration relationships, then apply the entire set. A failure retains the previous working look and names its lane/index.") { down ->
        if (down) lookAction {
            pool.cancel()
            group.apply(scope.Key(drafts.mapValues { parseRule(it.value) }.toMutableMap()))
        }
    }
    button("Refresh drafts from current rules", hoverText = "Replace text drafts with the current authored rules after a recall or code edit. Unapplied drafts are discarded.") { down -> if (down) showRules() }
}

// Each parameter has a constant slider and a source editor. Sliders deliberately replace its rule
// with a constant across the whole ring. Drafting source leaves the working rule alone until Apply.
fun showRules() {
    val current = group.capture()
    val resolved = group.resolve()
    val flashHints = mapOf(
        "flash.enabled" to Triple(0.0, 1.0, "At 0.5 or above, enable the headset-tick shutter."),
        "flash.onFrames" to Triple(0.0, 240.0, "Whole headset ticks at the on brightness. On plus off must round to at least one tick."),
        "flash.offFrames" to Triple(0.0, 240.0, "Whole headset ticks at the off brightness; independent of the video-frame cadence."),
        "flash.phaseFrames" to Triple(-240.0, 240.0, "Signed local shutter phase, added to the shared ring stagger."),
        "flash.onBrightness" to Triple(0.0, 4.0, "Linear-light gain during the shutter's on part; the supported domain extends to 16."),
        "flash.offBrightness" to Triple(0.0, 4.0, "Linear-light gain during the off part: zero closes it, nonzero retains a dim image.")
    )
    current.values.forEach { (lane, value) ->
        val descriptor = StereoVideoGroupLooks.parameters.find { it.name == lane }
        val hint = flashHints[lane]
        val lo = descriptor?.min ?: hint!!.first
        val hi = descriptor?.max ?: hint!!.second
        val help = descriptor?.help ?: hint!!.third
        drafts[lane] = ruleText(value)
        controlGroup(lane) {
            val first = resolved[lane, 0]
            slider("Constant · $lane", min = minOf(lo, first), max = maxOf(hi, first), step = .001, initialValue = first,
                hoverText = "$help Replaces this lane with one constant for all screens. When the rule varies, the handle shows screen index zero.") { v ->
                lookAction { pool.cancel(); group[lane] = v; drafts[lane] = v.toString() }
            }
            stringInput("Rule · $lane", initialValue = drafts.getValue(lane),
                hoverText = "A number, or a complete typed lambda: { screenNo: Int -> 0.1 + 0.04 * screenNo }. Use Apply all rule drafts above. Indices are zero-based. Available inherited box properties are looked up when the expression runs.") { drafts[lane] = it }
        }
    }
}
showRules()
keyframeBrowser(pool.directory, scope = scope, channel = "ordered looks and versions", width = 700, height = 420)
displayOpen("whole-ring looks")
displayOpen("resolved ring")
var lastLookStatus = 0L
var seenResolved = group.lastResolved
loop {
    runCatching { pool.tick() }.onFailure { lookError = it.message ?: "Transition failed" }
    val now = System.nanoTime()
    if (group.lastResolved !== seenResolved) {
        seenResolved = group.lastResolved
        workingDirty = true
        lastWorkingChange = now
    }
    // Native saves retain versions. Debounce edits and transitions so dragging never writes per frame.
    if (workingDirty && now - lastWorkingChange >= 1_000_000_000L) {
        workingDirty = false
        runCatching { keepWorking() }.onFailure { lookError = "Working rules could not save: ${it.message}" }
    }
    if (now - lastLookStatus >= 250_000_000L) {
        lastLookStatus = now
        val r = group.lastResolved ?: group.resolve()
        val rows = r.names.joinToString("\n") { lane -> "| $lane | ${r.values(lane).joinToString(" · ") { it.f3 }} |" }
        markdown("resolved ring", """
### ${if (lookError.isEmpty()) pool.lastAction else lookError}

One authored rule per lane · index **0** is screen **1** · shared speed **${carousel.source.speed.f2}×**.

| Rule | Screens 1 → ${r.size} |
| --- | --- |
$rows

The per-screen boxes retain manual inspector sliders. Import their edits explicitly before storing a shared look. Recall and shared rule edits replace those manual values.
        """, open = false)
    }
    frame()
}.end { pool.cancel(); keepWorking() }
