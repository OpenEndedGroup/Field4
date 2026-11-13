import afield.graphics.scene.video.StereoVideoGroupLooks
import afield.study.source.SourceBackedFunction
import afield.document.workspace.FileManager
import afield.ui.visualization.menu
import afield.graphics.scene.video.VideoAutoExposure
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

displayClearAll()

// One pool for the whole ordered ring. Look up carousel and clock dynamically on reruns.
prop<StereoVideoGroupLooks>(groupLooks)
once.groupLooks {
    StereoVideoGroupLooks(FileManager.current().path("%document/keys/ordered-looks"), thisBox.id,
        { carousel }, { carouselClock }).apply { restoreSpeed = true; selectedName = "vanilla-2x" }
}
val pool = groupLooks
val group = pool.group
val scope = pool.scope

// Presets retain the material cadence and the complete exposure tracker configuration too.
// The meter settings change as a block at the end of a look transition; numeric scene lanes blend.
val meterJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
scope.doubleTarget("playback.materialFramesPerSecond", { carousel.source.materialFramesPerSecond.toDouble() }) {
    require(it.isFinite() && it in 0.0..120.0) { "Material cadence must be 0..120 fps" }
    carousel.source.materialFramesPerSecond = it.toInt()
}
scope.target("meter.settings", { meterJson.encodeToString(carousel.autoExposure.settings) }) {
    carousel.autoExposure.settings = meterJson.decodeFromString<VideoAutoExposure.Settings>(it as String)
}

// Working rules survive a server restart; the native key format also retains expression source.
val workingFile = pool.path("working rules")
if (java.nio.file.Files.isRegularFile(workingFile)) scope.loadKey(workingFile)()
if (pool.names.isEmpty()) pool.store("vanilla-2x")
fun keepWorking() { scope.capture().save(workingFile) }

/*
Rules are evaluated only on Apply/recall, never in the graphics callback. Plain lambdas are rejected.
Example: group["input.shadowCooling"] = sourceBlock { screenNo: Int -> 0.1 + 0.04 * screenNo }
Screen numbers here are 0..7; labels in the interface are 1..8.
*/
var lookError = ""
var workingDirty = false
var lastWorkingChange = 0L
fun lookAction(action: () -> Unit) {
    runCatching {
        action()
        workingDirty = true
        lastWorkingChange = System.nanoTime()
        lookError = ""
    }
        .onFailure { lookError = it.message ?: it.javaClass.simpleName }
}
val drafts = mutableMapOf<String, String>()
val dirtyDrafts = mutableSetOf<String>()
fun ruleText(value: Any?): String = when (value) {
    is SourceBackedFunction<*, *> -> value.source
    else -> value.toString()
}
fun parseRule(text: String): Any =
    text.trim().toDoubleOrNull() ?: SourceBackedFunction<Int, Double>(thisBox.id, text.trim())

// One page of ordinary controls, and one source editor. Changing pages never applies a value.
// Human labels belong here; the stable lane names remain the keyframe / source API.
data class RingControl(val lane: String, val label: String, val unit: String? = null,
                       val step: Double = .001, val toggle: Boolean = false)
