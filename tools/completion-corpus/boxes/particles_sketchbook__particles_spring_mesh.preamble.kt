import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import arrow.core.right
import arrow.core.left
import afield.graphics.shader.*
import afield.graphics.scene.*
import afield.graphics.render.*
import afield.document.context.*
import afield.graphics.vulkan.buffers.*
/*compute-pc-structure:src*/ var Structure.src by Structure.FromStructure<Any>()
/*compute-pc-binding:src*/ var ComputeMesh.src by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:src*/ var ComputeMeshGroup.src by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:dst*/ var Structure.dst by Structure.FromStructure<Any>()
/*compute-pc-binding:dst*/ var ComputeMesh.dst by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:dst*/ var ComputeMeshGroup.dst by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:strokes*/ var Structure.strokes by Structure.FromStructure<Any>()
/*compute-pc-binding:strokes*/ var ComputeMesh.strokes by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:strokes*/ var ComputeMeshGroup.strokes by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:spawns*/ var Structure.spawns by Structure.FromStructure<Any>()
/*compute-pc-binding:spawns*/ var ComputeMesh.spawns by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:spawns*/ var ComputeMeshGroup.spawns by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:world*/ var Structure.world by Structure.FromStructure<Any>()
/*compute-pc-binding:world*/ var ComputeMesh.world by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:world*/ var ComputeMeshGroup.world by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:gridValues*/ var Structure.gridValues by Structure.FromStructure<Any>()
/*compute-pc-binding:gridValues*/ var ComputeMesh.gridValues by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:gridValues*/ var ComputeMeshGroup.gridValues by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:cellStart*/ var Structure.cellStart by Structure.FromStructure<Any>()
/*compute-pc-binding:cellStart*/ var ComputeMesh.cellStart by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:cellStart*/ var ComputeMeshGroup.cellStart by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:cellEnd*/ var Structure.cellEnd by Structure.FromStructure<Any>()
/*compute-pc-binding:cellEnd*/ var ComputeMesh.cellEnd by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:cellEnd*/ var ComputeMeshGroup.cellEnd by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:phase*/ var Structure.phase by Structure.FromStructure<Any>()
/*compute-pc-binding:phase*/ var ComputeMesh.phase by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:phase*/ var ComputeMeshGroup.phase by ComputeMeshGroup.BoundFromStructure<Any>()
var Box.window by Inheritance<Window>() /*from:particles_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:particles_renderer*/ /**/
val Why.window by Whying() /*from:particles_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:particles_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:particles_renderer*/ /**/
val Why.renderer by Whying() /*from:particles_renderer*/ /**/
