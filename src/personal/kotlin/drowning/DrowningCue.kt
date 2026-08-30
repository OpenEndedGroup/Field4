package drowning

import afield.four.fabricated.SplatTransport
import afield.four.texture.video.PlaybackClock
import afield.stdlib.linalg.Matrix4f
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

enum class DrowningMxgKind(val fileName: String) {
    Surface("source.mxg"),
    Depth("depth.mxg")
}

data class DrowningRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "rectangle must be non-empty, got $this" }
    }

    fun inside(parent: DrowningRect): DrowningRect =
        DrowningRect(parent.x + x, parent.y + y, width, height)

    fun normalized(atlasWidth: Int, atlasHeight: Int): FloatArray = floatArrayOf(
        x.toFloat() / atlasWidth,
        y.toFloat() / atlasHeight,
        width.toFloat() / atlasWidth,
        height.toFloat() / atlasHeight
    )
}

data class DrowningCalibration(
    val a: Float,
    val b: Float,
    val epsilon: Float,
    val focalLengthPixels: Float,
    val baseline: Float,
    val convergenceDepth: Float,
    val width: Int,
    val height: Int,
    val fps: Double,
    val frameCount: Int
) {
    val centreX: Float get() = width * 0.5f
    val centreY: Float get() = height * 0.5f

    /** q is the relative disparity decoded from the atlas; the calibration stores 1/z = a*q+b. */
    fun depth(relativeDisparity: Float): Float =
        1f / (a * relativeDisparity + b).coerceAtLeast(epsilon)
}

data class DrowningFlow(val x: Float, val y: Float)

data class DrowningCell(
    val index: Int,
    val angleDegrees: Float,
    val directory: File,
    val sourceRect: DrowningRect,
    val disparityRect: DrowningRect,
    val flowRect: DrowningRect,
    val disparityMultiplier: Float,
    val flowZero: Float,
    val flowMultiplierX: Float,
    val flowMultiplierY: Float,
    val calibration: DrowningCalibration,
    /** Places camera-local +z geometry into the shared circle space. */
    val modelFromCamera: Matrix4f,
    /** Projects shared circle-space positions back into this cell's camera. */
    val cameraFromModel: Matrix4f,
    /** Per-cell scored appearance. Opacity affects geometry; influence affects depth/flow/MXG forces. */
    var opacity: Float = 1f,
    var particleInfluence: Float = 1f,
    var projectedLight: Float = 1f,
    /** A fast appearance-only shutter multiplier, normally supplied by [DrowningScore]. */
    var flicker: Float = 1f
) {
    val splatDirectory: File get() = File(directory, "frames")
    fun mxg(kind: DrowningMxgKind): File = File(directory, kind.fileName)

    /** The atlas stores normalized relative disparity in R; G and B are duplicate safety copies. */
    fun decodeDisparity(encodedR: Float): Float = encodedR * disparityMultiplier

    fun decodeDepth(encodedR: Float): Float = calibration.depth(decodeDisparity(encodedR))

    /** Optical flow as authored: full-resolution source pixels per source frame, with +y down. */
    fun decodeFlowSource(encodedR: Float, encodedG: Float): DrowningFlow = DrowningFlow(
        (encodedR - flowZero) * flowMultiplierX,
        (encodedG - flowZero) * flowMultiplierY
    )

    /** Camera/world convention used by the shaders: +y up, hence the single deliberate y flip. */
    fun decodeFlowCamera(encodedR: Float, encodedG: Float): DrowningFlow =
        decodeFlowSource(encodedR, encodedG).let { DrowningFlow(it.x, -it.y) }
}

/**
 * One production cue: the lossless RGB atlas, its 15 cells, and every spatial/decode constant needed
 * to move between a cell's pixels, camera-local geometry, and the shared circle space.
 */
