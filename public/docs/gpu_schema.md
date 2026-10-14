# GPU schemas

`GpuSchema` lets one Kotlin data class define a memory layout that both the CPU and the GPU use. A schema describes bytes, and those bytes can live inline in push constants, in UBO/SSBO storage, or in any device-addressable buffer reached through a pointer.

`afield.four.buffers.GpuSchema` reads an annotated data class's primary constructor, computes a deterministic std430-compatible layout, writes values and arrays into `ByteBuffer`s, and generates GLSL structs plus `GL_EXT_buffer_reference` views. The generated GLSL reaches a shader in the same box through Field's shader templates.

## A particle schema

This example declares a `Particle` with a nested `Material`, and publishes its schema to the box's shader template:

```diffstatic {data-lang=kotlin}
import afield.four.buffers.*

@GpuStruct
data class Material(
    val color: Vector4f,
    val roughness: Float,
    val metallic: Float
)

@GpuStruct
data class Particle(
    val position: Vector3f,
    val radius: Float,
    val material: Material,
    val flags: UInt
)

val particle = gpuSchema<Particle>()
shaderTemplate["Particle"] = particle.template
```

Constructor-property order is ABI order. `Particle` above has one schema object containing member offsets, struct alignment, array stride, CPU writers, an ABI hash, and GLSL generation. A nested `@GpuStruct` is laid out recursively.

While live coding, you can print the layout the schema computed:

```diffstatic {data-lang=kotlin}
println(particle.sizeBytes)
println(particle.strideBytes)
println(particle.offsetOf("material"))
println(particle.offsetOf(Particle::flags))
println(particle.abiHash)
```

Adding, removing, reordering, or changing the type of a field changes `abiHash`. Treat that as an interface change: shaders using the declaration must be rebuilt and buffers whose stride changed must be reallocated.

## Using the schema in a shader

After `shaderTemplate["Particle"] = particle.template`, a shader in the same box can say:

```diffstatic {data-lang=glsl}
#version 460
{{ Particle.all }}
```

`Particle.all` expands to the nested ordinary GLSL structs followed by two addressable views. Without its comments, the expansion looks like this:

```diffstatic {data-lang=glsl}
#extension GL_EXT_buffer_reference : require

struct Material {
    vec4 color;
    float roughness;
    float metallic;
};

struct Particle {
    vec3 position;
    float radius;
    Material material;
    uint flags;
};

layout(buffer_reference, std430, buffer_reference_align = 16) buffer ParticleRef {
    Particle value;
};

layout(buffer_reference, std430, buffer_reference_align = 16) buffer ParticleArray {
    Particle elements[];
};
```

The generated source also carries offset comments, which help when comparing CPU and shader layouts.

The template publishes smaller views too:

- `{{ Particle.struct }}` — ordinary struct declarations, including dependencies.
- `{{ Particle.ref }}` — a one-value `ParticleRef` buffer reference.
- `{{ Particle.array }}` — an unsized `ParticleArray` buffer reference.
- `{{ Particle.all }}` — extensions + structs + both reference forms.
- `{{ Particle.size }}`, `{{ Particle.stride }}`, `{{ Particle.alignment }}`, `{{ Particle.abi }}` — compile-time metadata.

A GLSL struct named `Particle` always gets the reference names `ParticleRef` and `ParticleArray`.

## Arrays in ordinary Field buffers

`defaultBuffer` is already host-visible, storage-capable, and device-addressable. Allocate it using the schema stride and ask the schema to pack the values:

```diffstatic {data-lang=kotlin}
val values = [
    Particle(vec(-0.7f, -0.5f, 0f), 0.2f, Material(vec(1,0,0,1), .4f, 0f), 1u),
    Particle(vec( 0.0f,  0.5f, 0f), 0.2f, Material(vec(0,1,0,1), .6f, 0f), 2u),
    Particle(vec( 0.7f, -0.5f, 0f), 0.2f, Material(vec(0,0,1,1), .8f, 0f), 3u)
]

val particleBuffer = defaultBuffer {
    size = (values.size * particle.strideBytes).bytes
}.buffer

particleBuffer.mapAndWrite_bytes {
    particle.writeArray(values, this)
}
```

