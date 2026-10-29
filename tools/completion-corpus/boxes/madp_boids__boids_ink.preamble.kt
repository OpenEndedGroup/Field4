import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*compute-pc-structure:src*/ var Structure.src by Structure.FromStructure<Any>()
/*compute-pc-binding:src*/ var ComputeMesh.src by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:src*/ var ComputeMeshGroup.src by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:dst*/ var Structure.dst by Structure.FromStructure<Any>()
/*compute-pc-binding:dst*/ var ComputeMesh.dst by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:dst*/ var ComputeMeshGroup.dst by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:history*/ var Structure.history by Structure.FromStructure<Any>()
/*compute-pc-binding:history*/ var ComputeMesh.history by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:history*/ var ComputeMeshGroup.history by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:eaten*/ var Structure.eaten by Structure.FromStructure<Any>()
/*compute-pc-binding:eaten*/ var ComputeMesh.eaten by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:eaten*/ var ComputeMeshGroup.eaten by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:pick*/ var Structure.pick by Structure.FromStructure<Any>()
/*compute-pc-binding:pick*/ var ComputeMesh.pick by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:pick*/ var ComputeMeshGroup.pick by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:stats*/ var Structure.stats by Structure.FromStructure<Any>()
/*compute-pc-binding:stats*/ var ComputeMesh.stats by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:stats*/ var ComputeMeshGroup.stats by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:strokes*/ var Structure.strokes by Structure.FromStructure<Any>()
/*compute-pc-binding:strokes*/ var ComputeMesh.strokes by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:strokes*/ var ComputeMeshGroup.strokes by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
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
/*compute-pc-structure:step*/ var Structure.step by Structure.FromStructure<Any>()
/*compute-pc-binding:step*/ var ComputeMesh.step by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:step*/ var ComputeMeshGroup.step by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:lastStep*/ var Structure.lastStep by Structure.FromStructure<Any>()
/*compute-pc-binding:lastStep*/ var ComputeMesh.lastStep by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:lastStep*/ var ComputeMeshGroup.lastStep by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:t*/ var Structure.t by Structure.FromStructure<Any>()
/*compute-pc-binding:t*/ var ComputeMesh.t by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:t*/ var ComputeMeshGroup.t by ComputeMeshGroup.BoundFromStructure<Number>()
var Box.window by Inheritance<Window>() /*from:boids_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:boids_renderer*/ /**/
val Why.window by Whying() /*from:boids_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:boids_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:boids_renderer*/ /**/
val Why.renderer by Whying() /*from:boids_renderer*/ /**/