class DrowningCue private constructor(
    val directory: File,
    val format: String,
    val style: String,
    val subject: String,
    val cueOrder: Int,
    val fps: Double,
    val frameCount: Int,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val cells: List<DrowningCell>
) {
    val videoFile: File = File(directory, "circle-15.vrpack.mp4")
    val metadataFile: File = File(directory, "circle-15.vrpack.json")

    init {
        require(format == "splat2-vr-circle-mosaic-v2") { "unsupported vrpack format '$format'" }
        require(cells.size == 15) { "a circle cue needs 15 cells, got ${cells.size}" }
        require(cells.map { it.index } == (0 until 15).toList()) { "cell indices must be exactly 0..14" }
        require(videoFile.isFile) { "missing packed video $videoFile" }
        cells.forEach { cell ->
            require(cell.splatDirectory.isDirectory) { "missing splat frames for cell ${cell.index}: ${cell.splatDirectory}" }
            DrowningMxgKind.entries.forEach { kind ->
                require(cell.mxg(kind).isFile) { "missing ${kind.fileName} for cell ${cell.index}: ${cell.mxg(kind)}" }
            }
        }
    }

    fun splatFrameCounts(): IntArray = IntArray(cells.size) { index ->
        cells[index].splatDirectory.listFiles { file -> file.name.endsWith(".compressed.ply") }?.size ?: 0
    }

    /** Validate the timing/layout contract required for a hitch-free named-look switch. */
    fun requireSwitchCompatible(other: DrowningCue): DrowningCue {
        require(frameCount == other.frameCount && kotlin.math.abs(fps - other.fps) < 1e-6) {
            "look timing mismatch: $frameCount@$fps versus ${other.frameCount}@${other.fps}"
        }
        require(atlasWidth == other.atlasWidth && atlasHeight == other.atlasHeight) {
            "look atlas mismatch: ${atlasWidth}x$atlasHeight versus ${other.atlasWidth}x${other.atlasHeight}"
        }
        require(cells.size == other.cells.size) { "look cell count mismatch" }
        cells.indices.forEach { index ->
            val a = cells[index]
            val b = other.cells[index]
            require(a.index == b.index && a.angleDegrees == b.angleDegrees) { "look camera mismatch at cell $index" }
            require(a.sourceRect == b.sourceRect && a.disparityRect == b.disparityRect && a.flowRect == b.flowRect) {
                "look atlas layout mismatch at cell $index"
            }
            require(a.calibration.width == b.calibration.width && a.calibration.height == b.calibration.height &&
                a.calibration.frameCount == b.calibration.frameCount &&
                kotlin.math.abs(a.calibration.fps - b.calibration.fps) < 1e-6) {
                "look calibration dimensions/timing mismatch at cell $index"
            }
        }
        return other
    }

    fun describe(): String {
        val splats = splatFrameCounts()
        return "cue $cueOrder '$style/$subject': ${atlasWidth}x$atlasHeight RGB atlas, " +
            "$frameCount frames @ ${fps}fps, 15 cells, splats ${splats.minOrNull()}..${splats.maxOrNull()} frames"
    }

    companion object {
        fun open(directory: File): DrowningCue {
            val metadata = File(directory, "circle-15.vrpack.json")
            require(metadata.isFile) { "missing $metadata" }
            val root = Json.parseToJsonElement(metadata.readText()).jsonObject
            val resolution = root.array("resolution")
            val atlasWidth = resolution.int(0)
            val atlasHeight = resolution.int(1)
            val cells = root.array("cells").map { it.jsonObject }.sortedBy { it.int("index") }.map { cell ->
                val index = cell.int("index")
                val elementDirectory = directory.listFiles { f ->
                    f.isDirectory && f.name.startsWith("%02d-".format(index))
                }?.singleOrNull() ?: error("$directory: expected one element directory for cell $index")
                val outer = cell.rect("rectangle")
                val inner = cell.obj("inner_layout")
                val calibration = readCalibration(File(elementDirectory, "calibration.json"))
                val angle = cell.float("angle_degrees")
                val model = yRotation(-Math.toRadians(angle.toDouble()))
                val disparity = cell.obj("disparity")
                val flow = cell.obj("flow")
                val flowMultiplier = flow.array("multiplier")
                DrowningCell(
                    index = index,
                    angleDegrees = angle,
                    directory = elementDirectory,
                    sourceRect = inner.rect("source_rgb").inside(outer),
                    disparityRect = inner.rect("relative_disparity").inside(outer),
                    flowRect = inner.rect("forward_optical_flow").inside(outer),
                    disparityMultiplier = disparity.float("multiplier"),
                    flowZero = flow.float("zero"),
                    flowMultiplierX = flowMultiplier.float(0),
                    flowMultiplierY = flowMultiplier.float(1),
                    calibration = calibration,
                    modelFromCamera = model,
                    cameraFromModel = Matrix4f(model).invert()
                )
            }
            return DrowningCue(
                directory = directory,
                format = root.string("format"),
                style = root.string("style"),
                subject = root.string("subject"),
                cueOrder = root.int("cue_order"),
                fps = root.double("fps"),
                frameCount = root.int("frames"),
                atlasWidth = atlasWidth,
                atlasHeight = atlasHeight,
                cells = cells
            )
        }

        fun open(productionPack: File, revision: String, cue: String, look: String): DrowningCue =
            open(File(productionPack, "$revision/$cue/$look"))

        private fun readCalibration(file: File): DrowningCalibration {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val resolution = root.array("resolution")
            val frameRange = root.array("source_frame_range")
            return DrowningCalibration(
                a = root.float("a"),
                b = root.float("b"),
                epsilon = root.float("epsilon"),
                focalLengthPixels = root.float("focal_length_px"),
                baseline = root.float("baseline"),
                convergenceDepth = root.float("convergence_depth"),
                width = resolution.int(0),
                height = resolution.int(1),
                fps = root.double("fps"),
                frameCount = frameRange.int(1) - frameRange.int(0)
            )
        }

        private fun yRotation(radians: Double): Matrix4f {
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            return Matrix4f().set(
                c, 0f, -s, 0f,
                0f, 1f, 0f, 0f,
                s, 0f, c, 0f,
                0f, 0f, 0f, 1f
            )
        }
    }
}