The buffer can then travel through the existing reflected physical-pointer path. For example:

```diffstatic {data-lang=glsl}
{{ Particle.all }}

layout(push_constant) uniform Push {
    ParticleArray particles;
    uint count;
} pc;

void useParticle(uint i) {
    Particle p = pc.particles.elements[i];
    // ...
}
```

and Kotlin binds the actual address in the usual Field way:

```diffstatic {data-lang=kotlin}
mesh.particles = { particleBuffer }
mesh.count = { values.size.toUInt() }
```

The schema and the buffer divide the work between them:

```diffstatic {data-lang=text}
GpuSchema<Particle>          VulkanBuffer
layout / packing / GLSL      allocation / lifetime / device address
          \                    /
           \                  /
            address + schema
```

A device address is not permanently a `Particle*`: the type comes from the schema the shader reads it with. GLSL gets typed `buffer_reference` syntax because it is widely supported.

## Push constants are just another placement

A small value can also be the entire reflected push block. `GpuSchema.writer` adapts one value to `WritesToBuffer`, and `Structure.bindGpu` is the convenience form:

```diffstatic {data-lang=kotlin}
@GpuStruct
data class DrawParams(
    val color: Vector4f,
    val scale: Float
)

val draw = gpuSchema<DrawParams>()
var params = DrawParams(vec(1, .5f, 0, 1), 1f)

mesh.push.bindGpu(draw) { params }
```

For larger state, use a push constant holding one or more device addresses and a few small scalar controls, and let the same nested declarations describe the objects those addresses point to. That keeps structured state out of the push-constant budget.

## CPU arrays without a Vulkan allocation

`GpuArrayView` packs values into a `ByteBuffer` you allocate yourself and computes element offsets:

```diffstatic {data-lang=kotlin}
val bytes = ByteBuffer.allocateDirect(1024 * particle.strideBytes)
val array = particle.arrayView(bytes, 1024)

array[17] = values.first()
println(array.byteOffsetOf(17))
```

It does not own a Vulkan resource: allocation and lifetime stay with the buffer.

## Supported types

A schema field can be:

- `Float`, `Int`, `UInt`, `Long`, `ULong`
- `Vector2f`, `Vector3f`, `Vector4f`
- nested `@GpuStruct` classes

`@GpuIgnore` removes a primary-constructor property from the GPU representation.

Fixed-size Kotlin arrays, matrices, fp16 values, explicit arrays inside a struct, schema pointers, and scalar layout are not supported. Arrays of whole structs are, through `writeArray`, `GpuArrayView` and the generated `FooArray` reference.

Shader code should not treat a Kotlin-side object as the identity of an allocation. What stays stable is the schema, the allocation, and the address passed between them.

## Try it

The `5d. GPU schema` lesson in the [canonical_graphics](afield_four_graphics.md) starting point sits beneath its `window` box.
Launch `window`, then the lesson, to draw its three-particle triangle. To use it in another document,
copy the lesson's Kotlin and shader properties into a box connected beneath your renderer.

The GPU smoke test `four.gpu-schema.readback` packs three nested structs, reads them through
the generated buffer-reference array, and checks every scalar that comes back:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/run_field_tests.py --id four.gpu-schema.readback
```

??? aside "Where this is going"
    The design follows Vulkan's untyped-pointer model, where the type of a memory access belongs to the load or store rather than to the pointer. Because the schema is kept apart from the address, a later SPIR-V or Slang backend can emit `SPV_KHR_untyped_pointers` instead of GLSL `buffer_reference` types without changing the data declaration or the CPU packing API. Planned additions (matrix, fp16 and fixed-array fields, `GpuPtr<T>` members, a `GpuArray<T>` that owns its `VulkanBuffer`, ABI hashes driving live rebuilds) all keep layout, CPU packing and the emitted shader declaration as three views of one schema.
