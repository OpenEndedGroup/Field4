package afield.stdlib.linalg

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.*

/** A read-only quaternion, normally used to represent a 3D rotation. */
interface Quaternionfc {
    fun x(): Float
    fun y(): Float
    fun z(): Float
    fun w(): Float

    /** Rotates [value] by this quaternion and writes the result into [dest]. */
    fun transform(value: Vector3fc, dest: Vector3f): Vector3f = transformQuaternionVector3(this, value, dest)

    fun transform(value: Vector3f): Vector3f = transform(value, value)
    /** Measures quaternion agreement; unit quaternions q and -q describe the same rotation. */
    fun dot(other: Quaternionfc): Float = dotQuaternion(this, other)
    operator fun times(value: Vector3fc): Vector3f = transform(value, Vector3f())
}

/**
 * A mutable quaternion initialized to the identity rotation.
 *
 * Angles are in radians. Use this for persistent rotations; vector transformation
 * destination overloads avoid allocating a result.
 */
open class Quaternionf() : Quaternionfc, Cloneable {
    @JvmField var x: Float = 0f
    @JvmField var y: Float = 0f
    @JvmField var z: Float = 0f
    @JvmField var w: Float = 1f

    constructor(x: Float, y: Float, z: Float, w: Float) : this() { set(x, y, z, w) }
    constructor(other: Quaternionfc) : this(other.x(), other.y(), other.z(), other.w())

    override fun x(): Float = x
    override fun y(): Float = y
    override fun z(): Float = z
    override fun w(): Float = w

    fun identity(): Quaternionf = set(0f, 0f, 0f, 1f)
    fun set(x: Float, y: Float, z: Float, w: Float): Quaternionf {
        this.x = x; this.y = y; this.z = z; this.w = w
        return this
    }
    fun set(other: Quaternionfc): Quaternionf = set(other.x(), other.y(), other.z(), other.w())

    /** Scales this quaternion to unit length, as required for a pure rotation. */
    fun normalize(): Quaternionf = normalizeQuaternion(this, this)

    fun conjugate(): Quaternionf = conjugateQuaternion(this, this)
    fun invert(): Quaternionf = invertQuaternion(this, this)

    fun mul(right: Quaternionfc): Quaternionf = mul(right, this)
    fun mul(right: Quaternionfc, dest: Quaternionf): Quaternionf = multiplyQuaternion(this, right, dest)

    fun premul(left: Quaternionfc): Quaternionf = Quaternionf(left).mul(this, this)

