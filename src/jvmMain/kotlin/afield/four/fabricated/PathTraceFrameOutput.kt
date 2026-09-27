package afield.four.fabricated

import afield.four.Target
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.reconstruction.*
import afield.four.targets.RenderToTexture
import afield.stdlib.linalg.Matrix4f
import org.lwjgl.vulkan.VK13.*

/** GPU-resident output of a single path-tracing dispatch, before progressive accumulation.
 * Six vec4s per pixel (96 bytes). No readback or native library is needed to produce these images.
 * The tracer owns this object; consumers borrow the images until the tracer closes.
 * Jitter is projection displacement, positive right/down: primary rays sample pixel-jitter.
 * Depth is RH perspective Vulkan [0,1], clear=1, using [near]/[far]. Motion is previous-current
 * in input pixels without jitter; geometry is the tracer's immutable scene snapshot.
 */
class PathTraceFrameOutput internal constructor(val tracer: PathTracingRenderer) : Target, AutoCloseable {
    private val renderer=tracer.renderer
    init { require(renderer.device.physicalDevice.vkPhysicalDeviceProperties.limits().maxColorAttachments()>=7) { "Path reconstruction needs seven color attachments" } }
    val near=.01f
    val far=10000f
    var jitter=ReconstructionJitter(0f,0f)
    internal val buffer=VulkanBuffer(renderer,tracer.width.toLong()*tracer.height*96,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,needsDeviceAddress=true)
    val images=RenderToTexture(renderer) {
        width=tracer.width;height=tracer.height;useDepth=false;colorAttachments=7;debugName="${tracer.name}-frame"
        colorFormats=listOf(VK_FORMAT_R32G32B32A32_SFLOAT,VK_FORMAT_R32_SFLOAT,VK_FORMAT_R32G32_SFLOAT,
            VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R32_SFLOAT)
    }
    val color get()=images.imageViewArray[0]
    val depth get()=images.imageViewArray[1]
    val motion get()=images.imageViewArray[2]
    val diffuseAlbedo get()=images.imageViewArray[3]
    val specularAlbedo get()=images.imageViewArray[4]
    val normalRoughness get()=images.imageViewArray[5]
    val specularHitDistance get()=images.imageViewArray[6]
    val allocatedBytes get()=buffer.requestedSize+images.colorImageRefs.sumOf { it?.memorySize ?: 0 }
    fun inputs()=ReconstructionInputs(color,depth,motion)
    fun guides()=RayReconstructionGuides(diffuseAlbedo,specularAlbedo,normalRoughness,tracer.camera.modelView(),
        Matrix4f().setPerspective(tracer.camera.p.fov.toFloat(),tracer.width.toFloat()/tracer.height,near,far,true),specularHitDistance)
    private val convert=Module(parameters(renderer) {
        name="${tracer.name}-frame-convert";images.apply();depthTest=false;depthWrite=false;blend=false
        vertex=VERTEX;fragment=FRAGMENT
    })
    private var closed=false
    init {
        check(convert.valid) { "Path frame conversion did not compile" }
        val p=convert.pointer();val mesh=Mesh(renderer,p);mesh.resize(3,0,p)
        mesh.push.setMembers(convert.fi.pushConstants.single().members)
        mesh.push["pixels"]=buffer;mesh.push["width"]=tracer.width;mesh.push["height"]=tracer.height
        convert.meshes["quad"]=mesh;images.modules["convert"]=p
    }
    /** Record after tracer.render, before any reconstruction consumer. */
    override fun render(command: CommandBuffer,index: Int) {
        check(!closed)
        graphicsMemoryBarrier(command,buffer)
        images.render(command,index)
    }
    override fun close() {
        if(closed)return;closed=true
        renderer.cleanup+=Runnable {
            convert.cleanup();buffer.cleanup();images.imageViewArray.forEach { it.imageView.cleanup() }
            images.colorImageRefs.forEach { it?.cleanup() };images.defaultSampler?.cleanup()
        }
    }
    companion object {
        private const val VERTEX="""#version 460
        layout(location=0) out vec2 uv;
        void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);uv=vec2(p.x,1-p.y);}
        """
        private const val FRAGMENT="""#version 460
        #extension GL_EXT_buffer_reference : require
        struct FramePixel {vec4 radiance;vec4 positionDepth;vec4 normalRoughness;vec4 diffuse;vec4 specular;vec4 motion;};
        layout(buffer_reference,std430,buffer_reference_align=16) readonly buffer Pixels {FramePixel items[];};
        layout(push_constant) uniform Resolve {Pixels pixels;int width;int height;} pc;
        layout(location=0) in vec2 uv;
        layout(location=0) out vec4 color;
        layout(location=1) out float depth;
        layout(location=2) out vec2 motion;
        layout(location=3) out vec4 diffuse;
        layout(location=4) out vec4 specular;
        layout(location=5) out vec4 normalRoughness;
        layout(location=6) out float specularDistance;
        void main(){ivec2 p=clamp(ivec2(uv*vec2(pc.width,pc.height)),ivec2(0),ivec2(pc.width-1,pc.height-1));
            FramePixel f=pc.pixels.items[p.y*pc.width+p.x];color=f.radiance;depth=f.positionDepth.w;
            motion=f.motion.xy;diffuse=f.diffuse;specular=f.specular;normalRoughness=f.normalRoughness;specularDistance=f.motion.z;}
        """
    }
}
