package afield.graphics.scene.reading

import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.Parameters
import afield.graphics.render.pointer

/** A document shader box builds this pass with moduleHere; the library supplies its target/resources. */
typealias DiagramModuleFactory = (Parameters) -> ModulePointer

/** Library defaults remain usable without boxes; editable documents supply a factory for each pass. */
internal fun readingModule(
    parameters: Parameters,
    vertex: String,
    fragment: String,
    factories: Map<String, DiagramModuleFactory>,
): ModulePointer =
    factories[parameters.name]?.invoke(parameters)
        ?: Module(
            parameters.apply {
                this.vertex = vertex
                this.fragment = fragment
            },
        ).pointer()
