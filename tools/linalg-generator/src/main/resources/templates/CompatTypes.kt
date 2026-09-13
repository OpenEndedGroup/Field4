package afield.stdlib.linalg

import java.nio.FloatBuffer

operator fun Vector2fc.plus(other:Vector2fc)=add(other,Vector2f())
operator fun Vector2fc.plus(scalar:Number)=add(scalar.toFloat(),Vector2f())
operator fun Vector2fc.minus(other:Vector2fc)=sub(other,Vector2f())
operator fun Vector2fc.minus(scalar:Number)=sub(scalar.toFloat(),Vector2f())
operator fun Vector2fc.times(other:Vector2fc)=mul(other,Vector2f())
operator fun Vector2fc.times(scalar:Number)=mul(scalar.toFloat(),Vector2f())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector2fc.times(scalar:Float)=mul(scalar,Vector2f())
operator fun Vector2fc.div(scalar:Number)=div(scalar.toFloat(),Vector2f())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector2fc.div(scalar:Float)=div(scalar,Vector2f())
operator fun Vector2fc.unaryMinus()=negate(Vector2f())

operator fun Vector3fc.plus(other:Vector3fc)=add(other,Vector3f())
operator fun Vector3fc.plus(scalar:Number)=add(scalar.toFloat(),Vector3f())
operator fun Vector3fc.minus(other:Vector3fc)=sub(other,Vector3f())
operator fun Vector3fc.minus(scalar:Number)=sub(scalar.toFloat(),Vector3f())
operator fun Vector3fc.times(other:Vector3fc)=mul(other,Vector3f())
operator fun Vector3fc.times(scalar:Number)=mul(scalar.toFloat(),Vector3f())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector3fc.times(scalar:Float)=mul(scalar,Vector3f())
operator fun Vector3fc.div(scalar:Number)=div(scalar.toFloat(),Vector3f())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector3fc.div(scalar:Float)=div(scalar,Vector3f())
operator fun Vector3fc.unaryMinus()=negate(Vector3f())

operator fun Vector4fc.plus(other:Vector4fc)=add(other,Vector4f())
operator fun Vector4fc.plus(scalar:Number)=add(scalar.toFloat(),Vector4f())
operator fun Vector4fc.minus(other:Vector4fc)=sub(other,Vector4f())
operator fun Vector4fc.minus(scalar:Number)=sub(scalar.toFloat(),Vector4f())
operator fun Vector4fc.times(other:Vector4fc)=mul(other,Vector4f())
operator fun Vector4fc.times(scalar:Number)=mul(scalar.toFloat(),Vector4f())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector4fc.times(scalar:Float)=mul(scalar,Vector4f())
operator fun Vector4fc.div(scalar:Number)=div(scalar.toFloat(),Vector4f())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector4fc.div(scalar:Float)=div(scalar,Vector4f())
operator fun Vector4fc.unaryMinus()=negate(Vector4f())

// Vector2d, Vector3d, Vector4d and their read interfaces are generated in Vectors.kt from the
// same operation model as the float vectors; these operators mirror the float operator surface.
operator fun Vector2dc.plus(other:Vector2dc)=add(other,Vector2d())
operator fun Vector2dc.plus(scalar:Number)=add(scalar.toDouble(),Vector2d())
operator fun Vector2dc.minus(other:Vector2dc)=sub(other,Vector2d())
operator fun Vector2dc.minus(scalar:Number)=sub(scalar.toDouble(),Vector2d())
operator fun Vector2dc.times(other:Vector2dc)=mul(other,Vector2d())
operator fun Vector2dc.times(scalar:Number)=mul(scalar.toDouble(),Vector2d())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector2dc.times(scalar:Double)=mul(scalar,Vector2d())
operator fun Vector2dc.div(scalar:Number)=div(scalar.toDouble(),Vector2d())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector2dc.div(scalar:Double)=div(scalar,Vector2d())
operator fun Vector2dc.unaryMinus()=negate(Vector2d())