    fun setAngleAxis(angle: Float, axisX: Float, axisY: Float, axisZ: Float): Quaternionf {
        val inv = 1f / sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ)
        val half = angle * 0.5f
        val s = sin(half) * inv
        return set(axisX * s, axisY * s, axisZ * s, cos(half))
    }

    fun fromAxisAngleRad(axisX: Float, axisY: Float, axisZ: Float, angle: Float): Quaternionf =
        setAngleAxis(angle, axisX, axisY, axisZ)
    fun fromAxisAngleRad(axis: Vector3fc, angle: Float): Quaternionf =
        setAngleAxis(angle, axis.x(), axis.y(), axis.z())

    private fun rotateLocal(rx: Float, ry: Float, rz: Float, rw: Float): Quaternionf =
        mul(Quaternionf(rx, ry, rz, rw))

    fun rotateX(angle: Float): Quaternionf {
        val h = angle * 0.5f
        return rotateLocal(sin(h), 0f, 0f, cos(h))
    }
    fun rotateY(angle: Float): Quaternionf {
        val h = angle * 0.5f
        return rotateLocal(0f, sin(h), 0f, cos(h))
    }
    fun rotateZ(angle: Float): Quaternionf {
        val h = angle * 0.5f
        return rotateLocal(0f, 0f, sin(h), cos(h))
    }
    fun rotateXYZ(angleX: Float, angleY: Float, angleZ: Float): Quaternionf =
        rotateX(angleX).rotateY(angleY).rotateZ(angleZ)

    fun rotationTo(from: Vector3fc, to: Vector3fc): Quaternionf {
        val fx = from.x(); val fy = from.y(); val fz = from.z()
        val tx = to.x(); val ty = to.y(); val tz = to.z()
        val inv = 1f / sqrt((fx * fx + fy * fy + fz * fz) * (tx * tx + ty * ty + tz * tz))
        val d = (fx * tx + fy * ty + fz * tz) * inv
        if (d < -0.999999f) {
            var ax = fy; var ay = -fx; var az = 0f
            if (ax * ax + ay * ay < 1e-12f) { ax = 0f; ay = fz; az = -fy }
            return setAngleAxis(PI.toFloat(), ax, ay, az)
        }
        val cx = (fy * tz - fz * ty) * inv
        val cy = (fz * tx - fx * tz) * inv
        val cz = (fx * ty - fy * tx) * inv
        return set(cx, cy, cz, 1f + d).normalize()
    }

    fun rotateTo(from: Vector3fc, to: Vector3fc): Quaternionf = mul(Quaternionf().rotationTo(from, to))
    fun rotationToField(from: Vector3fc, to: Vector3fc): Quaternionf = rotationTo(from, to)

    fun lookAlong(direction: Vector3fc, up: Vector3fc): Quaternionf {
        val f = Vector3f(direction).normalize()
        val r = Vector3f(f).cross(up).normalize()
        val u = Vector3f(r).cross(f)
        return setFromNormalized(
            Matrix3f().set(
                r.x, r.y, r.z,
                u.x, u.y, u.z,
                -f.x, -f.y, -f.z
            )
        )
    }

    fun setFromNormalized(m: Matrix3fc): Quaternionf {
        val trace = m.m00() + m.m11() + m.m22()
        if (trace >= 0f) {
            val s = sqrt(trace + 1f) * 2f
            return set((m.m12() - m.m21()) / s, (m.m20() - m.m02()) / s, (m.m01() - m.m10()) / s, s * 0.25f)
        }
        if (m.m00() > m.m11() && m.m00() > m.m22()) {
            val s = sqrt(1f + m.m00() - m.m11() - m.m22()) * 2f
            return set(s * 0.25f, (m.m10() + m.m01()) / s, (m.m20() + m.m02()) / s, (m.m12() - m.m21()) / s)
        }
        if (m.m11() > m.m22()) {
            val s = sqrt(1f + m.m11() - m.m00() - m.m22()) * 2f
            return set((m.m10() + m.m01()) / s, s * 0.25f, (m.m21() + m.m12()) / s, (m.m20() - m.m02()) / s)
        }
        val s = sqrt(1f + m.m22() - m.m00() - m.m11()) * 2f
        return set((m.m20() + m.m02()) / s, (m.m21() + m.m12()) / s, s * 0.25f, (m.m01() - m.m10()) / s)
    }

    fun nlerp(other: Quaternionfc, t: Float): Quaternionf {
        val sign = if (dot(other) < 0f) -1f else 1f
        return set(
            x + (other.x() * sign - x) * t,
            y + (other.y() * sign - y) * t,
            z + (other.z() * sign - z) * t,
            w + (other.w() * sign - w) * t
        ).normalize()
    }

    fun slerp(other: Quaternionfc, t: Float): Quaternionf {
        var cosom = dot(other)
        var sign = 1f
        if (cosom < 0f) { cosom = -cosom; sign = -1f }
        val scale0: Float
        val scale1: Float
        if (1f - cosom > 1e-6f) {
            val omega = acos(cosom.coerceIn(-1f, 1f))
            val sinom = sin(omega)
            scale0 = sin((1f - t) * omega) / sinom
            scale1 = sin(t * omega) / sinom * sign
        } else {
            scale0 = 1f - t
            scale1 = t * sign
        }
        return set(x * scale0 + other.x() * scale1, y * scale0 + other.y() * scale1, z * scale0 + other.z() * scale1, w * scale0 + other.w() * scale1)
    }

    public override fun clone(): Quaternionf = Quaternionf(this)
    override fun toString(): String = "($x, $y, $z, $w)"
}

/** A read-only 3x3 column-major matrix, commonly used for linear and normal transforms. */
interface Matrix3fc {
    fun m00(): Float; fun m01(): Float; fun m02(): Float
    fun m10(): Float; fun m11(): Float; fun m12(): Float
    fun m20(): Float; fun m21(): Float; fun m22(): Float

    fun transform(value: Vector3fc, dest: Vector3f): Vector3f = transformMatrix3Vector3(this, value, dest)
    fun transform(value: Vector3f): Vector3f = transform(value, value)
    fun determinant(): Float = determinantMatrix3(this)
}

/** A mutable 3x3 column-major matrix initialized to identity. */
open class Matrix3f() : Matrix3fc {
    @JvmField var m00 = 1f; @JvmField var m01 = 0f; @JvmField var m02 = 0f
    @JvmField var m10 = 0f; @JvmField var m11 = 1f; @JvmField var m12 = 0f
    @JvmField var m20 = 0f; @JvmField var m21 = 0f; @JvmField var m22 = 1f

