@file:afield.runtime.completion.CompletionCategory("Animation")

package afield.animation.mocap

import afield.geometry.path.FLine
import afield.graphics.scene.GltfSkinning
import afield.math.linalg.Matrix3f
import afield.math.linalg.Matrix4f
import afield.math.linalg.Quat
import afield.math.linalg.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Motion uses metres, seconds, right handed Y-up coordinates and xyzw quaternions.
 * This matches Field's glTF scene coordinates. Cameras and projection remain the caller's.
 * [sourceToField] is a row-major orthogonal basis, also used for forces; moments are axial vectors.
 */
class MotionCoordinates(
    val sourceUnits: String = "m",
    val sourceHandedness: String = "right",
    val sourceUp: String = "+Y",
    val sourceForward: String = "+Z",
    sourceToField: List<Double> = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
    val metresPerUnit: Double = 1.0,
) {
    val sourceToField = sourceToField.toList()
    private val basis: Matrix3f
    val determinant: Float
    init {
        require(sourceToField.size == 9 && sourceToField.all(Double::isFinite))
        require(metresPerUnit.isFinite() && metresPerUnit > 0)
        val b = this.sourceToField
        basis = Matrix3f().set(b[0].toFloat(), b[3].toFloat(), b[6].toFloat(),
            b[1].toFloat(), b[4].toFloat(), b[7].toFloat(), b[2].toFloat(), b[5].toFloat(), b[8].toFloat())
        determinant = basis.determinant()
        for (i in 0..2) for (j in 0..2) {
            val dot = (0..2).sumOf { b[it * 3 + i] * b[it * 3 + j] }
            require(abs(dot - if (i == j) 1.0 else 0.0) < 1e-5) { "Coordinate basis must be orthogonal" }
        }
    }
    fun position(p: Vec3): Vec3 = basis.transform(Vec3(p)).mul(metresPerUnit.toFloat())
    fun direction(p: Vec3): Vec3 = basis.transform(Vec3(p))
    fun moment(p: Vec3): Vec3 = direction(p).mul(determinant)
    fun rotation(q: Quat): Quat {
        val r = Matrix3f().rotation(q)
        val transformed = Matrix3f(basis).mul(r).mul(Matrix3f(basis).transpose())
        return Quat().setFromNormalized(transformed).normalize()
    }
    fun json(): JSONObject = JSONObject().put("sourceUnits", sourceUnits).put("sourceHandedness", sourceHandedness)
        .put("sourceUp", sourceUp).put("sourceForward", sourceForward).put("sourceToField", sourceToField)
        .put("metresPerUnit", metresPerUnit).put("common", "RH_Y_UP_METRES_XYZW")
    companion object {
        fun fromJson(j: JSONObject): MotionCoordinates = MotionCoordinates(j.getString("sourceUnits"),
            j.getString("sourceHandedness"), j.getString("sourceUp"), j.getString("sourceForward"),
            j.getJSONArray("sourceToField").doubles(), j.getDouble("metresPerUnit")).also {
                require(j.getString("common") == "RH_Y_UP_METRES_XYZW")
            }
        /** Isaac Gym's Z-up world mapped by a proper rotation: (x,y,z) -> (x,z,-y). */
        fun isaacGym() = MotionCoordinates(sourceUp = "+Z", sourceForward = "+X",
            sourceToField = listOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, 0.0))
    }
}

/** A rigid body is explicitly named and mapped; a joint index is never a force-application body. */
data class MotionBody(val name: String, val joint: String, val massKg: Double? = null) {
    init { require(name.isNotBlank() && joint.isNotBlank()); require(massKg == null || massKg.isFinite() && massKg > 0) }
    fun json() = JSONObject().put("name", name).put("joint", joint).put("massKg", massKg ?: JSONObject.NULL)
}

