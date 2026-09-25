import afield.controlGroup
import afield.displayOpen
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.lighting.*
import afield.four.modules.fromContextWithNameOrNull
import afield.four.reconstruction.*
import afield.four.texture.TextureSampler
import afield.stdlib.fromViews
import org.lwjgl.vulkan.VK13.*

// This guide conversion describes the existing opaque Lambertian RTXDI bridge.
// It is not a template for inventing missing glossy or transmission guides.
NgxSupport.rayReconstructionAvailability(renderer.device).getOrThrow()
prop<AutoCloseable>(plumbing)
fromContextWithNameOrNull<AutoCloseable>("plumbing")?.close()
val size=ReconstructionSize(1920,1080)
prop<DirectLightingDemo>(scene)
scene=DirectLightingDemo(renderer,size.width,size.height)
val settings=RtxdiSettings({scene.bundle.scene.camera.modelView()},{scene.bundle.scene.projection.evaluateUniform()})
    .apply { temporal=true;spatialSamples=4;denoisePasses=0 }
scene.lighting.enableRtxdi(RtxdiProgram.load(),settings).getOrThrow()
scene.lighting.mode=DirectLighting.Mode.RTXDI
scene.lighting.samples=8
val rr=DlssReconstruction(renderer,size,size,ReconstructionMode.DLAA,rayReconstruction=true)
prop<RenderToTexture>(guides)
guides=RenderToTexture(renderer) {
    width=size.width;height=size.height;useDepth=false;colorAttachments=5
    colorFormats=listOf(VK_FORMAT_R32_SFLOAT,VK_FORMAT_R32G32_SFLOAT,
        VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT)
}
val sampler=TextureSampler(renderer,1,false,filter=VK_FILTER_NEAREST)
val surfaces=scene.lighting.surfaces
val inputs=mapOf("positions" to surfaces.position,"normals" to surfaces.normal,
    "albedo" to surfaces.albedo,"velocity" to surfaces.motion)
val guideModule=moduleHere {
    guides.apply();depthTest=false;depthWrite=false;blend=false
    textureResolver={ texture -> inputs[texture.name]?.let { fromViews(listOf(it),texture.name,sampler) } }
}
val quad=Mesh(renderer,guideModule)
quad.resize(3,0,guideModule)
guideModule += "convert" to quad
guides += "guides" to guideModule

// Remove independent scheduling: this pass now owns their order.
scene.bundle.frameGraph().passNames().forEach { renderer.renderList.preTargets.remove(it) }
renderer.renderList.preTargets.remove(scene.lighting.name)
renderer.renderList.modules.remove("direct-light-demo")
var bypass=false
var animate=true
var cut=false
var time=0.0
var frameIndex=0L
var revision=0L
var shaderVersion=guideModule.current.fi
val pass=object: Target {
    override fun render(command: CommandBuffer,index: Int) {
        if(shaderVersion!==guideModule.current.fi) {shaderVersion=guideModule.current.fi;revision++;rr.reset("guide edit")}
        val jitter=if(bypass)ReconstructionJitter(0f,0f) else ReconstructionJitter.halton(frameIndex,size,size)
        scene.bundle.scene.rasterJitterNdc.set(2*jitter.x/size.width,-2*jitter.y/size.height)
        scene.animate(time)
        if(animate)time+=1.0/60
        settings.commitLights(listOf(IdentifiedAreaLight(1,scene.lighting.emitter)))

        scene.bundle.gBuffer.render(command,index)  // geometry, unjittered motion
        scene.lighting.render(command,index)       // raw RTXDI RGB
        guides.render(command,index)               // moduleHere format / convention conversion
        if(!bypass)rr.evaluate(command,
            ReconstructionInputs(scene.lighting.output,guides.imageViewArray[0],guides.imageViewArray[1]),
            ReconstructionFrame(frameIndex,jitter,revision=revision),
            RayReconstructionGuides(guides.imageViewArray[2],guides.imageViewArray[3],guides.imageViewArray[4],
                scene.bundle.scene.camera.modelView(),scene.bundle.scene.projection.evaluateUniform()))
        frameIndex++
    }
}
renderer.renderList.preTargets["RR plumbing"]=pass
rr.output.blitToScreen(name="RR plumbing")
// Capture this allocation, not the mutable box prop that a rerun replaces.
val ownedTarget=guides
val owner=renderer // Cleanup must not depend on a still-running parent box.
var closed=false
plumbing=AutoCloseable {
    if(!closed) {
        closed=true;owner.renderList.preTargets.remove("RR plumbing",pass)
        owner.renderList.modules.remove("RR plumbing")
        rr.close();scene.close()
        owner.cleanup+=Runnable {
            guideModule.current.cleanup();sampler.cleanup()
            ownedTarget.imageViewArray.forEach { it.imageView.cleanup() }
            ownedTarget.colorImageRefs.forEach { it?.cleanup() };ownedTarget.defaultSampler?.cleanup()
        }
    }
}
controlGroup("RTXDI to RR") {
    checkbox("Bypass to raw lighting",initialValue=false) { bypass=it }
    checkbox("Move light",initialValue=true) { animate=it }
    button("Cut / reset history") { if(it)cut=true }
}
displayOpen("RTXDI to RR")
println("Read render(): G-buffer → raw RTXDI → five guide attachments → RR → screen. Edit fragment to inspect the guide mapping.")
frames {
    var wasBypass=bypass
    while(true) {
        if(wasBypass!=bypass) {
            rr.reset("bypass changed");settings.resetHistory("bypass changed")
            (if(bypass)scene.lighting.output else rr.output).blitToScreen(name="RR plumbing")
            wasBypass=bypass
        }
        if(cut) {time+=4;revision++;rr.reset("live cut");settings.resetHistory("live cut");scene.bundle.invalidateFrameHistory();cut=false}
        frame()
    }
}.end { plumbing.close() }
