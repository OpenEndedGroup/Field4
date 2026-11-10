package afield.graphics.scene.video

import afield.animation.keyframes.KeyframeScope
import java.nio.file.Files
import java.nio.file.Path

/** The independently editable appearance of a screen; GPU history and shared transport are separate. */
data class StereoVideoLook(
    val accumulationMix: Float,
    val halfLife: Double,
    val inputWeight: Double,
    val cadenceEnabled: Boolean,
    val pairsPerSecond: Double,
    val blurSigma: Float,
    val blurMix: Float,
    val darkening: Float,
    val opacity: Float = 1f,
    val blackLevel: Float = 0f,
    val whiteLevel: Float = 1f,
    val gamma: Float = 1f,
    val autoExposureMix: Float = 1f,
) {
    fun applyTo(screen: StereoVideoSurface) {
        screen.accumulationMix = accumulationMix
        screen.accumulationHalfLife = halfLife
        screen.accumulationInputWeight = inputWeight
        screen.exposureCadenceEnabled = cadenceEnabled
        screen.exposurePairsPerSecond = pairsPerSecond
        screen.exposureBlurSigma = blurSigma
        screen.exposureBlurMix = blurMix
        screen.exposureDarkening = darkening
        screen.opacity = opacity
        screen.accumulationBlackLevel = blackLevel
        screen.accumulationWhiteLevel = whiteLevel
        screen.accumulationGamma = gamma
        screen.autoExposureMix = autoExposureMix
    }

    companion object {
        fun capture(screen: StereoVideoSurface) = StereoVideoLook(screen.accumulationMix,
            screen.accumulationHalfLife, screen.accumulationInputWeight, screen.exposureCadenceEnabled,
            screen.exposurePairsPerSecond, screen.exposureBlurSigma, screen.exposureBlurMix, screen.exposureDarkening,
            screen.opacity, screen.accumulationBlackLevel, screen.accumulationWhiteLevel, screen.accumulationGamma, screen.autoExposureMix)
    }
}

/** Native, versioned keyframes shared across screens; capture one look, recall it onto a chosen subset. */
class StereoVideoLookPool internal constructor(
    val directory: Path,
    private val count: () -> Int,
    private val read: (Int) -> StereoVideoLook,
    private val write: (Int, StereoVideoLook) -> Unit,
    private val readSpeed: () -> Double,
    private val writeSpeed: (Double) -> Unit,
) {
    /** Resolve [carousel] on access, so relaunching its box does not leave keys attached to retired surfaces. */
    constructor(directory: Path, carousel: () -> StereoVideoCarousel) : this(directory,
        { carousel().surfaces.size }, { StereoVideoLook.capture(carousel().surfaces[it]) },
        { i, look -> look.applyTo(carousel().surfaces[i]) },
        { carousel().source.speed }, { carousel().source.speed = it })

    var captureScreen = 0
        set(value) { require(value in 0 until count()); field = value }
    var targetScreens: Set<Int> = (0 until count()).toSet()
        set(value) { require(value.all { it in 0 until count() }); field = value.toSet() }
    /** Speed is always stored. This switch gates its setter even for native browser Apply/morph. */
    var restoreSpeed = false
    var selectedName = "slow blur"
    val scope = KeyframeScope(schemaVersion = 1)
    private val controls = mutableMapOf<Int, () -> Unit>()
    var lastAction = "Ready"
        private set

    init {
        require(count() > 0)
        scope.floatTarget("exposure.mix", { read(captureScreen).accumulationMix }) { value ->
            change { it.copy(accumulationMix = value) }
        }
        scope.doubleTarget("exposure.halfLife", { read(captureScreen).halfLife }) { value ->
            change { it.copy(halfLife = value) }
        }
        scope.doubleTarget("exposure.inputWeight", { read(captureScreen).inputWeight }) { value ->
            change { it.copy(inputWeight = value) }
        }
        scope.booleanTarget("cadence.enabled", { read(captureScreen).cadenceEnabled }) { value ->
            change { it.copy(cadenceEnabled = value) }
        }
        scope.doubleTarget("cadence.pairsPerSecond", { read(captureScreen).pairsPerSecond }) { value ->
            change { it.copy(pairsPerSecond = value) }
        }
        scope.floatTarget("cadence.blurSigma", { read(captureScreen).blurSigma }) { value ->
            change { it.copy(blurSigma = value) }
        }
        scope.floatTarget("cadence.blurMix", { read(captureScreen).blurMix }) { value ->
            change { it.copy(blurMix = value) }
        }
        scope.floatTarget("cadence.darkening", { read(captureScreen).darkening }) { value ->
            change { it.copy(darkening = value) }
        }
        scope.floatTarget("appearance.opacity", { read(captureScreen).opacity }) { value ->
            change { it.copy(opacity = value) }
        }
        scope.floatTarget("presentation.black", { read(captureScreen).blackLevel }) { value -> change { it.copy(blackLevel = value) } }
        scope.floatTarget("presentation.white", { read(captureScreen).whiteLevel }) { value -> change { it.copy(whiteLevel = value) } }
        scope.floatTarget("presentation.gamma", { read(captureScreen).gamma }) { value -> change { it.copy(gamma = value) } }
        scope.floatTarget("presentation.autoMix", { read(captureScreen).autoExposureMix }) { value -> change { it.copy(autoExposureMix = value) } }
        scope.doubleTarget("playback.speed", readSpeed) { value -> if (restoreSpeed) writeSpeed(value) }
    }

    private fun change(update: (StereoVideoLook) -> StereoVideoLook) {
        targetScreens.forEach { index -> write(index, update(read(index))) }
        refreshControls()
    }

    /** Replace a screen's previous widget binding on rerun, rather than keeping stale control handles. */
    fun watchControls(screen: Int, refresh: () -> Unit) { require(screen in 0 until count()); controls[screen] = refresh }

    /** Handles suppress unchanged values; call at UI cadence to reflect scores, styles and recalled keys. */
    fun refreshControls() { controls.values.toList().forEach { it() } }

    val names: List<String> get() = scope.savedKeyNames(directory)

    fun path(name: String): Path {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean !in setOf(".", "..") && clean.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "Choose a look name without path separators or control characters"
        }
        return directory.resolve(clean)
    }

    /** Store the appearance lanes plus the shared speed, without changing a screen. */
    fun store(name: String, screen: Int = captureScreen, note: String? = null): KeyframeScope.Key {
        captureScreen = screen
        return scope.capture().save(path(name), note).also {
            selectedName = name.trim()
            lastAction = "Stored ${name.trim()} from screen ${screen + 1}"
        }
    }

    /** Apply to [screens]; an empty set is useful for a speed-only recall when [withSpeed] is true. */
    fun recall(name: String, screens: Collection<Int> = targetScreens, withSpeed: Boolean = restoreSpeed): Boolean {
        val file = path(name)
        if (!Files.isRegularFile(file)) { lastAction = "No saved look: ${name.trim()}"; return false }
        require(screens.isNotEmpty() || withSpeed) { "Choose target screens, or enable global speed recall" }
        targetScreens = screens.toSet()
        restoreSpeed = withSpeed
        scope.loadKey(file)()
        selectedName = name.trim()
        lastAction = "Recalled ${name.trim()} on ${targetScreens.sorted().map { it + 1 }}; speed ${if (withSpeed) "restored" else "kept"}"
        return true
    }
}