/** Runtime joint order and rest transforms. Reuses Field's scene hierarchy rather than a new FK engine. */
class MotionSkeleton(
    val id: String,
    val version: String,
    names: List<String>,
    parents: List<Int>,
    restTranslations: List<Double>,
    restRotations: List<Double>,
    val coordinates: MotionCoordinates = MotionCoordinates(),
    bodies: List<MotionBody> = emptyList(),
) {
    val names = names.toList()
    val parents = parents.toList()
    val restTranslations = restTranslations.toList()
    val restRotations = restRotations.toList()
    val bodies = bodies.toList()
    val size get() = names.size
    val hash: String
    init {
        require(id.isNotBlank() && version.isNotBlank())
        require((listOf(id, version, coordinates.sourceUnits, coordinates.sourceHandedness,
            coordinates.sourceUp, coordinates.sourceForward) + names + bodies.flatMap { listOf(it.name, it.joint) })
            .all { text -> text.none { it.code == 0 } }) { "Skeleton hash strings must not contain NUL" }
        require(size in 1..1024 && names.toSet().size == size && names.all { it.isNotBlank() })
        require(parents.size == size && parents.count { it == -1 } == 1)
        parents.forEachIndexed { i, p -> require(p in -1 until i) { "Parent $p must precede joint $i" } }
        require(restTranslations.size == 3 * size && restTranslations.all(Double::isFinite))
        validateRotations(restRotations, size)
        require(bodies.map { it.name }.toSet().size == bodies.size && bodies.all { it.joint in names })
        // Hash a canonical array, avoiding JSONObject's unspecified key ordering; shared with Python.
        // Numeric spelling differs across JSON implementations; hash IEEE binary values separately.
        val digest = MessageDigest.getInstance("SHA-256")
        fun string(s: String) { digest.update(s.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        string(id); string(version); names.forEach(::string)
        val bytes = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun number(n: Double) { bytes.clear(); bytes.putDouble(n); digest.update(bytes.array()) }
        parents.forEach { number(it.toDouble()) }; restTranslations.forEach(::number); restRotations.forEach(::number)
        coordinates.sourceToField.forEach(::number); number(coordinates.metresPerUnit)
        string(coordinates.sourceUnits); string(coordinates.sourceHandedness); string(coordinates.sourceUp); string(coordinates.sourceForward)
        bodies.forEach { string(it.name); string(it.joint); number(it.massKg ?: -1.0) }
        hash = digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun joint(name: String): Int = names.indexOf(name).also { require(it >= 0) { "Unknown joint '$name'; available: $names" } }
    fun body(name: String): MotionBody = bodies.firstOrNull { it.name == name }
        ?: throw IllegalArgumentException("Unknown rigid body '$name'; available: ${bodies.map { it.name }}")
    fun sceneSkeleton(): GltfSkinning.Skeleton = GltfSkinning.Skeleton(names.indices.map { i ->
        GltfSkinning.SkeletonNode(names[i], parents[i], Matrix4f().translation(
            restTranslations[i * 3].toFloat(), restTranslations[i * 3 + 1].toFloat(), restTranslations[i * 3 + 2].toFloat()).rotate(Quat(
            restRotations[i * 4].toFloat(), restRotations[i * 4 + 1].toFloat(), restRotations[i * 4 + 2].toFloat(), restRotations[i * 4 + 3].toFloat())))
    }, emptyList(), emptyList())
    fun json() = JSONObject().put("id", id).put("version", version).put("names", names).put("parents", parents)
        .put("restTranslations", restTranslations).put("restRotations", restRotations).put("coordinates", coordinates.json())
        .put("bodies", JSONArray(bodies.map { it.json() })).put("hash", hash)
    companion object {
        fun fromJson(j: JSONObject): MotionSkeleton {
            val bodies = j.getJSONArray("bodies")
            val s = MotionSkeleton(j.getString("id"), j.getString("version"), j.getJSONArray("names").strings(),
                j.getJSONArray("parents").let { a -> List(a.length()) { a.getInt(it) } },
                j.getJSONArray("restTranslations").doubles(), j.getJSONArray("restRotations").doubles(),
                MotionCoordinates.fromJson(j.getJSONObject("coordinates")), List(bodies.length()) {
                    val b = bodies.getJSONObject(it)
                    MotionBody(b.getString("name"), b.getString("joint"), if (b.isNull("massKg")) null else b.getDouble("massKg"))
                })
            require(s.hash == j.getString("hash")) { "Skeleton hash mismatch" }
            return s
        }
    }
}

/** Lists are copied on construction. Display code may create mutable Field vectors without sharing worker state. */
class MotionPose(root: List<Double>, localRotations: List<Double>, worldPositions: List<Double>) {
    val root = root.toList()
    val localRotations = localRotations.toList()
    val worldPositions = worldPositions.toList()
    val size: Int get() = worldPositions.size / 3
    val hasRotations: Boolean get() = localRotations.isNotEmpty()
    init {
        require(root.size == 3 && root.all { it.isFinite() && abs(it) <= Float.MAX_VALUE })
        require(worldPositions.isNotEmpty() && worldPositions.size % 3 == 0)
        if (hasRotations) validateRotations(localRotations, size)
        require(worldPositions.size == size * 3 && worldPositions.all { it.isFinite() && abs(it) <= Float.MAX_VALUE })
    }
    fun position(index: Int): Vec3 = Vec3(worldPositions[index * 3].toFloat(), worldPositions[index * 3 + 1].toFloat(), worldPositions[index * 3 + 2].toFloat())
    fun rotation(index: Int): Quat {
        check(hasRotations) { "This reference pose has positions only; upstream does not supply orientations" }
        return Quat(localRotations[index * 4].toFloat(), localRotations[index * 4 + 1].toFloat(), localRotations[index * 4 + 2].toFloat(), localRotations[index * 4 + 3].toFloat())
    }
    fun json() = JSONObject().put("root", root).put("localRotations", localRotations).put("worldPositions", worldPositions)
    companion object {
        fun fromJson(j: JSONObject) = MotionPose(j.getJSONArray("root").doubles(), j.getJSONArray("localRotations").doubles(), j.getJSONArray("worldPositions").doubles())
    }
}

class MotionFrame(
    val time: Double, val index: Long, val episode: Long, val revision: Long,
    val executed: MotionPose, val intended: MotionPose? = null,
    contacts: List<Double> = emptyList(), val contactProvenance: String = "unavailable",
    diagnostics: Map<String, Double> = emptyMap(),
) {
    val contacts = contacts.toList()
    val diagnostics = diagnostics.toMap()
    init {
        require(time.isFinite() && time >= 0 && index >= 0 && episode >= 0 && revision >= 0)
        require(executed.hasRotations) { "Executed pose requires orientations" }
        require(intended == null || intended.size == executed.size)
        require(contacts.all { it.isFinite() && it in 0.0..1.0 })
        require(contactProvenance in setOf("unavailable", "ardy.predicted", "simulator.measured", "fixture"))
        require(diagnostics.values.all(Double::isFinite))
    }
    fun json() = JSONObject().put("time", time).put("index", index).put("episode", episode).put("revision", revision)
        .put("executed", executed.json()).put("intended", intended?.json() ?: JSONObject.NULL).put("contacts", contacts)
        .put("contactProvenance", contactProvenance).put("diagnostics", JSONObject(diagnostics))
    companion object {
        fun fromJson(j: JSONObject): MotionFrame {
            val d = j.getJSONObject("diagnostics")
            return MotionFrame(j.getDouble("time"), j.getLong("index"), j.getLong("episode"), j.getLong("revision"),
                MotionPose.fromJson(j.getJSONObject("executed")), if (j.isNull("intended")) null else MotionPose.fromJson(j.getJSONObject("intended")),
                j.getJSONArray("contacts").doubles(), j.getString("contactProvenance"), d.keySet().associateWith { d.getDouble(it) })
        }
    }
}

/** Parent-child segments in 3D; pass these to the existing Field line renderer and camera. */
fun MotionPose.bones(skeleton: MotionSkeleton): FLine {
    require(size == skeleton.size)
    return FLine().also { line -> skeleton.parents.forEachIndexed { i, parent ->
        if (parent >= 0) line.moveTo(position(parent)).lineTo(position(i))
    } }
}

data class TimedRootPoint(val time: Double, val x: Double, val z: Double, val heading: Double? = null) {
    init { require(listOf(time, x, z).all(Double::isFinite) && time >= 0 && (heading == null || heading.isFinite())) }
    fun json() = JSONObject().put("time", time).put("x", x).put("z", z).put("heading", heading ?: JSONObject.NULL)
}

/** Existing FLine arc-length cursor; [distanceFraction] supplies timing separately from geometry. */
fun FLine.motionRoute(startTime: Double, duration: Double, count: Int = 41,
    headings: Boolean = false, distanceFraction: (Double) -> Double = { it }): List<TimedRootPoint> {
    require(startTime.isFinite() && startTime >= 0 && duration.isFinite() && duration > 0 && count in 2..10000)
    val cursor = cursor()
    val length = cursor.lengthD()
    var previous = -1.0
    val points = List(count) { i ->
        val t = i.toDouble() / (count - 1)
        val distance = distanceFraction(t)
        require(distance.isFinite() && distance in 0.0..1.0 && distance >= previous) { "Distance envelope must be monotone in [0,1]" }
        previous = distance
        cursor.setD((length * distance).toFloat())
        cursor.position()
    }
    return points.mapIndexed { i, p ->
        val before = points[(i - 1).coerceAtLeast(0)]
        val after = points[(i + 1).coerceAtMost(points.lastIndex)]
        TimedRootPoint(startTime + duration * i / (count - 1), p.x.toDouble(), p.z.toDouble(),
            if (headings) atan2((after.x - before.x).toDouble(), (after.z - before.z).toDouble()) else null)
    }
}

internal fun JSONArray.doubles() = List(length()) { getDouble(it) }
internal fun JSONArray.strings() = List(length()) { getString(it) }
private fun validateRotations(values: List<Double>, size: Int) {
    require(values.size == size * 4 && values.all(Double::isFinite))
    repeat(size) { i -> require(abs((0..3).sumOf { values[4 * i + it] * values[4 * i + it] } - 1.0) < .002) { "Quaternion $i must be normalized xyzw" } }
}
