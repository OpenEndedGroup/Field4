package afield.four.audio

import org.lwjgl.openal.AL10.*
import org.lwjgl.openal.ALC10.alcGetCurrentContext
import org.lwjgl.openal.EXTThreadLocalContext.alcGetThreadContext
import org.lwjgl.system.MemoryUtil
import kotlin.math.*

/*
FIELD_TEST
id: audio.spatial.loopback
runner: jvm-main
entrypoint: afield.four.audio.SpatialAudioLoopbackSmoke
tags: [smoke, audio, openal, cpu]
timeoutSec: 90
expect:
  exitCode: 0
  stdoutContains:
    - "SPATIAL_AUDIO_LOOPBACK_PASSED"
*/
/** Real OpenAL rendering without speakers: context isolation, binaural rotation, Doppler and cleanup. */
object SpatialAudioLoopbackSmoke {
    @JvmStatic fun main(args:Array<String>) {
        val outer=SimpleSpatialAudioEngine(loopbackSampleRate=48000)
        check(outer.initialize())
        val outerContext=alcGetCurrentContext()
        val nested=SimpleSpatialAudioEngine(threadLocalContext=true,loopbackSampleRate=48000)
        check(nested.initialize())
        check(runCatching { nested.renderSamples(java.nio.ShortBuffer.allocate(128),64) }.exceptionOrNull() is IllegalArgumentException)
        val nestedContext=alcGetThreadContext()
        check(nestedContext!=0L && nestedContext!=outerContext)
        try {
            check(nested.hrtfEnabled) { "Bundled OpenAL did not enable HRTF: ${nested.hrtfStatus}" }
            val inner=SimpleSpatialAudioEngine(threadLocalContext=true,loopbackSampleRate=48000)
            check(inner.initialize());inner.destroy()
            check(alcGetThreadContext()==nestedContext)
            var refused=false
            val wrong=Thread { try { nested.setListener(0f,0f,0f,0f,0f,-1f,0f,1f,0f) } catch(_:IllegalStateException) { refused=true } }
            wrong.start();wrong.join();check(refused)
            val pcm=ShortArray(48000) { (sin(2*PI*1000*it/48000)*9000).toInt().toShort() }
            val buffer=nested.loadPcm16(pcm,48000,1)
            val source=nested.createSource(buffer,rolloffFactor=0f)
            val out=MemoryUtil.memAllocShort(48000)
            fun render(fz:Float,x:Float,z:Float,vz:Float):DoubleArray {
                source.stop();source.seekTo(0f)
                nested.setListener(0f,0f,0f,0f,0f,fz,0f,1f,0f)
                alListener3f(AL_VELOCITY,0f,0f,vz)
                source.setPosition(x,0f,z);source.setVelocity(0f,0f,0f);source.play()
                out.clear();nested.renderSamples(out,24000)
                var l=0.0;var r=0.0;var crosses=0
                for(i in 3000 until 23000) {
                    val a=out.get(i*2).toDouble();val b=out.get(i*2+1).toDouble()
                    l+=a*a;r+=b*b
                    if(a>0 && out.get((i-1)*2)<=0) crosses++
                }
                return doubleArrayOf(l,r,crosses*48000.0/20000)
            }
            try {
                val left=render(-1f,-8f,-2f,0f)
                val turned=render(1f,-8f,-2f,0f)
                check(left[0]>left[1]*1.08 && turned[1]>turned[0]*1.08) { "Head rotation did not exchange ears: ${left.toList()} ${turned.toList()}" }
                val rest=render(-1f,0f,-10f,0f)
                val approach=render(-1f,0f,-10f,-30f)
                check(abs(rest[2]-1000)<8 && approach[2]>rest[2]*1.07) { "Doppler missing: ${rest[2]} -> ${approach[2]}" }
                println("HRTF=${nested.hrtfEnabled}; ear energy ratios=${left[0]/left[1]}, ${turned[1]/turned[0]}; Doppler=${rest[2]} -> ${approach[2]} Hz")
            } finally { MemoryUtil.memFree(out);source.destroy();nested.destroyBuffer(buffer) }
            check(nested.sourceCount==0 && nested.bufferCount==0)
        } finally { nested.destroy() }
        check(alcGetCurrentContext()==outerContext && alcGetThreadContext()==0L)
        // The original engine can still create and use sources after nested context teardown.
        val survivor=outer.createSource();survivor.gain=.25f;check(alGetError()==AL_NO_ERROR);survivor.destroy();outer.destroy()
        println("SPATIAL_AUDIO_LOOPBACK_PASSED: isolated contexts, restored capabilities, thread ownership, HRTF rotation, Doppler, cleanup")
    }
}
