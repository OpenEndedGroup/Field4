# GPU schemas: one Kotlin ABI for push constants and addressable buffers

`GpuSchema` is the starting point for making a Kotlin data declaration the single source of truth for CPU/GPU memory layout. It is deliberately **not** a push-constant abstraction: one schema describes bytes, and those bytes can live inline in push constants or UBO/SSBO storage, or in an arbitrary device-addressable buffer reached through a pointer.

The first slice lives in `afield.four.buffers.GpuSchema`. It reflects an annotated primary-constructor data class, computes a deterministic std430-compatible layout, writes values and arrays into `ByteBuffer`s, and generates GLSL structs plus `GL_EXT_buffer_reference` views. Field's existing shader template expansion is the bridge between the Kotlin declaration in a box and the shader source in that same box.

## A particle schema

```kotlin
import afield.four.buffers.*
import afield.stdlib.linalg.*

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

Useful inspection while live coding:

```kotlin
println(particle.sizeBytes)
println(particle.strideBytes)
println(particle.offsetOf("material"))
println(particle.offsetOf(Particle::flags))
println(particle.abiHash)
```

A schema change — adding, removing, reordering, or changing the type of a field — changes `abiHash`. Treat that as an interface change: shaders using the declaration must be rebuilt and buffers whose stride changed must be reallocated.

## The shader declaration arrives through the box template

After `shaderTemplate["Particle"] = particle.template`, a shader in the same box can say:

```glsl
#version 460
{{ Particle.all }}
```

`Particle.all` expands to the nested ordinary GLSL structs followed by two addressable views, conceptually:

```glsl
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
    Particle values[];
};
```

The exact generated source contains offset comments, useful when comparing CPU and shader layouts.

The template publishes smaller views too:

- `{{ Particle.struct }}` — ordinary struct declarations, including dependencies.
- `{{ Particle.ref }}` — a one-value `ParticleRef` buffer reference.
- `{{ Particle.array }}` — an unsized `ParticleArray` buffer reference.
- `{{ Particle.all }}` — extensions + structs + both reference forms.
- `{{ Particle.size }}`, `{{ Particle.stride }}`, `{{ Particle.alignment }}`, `{{ Particle.abi }}` — compile-time metadata.

The generated reference names are therefore predictable: `ParticleRef` and `ParticleArray` for a GLSL struct named `Particle`.

## Arrays in ordinary Field buffers

`defaultBuffer` is already host-visible, storage-capable, and device-addressable. Allocate it using the schema stride and ask the schema to pack the values:

```kotlin
val values = listOf(
    Particle(vec(-0.7f, -0.5f, 0f), 0.2f, Material(vec(1,0,0,1), .4f, 0f), 1u),
    Particle(vec( 0.0f,  0.5f, 0f), 0.2f, Material(vec(0,1,0,1), .6f, 0f), 2u),
    Particle(vec( 0.7f, -0.5f, 0f), 0.2f, Material(vec(0,0,1,1), .8f, 0f), 3u)
)

val particleBuffer = defaultBuffer {
    size = (values.size * particle.strideBytes).bytes
}.buffer

particleBuffer.mapAndWrite_bytes {
    particle.writeArray(values, this)
}
```

The buffer can then travel through the existing reflected physical-pointer path. For example:

```glsl
{{ Particle.all }}

layout(push_constant) uniform Push {
    ParticleArray particles;
    uint count;
} pc;

void useParticle(uint i) {
    Particle p = pc.particles.values[i];
    // ...
}
```

and Kotlin binds the actual address in the usual Field way:

```kotlin
mesh.particles = { particleBuffer }
mesh.count = { values.size.toUInt() }
```

The important division is:

```text
GpuSchema<Particle>          VulkanBuffer
layout / packing / GLSL      allocation / lifetime / device address
          \                    /
           \                  /
            address + schema
```

The Kotlin model does not say that a device address *is* permanently a `Particle*`. GLSL currently gets typed `buffer_reference` syntax because that is a useful and widely supported source spelling. This leaves the backend free to lower the same `address + schema` model to `SPV_KHR_untyped_pointers` later.

## Push constants are just another placement

A small value can also be the entire reflected push block. `GpuSchema.writer` adapts one value to `WritesToBuffer`, and `Structure.bindGpu` is the convenience form:

```kotlin
@GpuStruct
data class DrawParams(
    val color: Vector4f,
    val scale: Float
)

val draw = gpuSchema<DrawParams>()
var params = DrawParams(vec(1, .5f, 0, 1), 1f)

mesh.push.bindGpu(draw) { params }
```

For larger state, prefer a push constant containing one or more device addresses and small scalar controls. The schema system is useful precisely because the same nested declarations can describe the pointed-to objects rather than forcing all structured state into the push-constant budget.

## CPU arrays without a Vulkan allocation

`GpuArrayView` is the lightweight packing/address-arithmetic half of the eventual typed buffer API:

```kotlin
val bytes = ByteBuffer.allocateDirect(1024 * particle.strideBytes)
val array = particle.arrayView(bytes, 1024)

array[17] = values.first()
println(array.byteOffsetOf(17))
```

It is intentionally not an owner of a Vulkan resource. This first slice keeps memory schema and Vulkan lifetime separate; a later `GpuArray<T>` can compose a schema with a `VulkanBuffer` rather than duplicating layout logic.

## Supported types in the first slice

The initial ABI supports:

- `Float`, `Int`, `UInt`, `Long`, `ULong`
- `Vector2f`, `Vector3f`, `Vector4f`
- nested `@GpuStruct` classes

`@GpuIgnore` removes a primary-constructor property from the GPU representation.

Fixed-size Kotlin arrays, matrices, fp16 values, explicit arrays inside a struct, schema pointers (`GpuPtr<T>`), scalar layout, and direct SPIR-V untyped-pointer emission are intentionally next steps rather than being guessed into the first ABI. Arrays of *whole structs* are supported now through `writeArray` / `GpuArrayView` / the generated `FooArray` reference.

## Untyped pointers

The target architecture is Vulkan's untyped-pointer model: a GPU address has storage-class/address meaning, while the type of a memory operation belongs to the load/store rather than permanently to the pointer. That lines up with Field's existing buffer-device-address architecture and with future descriptor-heap work.

The source-level contract here already reflects that direction: Kotlin keeps `GpuSchema` separate from the address. The current GLSL backend casts an address into a generated `buffer_reference` type; a future SPIR-V/Slang backend can instead use `SPV_KHR_untyped_pointers` without changing the data declaration or CPU packing API.

Do not make shader code depend on a Kotlin-side `ParticlePtr` object being the identity of the allocation. The stable concepts are the schema, the allocation, and the address passed between them.

## Where to extend this

The next useful increments are deliberately small:

1. add matrix/fp16/fixed-array field kinds, each with layout + packing + shaderc tests;
2. add `GpuPtr<T>` as an 8-byte schema member so nested data can form GPU pointer graphs;
3. compose `GpuSchema<T>` with `BufferParameters`/`FrameBufferGroup` as a real `GpuArray<T>` owner;
4. hook ABI hashes into Field's shader-interface transition machinery so a live schema edit automatically chooses the correct rebuild/reallocation path;
5. enable and probe `VK_KHR_shader_untyped_pointers` at device creation, then add a compiler backend that can actually emit `OpTypeUntypedPointerKHR` / untyped access chains;
6. eventually let descriptor heaps and device-generated execution consume the same schemas and addresses.

The rule for additions is simple: **layout, CPU serialization and emitted shader declaration must remain three views of one schema.** If a feature would require them to calculate offsets independently, the abstraction is wrong.
