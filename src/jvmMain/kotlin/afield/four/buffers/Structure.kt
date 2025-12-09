package afield.four.buffers

import afield.four.device.CommandBuffer
import afield.four.modules.Introspected
import org.joml.*
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.reflect.KProperty

interface HasUniform<T> : () -> T {
    fun evaluateUniform(): T

    override fun invoke() = evaluateUniform()
}


class MissingRefException(override val message: String) : Exception(message)

@Suppress("UNCHECKED_CAST")
class Structure(var members: List<Introspected.Member> = emptyList()) : BufferWriteable, BufferReadable {


    class FromStructure<T> {
        operator fun setValue(inside: Structure, property: KProperty<*>, any: T) {
            inside.set(property.name, any as Any)
        }

        operator fun getValue(inside: Structure, property: KProperty<*>): T {
            return inside.get(property.name) as T
        }
    }

    var nullPointerChecks: MutableMap<String, Boolean> = mutableMapOf()
    var offsets = mutableMapOf<String, Int>()
    var sizes = mutableMapOf<String, Int>()
    var pointers = mutableMapOf<String, Boolean>()

    // structures elements either have live bindings (evaluated on demand) or values
    val bindings = mutableMapOf<String, Function0<*>>()
    val values = mutableMapOf<String, Any>()

    var dirty = false

    var proxying: FrameBufferGroup? = null

    fun setMembers(members: List<Introspected.Member>): Structure {
        this.members = members
        offsets = members.associate { it.name to it.offset }.toMutableMap()
        sizes = members.associate { it.name to it.memberSize().toInt() }.toMutableMap()
        pointers = members.associate {
            it.name to it.physicalPointer
        }.toMutableMap()
        return this
    }

    operator fun set(property: String, value: Any) {
        dirty = true
        if (!offsets.containsKey(property)) {
            println("Warning: property $property does not exist in structure $offsets")
        }
        values[property] = value
        bindings.remove(property)
    }

    fun bind(property: String, value: Function0<*>) {
        bindings[property] = value
    }

    operator fun <T> get(property: String) = (bindings[property]?.invoke() ?: values[property]) as T

    fun setProxyFor(p: FrameBufferGroup) {
        proxying = p
    }

    override fun mapAndWrite(builder: FloatBuffer.() -> Unit): Structure {
        proxying?.let {
            it.get().mapAndWrite(builder)
        } ?: throw IllegalArgumentException("This Structure is not bound to any Buffer")
        return this
    }


    fun write(name: String, builder: FloatBuffer.() -> Unit) {
        proxying?.let {
            val m = offsets[name]
                ?: throw IllegalArgumentException("Can't find '$name' in this Structure, options are ${offsets.keys}")
            it.get().mapAndWrite {
                this.position(m)
                builder()
            }
        } ?: throw IllegalArgumentException("This Structure is not bound to any Buffer")
    }

    override fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit): Structure {
        proxying?.let {
            it.get().mapAndWrite_bytes(builder)
        } ?: throw IllegalArgumentException("This Structure is not bound to any Buffer")
        return this
    }

    override fun mapAndWrite_ints(builder: IntBuffer.() -> Unit): Structure {
        proxying?.let {
            it.get().mapAndWrite_ints(builder)
        } ?: throw IllegalArgumentException("This Structure is not bound to any Buffer")
        return this
    }

    override fun clean(hostReading: Boolean, cmd: CommandBuffer?): Structure {
        TODO("Not yet implemented")
        return this
    }


    fun writeToBuffer(f: ByteBuffer, cmd : CommandBuffer, autowireReferences: (String) -> () -> HasBuffer) {
        nullPointerChecks = pointers.filter { it.value }.toMutableMap()
        offsets.forEach { (name, offset) ->
            val live = bindings[name]
            if (live != null) {
                writeToBuffer(offset, live()!!, f, cmd, name = name)
            } else {
                values[name]?.let { v ->
                    writeToBuffer(offset, v, f, cmd, name = name)
                }
            }
        }
        if (nullPointerChecks.isNotEmpty()) {

            nullPointerChecks.keys.forEach { name ->
                println("WARNING: trying to autowire $name (and ultimately ${nullPointerChecks.keys}) into push constants")
                val buffer = autowireReferences(name)
                bindings[name] = { buffer }
                writeToBuffer(offsets[name]!!, buffer, f, cmd, name = name)
            }
        }
        dirty = false
    }

    internal fun writeToBuffer(offset: Int, v: Any, f: ByteBuffer, cmd : CommandBuffer, name: String = "unnamed") {
        when (v) {
            is Float -> f.putFloat(offset, v)
            is Int -> f.putInt(offset, v)
            is Long -> f.putLong(offset, v)
            is Vector2f -> v.get(offset, f)
            is Vector3f -> v.get(offset, f)
            is Vector4f -> v.get(offset, f)
            is Matrix2f -> v.get(offset, f)
            is Matrix3f -> v.get(offset, f)
            is Matrix4f -> v.get(offset, f)
            is UInt -> f.putInt(offset, v.toInt())
            is HasUniform<*> -> writeToBuffer(offset, v.evaluateUniform()!!, f, cmd, name)

            is HasBuffer -> {
                val address = v.getGPUAddress(cmd)
                if (address <= 0) {
                    throw IllegalArgumentException(" buffer for member ${name} does not have a gpu address. Make sure you call .addressable() on it's buffer parameters")
                }
                nullPointerChecks.remove(name)
                writeToBuffer(offset, address, f, cmd, name)
            }

            else -> println("Warning: unknown type: $v")
        }
    }

    override fun mapAndRead(builder: FloatBuffer.() -> Unit): Structure {
        proxying?.let {
            it.get().mapAndRead(builder)
        } ?: throw IllegalArgumentException("This Structure is not bound to any Buffer")
        return this
    }

    override fun mapAndRead_bytes(builder: ByteBuffer.() -> Unit): Structure {
        proxying?.let {
            it.get().mapAndRead_bytes(builder)
        } ?: throw IllegalArgumentException("This Structure is not bound to any Buffer")
        return this
    }

    override fun mapAndRead_ints(builder: IntBuffer.() -> Unit): Structure {
        proxying?.let {
            it.get().mapAndRead_ints(builder)
        } ?: throw IllegalArgumentException("This Structure is not bound to any Buffer")
        return this
    }
}
