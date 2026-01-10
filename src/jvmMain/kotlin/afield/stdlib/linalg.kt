package afield.stdlib

import org.joml.*
import kotlin.math.max
import kotlin.math.min

typealias Vec4 = Vector4f
typealias Vec3 = Vector3f
typealias Vec2 = Vector2f
typealias Quat = Quaternionf

fun Vec3.closest(a: Iterable<Vec3>) = a.minByOrNull { this.distance(it) }!!
fun Vec4.closest(a: Iterable<Vec4>) = a.minByOrNull { this.distance(it) }!!
fun Vec2.closest(a: Iterable<Vec2>) = a.minByOrNull { this.distance(it) }!!


operator fun Vec2.rem(other: Vec2) = this.set(other)
operator fun Vec3.rem(other: Vec3) = this.set(other)
operator fun Vec4.rem(other: Vec4) = this.set(other)

val Vector2dc.v3: Vector3d
    get() = Vector3d(this.x(), this.y(), 0.0)

val Vector3dc.v2: Vector2d
    get() = Vector2d(this.x(), this.y())

val Vector2fc.v3: Vector3f
    get() = Vector3f(this.x().toFloat(), this.y().toFloat(), 0.0f)

val Vector3fc.v2: Vector2f
    get() = Vector2f(this.x(), this.y())

val Vector3fc.xy: Vector2fc
    get() = Vector2f(this.x(), this.y())

val Vector3fc.xz: Vector2fc
    get() = Vector2f(this.x(), this.z())

val Vector3fc.yz: Vector2fc
    get() = Vector2f(this.y(), this.z())

val Vector3fc.xx: Vector2fc
    get() = Vector2f(this.x(), this.x())

val Vector3fc.yy: Vector2fc
    get() = Vector2f(this.y(), this.z())

val Vector3fc.zz: Vector2fc
    get() = Vector2f(this.z(), this.z())

val Vector4fc.xy: Vector2fc
    get() = Vector2f(this.x(), this.y())

val Vector4fc.xz: Vector2fc
    get() = Vector2f(this.x(), this.z())

val Vector4fc.yz: Vector2fc
    get() = Vector2f(this.y(), this.z())

val Vector4fc.xx: Vector2fc
    get() = Vector2f(this.x(), this.x())

val Vector4fc.yy: Vector2fc
    get() = Vector2f(this.y(), this.z())

val Vector4fc.zz: Vector2fc
    get() = Vector2f(this.z(), this.z())

fun vec(x: Number, y: Number) = Vector2f(x.toFloat(), y.toFloat())
fun vec(x: Number, y: Number, z: Number) = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
fun vec(x: Number, y: Number, z: Number, w: Number) = Vector4f(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())

fun vec(v: Vec2, z: Number) = Vector3f(v.x, v.y, z.toFloat())
fun vec(x : Number, yz: Vec2) = Vector3f(x.toFloat(), yz.x, yz.y)
fun vec(x: Number, yzw: Vec3) = Vector4f(x.toFloat(), yzw.x, yzw.y, yzw.z)

operator fun Vector4f.invoke(x: Float = this.x, y: Float = this.y, z: Float = this.z, w: Float = this.w) =
    Vector4f(x, y, z, w)

operator fun Vector3f.invoke(x: Float = this.x, y: Float = this.y, z: Float = this.z) = Vector3f(x, y, z)
operator fun Vector2f.invoke(x: Float = this.x, y: Float = this.y) = Vector2f(x, y)

infix fun Vector2f.len(l: Number) = this {
    normalize().mul(l.toFloat())
}

infix fun Vector3f.len(l: Number) = this {
    normalize().mul(l.toFloat())
}

infix fun Vector4f.len(l: Number) = this {
    normalize().mul(l.toFloat())
}

operator fun Vector2f.invoke(m: Vector2f.() -> Unit): Vector2f {
    val c = Vector2f(x, y)
    c.m()
    return c
}

operator fun Vector3f.invoke(m: Vector3f.() -> Unit): Vector3f {
    val c = Vector3f(x, y, z)
    c.m()
    return c
}

operator fun Vector4f.invoke(m: Vector4f.() -> Unit): Vector4f {
    val c = Vector4f(x, y, z, w)
    c.m()
    return c
}

operator fun Vector4f.remAssign(a: Vector4f) {
    this.set(a)
}

operator fun Vector3f.remAssign(a: Vector3f) {
    this.set(a)
}

operator fun Vector2f.remAssign(a: Vector2f) {
    this.set(a)
}