    constructor(other: Matrix3fc) : this() { set(other) }
    override fun m00() = m00; override fun m01() = m01; override fun m02() = m02
    override fun m10() = m10; override fun m11() = m11; override fun m12() = m12
    override fun m20() = m20; override fun m21() = m21; override fun m22() = m22
    fun m00(v: Float) = apply { m00 = v }; fun m01(v: Float) = apply { m01 = v }; fun m02(v: Float) = apply { m02 = v }
    fun m10(v: Float) = apply { m10 = v }; fun m11(v: Float) = apply { m11 = v }; fun m12(v: Float) = apply { m12 = v }
    fun m20(v: Float) = apply { m20 = v }; fun m21(v: Float) = apply { m21 = v }; fun m22(v: Float) = apply { m22 = v }

    fun identity(): Matrix3f = set(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
    fun zero(): Matrix3f = set(0f,0f,0f, 0f,0f,0f, 0f,0f,0f)
    fun set(other: Matrix3fc): Matrix3f = set(other.m00(),other.m01(),other.m02(), other.m10(),other.m11(),other.m12(), other.m20(),other.m21(),other.m22())
    fun set(a00:Float,a01:Float,a02:Float,a10:Float,a11:Float,a12:Float,a20:Float,a21:Float,a22:Float): Matrix3f {
        m00=a00;m01=a01;m02=a02;m10=a10;m11=a11;m12=a12;m20=a20;m21=a21;m22=a22; return this
    }
    fun scale(x: Float, y: Float, z: Float): Matrix3f {
        m00*=x;m01*=x;m02*=x; m10*=y;m11*=y;m12*=y; m20*=z;m21*=z;m22*=z; return this
    }
    fun scale(v: Vector3fc): Matrix3f = scale(v.x(), v.y(), v.z())
    fun rotate(q: Quaternionfc): Matrix3f = mul(Matrix3f().rotation(q))
    fun rotation(q: Quaternionfc): Matrix3f {
        val x=q.x();val y=q.y();val z=q.z();val w=q.w(); val x2=x+x;val y2=y+y;val z2=z+z
        val xx=x*x2;val xy=x*y2;val xz=x*z2;val yy=y*y2;val yz=y*z2;val zz=z*z2;val wx=w*x2;val wy=w*y2;val wz=w*z2
        return set(1f-yy-zz, xy+wz, xz-wy, xy-wz,1f-xx-zz,yz+wx, xz+wy,yz-wx,1f-xx-yy)
    }
    fun mul(right: Matrix3fc): Matrix3f = multiplyMatrix3(this, right, this)
    override fun determinant(): Float = determinantMatrix3(this)
    fun invert(): Matrix3f = invertMatrix3(this, this)
    fun transpose(): Matrix3f = transposeMatrix3(this, this)
    fun get(buffer:FloatBuffer):FloatBuffer{val i=buffer.position();buffer.put(i,m00);buffer.put(i+1,m01);buffer.put(i+2,m02);buffer.put(i+3,m10);buffer.put(i+4,m11);buffer.put(i+5,m12);buffer.put(i+6,m20);buffer.put(i+7,m21);buffer.put(i+8,m22);return buffer}
    fun get(index:Int,buffer:ByteBuffer):ByteBuffer{buffer.putFloat(index,m00);buffer.putFloat(index+4,m01);buffer.putFloat(index+8,m02);buffer.putFloat(index+12,m10);buffer.putFloat(index+16,m11);buffer.putFloat(index+20,m12);buffer.putFloat(index+24,m20);buffer.putFloat(index+28,m21);buffer.putFloat(index+32,m22);return buffer}
}

/**
 * A read-only double-precision 3x3 column-major matrix.
 *
 * The whole square matrix family comes in a double-precision variant (`Matrix2d`, `Matrix3d`,
 * `Matrix4d`) for numerically sensitive code (e.g. Kalman covariance propagation) that must not
 * drop to `float`. Its core equations are generated from the same transform model as `Matrix3f`,
 * in f64.
 */
interface Matrix3dc {
    fun m00(): Double; fun m01(): Double; fun m02(): Double
    fun m10(): Double; fun m11(): Double; fun m12(): Double
    fun m20(): Double; fun m21(): Double; fun m22(): Double

    fun determinant(): Double = determinantMatrix3d(this)
}

/** A mutable double-precision 3x3 column-major matrix initialized to identity. */
class Matrix3d() : Matrix3dc {
    @JvmField var m00 = 1.0; @JvmField var m01 = 0.0; @JvmField var m02 = 0.0
    @JvmField var m10 = 0.0; @JvmField var m11 = 1.0; @JvmField var m12 = 0.0
    @JvmField var m20 = 0.0; @JvmField var m21 = 0.0; @JvmField var m22 = 1.0

