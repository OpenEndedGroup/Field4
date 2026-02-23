package afield.four.modules

import kotlin.reflect.KProperty


class MeshGroup : ArrayList<Mesh>() {
    class BoundFromStructure<T> {
        operator fun setValue(inside: MeshGroup, property: KProperty<*>, any: () -> T) {
            inside.forEach { inside ->
                if (inside.push.offsets.isEmpty()) {
                    inside.initializePushConstantsFromModule(inside.inside)
                }
                if (inside.push.offsets.containsKey(property.name)) inside.push.bind(property.name, any as Function0<*>)
                else throw IllegalArgumentException(" can't find ${property.name} in a mesh group  in this module ${inside.inside.current.parameters.name} as a push_constant")
            }
        }

        operator fun getValue(inside: MeshGroup, property: KProperty<*>): () -> T {
            inside.forEach { inside ->
                if (inside.push.offsets.isEmpty()) {
                    inside.initializePushConstantsFromModule(inside.inside)
                }
                if (inside.push.offsets.containsKey(property.name)) return@getValue inside.push.bindings[property.name] as () -> T
            }
            throw IllegalArgumentException(" can't find ${property.name} in a mesh group in this module ${inside} as a push_constant or a UBO structure")
        }
    }
}

class MeshMeshGroup : ArrayList<MeshMesh>() {
    class BoundFromStructure<T> {
        operator fun setValue(inside: MeshMeshGroup, property: KProperty<*>, any: () -> T) {
            inside.forEach { inside ->
                if (inside.push.offsets.isEmpty()) {
                    inside.initializePushConstantsFromModule(inside.inside)
                }
                if (inside.push.offsets.containsKey(property.name)) inside.push.bind(property.name, any as Function0<*>)
                else throw IllegalArgumentException(" can't find ${property.name} in a mesh group  in this module ${inside.inside.current.parameters.name} as a push_constant")
            }
        }

        operator fun getValue(inside: MeshMeshGroup, property: KProperty<*>): () -> T {
            inside.forEach { inside ->
                if (inside.push.offsets.isEmpty()) {
                    inside.initializePushConstantsFromModule(inside.inside)
                }
                if (inside.push.offsets.containsKey(property.name)) return@getValue inside.push.bindings[property.name] as () -> T
            }
            throw IllegalArgumentException(" can't find ${property.name} in a mesh group in this module ${inside} as a push_constant or a UBO structure")
        }
    }
}

class ComputeMeshGroup : ArrayList<ComputeMesh>() {
    class BoundFromStructure<T> {
        operator fun setValue(inside: ComputeMeshGroup, property: KProperty<*>, any: () -> T) {
            inside.forEach { inside ->
                if (inside.push.offsets.isEmpty()) {
                    inside.initializePushConstantsFromModule(inside.inside)
                }
                if (inside.push.offsets.containsKey(property.name)) inside.push.bind(property.name, any as Function0<*>)
                else throw IllegalArgumentException(" can't find ${property.name} in a mesh group  in this module ${inside.inside.current.parameters.name} as a push_constant")
            }
        }

        operator fun getValue(inside: ComputeMeshGroup, property: KProperty<*>): () -> T {
            inside.forEach { inside ->
                if (inside.push.offsets.isEmpty()) {
                    inside.initializePushConstantsFromModule(inside.inside)
                }
                if (inside.push.offsets.containsKey(property.name)) return@getValue inside.push.bindings[property.name] as () -> T
            }
            throw IllegalArgumentException(" can't find ${property.name} in a mesh group in this module ${inside} as a push_constant or a UBO structure")
        }
    }
}

