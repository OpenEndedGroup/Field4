package afield.four.modules

import afield.four.buffers.bindPushConstantAcrossGroup
import afield.four.buffers.firstBoundPushConstant
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
