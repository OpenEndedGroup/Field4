import afield.graphics.scene.video.StereoVideoLookPool
import afield.graphics.scene.video.StereoVideoLookRecall
import afield.document.workspace.FileManager
import afield.ui.output.ControlValueHandle

// One native keyframe pool, with index-free lanes so a key can move between screens.
// Reuse the pool on this box's rerun; its carousel lookup remains live through prop inheritance.
prop<afield.graphics.scene.video.StereoVideoLookPool>(screenLooks) =
    (thisBox.getPropertyRaw("screenLooks") as? StereoVideoLookPool)
        ?: StereoVideoLookPool(FileManager.current().path("%document/keys/screen-looks")) { carousel }
prop<afield.graphics.scene.video.StereoVideoLookRecall>(screenLookRecall) =
    (thisBox.getPropertyRaw("screenLookRecall") as? StereoVideoLookRecall)
        ?: StereoVideoLookRecall({ screenLooks }, { carousel.source.speed = it })
val pool = screenLooks

// Seed only once. Saving reads parameters and speed; it never changes a running screen.
if ("slow blur" !in pool.names) pool.store("slow blur", screen = 0,
    note = "Initial slow blur captured from screen 1, including the shared playback speed")

displayClearAll()
var nameControl: ControlValueHandle<String>? = null
var captureControl: ControlValueHandle<Double>? = null
var speedRecallControl: ControlValueHandle<Boolean>? = null
val targetControls = mutableMapOf<Int, ControlValueHandle<Boolean>>()
var lookError = ""
fun lookAction(action: () -> Unit) {
    runCatching { action(); lookError = "" }.onFailure { lookError = it.message ?: it.javaClass.simpleName }
}
controlGroup("shared look pool") {
    nameControl = stringInput("Look name", initialValue = pool.selectedName,
        hoverText = "One name in the document-wide pool. Store captures one screen's appearance plus the shared speed. Storing an existing name adds a version, retaining its earlier saves.") { pool.selectedName = it }
    captureControl = slider("Capture from screen", min = 1.0, max = carousel.surfaces.size.toDouble(), step = 1.0,
        initialValue = (pool.captureScreen + 1).toDouble(),
        hoverText = "Which single screen supplies the appearance for Store. Keys have no screen index, so that look can later be recalled onto any chosen screens.") { pool.captureScreen = it.toInt() - 1 }
    button("Store named look", hoverText = "Save the capture screen's eight appearance settings and current shared playback speed as a native, versioned Field key. No screen or transport state changes.") { down ->
        if (down) lookAction { pool.store(pool.selectedName) }
    }
    speedRecallControl = checkbox("Restore playback speed", initialValue = pool.restoreSpeed,
        hoverText = "Opt in to restoring the key's saved shared speed. This affects the single source and every screen. Off keeps the current speed, when a saved appearance is applied.") { pool.restoreSpeed = it }
    button("Recall to checked screens", hoverText = "Apply the named appearance to the checked screens only. Other screens retain their current look. The speed option independently controls the one shared playback rate.") { down ->
        if (down) lookAction { screenLookRecall.recall(pool.selectedName) }
    }
    slider("Stagger / screen (s)", min = 0.0, max = 3.0, step = .01, initialValue = screenLookRecall.staggerSeconds,
        hoverText = "Delay between selected screens, in ring order, when recalling a look. Zero applies together. The saved speed, when enabled, applies once at the start. Another recall cancels pending arrivals.") { screenLookRecall.staggerSeconds = it }
    button("Cancel pending recall", hoverText = "Cancel future staggered arrivals, leaving already-applied screens as they are.") { down -> if (down) screenLookRecall.cancel() }
    button("Select all screens") { down -> if (down) pool.targetScreens = carousel.surfaces.indices.toSet() }
    button("Select no screens", hoverText = "Clear the target set. With Restore playback speed on, Recall can then restore just the speed.") { down -> if (down) pool.targetScreens = emptySet() }
}
controlGroup("recall targets") {
    carousel.surfaces.indices.forEach { i ->
        targetControls[i] = checkbox("Screen ${i + 1}", initialValue = i in pool.targetScreens,
            hoverText = "Include this screen when applying the named look, during a staggered recall. Capture selection is independent.") { checked ->
            pool.targetScreens = if (checked) pool.targetScreens + i else pool.targetScreens - i
        }
    }
}
keyframeBrowser(pool.directory, channel = "saved looks and versions", width = 650, height = 420)
displayOpen("shared look pool")
displayOpen("recall targets")
displayOpen("look status")

// Mirror controls at UI rate, including other boxes' presets and global style buttons.
var lastLookStatus = 0L
loop {
    screenLookRecall.tick()
    val now = System.nanoTime()
    if (now - lastLookStatus >= 250_000_000L) {
        lastLookStatus = now
        pool.refreshControls()
        nameControl?.set(pool.selectedName)
        captureControl?.set((pool.captureScreen + 1).toDouble())
        speedRecallControl?.set(pool.restoreSpeed)
        targetControls.forEach { (i, handle) -> handle.set(i in pool.targetScreens) }
        markdown("look status", """
### ${if (lookError.isEmpty()) screenLookRecall.status + " · " + pool.lastAction else "Could not apply/store: $lookError"}

Capture **screen ${pool.captureScreen + 1}** · recall **${pool.targetScreens.sorted().map { it + 1 }}** · speed **${if (pool.restoreSpeed) "restore" else "keep current"}**.

Stagger **${screenLookRecall.staggerSeconds.f2}s / selected screen** · **${screenLookRecall.pendingCount}** pending.

Saved looks: **${pool.names.joinToString(", ").ifEmpty { "none" }}**.

Keys contain accumulation mix, half-life, input weight, paired cadence enable/rate, blur sigma/mix, darkening and shared speed. They retain parameters rather than GPU exposure pixels. A running score can overwrite recalled parameters; stop it for manual look work.
        """, open = false)
    }
    frame()
}
    .end { screenLookRecall.cancel() }
