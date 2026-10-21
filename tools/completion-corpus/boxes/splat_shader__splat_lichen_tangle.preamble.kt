import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import arrow.core.right
import arrow.core.left
import afield.graphics.shader.*
import afield.graphics.scene.*
import afield.graphics.render.*
import afield.document.context.*
import afield.graphics.vulkan.buffers.*
/*compute-pc-structure:splatsIn*/ var Structure.splatsIn by Structure.FromStructure<Any>()
/*compute-pc-binding:splatsIn*/ var ComputeMesh.splatsIn by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:splatsIn*/ var ComputeMeshGroup.splatsIn by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:splatsOut*/ var Structure.splatsOut by Structure.FromStructure<Any>()
/*compute-pc-binding:splatsOut*/ var ComputeMesh.splatsOut by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:splatsOut*/ var ComputeMeshGroup.splatsOut by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:splatCount*/ var Structure.splatCount by Structure.FromStructure<Any>()
/*compute-pc-binding:splatCount*/ var ComputeMesh.splatCount by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:splatCount*/ var ComputeMeshGroup.splatCount by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:t*/ var Structure.t by Structure.FromStructure<Any>()
/*compute-pc-binding:t*/ var ComputeMesh.t by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:t*/ var ComputeMeshGroup.t by ComputeMeshGroup.BoundFromStructure<Number>()
var Box.window by Inheritance<Window>() /*from:splat_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:splat_renderer*/ /**/
val Why.window by Whying() /*from:splat_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:splat_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:splat_renderer*/ /**/
val Why.renderer by Whying() /*from:splat_renderer*/ /**/