val pages = linkedMapOf(
    "Exposure" to listOf(
        RingControl("exposure.mix", "Accumulation mix"),
        RingControl("exposure.halfLife", "Half-life", "s", .01),
        RingControl("exposure.inputWeight", "Incoming weight"),
        RingControl("appearance.opacity", "Opacity")),
    "Tone" to listOf(
        RingControl("presentation.black", "Black level"),
        RingControl("presentation.white", "White level"),
        RingControl("presentation.gamma", "Gamma"),
        RingControl("presentation.autoMix", "Auto exposure mix")),
    "Colour" to listOf(
        RingControl("presentation.colourTransfer", "Accumulated colour"),
        RingControl("input.shadowCooling", "Input shadow cooling"),
        RingControl("output.shadowCooling", "Output shadow cooling")),
    "Cadence" to listOf(
        RingControl("cadence.enabled", "Paired cadence", toggle = true),
        RingControl("cadence.pairsPerSecond", "Pairs per second", "Hz", .1),
        RingControl("cadence.blurSigma", "Soft-half blur", "px", .1),
        RingControl("cadence.blurMix", "Soft-half blur mix"),
        RingControl("cadence.darkening", "Soft-half darkening")),
    "Shutter" to listOf(
        RingControl("flash.enabled", "Shutter enabled", toggle = true),
        RingControl("flash.onFrames", "On time", "frames", 1.0),
        RingControl("flash.offFrames", "Off time", "frames", 1.0),
        RingControl("flash.phaseFrames", "Phase", "frames", 1.0),
        RingControl("flash.onBrightness", "On brightness"),
        RingControl("flash.offBrightness", "Off brightness"))
)
val allControls = pages.values.flatten()
val flashHints = mapOf(
    "flash.enabled" to Triple(0.0, 1.0, "Enable the headset-tick shutter."),
    "flash.onFrames" to Triple(0.0, 240.0, "Whole headset ticks at the on brightness. On plus off must total at least one tick."),
    "flash.offFrames" to Triple(0.0, 240.0, "Whole headset ticks at the off brightness; independent of video cadence."),
    "flash.phaseFrames" to Triple(-240.0, 240.0, "Signed local shutter phase, added to the shared ring stagger."),
    "flash.onBrightness" to Triple(0.0, 4.0, "Linear-light gain during the on part; the domain extends to 16."),
    "flash.offBrightness" to Triple(0.0, 4.0, "Zero closes the shutter; nonzero retains a dim image.")
)
var pageIndex = 0
var ruleIndex = 0
val controlMirrors = mutableListOf<() -> Unit>()

fun draftFor(lane: String): String = if (lane in dirtyDrafts) drafts.getValue(lane)
    else ruleText(group.capture().values.getValue(lane))

fun applyDrafts(lanes: Set<String>) {
    // Overlay only the requested drafts onto the CURRENT look. Recalls and slider edits survive.
    val key = group.capture()
    lanes.forEach { key[it] = parseRule(draftFor(it)) }
    pool.cancel()
    group.apply(key)
    dirtyDrafts.removeAll(lanes)
    showRuleEditor()
}

fun showLookControls() {
    controlGroup("whole-ring looks", restoreValues = false) {
        val names = pool.names
        if (names.isNotEmpty()) menu("Saved look", names,
            initialIndex = names.indexOf(pool.selectedName).coerceAtLeast(0)) { _, name ->
            pool.selectedName = name
            showLookControls()
        }
        stringInput("Save as", initialValue = pool.selectedName,
            hoverText = "A named whole-ring look retains its authored rules and one shared playback speed. Unapplied drafts are not saved.") { pool.selectedName = it }
        button("Store look") { down -> if (down) lookAction { pool.store(); showLookControls(); showTransitionPreview() } }
        button("Recall look") { down -> if (down) lookAction { pool.recall(); showRuleEditor() } }
        button("Hold transition") { down -> if (down) lookAction { pool.cancel() } }
        button("Import screen edits", hoverText = "Import the actual renderer values after using individual screen inspectors or scores. Equal values become constants; differences become indexed rules.") { down ->
            if (down) lookAction { pool.importCurrentScreens(); showControls(); showRuleEditor() }
        }
    }
}

// Preview always starts from a named source look. Speed changes at the start of the fade;
// the running Recall captures its duration, so this leaves the ordinary recall controls alone.
prop<String>(previewFrom)
once.previewFrom { "copper-1x" }
prop<String>(previewTo)
once.previewTo { "vanilla-2x-warm" }
prop<Double>(previewDuration)
once.previewDuration { 8.0 }
var previewStarted = 0L
var previewRunningDuration = 8.0
var previewDestination = ""

fun loadPreviewStart() {
    val key = scope.loadKey(pool.path(previewFrom))
    group.resolve(key)
    val restore = pool.restoreSpeed
    try {
        pool.cancel()
        pool.restoreSpeed = true
        key()
        pool.selectedName = previewFrom
        previewStarted = 0L
    } finally { pool.restoreSpeed = restore }
    showLookControls()
    showControls()
    showRuleEditor()
}