/** One editable playhead feeding the atlas plus 15 related splat/MXG playheads. */
class DrowningTransport(
    val frameCount: Int,
    fps: Double,
    val elementOffsets: DoubleArray = DoubleArray(15)
) : PlaybackClock {
    val splats = SplatTransport(frameCount, fps)

    init {
        require(elementOffsets.size == 15) { "elementOffsets must contain 15 frame offsets" }
    }

    var fps: Double
        get() = splats.fps
        set(value) { splats.fps = value }
    var playing: Boolean
        get() = splats.playing
        set(value) { splats.playing = value }
    var speed: Double
        get() = splats.speed
        set(value) { splats.speed = value }
    var loop: Boolean
        get() = splats.loop
        set(value) { splats.loop = value }
    var position: Double
        get() = splats.position
        set(value) { splats.position = value }
    var normalized: Double
        get() = splats.normalized
        set(value) { splats.normalized = value }
    val frame: Int get() = splats.frame

    fun advance(nowNanos: Long = System.nanoTime()): Boolean = splats.advance(nowNanos)
    fun seek(frame: Double): Boolean = splats.seek(frame)
    fun scrubBy(delta: Double): Boolean = splats.scrubBy(delta)
    fun invalidate() = splats.invalidate()
    fun resetClock() = splats.resetClock()

    fun unwrappedFrameFor(element: Int): Int {
        require(element in elementOffsets.indices)
        return floor(position + elementOffsets[element]).toInt()
    }

    fun frameFor(element: Int, availableFrames: Int = frameCount): Int {
        require(availableFrames > 0)
        return Math.floorMod(unwrappedFrameFor(element), availableFrames)
    }

    override fun getCurrentTime(): Double = position / fps
    override fun isPlaying(): Boolean = playing && speed != 0.0
    override fun toString(): String = "DrowningTransport(${splats}, offsets=${elementOffsets.contentToString()})"
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
private fun JsonObject.double(name: String): Double = getValue(name).jsonPrimitive.double
private fun JsonObject.float(name: String): Float = double(name).toFloat()
private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
private fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
private fun JsonObject.rect(name: String): DrowningRect = obj(name).let {
    DrowningRect(it.int("x"), it.int("y"), it.int("width"), it.int("height"))
}
private fun JsonArray.int(index: Int): Int = get(index).jsonPrimitive.int
private fun JsonArray.float(index: Int): Float = get(index).jsonPrimitive.double.toFloat()
