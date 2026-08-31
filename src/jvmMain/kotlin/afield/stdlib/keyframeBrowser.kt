@file:afield.CompletionCategory("Visualization")

package afield.stdlib

import afield.four.util.CameraPose
import afield.logger.lprintln
import afield.stdlib.linalg.DualQuaternionf
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.streams.asSequence

/**
 * Reading a *collection* of saved keys the way [KeyframeScope] reads one: a glob of key files in,
 * a slot-major summary out.
 *
 * ## Why slot-major
 *
 * A directory of keys is a sparse three-axis cube — file × version × slot — and only one of those
 * axes tells you anything you did not already know. You know your file names. What you cannot see
 * from a directory listing is which slots you actually kept pushing on, which have been frozen since
 * the first save, and which are missing from half the files because the scope grew a target after
 * they were written. So this pivots to slots, and every summary here is computed across files
 * rather than within one.
 *
 * ## What is read, and what is not
 *
 * [load] decodes each matched file straight to [KeyDTO] rather than going through
 * [KeyframeScope.loadKey]. That is deliberate: `loadKey` fills [KeyframeScope.missingKey] defaults
 * for names a file lacks, which is exactly right when you are about to apply a key and exactly wrong
 * when you are trying to *see* which names a file lacks. The browser wants the file as written.
 *
 * Version history is read lazily. [load] calls [KeyframeScope.keyVersions] for each file, which
 * lists a directory and parses one manifest, but it does not open the numbered snapshots. Those are
 * only read when [slotHistory] is asked for one slot's trajectory — a hundred saves of ten files is
 * a thousand files, and no one wants that on open.
 *
 * ## Failure is per-file
 *
 * A collection with one unparseable file still browses. [load] records the failure in
 * [KeyframeCollection.problems] and carries on, because the alternative — one bad file making the
 * other nine invisible — is how you lose an afternoon.
 */

// ---------------------------------------------------------------------------------------------
// Discovery
// ---------------------------------------------------------------------------------------------

/** The same JSON configuration `KeyframeScope` persists with — `type` discriminator, lenient reads. */
private val keyBrowserJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    encodeDefaults = true
}

/**
 * Every regular file under [root] whose path relative to [root] matches the glob [pattern], sorted
 * by path, **excluding anything inside a `.versions` directory**.
 *
 * That exclusion is the whole reason this is not three lines at the call site. A key file's history
 * lives in `<file>.versions/000000.json` beside it, so the obvious recursive-json glob finds every
 * snapshot ever saved and reports a collection of seven keys as one of four hundred. The history is
 * reached through [KeyframeScope.keyVersions], never by matching it as a key in its own right.
 *
 * (The glob itself cannot be written in this KDoc: a recursive wildcard followed by a slash puts a
 * comment terminator in the middle of the sentence and swallows the rest of the file.)
 *
 * Unlike `FileManager`'s glob resolution — which is `findFirst` and answers "which file did you
 * mean" — this answers "which files are there", so it returns all of them.
 */
fun keyFilesMatching(root: Path, pattern: String = "**/*.json"): List<Path> {
    if (!root.exists() || !root.isDirectory()) return emptyList()
    val normalized = pattern.replace('\\', '/')
    val matcher = root.fileSystem.getPathMatcher("glob:$normalized")
    // A bare `*.json` should still find `looks/*.json` the way FileManager's alternate pattern does,
    // but only when the caller did not already say where to look.
    val alternate = if (normalized.startsWith("**/")) {
        root.fileSystem.getPathMatcher("glob:${normalized.removePrefix("**/")}")
    } else if (!normalized.contains('/')) {
        root.fileSystem.getPathMatcher("glob:**/$normalized")
    } else null

    return Files.walk(root).use { stream ->
        stream.asSequence()
            .filter { it.isRegularFile() }
            .filter { path -> root.relativize(path).none { it.name.endsWith(".versions") } }
            .filter { path ->
                val relative = root.relativize(path)
                matcher.matches(relative) || alternate?.matches(relative) == true
            }
            .sortedBy { it.toString() }
            .toList()
    }
}

