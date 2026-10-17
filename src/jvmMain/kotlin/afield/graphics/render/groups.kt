package afield.graphics.render

import afield.graphics.vulkan.buffers.bindPushConstantAcrossGroup
import afield.graphics.vulkan.buffers.firstBoundPushConstant
import kotlin.reflect.KProperty


class MeshGroup : ArrayList<Mesh>() {
    class BoundFromStructure<T> {
        operator fun setValue(inside: MeshGroup, property: KProperty<*>, any: () -> T) {
            inside.bindPushConstantAcrossGroup(
                property.name, any,
                push = { it.push },
                ensureInitialized = { if (it.push.offsets.isEmpty()) it.initializePushConstantsFromModule(it.inside) },
            ) { element ->
                throw IllegalArgumentException(" can't find ${property.name} in a mesh group  in this module ${element.inside.current.parameters.name} as a push_constant")
            }
        }

        operator fun getValue(inside: MeshGroup, property: KProperty<*>): () -> T =
            inside.firstBoundPushConstant(
                property.name,
                push = { it.push },
                ensureInitialized = { if (it.push.offsets.isEmpty()) it.initializePushConstantsFromModule(it.inside) },
            ) {
                throw IllegalArgumentException(" can't find ${property.name} in a mesh group in this module $inside as a push_constant or a UBO structure")
            }
    }
}

class MeshMeshGroup : ArrayList<MeshMesh>() {
    class BoundFromStructure<T> {
        operator fun setValue(inside: MeshMeshGroup, property: KProperty<*>, any: () -> T) {
            inside.bindPushConstantAcrossGroup(
                property.name, any,
                push = { it.push },
                ensureInitialized = { if (it.push.offsets.isEmpty()) it.initializePushConstantsFromModule(it.inside) },
            ) { element ->
                throw IllegalArgumentException(" can't find ${property.name} in a mesh group  in this module ${element.inside.current.parameters.name} as a push_constant")
            }
        }

        operator fun getValue(inside: MeshMeshGroup, property: KProperty<*>): () -> T =
            inside.firstBoundPushConstant(
                property.name,
                push = { it.push },
                ensureInitialized = { if (it.push.offsets.isEmpty()) it.initializePushConstantsFromModule(it.inside) },
            ) {
                throw IllegalArgumentException(" can't find ${property.name} in a mesh group in this module $inside as a push_constant or a UBO structure")
            }
    }
}

class ComputeMeshGroup : ArrayList<ComputeMesh>() {
    class BoundFromStructure<T> {
        operator fun setValue(inside: ComputeMeshGroup, property: KProperty<*>, any: () -> T) {
            inside.bindPushConstantAcrossGroup(
                property.name, any,
                push = { it.push },
                ensureInitialized = { if (it.push.offsets.isEmpty()) it.initializePushConstantsFromModule(it.inside) },
            ) { element ->
                throw IllegalArgumentException(" can't find ${property.name} in a mesh group  in this module ${element.inside.current.parameters.name} as a push_constant")
            }
        }

        operator fun getValue(inside: ComputeMeshGroup, property: KProperty<*>): () -> T =
            inside.firstBoundPushConstant(
                property.name,
                push = { it.push },
                ensureInitialized = { if (it.push.offsets.isEmpty()) it.initializePushConstantsFromModule(it.inside) },
            ) {
                throw IllegalArgumentException(" can't find ${property.name} in a mesh group in this module $inside as a push_constant or a UBO structure")
            }
    }
}

/**
 * Apply the same live push bindings to each mesh: `bindPush(even, odd) { count = { n } }`.
 * The receiver retains its concrete mesh type and generated shader properties. The block executes
 * once per target; suppliers assigned inside it remain live rather than being sampled here.
 */
fun <T> bindPush(vararg targets: T, bindings: T.() -> Unit) {
    targets.forEach { target -> target.bindings() }
}