fun playPreview() {
    // Validate both endpoints before resetting the scene or changing its transport.
    group.resolve(scope.loadKey(pool.path(previewFrom)))
    val target = scope.loadKey(pool.path(previewTo))
    group.resolve(target)
    val speed = (target.values.getValue("playback.speed") as Number).toDouble()
    require(speed.isFinite() && speed in .1..8.0)
    loadPreviewStart()
    val duration = pool.transitionSeconds
    val stagger = pool.staggerSeconds
    val restore = pool.restoreSpeed
    try {
        pool.restoreSpeed = true
        pool.transitionSeconds = previewDuration
        pool.staggerSeconds = 0.0
        pool.recall(previewTo)
        previewRunningDuration = previewDuration
        previewDestination = previewTo
        previewStarted = System.nanoTime()
    } finally {
        pool.transitionSeconds = duration
        pool.staggerSeconds = stagger
        pool.restoreSpeed = restore
    }
    showLookControls()
}

fun showTransitionPreview() {
    val names = pool.names.filter { it != "working rules" }
    if (previewFrom !in names) previewFrom = names.first()
    if (previewTo !in names) previewTo = names.last()
    controlGroup("transition preview", restoreValues = false) {
        menu("From", names, initialIndex = names.indexOf(previewFrom).coerceAtLeast(0)) { _, name -> previewFrom = name }
        menu("To", names, initialIndex = names.indexOf(previewTo).coerceAtLeast(0)) { _, name -> previewTo = name }
        slider("Fade duration", min = 0.0, max = 60.0, step = .1, initialValue = previewDuration, unit = "s") { previewDuration = it }
        button("Load start", hoverText = "Recall the From look, including its playback speed, with a cut. Movie time and exposure history continue.") { down -> if (down) lookAction { loadPreviewStart() } }
        button("Preview transition", hoverText = "Reset to From, immediately switch to the To playback speed, then fade the other scene parameters over Fade duration. Repeat without overwriting either named look.") { down -> if (down) lookAction { playPreview() } }
    }
}

fun showControls() {
    controlMirrors.clear()
    val resolved = group.lastResolved ?: group.resolve()
    controlGroup("ring controls", restoreValues = false) {
        menu("Section", pages.keys.toList(), initialIndex = pageIndex) { index, _ ->
            pageIndex = index
            showControls()
        }
        pages.values.elementAt(pageIndex).forEach { control ->
            val lane = control.lane
            val descriptor = StereoVideoGroupLooks.parameters.find { it.name == lane }
            val hint = flashHints[lane]
            val first = resolved[lane, 0]
            val help = "${descriptor?.help ?: hint!!.third} Changes all screens to the same value. A varying rule is shown at screen 1; edit its source in Per-screen rule."
            if (control.toggle) {
                val handle = checkbox(control.label, initialValue = first >= .5, hoverText = help) { checked ->
                    lookAction { pool.cancel(); group[lane] = if (checked) 1.0 else 0.0 }
                }
                controlMirrors += { handle.set((group.lastResolved ?: group.resolve())[lane, 0] >= .5) }
            } else {
                val handle = slider(control.label, min = minOf(descriptor?.min ?: hint!!.first, first),
                    max = maxOf(descriptor?.max ?: hint!!.second, first), step = control.step,
                    initialValue = first, unit = control.unit, hoverText = help) { value ->
                    lookAction { pool.cancel(); group[lane] = value }
                }
                controlMirrors += { handle.set((group.lastResolved ?: group.resolve())[lane, 0]) }
            }
        }
        button("Edit per-screen rule", hoverText = "Open one source editor for the parameters in this section. Switching parameters retains unapplied drafts.") { down ->
            if (down) {
                ruleIndex = allControls.indexOf(pages.values.elementAt(pageIndex).first())
                showRuleEditor()
                displayOpen("per-screen rule")
            }
        }
    }
}