infix fun Vector3f.x(b: Vector3f) = Vector3f(this).cross(b)


operator fun Vector3fc.times(s: Number): Vector3f = mul(s.toFloat(), Vector3f())

operator fun Number.times(s: Vector3fc): Vector3f = s.mul(this.toFloat(), Vector3f())
operator fun Number.times(s: Vector2fc): Vector2f = s.mul(this.toFloat(), Vector2f())
operator fun Number.times(s: Vector4fc): Vector4f = s.mul(this.toFloat(), Vector4f())

fun Vector3dc.isNaN() = this.x().isNaN() || this.y().isNaN() || this.z().isNaN()
fun Vector2dc.isNaN() = this.x().isNaN() || this.y().isNaN()

fun Vector3fc.isNaN() = this.x().isNaN() || this.y().isNaN() || this.z().isNaN()
fun Vector2fc.isNaN() = this.x().isNaN() || this.y().isNaN()

operator fun Vector3fc.times(v: Quaternionfc): Vector3f = v.transform(this, Vector3f())
operator fun Vector2fc.times(v: Quaternionfc): Vector2fc = v.transform(Vector3f(this.x(), this.y(), 0f), Vector3f()).xy
operator fun Vector2dc.times(v: Quaterniondc): Vector2dc = v.transform(Vector3d(this.x(), this.y(), 0.0), Vector3d()).v2


fun quat(angle: Number = 0.0, axis: Vector3f = vec(0, 0, 1)) =
    Quaternionf().setAngleAxis(angle.toFloat(), axis.x, axis.y, axis.z)


class Cuboid(val x: Float, val y: Float, val z: Float, val w: Float, val h: Float, val d: Float) {

    fun union(r: Cuboid?): Cuboid {
        if (r == null) return this
        val minx = min(r.x, x)
        val miny = min(r.y, y)
        val minz = min(r.z, z)

        val maxx = max((r.x + r.w), (x + w))
        val maxy = max((r.y + r.h), (y + h))
        val maxz = max((r.z + r.d), (z + d))

        return Cuboid(
            minx, miny, minz, (maxx - minx), (maxy - miny), (maxz - minz)
        )
    }

    fun translate(by: Vector2d): Cuboid {
        return Cuboid((x + by.x).toFloat(), (y + by.y).toFloat(), z, w, h, d)
    }

    fun translate(by: Vector3d): Cuboid {
        return Cuboid((x + by.x).toFloat(), (y + by.y).toFloat(), (z + by.z).toFloat(), w, h, d)
    }

    fun intersects(point: Vector3d): Boolean {
        return (point.x >= x && point.x <= x + w) && point.y >= y && point.y <= (y + h) && point.z >= z && point.z <= (z + d)
    }

    fun intersects(r: Cuboid): Boolean {
        return ((r.x < this.x + this.w) && r.w + r.x > this.x && r.y < this.y + this.h && r.y + r.h > this.y && r.z < this.z + this.d && r.z + r.d > this.z)
    }


    override fun toString(): String {
        return "Cuboid{" + "x=" + x + ", y=" + y + ", z=" + z + ", w=" + w + ", h=" + h + ", d=" + d + '}'
    }


    fun inset(by: Double): Cuboid {
        return Cuboid(
            (x + by).toFloat(),
            (y + by).toFloat(),
            (z + by).toFloat(),
            (w - by * 2).toFloat(),
            (h - by * 2).toFloat(),
            (d - by * 2).toFloat()
        )
    }

    fun intersectsX(x: Double): Boolean {
        return (x >= this.x && x < this.x + this.w)
    }

    fun inside(start: Double, end: Double): Boolean {
        return this.x >= start && this.x + this.w < end
    }

    fun convert(x: Double, y: Double, z: Double): Vector3d {
        return Vector3d(this.x + x * this.w, this.y + y * this.h, this.z + z * this.d)
    }

    fun duplicate(): Cuboid {
        return Cuboid(x, y, z, w, h, d)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Cuboid

        if (x != other.x) return false
        if (y != other.y) return false
        if (z != other.z) return false
        if (w != other.w) return false
        if (h != other.h) return false
        if (d != other.d) return false

        return true
    }

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        result = 31 * result + w.hashCode()
        result = 31 * result + h.hashCode()
        result = 31 * result + d.hashCode()
        return result
    }


    companion object {
        fun union(r: Cuboid?, rect: Cuboid?): Cuboid? {
            if (r == null) return rect
            if (rect == null) return r
            return r.union(rect)
        }
    }
}