operator fun Vector3dc.plus(other:Vector3dc)=add(other,Vector3d())
operator fun Vector3dc.plus(scalar:Number)=add(scalar.toDouble(),Vector3d())
operator fun Vector3dc.minus(other:Vector3dc)=sub(other,Vector3d())
operator fun Vector3dc.minus(scalar:Number)=sub(scalar.toDouble(),Vector3d())
operator fun Vector3dc.times(other:Vector3dc)=mul(other,Vector3d())
operator fun Vector3dc.times(scalar:Number)=mul(scalar.toDouble(),Vector3d())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector3dc.times(scalar:Double)=mul(scalar,Vector3d())
operator fun Vector3dc.div(scalar:Number)=div(scalar.toDouble(),Vector3d())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector3dc.div(scalar:Double)=div(scalar,Vector3d())
operator fun Vector3dc.unaryMinus()=negate(Vector3d())

operator fun Vector4dc.plus(other:Vector4dc)=add(other,Vector4d())
operator fun Vector4dc.plus(scalar:Number)=add(scalar.toDouble(),Vector4d())
operator fun Vector4dc.minus(other:Vector4dc)=sub(other,Vector4d())
operator fun Vector4dc.minus(scalar:Number)=sub(scalar.toDouble(),Vector4d())
operator fun Vector4dc.times(other:Vector4dc)=mul(other,Vector4d())
operator fun Vector4dc.times(scalar:Number)=mul(scalar.toDouble(),Vector4d())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector4dc.times(scalar:Double)=mul(scalar,Vector4d())
operator fun Vector4dc.div(scalar:Number)=div(scalar.toDouble(),Vector4d())
/** Primitive scalar overload; returns a new vector. */
operator fun Vector4dc.div(scalar:Double)=div(scalar,Vector4d())
operator fun Vector4dc.unaryMinus()=negate(Vector4d())

// Vector8h is half precision (its components are Float in the API), so its scalar operators
// mirror the float surface. Arithmetic runs in Float and results are stored back as binary16.
operator fun Vector8hc.plus(other:Vector8hc)=add(other,Vector8h())
operator fun Vector8hc.plus(scalar:Number)=add(scalar.toFloat(),Vector8h())
operator fun Vector8hc.minus(other:Vector8hc)=sub(other,Vector8h())
operator fun Vector8hc.minus(scalar:Number)=sub(scalar.toFloat(),Vector8h())
operator fun Vector8hc.times(other:Vector8hc)=mul(other,Vector8h())
operator fun Vector8hc.times(scalar:Number)=mul(scalar.toFloat(),Vector8h())
operator fun Vector8hc.div(scalar:Number)=div(scalar.toFloat(),Vector8h())
operator fun Vector8hc.unaryMinus()=negate(Vector8h())

// Vector8h packs eight binary16 lanes into 16 bytes (one GLSL vec4). These bridge to the float
// Vector4f pair used by Field's push-constant transit path: lanes x,y,z,w are the low half and
// a,b,c,d the high half.
fun Vector8hc.lo(dest:Vector4f=Vector4f()):Vector4f=dest.set(x(),y(),z(),w())
fun Vector8hc.hi(dest:Vector4f=Vector4f()):Vector4f=dest.set(a(),b(),c(),d())
fun Vector8h.set(lo:Vector4fc,hi:Vector4fc):Vector8h=set(lo.x(),lo.y(),lo.z(),lo.w(),hi.x(),hi.y(),hi.z(),hi.w())
fun vec8h(lo:Vector4fc,hi:Vector4fc)=Vector8h(lo.x(),lo.y(),lo.z(),lo.w(),hi.x(),hi.y(),hi.z(),hi.w())
fun vec8h(x:Number,y:Number,z:Number,w:Number,a:Number,b:Number,c:Number,d:Number)=
    Vector8h(x.toFloat(),y.toFloat(),z.toFloat(),w.toFloat(),a.toFloat(),b.toFloat(),c.toFloat(),d.toFloat())

/** A read-only 2x2 column-major matrix. */
interface Matrix2fc {
    fun m00():Float;fun m01():Float;fun m10():Float;fun m11():Float
    fun determinant(): Float = determinantMatrix2(this)
}

