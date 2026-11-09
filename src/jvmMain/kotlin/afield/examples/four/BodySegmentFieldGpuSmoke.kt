package afield.examples.four

import afield.graphics.scene.body.*
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.OpenXRHmdTarget.BodyJoint
import afield.graphics.vulkan.targets.OpenXRHmdTarget.BodyTrackingState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.JointPose
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.util.gpuProbe
import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector3f
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.body-segment-field
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.BodySegmentFieldGpuSmoke
tags: [smoke, four, gpu, body, compute]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains: ["Body segment GPU field PASSED"]
  stdoutNotContains: ["VUID-", "Validation Error"]
  stderrNotContains: ["VUID-", "Validation Error"]
*/

/** Real GPU address/layout, current-pose search, force sign and world/local transform checks. */
object BodySegmentFieldGpuSmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR = false
        Options.validate = true
        val window = Window("Body segment field proof")
        val renderer = Renderer(window)
        val world = listOf(Vector3f(.25f,.5f,0f),Vector3f(.05f,.5f,0f),Vector3f(0f,0f,0f),
            Vector3f(1.1f,1f,0f),Vector3f(1f,1f,0f),Vector3f(0f,1.3f,0f),Vector3f(3f,3f,3f))
        val field = BodySegmentField(renderer, world.size)
        fun pose(x: Float,y: Float,z: Float) = JointPose(position=Vector3f(x,y,z),positionValid=true)
        val body=BodyTrackingState()
        body.joints[BodyJoint.HIPS.ordinal]=pose(0f,0f,0f)
        body.joints[BodyJoint.CHEST.ordinal]=pose(0f,1f,0f)
        val snapshot=BodySegments.from(body,pose(1f,1f,0f))
        val shader="""#version 460
        #extension GL_EXT_buffer_reference : require
        #extension GL_EXT_scalar_block_layout : require
        ${BodySegmentField.shaderHeader}
        layout(std430,buffer_reference) buffer Vectors { vec4 value[]; };
        layout(push_constant) uniform Test { Vectors inputData; Vectors outputData; uint count; } pc;
        layout(local_size_x=32) in;
        void main(){
            uint i=gl_GlobalInvocationID.x;if(i>=pc.count)return;
            vec3 force=vec3(0);
            // Deliberately visit a complete cycle in this fixture; production calls exactly once.
            for(uint tick=0u;tick<64u;tick++)force=bodySegmentForce(i,tick,pc.inputData.value[i].xyz);
            pc.outputData.value[i*2u]=vec4(force,gBodyDistance);
            pc.outputData.value[i*2u+1u]=vec4(gBodyNormal.xyz,gBodySegment);
        }
        """.trimIndent()
        try {
            gpuProbe(renderer,name="body-field",pump={window.pollEvents();renderer.render()}) { probe ->
                val input=probe.fixture("positions",world.size*4)
                val output=probe.fixture("forces",world.size*8)
                val stage=probe.stage("body-force",shader) { field.configure(this) }
                field.attach(stage.module)
                stage.dispatch(groups=1) {
                    bind("inputData") { input };bind("outputData") { output };bind("count") { world.size.toUInt() }
                }
                for ((label, model, observations, enabled) in listOf(
                    Case("identity",Matrix4f(),snapshot,true),
                    Case("transformed",Matrix4f().translation(2f,1f,-3f).rotateY(.4f).scale(1.7f),snapshot,true),
                    Case("lost tracking",Matrix4f(),BodySegments.from(null),true),
                    Case("disabled",Matrix4f(),snapshot,false),
                    Case("reacquired",Matrix4f(),snapshot,true),
                )) {
                    field.enabled=enabled
                    field.update(observations,model)
                    val inverse=Matrix4f(model).invert()
                    val local=world.map { inverse.transformPosition(Vector3f(it)) }
                    probe.upload(input,local.flatMap { listOf(it.x,it.y,it.z,1f) }.toFloatArray())
                    probe.settle(output)
                    val got=probe.read(output)
                    for(i in world.indices) {
                        val hit=if(enabled) observations.closest(world[i],field.gap) else null
                        val expected=hit?.force(field.pull,field.push,field.reach,field.falloff,field.maximumForce) ?: Vector3f()
                        inverse.transformDirection(expected)
                        val values=floatArrayOf(expected.x,expected.y,expected.z,hit?.distance ?: 0f,
                            hit?.normal?.x ?: 0f,hit?.normal?.y ?: 0f,hit?.normal?.z ?: 0f,hit?.segment?.toFloat() ?: -1f)
                        for(lane in values.indices)check(abs(got[i*8+lane]-values[lane])<3e-5f) {
                            "$label particle $i lane $lane expected ${values.toList()}, GPU ${got.slice(i*8 until i*8+8)}"
                        }
                    }
                    println("body-field $label: ${world.size} actual GPU samples")
                }
            }
            println("Body segment GPU field PASSED")
        } finally {
            field.close();renderer.cleanup();window.cleanup()
        }
        exitProcess(0)
    }
    private data class Case(val label: String,val model: Matrix4f,val observations: BodySegments,val enabled: Boolean)
}
