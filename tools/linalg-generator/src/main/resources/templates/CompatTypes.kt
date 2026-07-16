package afield.stdlib.linalg

import java.nio.FloatBuffer

operator fun Vector2fc.plus(other:Vector2fc)=add(other,Vector2f())
operator fun Vector2fc.plus(scalar:Number)=add(scalar.toFloat(),Vector2f())
operator fun Vector2fc.minus(other:Vector2fc)=sub(other,Vector2f())
operator fun Vector2fc.minus(scalar:Number)=sub(scalar.toFloat(),Vector2f())
operator fun Vector2fc.times(other:Vector2fc)=mul(other,Vector2f())
operator fun Vector2fc.times(scalar:Number)=mul(scalar.toFloat(),Vector2f())
operator fun Vector2fc.div(scalar:Number)=div(scalar.toFloat(),Vector2f())
operator fun Vector2fc.unaryMinus()=negate(Vector2f())

operator fun Vector3fc.plus(other:Vector3fc)=add(other,Vector3f())
operator fun Vector3fc.plus(scalar:Number)=add(scalar.toFloat(),Vector3f())
operator fun Vector3fc.minus(other:Vector3fc)=sub(other,Vector3f())
operator fun Vector3fc.minus(scalar:Number)=sub(scalar.toFloat(),Vector3f())
operator fun Vector3fc.times(other:Vector3fc)=mul(other,Vector3f())
operator fun Vector3fc.times(scalar:Number)=mul(scalar.toFloat(),Vector3f())
operator fun Vector3fc.div(scalar:Number)=div(scalar.toFloat(),Vector3f())
operator fun Vector3fc.unaryMinus()=negate(Vector3f())

operator fun Vector4fc.plus(other:Vector4fc)=add(other,Vector4f())
operator fun Vector4fc.plus(scalar:Number)=add(scalar.toFloat(),Vector4f())
operator fun Vector4fc.minus(other:Vector4fc)=sub(other,Vector4f())
operator fun Vector4fc.minus(scalar:Number)=sub(scalar.toFloat(),Vector4f())
operator fun Vector4fc.times(other:Vector4fc)=mul(other,Vector4f())
operator fun Vector4fc.times(scalar:Number)=mul(scalar.toFloat(),Vector4f())
operator fun Vector4fc.div(scalar:Number)=div(scalar.toFloat(),Vector4f())
operator fun Vector4fc.unaryMinus()=negate(Vector4f())

// Vector2d, Vector3d, Vector4d and their read interfaces are generated in Vectors.kt from the
// same operation model as the float vectors; these operators mirror the float operator surface.
operator fun Vector2dc.plus(other:Vector2dc)=add(other,Vector2d())
operator fun Vector2dc.plus(scalar:Number)=add(scalar.toDouble(),Vector2d())
operator fun Vector2dc.minus(other:Vector2dc)=sub(other,Vector2d())
operator fun Vector2dc.minus(scalar:Number)=sub(scalar.toDouble(),Vector2d())
operator fun Vector2dc.times(other:Vector2dc)=mul(other,Vector2d())
operator fun Vector2dc.times(scalar:Number)=mul(scalar.toDouble(),Vector2d())
operator fun Vector2dc.div(scalar:Number)=div(scalar.toDouble(),Vector2d())
operator fun Vector2dc.unaryMinus()=negate(Vector2d())

operator fun Vector3dc.plus(other:Vector3dc)=add(other,Vector3d())
operator fun Vector3dc.plus(scalar:Number)=add(scalar.toDouble(),Vector3d())
operator fun Vector3dc.minus(other:Vector3dc)=sub(other,Vector3d())
operator fun Vector3dc.minus(scalar:Number)=sub(scalar.toDouble(),Vector3d())
operator fun Vector3dc.times(other:Vector3dc)=mul(other,Vector3d())
operator fun Vector3dc.times(scalar:Number)=mul(scalar.toDouble(),Vector3d())
operator fun Vector3dc.div(scalar:Number)=div(scalar.toDouble(),Vector3d())
operator fun Vector3dc.unaryMinus()=negate(Vector3d())

operator fun Vector4dc.plus(other:Vector4dc)=add(other,Vector4d())
operator fun Vector4dc.plus(scalar:Number)=add(scalar.toDouble(),Vector4d())
operator fun Vector4dc.minus(other:Vector4dc)=sub(other,Vector4d())
operator fun Vector4dc.minus(scalar:Number)=sub(scalar.toDouble(),Vector4d())
operator fun Vector4dc.times(other:Vector4dc)=mul(other,Vector4d())
operator fun Vector4dc.times(scalar:Number)=mul(scalar.toDouble(),Vector4d())
operator fun Vector4dc.div(scalar:Number)=div(scalar.toDouble(),Vector4d())
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

interface Matrix2fc { fun m00():Float;fun m01():Float;fun m10():Float;fun m11():Float }
class Matrix2f(
    @JvmField var m00:Float=1f,@JvmField var m01:Float=0f,
    @JvmField var m10:Float=0f,@JvmField var m11:Float=1f
) : Matrix2fc {
    override fun m00()=m00;override fun m01()=m01;override fun m10()=m10;override fun m11()=m11
    fun get(buffer:FloatBuffer):FloatBuffer{val i=buffer.position();buffer.put(i,m00);buffer.put(i+1,m01);buffer.put(i+2,m10);buffer.put(i+3,m11);return buffer}
    fun get(index:Int,buffer:java.nio.ByteBuffer):java.nio.ByteBuffer{buffer.putFloat(index,m00);buffer.putFloat(index+4,m01);buffer.putFloat(index+8,m10);buffer.putFloat(index+12,m11);return buffer}
}

data class Rectanglef(
    @JvmField var minX:Float=0f,@JvmField var minY:Float=0f,
    @JvmField var maxX:Float=0f,@JvmField var maxY:Float=0f
)
