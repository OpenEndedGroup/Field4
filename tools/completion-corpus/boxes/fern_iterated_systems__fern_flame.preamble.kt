import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*compute-pc-structure:particles*/ var Structure.particles by Structure.FromStructure<Any>()
/*compute-pc-binding:particles*/ var ComputeMesh.particles by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:particles*/ var ComputeMeshGroup.particles by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:points*/ var Structure.points by Structure.FromStructure<Any>()
/*compute-pc-binding:points*/ var ComputeMesh.points by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:points*/ var ComputeMeshGroup.points by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:count*/ var Structure.count by Structure.FromStructure<Any>()
/*compute-pc-binding:count*/ var ComputeMesh.count by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:count*/ var ComputeMeshGroup.count by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:steps*/ var Structure.steps by Structure.FromStructure<Any>()
/*compute-pc-binding:steps*/ var ComputeMesh.steps by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:steps*/ var ComputeMeshGroup.steps by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:maxSteps*/ var Structure.maxSteps by Structure.FromStructure<Any>()
/*compute-pc-binding:maxSteps*/ var ComputeMesh.maxSteps by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:maxSteps*/ var ComputeMeshGroup.maxSteps by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:frame*/ var Structure.frame by Structure.FromStructure<Any>()
/*compute-pc-binding:frame*/ var ComputeMesh.frame by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:frame*/ var ComputeMeshGroup.frame by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:seed*/ var Structure.seed by Structure.FromStructure<Any>()
/*compute-pc-binding:seed*/ var ComputeMesh.seed by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:seed*/ var ComputeMeshGroup.seed by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:reset*/ var Structure.reset by Structure.FromStructure<Any>()
/*compute-pc-binding:reset*/ var ComputeMesh.reset by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:reset*/ var ComputeMeshGroup.reset by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:dt*/ var Structure.dt by Structure.FromStructure<Any>()
/*compute-pc-binding:dt*/ var ComputeMesh.dt by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:dt*/ var ComputeMeshGroup.dt by ComputeMeshGroup.BoundFromStructure<Number>()
var Box.window by Inheritance<Window>() /*from:fern_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:fern_renderer*/ /**/
val Why.window by Whying() /*from:fern_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:fern_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:fern_renderer*/ /**/
val Why.renderer by Whying() /*from:fern_renderer*/ /**/