/** One key file as the browser sees it: what is on disk, plus how many times it has been saved. */
data class KeyframeFile(
    /** Stable index within the collection, used as the wire identity for selection and diffing. */
    val id: Int,
    val path: Path,
    /** [path] relative to the collection root, with `/` separators — what the UI shows. */
    val relative: String,
    /** [KeyDTO.schemaVersion] as written. Zero both when it was written as zero and when absent. */
    val schemaVersion: Int,
    /** The file's entries exactly as written — no [KeyframeScope.missingKey] defaults applied. */
    val values: Map<String, KValue>,
    /** The save history from `<file>.versions`, oldest first; empty when nothing has been saved. */
    val versions: List<KeyVersionInfo>
) {
    /** The most recent history row, which carries the note and timestamp the file name cannot. */
    val latest: KeyVersionInfo? get() = versions.lastOrNull()
}

/**
 * A loaded collection of key files and the slot pivot over them.
 *
 * Construct with [load]. Holding one pins the decoded values in memory — a few kilobytes per file —
 * which is what makes [slots] and [slot] cheap to ask repeatedly while a person clicks around.
 */
class KeyframeCollection(
    val root: Path,
    val pattern: String,
    val files: List<KeyframeFile>,
    /** Per-file failures, one human-readable line each. A collection with problems still browses. */
    val problems: List<String>,
    /**
     * Slot names the live scope watches, from `scope.capture().values.keys`. Used to mark a slot the
     * scope has but no file does — the "you have never saved this" row, which is the one thing a
     * collection cannot tell you about itself.
     */
    val scopeSlots: Set<String> = emptySet()
) {
    /** Every slot name in any file, plus any [scopeSlots] no file has, sorted. */
    val slotNames: List<String> =
        (files.flatMap { it.values.keys } + scopeSlots).distinct().sorted()

    val versionCount: Int get() = files.sumOf { it.versions.size }

    /** The distinct [KeyDTO.schemaVersion]s present, ascending — more than one means schema drift. */
    val schemaVersions: List<Int> = files.map { it.schemaVersion }.distinct().sorted()

    /** The newest history row across every file, by timestamp. */
    val latest: KeyVersionInfo? =
        files.mapNotNull { it.latest }.maxByOrNull { runCatching { Instant.parse(it.timestamp) }.getOrNull() ?: Instant.EPOCH }

    fun file(id: Int): KeyframeFile? = files.firstOrNull { it.id == id }

    /** The pivot: one [SlotSummary] per name in [slotNames], in the same order. */
    fun slots(): List<SlotSummary> = slotNames.map { slot(it) }

    /** The summary for one slot — what value it takes in each file, and what that set looks like. */
    fun slot(name: String): SlotSummary = summariseSlot(name, files, scopeSlots.contains(name))

    /**
     * One slot's trajectory through a single file's save history, oldest first — the view that
     * answers "what did I actually keep pushing on".
     *
     * This is the only call here that opens the numbered snapshots, and it opens all of them for the
     * one file, so it belongs behind a click rather than on the initial load. Versions the slot is
     * absent from contribute a null rather than being skipped, so the index of an entry is the index
     * of the save.
     */
    fun slotHistory(fileId: Int, slot: String): List<Pair<KeyVersionInfo, KValue?>> {
        val file = file(fileId) ?: return emptyList()
        val dir = file.path.resolveSibling("${file.path.name}.versions")
        return file.versions.map { info ->
            val text = runCatching { Files.readString(dir.resolve(info.file)) }.getOrNull()
            val dto = text?.let { runCatching { keyBrowserJson.decodeFromString<KeyDTO>(it) }.getOrNull() }
            info to dto?.values?.get(slot)
        }
    }

    companion object {
        /**
         * Decode every file under [root] matching [pattern] into a collection.
         *
         * [scope] is optional and read-only: it is used for [scopeSlots] (via `capture()`, which
         * touches nothing live) so the browser can show a slot the scope watches and no file holds.
         */
        fun load(
            root: Path,
            pattern: String = "**/*.json",
            scope: KeyframeScope? = null
        ): KeyframeCollection {
            val problems = mutableListOf<String>()
            val paths = runCatching { keyFilesMatching(root, pattern) }.getOrElse { e ->
                problems += "could not search $root: ${e.message ?: e::class.simpleName}"
                emptyList()
            }

            // One bare scope for the whole load: `keyVersions` only reads the filesystem, and a scope
            // with no targets and no missingKey defaults adds nothing per file.
            val reader = KeyframeScope()

            val files = paths.mapIndexedNotNull { index, path ->
                val text = runCatching { Files.readString(path) }.getOrElse { e ->
                    problems += "${root.relativize(path)}: unreadable (${e.message ?: e::class.simpleName})"
                    return@mapIndexedNotNull null
                }
                val dto = runCatching { keyBrowserJson.decodeFromString<KeyDTO>(text) }.getOrElse { e ->
                    // Not a key file, or a damaged one. Either way the other files are still fine, and
                    // saying which one failed is more use than a stack trace over the whole directory.
                    problems += "${root.relativize(path)}: not a readable key (${e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName})"
                    return@mapIndexedNotNull null
                }
                val versions = runCatching { reader.keyVersions(path) }.getOrElse { e ->
                    problems += "${root.relativize(path)}: version history unreadable (${e.message ?: e::class.simpleName})"
                    emptyList()
                }
                KeyframeFile(
                    id = index,
                    path = path,
                    relative = root.relativize(path).toString().replace('\\', '/'),
                    schemaVersion = dto.schemaVersion,
                    values = dto.values,
                    versions = versions
                )
            }

            val scopeSlots = scope?.let {
                runCatching { it.capture().values.keys.toSet() }.getOrElse { e ->
                    lprintln("error,keyframer", "keyframe browser could not capture the scope: ${e.message}")
                    emptySet()
                }
            } ?: emptySet()

            return KeyframeCollection(root, pattern, files, problems, scopeSlots)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The slot pivot
// ---------------------------------------------------------------------------------------------

/** A low–high band with the individual values marked inside it, as a range strip is drawn. */
@Serializable
data class SlotBand(
    val label: String,
    val lo: Double,
    val hi: Double,
    /** Each value's position in `0..1` across `lo..hi`. A degenerate band puts every mark at 0.5. */
    val marks: List<Double>
)

/** One distinct value of a slot that varies discretely, and which files hold it. */
@Serializable
data class SlotVariant(
    /** Full text — source for a code slot, the string itself for a string slot. */
    val text: String,
    /** First line, clipped, for the collapsed row. */
    val preview: String,
    val count: Int,
    val fileIds: List<Int>
)

/**
 * How a slot's spread should be drawn. The split is not cosmetic: it follows
 * [KeyframeScope.interpolate], which blends numbers and vectors and *steps* strings, StackSL source
 * and source blocks. A slot that steps has no range, only a set of variants, and drawing a bar for
 * one would claim a continuum that does not exist.
 */
@Serializable
sealed interface SlotDisplay {
    /** A single numeric band. */
    @Serializable @SerialName("range")
    data class Range(val band: SlotBand, val fileIds: List<Int>) : SlotDisplay

    /** One band per component — `x`/`y`/`z`, or the four of a quaternion. */
    @Serializable @SerialName("components")
    data class Components(val bands: List<SlotBand>, val fileIds: List<Int>) : SlotDisplay

    /** A `vec4` whose components all sit in `0..1` in every file: shown as colour, plus its bands. */
    @Serializable @SerialName("swatches")
    data class Swatches(val colors: List<String>, val fileIds: List<Int>, val bands: List<SlotBand>) : SlotDisplay

    /**
     * A rigid transform or camera pose: the translations as a 2D scatter (x across, z up — the plan
     * view a camera move is usually read in), the orientation spread as an angle.
     */
    @Serializable @SerialName("poses")
    data class Poses(
        val points: List<List<Double>>,
        val fileIds: List<Int>,
        val arcDegrees: Double,
        val bands: List<SlotBand>
    ) : SlotDisplay

    /** A slot that steps rather than blends. */
    @Serializable @SerialName("variants")
    data class Variants(val variants: List<SlotVariant>) : SlotDisplay

    /** Nothing to draw: the slot is absent, or holds types that cannot be summarised together. */
    @Serializable @SerialName("note")
    data class Note(val text: String, val severity: String = "info") : SlotDisplay
}

/** The pivot's output for one slot name. */
@Serializable
data class SlotSummary(
    val name: String,
    /** The [KValue] serial name shared by every present value, `"mixed"`, or `"absent"`. */
    val kind: String,
    /** Human label for the type column — `float`, `vec4`, `pose`, `stacksl`, `mixed (num, string)`. */
    val typeLabel: String,
    val presentIn: Int,
    val total: Int,
    /** True when a live scope watches this name. */
    val inScope: Boolean,
    /** The right-hand column: `2.0 – 11.4`, `7 swatches`, `3 variants`. */
    val summaryText: String,
    val display: SlotDisplay
)

private fun kValueKind(v: KValue): String = when (v) {
    is KValue.Num -> "num"
    is KValue.Vec2 -> "vec2"
    is KValue.Vec3 -> "vec3"
    is KValue.Vec4 -> "vec4"
    is KValue.Quat -> "quat"
    is KValue.DualQuat -> "dualquat"
    is KValue.CameraPoseValue -> "cameraPose"
    is KValue.StackSL -> "stacksl"
    is KValue.Str -> "string"
    is KValue.SourceBlock -> "sourceBlock"
}

private fun typeLabelFor(kind: String, values: List<KValue>): String = when (kind) {
    // A numeric slot's useful label is its NumKind, not "num" — an Int uniform and a Float uniform
    // behave differently on apply, and a slot that is Int in one file and Float in another is a
    // real hazard worth seeing in the type column.
    "num" -> values.filterIsInstance<KValue.Num>().map { it.kind.name.lowercase() }.distinct().sorted().joinToString("/")
    "cameraPose" -> "pose"
    "sourceBlock" -> "source"
    else -> kind
}

/** Format a number the way the range columns do: short, but never lying about magnitude. */
internal fun formatSlotNumber(v: Double): String = when {
    v.isNaN() -> "NaN"
    v.isInfinite() -> if (v > 0) "∞" else "-∞"
    v == 0.0 -> "0"
    abs(v) >= 1e6 || abs(v) < 1e-4 -> String.format("%.3g", v)
    v == v.toLong().toDouble() -> v.toLong().toString()
    else -> String.format("%.4g", v).trimEnd('0').trimEnd('.')
}

private fun bandOf(label: String, values: List<Double>): SlotBand {
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty()) return SlotBand(label, 0.0, 0.0, values.map { 0.5 })
    val lo = finite.min()
    val hi = finite.max()
    val span = hi - lo
    val marks = values.map { if (!it.isFinite() || span <= 0.0) 0.5 else (it - lo) / span }
    return SlotBand(label, lo, hi, marks)
}

private fun bandText(band: SlotBand): String =
    if (band.lo == band.hi) formatSlotNumber(band.lo)
    else "${formatSlotNumber(band.lo)} – ${formatSlotNumber(band.hi)}"

private fun clip(text: String, n: Int = 64): String {
    val oneLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
    val more = text.lineSequence().count() > 1
    val head = if (oneLine.length > n) oneLine.take(n - 1) + "…" else oneLine
    return if (more && !head.endsWith("…")) "$head …" else head
}

private fun variantsOf(present: List<Pair<Int, KValue>>): List<SlotVariant> {
    fun textOf(v: KValue): String = when (v) {
        is KValue.Str -> v.value
        is KValue.StackSL -> v.value
        is KValue.SourceBlock -> v.source
        else -> v.toString()
    }
    return present
        .groupBy { textOf(it.second) }
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, List<Pair<Int, KValue>>>> { it.value.size }.thenBy { it.key })
        .map { (text, hits) ->
            SlotVariant(
                text = text,
                preview = clip(text),
                count = hits.size,
                fileIds = hits.map { it.first }
            )
        }
}

/** Components of a vec/quat value as `label to component` pairs, in declaration order. */
private fun componentsOf(v: KValue): List<Pair<String, Double>> = when (v) {
    is KValue.Vec2 -> listOf("x" to v.x.toDouble(), "y" to v.y.toDouble())
    is KValue.Vec3 -> listOf("x" to v.x.toDouble(), "y" to v.y.toDouble(), "z" to v.z.toDouble())
    is KValue.Vec4 -> listOf("x" to v.x.toDouble(), "y" to v.y.toDouble(), "z" to v.z.toDouble(), "w" to v.w.toDouble())
    is KValue.Quat -> listOf("x" to v.x.toDouble(), "y" to v.y.toDouble(), "z" to v.z.toDouble(), "w" to v.w.toDouble())
    else -> emptyList()
}

private fun toQuaternion(v: KValue): Quaternionf? = when (v) {
    is KValue.Quat -> Quaternionf(v.x, v.y, v.z, v.w)
    is KValue.DualQuat -> (v.toAny() as DualQuaternionf).getRotation()
    is KValue.CameraPoseValue -> (v.toAny() as CameraPose).rotation
    else -> null
}

private fun toTranslation(v: KValue): Vector3f? = when (v) {
    is KValue.DualQuat -> (v.toAny() as DualQuaternionf).getTranslation()
    is KValue.CameraPoseValue -> (v.toAny() as CameraPose).position
    else -> null
}

/**
 * The widest angle between any two orientations in [quats], in degrees.
 *
 * Pairwise rather than spread-about-a-mean because a mean of quaternions is its own small research
 * project and this only has to answer "did the camera barely move, or swing right round". `abs` on
 * the dot handles the double cover: `q` and `-q` are the same orientation and must read as 0°, not
 * 180°.
 */
private fun angularSpreadDegrees(quats: List<Quaternionf>): Double {
    if (quats.size < 2) return 0.0
    var worst = 0.0
    for (i in quats.indices) {
        for (j in i + 1 until quats.size) {
            val d = abs(quats[i].dot(quats[j]).toDouble()).coerceIn(0.0, 1.0)
            worst = max(worst, 2.0 * acos(d))
        }
    }
    return Math.toDegrees(worst)
}

private fun summariseSlot(name: String, files: List<KeyframeFile>, inScope: Boolean): SlotSummary {
    val present: List<Pair<Int, KValue>> = files.mapNotNull { f -> f.values[name]?.let { f.id to it } }
    val total = files.size
    val fileIds = present.map { it.first }
    val values = present.map { it.second }

    if (values.isEmpty()) {
        return SlotSummary(
            name = name,
            kind = "absent",
            typeLabel = "—",
            presentIn = 0,
            total = total,
            inScope = inScope,
            summaryText = "—",
            display = SlotDisplay.Note(
                if (inScope) "in the scope, in no key on disk — capture would fill it"
                else "in no key on disk",
                if (inScope) "warn" else "info"
            )
        )
    }

    val kinds = values.map { kValueKind(it) }.distinct()
    if (kinds.size > 1) {
        // Two different types under one name is not a range, it is a bug or a rename half-done. Say
        // so rather than picking one and summarising it as if the others were not there.
        val counts = values.groupingBy { kValueKind(it) }.eachCount().entries.sortedBy { it.key }
        return SlotSummary(
            name = name,
            kind = "mixed",
            typeLabel = "mixed (${kinds.sorted().joinToString(", ")})",
            presentIn = present.size,
            total = total,
            inScope = inScope,
            summaryText = "${kinds.size} types",
            display = SlotDisplay.Note(
                counts.joinToString(", ") { "${it.key} ×${it.value}" } + " — this slot changed type between saves",
                "warn"
            )
        )
    }

    val kind = kinds.single()
    val typeLabel = typeLabelFor(kind, values)

    fun summary(text: String, display: SlotDisplay) =
        SlotSummary(name, kind, typeLabel, present.size, total, inScope, text, display)

    return when (kind) {
        "num" -> {
            val band = bandOf("value", values.map { (it as KValue.Num).value })
            summary(bandText(band), SlotDisplay.Range(band, fileIds))
        }

        "vec2", "vec3" -> {
            val bands = buildComponentBands(values)
            summary(spreadText(bands), SlotDisplay.Components(bands, fileIds))
        }

        "vec4" -> {
            val bands = buildComponentBands(values)
            // A vec4 whose every component sits inside 0..1 in every file is almost always a colour,
            // and a row of swatches says more in the same width than four near-identical bars. The
            // bands ride along regardless, so nothing is hidden by the guess.
            val colorish = values.all { v ->
                componentsOf(v).all { (_, c) -> c in 0.0..1.0 }
            }
            if (colorish) {
                val colors = values.map { v ->
                    val c = componentsOf(v).map { it.second }
                    // Round rather than truncate: a mid-grey 0.5 is 128, and truncation would send
                    // everything in [254/255, 1) to 254 while only an exact 1.0 reached 255.
                    fun channel(x: Double) = (x * 255).roundToInt().coerceIn(0, 255)
                    "#%02x%02x%02x".format(channel(c[0]), channel(c[1]), channel(c[2]))
                }
                summary("${colors.distinct().size} swatches", SlotDisplay.Swatches(colors, fileIds, bands))
            } else {
                summary(spreadText(bands), SlotDisplay.Components(bands, fileIds))
            }
        }

        "quat" -> {
            val spread = angularSpreadDegrees(values.mapNotNull { toQuaternion(it) })
            summary(
                "${formatSlotNumber(spread)}° arc",
                SlotDisplay.Components(buildComponentBands(values), fileIds)
            )
        }

        "dualquat", "cameraPose" -> {
            val translations = values.mapNotNull { toTranslation(it) }
            val spread = angularSpreadDegrees(values.mapNotNull { toQuaternion(it) })
            val bands = mutableListOf(
                bandOf("x", translations.map { it.x.toDouble() }),
                bandOf("y", translations.map { it.y.toDouble() }),
                bandOf("z", translations.map { it.z.toDouble() })
            )
            if (kind == "cameraPose") {
                bands += bandOf("focus", values.map { (it as KValue.CameraPoseValue).focusDistance.toDouble() })
            }
            // Plan view: x across, z up. Normalised over the union of both extents so the scatter is
            // not stretched into a line by a camera that only moved along one axis.
            val xs = translations.map { it.x.toDouble() }
            val zs = translations.map { it.z.toDouble() }
            val lo = min(xs.minOrNull() ?: 0.0, zs.minOrNull() ?: 0.0)
            val hi = max(xs.maxOrNull() ?: 0.0, zs.maxOrNull() ?: 0.0)
            val span = (hi - lo).takeIf { it > 0.0 }
            val points = translations.map {
                listOf(
                    if (span == null) 0.5 else (it.x.toDouble() - lo) / span,
                    if (span == null) 0.5 else (it.z.toDouble() - lo) / span
                )
            }
            summary("${formatSlotNumber(spread)}° arc", SlotDisplay.Poses(points, fileIds, spread, bands))
        }

        "string", "stacksl", "sourceBlock" -> {
            val variants = variantsOf(present)
            val text = if (variants.size == 1) "1 value" else "${variants.size} variants"
            summary(text, SlotDisplay.Variants(variants))
        }

        else -> summary("—", SlotDisplay.Note("no summary for $kind"))
    }
}

private fun buildComponentBands(values: List<KValue>): List<SlotBand> {
    val labels = componentsOf(values.first()).map { it.first }
    return labels.mapIndexed { i, label ->
        bandOf(label, values.map { componentsOf(it)[i].second })
    }
}

/** The right-hand column for a multi-component slot: the widest component's range. */
private fun spreadText(bands: List<SlotBand>): String {
    val widest = bands.maxByOrNull { it.hi - it.lo } ?: return "—"
    return if (bands.all { it.lo == it.hi }) "fixed" else "${widest.label} ${bandText(widest)}"
}