/** A mutable 2x2 column-major matrix initialized to identity. */
open class Matrix2f(
    @JvmField var m00:Float=1f,@JvmField var m01:Float=0f,
    @JvmField var m10:Float=0f,@JvmField var m11:Float=1f
) : Matrix2fc {
    constructor(other: Matrix2fc) : this() { set(other) }
    override fun m00()=m00;override fun m01()=m01;override fun m10()=m10;override fun m11()=m11
    fun m00(v:Float)=apply{m00=v};fun m01(v:Float)=apply{m01=v};fun m10(v:Float)=apply{m10=v};fun m11(v:Float)=apply{m11=v}
    fun identity():Matrix2f=set(1f,0f,0f,1f)
    fun zero():Matrix2f=set(0f,0f,0f,0f)
    fun set(other:Matrix2fc):Matrix2f=set(other.m00(),other.m01(),other.m10(),other.m11())
    fun set(a00:Float,a01:Float,a10:Float,a11:Float):Matrix2f{m00=a00;m01=a01;m10=a10;m11=a11;return this}
    fun mul(right:Matrix2fc):Matrix2f=multiplyMatrix2(this,right,this)
    fun mul(right:Matrix2fc,dest:Matrix2f):Matrix2f=multiplyMatrix2(this,right,dest)
    override fun determinant():Float=determinantMatrix2(this)
    fun invert():Matrix2f=invertMatrix2(this,this)
    fun transpose():Matrix2f=transposeMatrix2(this,this)
    fun transpose(dest:Matrix2f):Matrix2f=transposeMatrix2(this,dest)
    fun get(buffer:FloatBuffer):FloatBuffer{val i=buffer.position();buffer.put(i,m00);buffer.put(i+1,m01);buffer.put(i+2,m10);buffer.put(i+3,m11);return buffer}
    fun get(index:Int,buffer:java.nio.ByteBuffer):java.nio.ByteBuffer{buffer.putFloat(index,m00);buffer.putFloat(index+4,m01);buffer.putFloat(index+8,m10);buffer.putFloat(index+12,m11);return buffer}
}

/** A read-only double-precision 2x2 column-major matrix; the f64 sibling of [Matrix2fc]. */
interface Matrix2dc {
    fun m00():Double;fun m01():Double;fun m10():Double;fun m11():Double
    fun determinant(): Double = determinantMatrix2d(this)
}

/** A mutable double-precision 2x2 column-major matrix initialized to identity. */
open class Matrix2d(
    @JvmField var m00:Double=1.0,@JvmField var m01:Double=0.0,
    @JvmField var m10:Double=0.0,@JvmField var m11:Double=1.0
) : Matrix2dc {
    constructor(other: Matrix2dc) : this() { set(other) }
    override fun m00()=m00;override fun m01()=m01;override fun m10()=m10;override fun m11()=m11
    fun m00(v:Double)=apply{m00=v};fun m01(v:Double)=apply{m01=v};fun m10(v:Double)=apply{m10=v};fun m11(v:Double)=apply{m11=v}
    fun identity():Matrix2d=set(1.0,0.0,0.0,1.0)
    fun zero():Matrix2d=set(0.0,0.0,0.0,0.0)
    fun set(other:Matrix2dc):Matrix2d=set(other.m00(),other.m01(),other.m10(),other.m11())
    fun set(a00:Double,a01:Double,a10:Double,a11:Double):Matrix2d{m00=a00;m01=a01;m10=a10;m11=a11;return this}
    fun mul(right:Matrix2dc):Matrix2d=multiplyMatrix2d(this,right,this)
    fun mul(right:Matrix2dc,dest:Matrix2d):Matrix2d=multiplyMatrix2d(this,right,dest)
    override fun determinant():Double=determinantMatrix2d(this)
    fun invert():Matrix2d=invertMatrix2d(this,this)
    fun transpose():Matrix2d=transposeMatrix2d(this,this)
    fun transpose(dest:Matrix2d):Matrix2d=transposeMatrix2d(this,dest)
    fun get(buffer:java.nio.DoubleBuffer):java.nio.DoubleBuffer{val i=buffer.position();buffer.put(i,m00);buffer.put(i+1,m01);buffer.put(i+2,m10);buffer.put(i+3,m11);return buffer}
    fun get(index:Int,buffer:java.nio.ByteBuffer):java.nio.ByteBuffer{buffer.putDouble(index,m00);buffer.putDouble(index+8,m01);buffer.putDouble(index+16,m10);buffer.putDouble(index+24,m11);return buffer}
    override fun toString():String="[$m00 $m10; $m01 $m11]"
}

data class Rectanglef(
    @JvmField var minX:Float=0f,@JvmField var minY:Float=0f,
    @JvmField var maxX:Float=0f,@JvmField var maxY:Float=0f
)