    constructor(other: Matrix3dc) : this() { set(other) }
    override fun m00() = m00; override fun m01() = m01; override fun m02() = m02
    override fun m10() = m10; override fun m11() = m11; override fun m12() = m12
    override fun m20() = m20; override fun m21() = m21; override fun m22() = m22
    fun m00(v: Double) = apply { m00 = v }; fun m01(v: Double) = apply { m01 = v }; fun m02(v: Double) = apply { m02 = v }
    fun m10(v: Double) = apply { m10 = v }; fun m11(v: Double) = apply { m11 = v }; fun m12(v: Double) = apply { m12 = v }
    fun m20(v: Double) = apply { m20 = v }; fun m21(v: Double) = apply { m21 = v }; fun m22(v: Double) = apply { m22 = v }

    fun identity(): Matrix3d = set(1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0)
    fun zero(): Matrix3d = set(0.0,0.0,0.0, 0.0,0.0,0.0, 0.0,0.0,0.0)
    fun set(other: Matrix3dc): Matrix3d = set(other.m00(),other.m01(),other.m02(), other.m10(),other.m11(),other.m12(), other.m20(),other.m21(),other.m22())
    fun set(a00:Double,a01:Double,a02:Double,a10:Double,a11:Double,a12:Double,a20:Double,a21:Double,a22:Double): Matrix3d {
        m00=a00;m01=a01;m02=a02;m10=a10;m11=a11;m12=a12;m20=a20;m21=a21;m22=a22; return this
    }
    /** Sets `this = this * right`. */
    fun mul(right: Matrix3dc): Matrix3d = multiplyMatrix3d(this, right, this)
    fun mul(right: Matrix3dc, dest: Matrix3d): Matrix3d = multiplyMatrix3d(this, right, dest)
    override fun determinant(): Double = determinantMatrix3d(this)
    fun invert(): Matrix3d = invertMatrix3d(this, this)
    /** Transposes this matrix in place. */
    fun transpose(): Matrix3d = transposeMatrix3d(this, this)
    /** Writes the transpose of this matrix into [dest] without modifying this. */
    fun transpose(dest: Matrix3d): Matrix3d = transposeMatrix3d(this, dest)
}

/** A read-only 4x4 column-major matrix for affine, camera, and projection transforms. */
interface Matrix4fc {
    fun m00():Float;fun m01():Float;fun m02():Float;fun m03():Float
    fun m10():Float;fun m11():Float;fun m12():Float;fun m13():Float
    fun m20():Float;fun m21():Float;fun m22():Float;fun m23():Float
    fun m30():Float;fun m31():Float;fun m32():Float;fun m33():Float

