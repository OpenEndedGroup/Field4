import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.cudaParticles by Inheritance<VulkanBuffer>() /**/
val Once.cudaParticles by Onceing<VulkanBuffer>() /**/
val Why.cudaParticles by Whying() /**/
var Box.cudaParticleCount by Inheritance<Int>() /**/
val Once.cudaParticleCount by Onceing<Int>() /**/
val Why.cudaParticleCount by Whying() /**/
var Box.window by Inheritance<Window>() /*from:cuda_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:cuda_renderer*/ /**/
val Why.window by Whying() /*from:cuda_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:cuda_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:cuda_renderer*/ /**/
val Why.renderer by Whying() /*from:cuda_renderer*/ /**/