fun showRuleEditor() {
    val selected = allControls[ruleIndex]
    controlGroup("per-screen rule", restoreValues = false) {
        menu("Parameter", allControls.map { "${it.label} · ${it.lane}" }, initialIndex = ruleIndex) { index, _ ->
            ruleIndex = index
            showRuleEditor()
        }
        multilineText("Rule", initialValue = draftFor(selected.lane), rows = 5,
            hoverText = "A number, or { screenNo: Int -> 0.1 + 0.04 * screenNo }. Screen 1 has index 0. Inherited props are available. Editing is a draft until Apply.") { text ->
            drafts[selected.lane] = text
            if (text == ruleText(group.capture().values.getValue(selected.lane))) dirtyDrafts.remove(selected.lane)
            else dirtyDrafts.add(selected.lane)
        }
        button("Apply this rule") { down -> if (down) lookAction { applyDrafts(setOf(selected.lane)) } }
        button("Apply edited rules", hoverText = "Apply all unapplied drafts together. Every lane and screen is validated before anything changes. Other current rules are retained.") { down ->
            if (down && dirtyDrafts.isNotEmpty()) lookAction { applyDrafts(dirtyDrafts.toSet()) }
        }
        button("Discard drafts", hoverText = "Discard all unapplied text and show the current authored rule.") { down ->
            if (down) { dirtyDrafts.clear(); drafts.clear(); showRuleEditor() }
        }
    }
}

showLookControls()
showTransitionPreview()
showControls()
controlGroup("look transitions", restoreValues = false) {
    slider("Transition", min = 0.0, max = 30.0, step = .01, initialValue = pool.transitionSeconds, unit = "s",
        hoverText = "Zero makes a cut. Otherwise blend the evaluated values at each screen; source programs are not structurally interpolated.") { pool.transitionSeconds = it }
    slider("Stagger per screen", min = 0.0, max = 5.0, step = .01, initialValue = pool.staggerSeconds, unit = "s",
        hoverText = "Screen index times this delay before its transition begins.") { pool.staggerSeconds = it }
    checkbox("Restore shared speed", initialValue = pool.restoreSpeed,
        hoverText = "Opt in to changing the one decoder's speed at recall start.") { pool.restoreSpeed = it }
}
showRuleEditor()
keyframeBrowser(pool.directory, scope = scope, channel = "ordered looks and versions", width = 700, height = 420)
listOf("whole-ring looks", "transition preview", "ring controls", "look status").forEach { displayOpen(it) }
listOf("look transitions", "per-screen rule", "ordered looks and versions", "resolved ring").forEach { displayClose(it) }

var lastLookStatus = 0L
var lastStatus = ""
var seenResolved = group.lastResolved
var tableDirty = true
loop {
    runCatching { pool.tick() }.onFailure { lookError = it.message ?: "Transition failed" }
    val now = System.nanoTime()
    if (group.lastResolved !== seenResolved) {
        seenResolved = group.lastResolved
        tableDirty = true
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
        controlMirrors.forEach { it() }
        val progress = if (previewStarted > 0L && pool.lastAction == "Recalling $previewDestination") {
            val elapsed = (now - previewStarted) / 1e9
            " · preview **${(100 * elapsed / previewRunningDuration.coerceAtLeast(.001)).coerceIn(0.0, 100.0).f1}%**"
        } else ""
        val status = "${if (lookError.isEmpty()) pool.lastAction else lookError}$progress · shared speed **${carousel.source.speed.f2}×** · **${dirtyDrafts.size}** unapplied drafts.\n\nControls set all screens; per-screen rules use indices 0–7."
        if (status != lastStatus) {
            lastStatus = status
            markdown("look status", status, open = false)
        }
        if (tableDirty) {
            tableDirty = false
            val r = group.lastResolved ?: group.resolve()
            val rows = r.names.joinToString("\n") { lane -> "| $lane | ${r.values(lane).joinToString(" · ") { it.f3 }} |" }
            markdown("resolved ring", "| Rule | Screens 1 → ${r.size} |\n| --- | --- |\n$rows", open = false)
        }
    }
    frame()
}
    .end {
        pool.cancel()
        keepWorking()
    }