    /** Transforms a position, including this matrix's translation, into [dest]. */
    fun transformPosition(value: Vector3fc, dest: Vector3f): Vector3f = transformMatrix4Position(this, value, dest)
    fun transformPosition(value: Vector3f): Vector3f = transformPosition(value,value)
    /** Transforms a direction without applying this matrix's translation. */
    fun transformDirection(value: Vector3fc, dest: Vector3f): Vector3f = transformMatrix4Direction(this, value, dest)
    fun transformDirection(value: Vector3f): Vector3f=transformDirection(value,value)
    fun transform(value: Vector4fc, dest: Vector4f): Vector4f = transformMatrix4Vector4(this, value, dest)
    fun transform(value:Vector4f):Vector4f=transform(value,value)
    fun getTranslation(dest:Vector3f):Vector3f=dest.set(m30(),m31(),m32())
    fun getScale(dest:Vector3f):Vector3f=dest.set(sqrt(m00()*m00()+m01()*m01()+m02()*m02()),sqrt(m10()*m10()+m11()*m11()+m12()*m12()),sqrt(m20()*m20()+m21()*m21()+m22()*m22()))
    operator fun times(right:Matrix4fc):Matrix4f=Matrix4f(this).mul(right)
    operator fun times(value:Vector4fc):Vector4f=transform(value,Vector4f())
    operator fun times(value:Vector3fc):Vector3f=transformPosition(value,Vector3f())
    fun determinant(): Float = determinantMatrix4(this)
}

/**
 * A mutable 4x4 column-major matrix initialized to identity.
 *
 * Transformation calls compose onto the receiver. Use [transformPosition] for
 * points and [transformDirection] for vectors that must ignore translation.
 */
open class Matrix4f() : Matrix4fc {
    @JvmField var m00=1f;@JvmField var m01=0f;@JvmField var m02=0f;@JvmField var m03=0f
    @JvmField var m10=0f;@JvmField var m11=1f;@JvmField var m12=0f;@JvmField var m13=0f
    @JvmField var m20=0f;@JvmField var m21=0f;@JvmField var m22=1f;@JvmField var m23=0f
    @JvmField var m30=0f;@JvmField var m31=0f;@JvmField var m32=0f;@JvmField var m33=1f
    constructor(other:Matrix4fc):this(){set(other)}
    constructor(a00:Float,a01:Float,a02:Float,a03:Float,a10:Float,a11:Float,a12:Float,a13:Float,a20:Float,a21:Float,a22:Float,a23:Float,a30:Float,a31:Float,a32:Float,a33:Float):this(){set(a00,a01,a02,a03,a10,a11,a12,a13,a20,a21,a22,a23,a30,a31,a32,a33)}
    override fun m00()=m00;override fun m01()=m01;override fun m02()=m02;override fun m03()=m03;override fun m10()=m10;override fun m11()=m11;override fun m12()=m12;override fun m13()=m13;override fun m20()=m20;override fun m21()=m21;override fun m22()=m22;override fun m23()=m23;override fun m30()=m30;override fun m31()=m31;override fun m32()=m32;override fun m33()=m33
    fun m00(v:Float)=apply{m00=v};fun m01(v:Float)=apply{m01=v};fun m02(v:Float)=apply{m02=v};fun m03(v:Float)=apply{m03=v};fun m10(v:Float)=apply{m10=v};fun m11(v:Float)=apply{m11=v};fun m12(v:Float)=apply{m12=v};fun m13(v:Float)=apply{m13=v};fun m20(v:Float)=apply{m20=v};fun m21(v:Float)=apply{m21=v};fun m22(v:Float)=apply{m22=v};fun m23(v:Float)=apply{m23=v};fun m30(v:Float)=apply{m30=v};fun m31(v:Float)=apply{m31=v};fun m32(v:Float)=apply{m32=v};fun m33(v:Float)=apply{m33=v}
    fun identity():Matrix4f=set(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
    fun zero():Matrix4f=set(0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f)
    fun set(o:Matrix4fc):Matrix4f=set(o.m00(),o.m01(),o.m02(),o.m03(),o.m10(),o.m11(),o.m12(),o.m13(),o.m20(),o.m21(),o.m22(),o.m23(),o.m30(),o.m31(),o.m32(),o.m33())
    fun set(a00:Float,a01:Float,a02:Float,a03:Float,a10:Float,a11:Float,a12:Float,a13:Float,a20:Float,a21:Float,a22:Float,a23:Float,a30:Float,a31:Float,a32:Float,a33:Float):Matrix4f{m00=a00;m01=a01;m02=a02;m03=a03;m10=a10;m11=a11;m12=a12;m13=a13;m20=a20;m21=a21;m22=a22;m23=a23;m30=a30;m31=a31;m32=a32;m33=a33;return this}
    fun set(buffer:FloatBuffer):Matrix4f{val i=buffer.position();return set(buffer[i],buffer[i+1],buffer[i+2],buffer[i+3],buffer[i+4],buffer[i+5],buffer[i+6],buffer[i+7],buffer[i+8],buffer[i+9],buffer[i+10],buffer[i+11],buffer[i+12],buffer[i+13],buffer[i+14],buffer[i+15])}
    operator fun get(index:Int):Float=when(index){0->m00;1->m01;2->m02;3->m03;4->m10;5->m11;6->m12;7->m13;8->m20;9->m21;10->m22;11->m23;12->m30;13->m31;14->m32;15->m33;else->throw IndexOutOfBoundsException("matrix component $index")}
    operator fun set(index:Int,value:Float){when(index){0->m00=value;1->m01=value;2->m02=value;3->m03=value;4->m10=value;5->m11=value;6->m12=value;7->m13=value;8->m20=value;9->m21=value;10->m22=value;11->m23=value;12->m30=value;13->m31=value;14->m32=value;15->m33=value;else->throw IndexOutOfBoundsException("matrix component $index")}}
    operator fun get(column:Int,row:Int):Float=get(column*4+row)
    operator fun set(column:Int,row:Int,value:Float){set(column*4+row,value)}
    fun get(buffer:FloatBuffer):FloatBuffer{val i=buffer.position();for(n in 0 until 16)buffer.put(i+n,this[n]);return buffer}
    fun get(dest:FloatArray):FloatArray{require(dest.size>=16);for(n in 0 until 16)dest[n]=this[n];return dest}
    fun get(index:Int,buffer:ByteBuffer):ByteBuffer{for(n in 0 until 16)buffer.putFloat(index+n*4,this[n]);return buffer}
    fun mul(r:Matrix4fc):Matrix4f=mul(r,this)
    fun mul(r: Matrix4fc, d: Matrix4f): Matrix4f = multiplyMatrix4(this, r, d)
    fun translate(x: Float, y: Float, z: Float): Matrix4f = translateMatrix4(this, x, y, z, this)
    fun translate(v:Vector3fc):Matrix4f=translate(v.x(),v.y(),v.z())
    fun translation(x:Float,y:Float,z:Float):Matrix4f=identity().translate(x,y,z)
    fun translation(v:Vector3fc):Matrix4f=translation(v.x(),v.y(),v.z())
    fun setTranslation(x:Float,y:Float,z:Float):Matrix4f{m30=x;m31=y;m32=z;return this};fun setTranslation(v:Vector3fc)=setTranslation(v.x(),v.y(),v.z())
    fun scale(x: Float, y: Float, z: Float): Matrix4f = scaleMatrix4(this, x, y, z, this);fun scale(s:Float)=scale(s,s,s);fun scale(v:Vector3fc)=scale(v.x(),v.y(),v.z())
    fun scaling(x:Float,y:Float,z:Float):Matrix4f=identity().scale(x,y,z)
    fun rotate(q:Quaternionfc):Matrix4f=mul(Matrix4f().rotation(q));fun rotation(q:Quaternionfc):Matrix4f{val r=Matrix3f().rotation(q);return set(r.m00,r.m01,r.m02,0f,r.m10,r.m11,r.m12,0f,r.m20,r.m21,r.m22,0f,0f,0f,0f,1f)}
    fun rotate(angle:Float,x:Float,y:Float,z:Float):Matrix4f=rotate(Quaternionf().setAngleAxis(angle,x,y,z));fun rotate(angle:Float,axis:Vector3fc)=rotate(angle,axis.x(),axis.y(),axis.z())
    fun rotateX(a:Float)=rotate(a,1f,0f,0f);fun rotateY(a:Float)=rotate(a,0f,1f,0f);fun rotateZ(a:Float)=rotate(a,0f,0f,1f)
    fun transpose(): Matrix4f = transposeMatrix4(this, this)
    fun invert(): Matrix4f = invertMatrix4(this, this)
    fun perspective(fovy:Float,aspect:Float,zNear:Float,zFar:Float):Matrix4f=setPerspective(fovy,aspect,zNear,zFar,false)
    fun setPerspective(fovy:Float,aspect:Float,zNear:Float,zFar:Float,zZeroToOne:Boolean=false):Matrix4f{val h=1f/tan(fovy*.5f);val rm22=if(zZeroToOne)zFar/(zNear-zFar) else (zFar+zNear)/(zNear-zFar);val rm32=if(zZeroToOne)zFar*zNear/(zNear-zFar) else 2f*zFar*zNear/(zNear-zFar);return set(h/aspect,0f,0f,0f,0f,h,0f,0f,0f,0f,rm22,-1f,0f,0f,rm32,0f)}
    fun frustum(left:Float,right:Float,bottom:Float,top:Float,zNear:Float,zFar:Float,zZeroToOne:Boolean=false):Matrix4f{val rm22=if(zZeroToOne)zFar/(zNear-zFar) else (zFar+zNear)/(zNear-zFar);val rm32=if(zZeroToOne)zFar*zNear/(zNear-zFar) else 2f*zFar*zNear/(zNear-zFar);return mul(Matrix4f().set(2f*zNear/(right-left),0f,0f,0f,0f,2f*zNear/(top-bottom),0f,0f,(right+left)/(right-left),(top+bottom)/(top-bottom),rm22,-1f,0f,0f,rm32,0f))}
    fun ortho(left:Float,right:Float,bottom:Float,top:Float,zNear:Float,zFar:Float,zZeroToOne:Boolean=false):Matrix4f{val rm22=if(zZeroToOne)1f/(zNear-zFar) else -2f/(zFar-zNear);val rm32=if(zZeroToOne)zNear/(zNear-zFar) else -(zFar+zNear)/(zFar-zNear);return mul(Matrix4f().set(2f/(right-left),0f,0f,0f,0f,2f/(top-bottom),0f,0f,0f,0f,rm22,0f,-(right+left)/(right-left),-(top+bottom)/(top-bottom),rm32,1f))}
    fun lookAtField(eye:Vector3fc,center:Vector3fc,up:Vector3fc):Matrix4f=lookAt(eye,center,up)
    fun lookAt(eye:Vector3fc,center:Vector3fc,up:Vector3fc):Matrix4f{val f=Vector3f(center).sub(eye).normalize();val s=Vector3f(f).cross(up).normalize();val u=Vector3f(s).cross(f);val v=Matrix4f().set(s.x,u.x,-f.x,0f,s.y,u.y,-f.y,0f,s.z,u.z,-f.z,0f,-s.dot(eye),-u.dot(eye),f.dot(eye),1f);return mul(v)}
    fun lookAlong(direction:Vector3fc,up:Vector3fc):Matrix4f=lookAt(Vector3f(),direction,up)
    override fun getTranslation(dest:Vector3f):Vector3f=dest.set(m30,m31,m32);fun getTranslation():Vector3f=getTranslation(Vector3f())
    override fun getScale(dest:Vector3f):Vector3f=dest.set(sqrt(m00*m00+m01*m01+m02*m02),sqrt(m10*m10+m11*m11+m12*m12),sqrt(m20*m20+m21*m21+m22*m22));fun getScale():Vector3f=getScale(Vector3f())
    fun get3x3(dest:Matrix3f):Matrix3f=dest.set(m00,m01,m02,m10,m11,m12,m20,m21,m22)
    /** Extracts rotation after removing the scale carried by each basis column. */
    fun getUnnormalizedRotation(dest:Quaternionf):Quaternionf {
        val sx=sqrt(m00*m00+m01*m01+m02*m02).takeIf{it>1e-12f}?:1f
        val sy=sqrt(m10*m10+m11*m11+m12*m12).takeIf{it>1e-12f}?:1f
        val sz=sqrt(m20*m20+m21*m21+m22*m22).takeIf{it>1e-12f}?:1f
        return dest.setFromNormalized(Matrix3f().set(
            m00/sx,m01/sx,m02/sx,
            m10/sy,m11/sy,m12/sy,
            m20/sz,m21/sz,m22/sz
        ))
    }
    override fun toString():String="[$m00 $m10 $m20 $m30; $m01 $m11 $m21 $m31; $m02 $m12 $m22 $m32; $m03 $m13 $m23 $m33]"
}

/**
 * A read-only double-precision 4x4 column-major matrix; the f64 sibling of [Matrix4fc].
 *
 * Covers the linear/affine surface that is precision-agnostic (multiply, determinant, invert,
 * transpose, translate, scale). Camera and quaternion helpers (`perspective`, `lookAt`,
 * rotation-from-quaternion) stay on the single-precision `Matrix4f`, matching Field's f32
 * quaternion and render/camera path.
 */
interface Matrix4dc {
    fun m00():Double;fun m01():Double;fun m02():Double;fun m03():Double
    fun m10():Double;fun m11():Double;fun m12():Double;fun m13():Double
    fun m20():Double;fun m21():Double;fun m22():Double;fun m23():Double
    fun m30():Double;fun m31():Double;fun m32():Double;fun m33():Double
    fun determinant(): Double = determinantMatrix4d(this)
}

/** A mutable double-precision 4x4 column-major matrix initialized to identity. */
open class Matrix4d() : Matrix4dc {
    @JvmField var m00=1.0;@JvmField var m01=0.0;@JvmField var m02=0.0;@JvmField var m03=0.0
    @JvmField var m10=0.0;@JvmField var m11=1.0;@JvmField var m12=0.0;@JvmField var m13=0.0
    @JvmField var m20=0.0;@JvmField var m21=0.0;@JvmField var m22=1.0;@JvmField var m23=0.0
    @JvmField var m30=0.0;@JvmField var m31=0.0;@JvmField var m32=0.0;@JvmField var m33=1.0
    constructor(other:Matrix4dc):this(){set(other)}
    constructor(a00:Double,a01:Double,a02:Double,a03:Double,a10:Double,a11:Double,a12:Double,a13:Double,a20:Double,a21:Double,a22:Double,a23:Double,a30:Double,a31:Double,a32:Double,a33:Double):this(){set(a00,a01,a02,a03,a10,a11,a12,a13,a20,a21,a22,a23,a30,a31,a32,a33)}
    override fun m00()=m00;override fun m01()=m01;override fun m02()=m02;override fun m03()=m03;override fun m10()=m10;override fun m11()=m11;override fun m12()=m12;override fun m13()=m13;override fun m20()=m20;override fun m21()=m21;override fun m22()=m22;override fun m23()=m23;override fun m30()=m30;override fun m31()=m31;override fun m32()=m32;override fun m33()=m33
    fun m00(v:Double)=apply{m00=v};fun m01(v:Double)=apply{m01=v};fun m02(v:Double)=apply{m02=v};fun m03(v:Double)=apply{m03=v};fun m10(v:Double)=apply{m10=v};fun m11(v:Double)=apply{m11=v};fun m12(v:Double)=apply{m12=v};fun m13(v:Double)=apply{m13=v};fun m20(v:Double)=apply{m20=v};fun m21(v:Double)=apply{m21=v};fun m22(v:Double)=apply{m22=v};fun m23(v:Double)=apply{m23=v};fun m30(v:Double)=apply{m30=v};fun m31(v:Double)=apply{m31=v};fun m32(v:Double)=apply{m32=v};fun m33(v:Double)=apply{m33=v}
    fun identity():Matrix4d=set(1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0)
    fun zero():Matrix4d=set(0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0)
    fun set(o:Matrix4dc):Matrix4d=set(o.m00(),o.m01(),o.m02(),o.m03(),o.m10(),o.m11(),o.m12(),o.m13(),o.m20(),o.m21(),o.m22(),o.m23(),o.m30(),o.m31(),o.m32(),o.m33())
    fun set(a00:Double,a01:Double,a02:Double,a03:Double,a10:Double,a11:Double,a12:Double,a13:Double,a20:Double,a21:Double,a22:Double,a23:Double,a30:Double,a31:Double,a32:Double,a33:Double):Matrix4d{m00=a00;m01=a01;m02=a02;m03=a03;m10=a10;m11=a11;m12=a12;m13=a13;m20=a20;m21=a21;m22=a22;m23=a23;m30=a30;m31=a31;m32=a32;m33=a33;return this}
    fun set(buffer:java.nio.DoubleBuffer):Matrix4d{val i=buffer.position();return set(buffer[i],buffer[i+1],buffer[i+2],buffer[i+3],buffer[i+4],buffer[i+5],buffer[i+6],buffer[i+7],buffer[i+8],buffer[i+9],buffer[i+10],buffer[i+11],buffer[i+12],buffer[i+13],buffer[i+14],buffer[i+15])}
    operator fun get(index:Int):Double=when(index){0->m00;1->m01;2->m02;3->m03;4->m10;5->m11;6->m12;7->m13;8->m20;9->m21;10->m22;11->m23;12->m30;13->m31;14->m32;15->m33;else->throw IndexOutOfBoundsException("matrix component $index")}
    operator fun set(index:Int,value:Double){when(index){0->m00=value;1->m01=value;2->m02=value;3->m03=value;4->m10=value;5->m11=value;6->m12=value;7->m13=value;8->m20=value;9->m21=value;10->m22=value;11->m23=value;12->m30=value;13->m31=value;14->m32=value;15->m33=value;else->throw IndexOutOfBoundsException("matrix component $index")}}
    operator fun get(column:Int,row:Int):Double=get(column*4+row)
    operator fun set(column:Int,row:Int,value:Double){set(column*4+row,value)}
    fun get(buffer:java.nio.DoubleBuffer):java.nio.DoubleBuffer{val i=buffer.position();for(n in 0 until 16)buffer.put(i+n,this[n]);return buffer}
    fun get(dest:DoubleArray):DoubleArray{require(dest.size>=16);for(n in 0 until 16)dest[n]=this[n];return dest}
    fun get(index:Int,buffer:ByteBuffer):ByteBuffer{for(n in 0 until 16)buffer.putDouble(index+n*8,this[n]);return buffer}
    fun mul(r:Matrix4dc):Matrix4d=mul(r,this)
    fun mul(r:Matrix4dc,d:Matrix4d):Matrix4d=multiplyMatrix4d(this,r,d)
    fun translate(x:Double,y:Double,z:Double):Matrix4d=translateMatrix4d(this,x,y,z,this)
    fun translation(x:Double,y:Double,z:Double):Matrix4d=identity().translate(x,y,z)
    fun setTranslation(x:Double,y:Double,z:Double):Matrix4d{m30=x;m31=y;m32=z;return this}
    fun scale(x:Double,y:Double,z:Double):Matrix4d=scaleMatrix4d(this,x,y,z,this);fun scale(s:Double)=scale(s,s,s)
    fun scaling(x:Double,y:Double,z:Double):Matrix4d=identity().scale(x,y,z)
    override fun determinant():Double=determinantMatrix4d(this)
    fun transpose():Matrix4d=transposeMatrix4d(this,this)
    fun transpose(dest:Matrix4d):Matrix4d=transposeMatrix4d(this,dest)
    fun invert():Matrix4d=invertMatrix4d(this,this)
    fun get3x3(dest:Matrix3d):Matrix3d=dest.set(m00,m01,m02,m10,m11,m12,m20,m21,m22)
    override fun toString():String="[$m00 $m10 $m20 $m30; $m01 $m11 $m21 $m31; $m02 $m12 $m22 $m32; $m03 $m13 $m23 $m33]"
}
