package afield.four.synth

import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin

/*
FIELD_TEST
id: examples.ableton-set.smoke
runner: jvm-main
entrypoint: afield.four.synth.AbletonSetSmokeTest
tags: [smoke, ableton, xml, audio]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "AbletonSet smoke tests passed"
*/
object AbletonSetSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isNotEmpty()) {
            printSummary(args.first(), args.getOrNull(1))
            return
        }

        check(runTests()) { "AbletonSet smoke tests failed" }
    }

    fun runTests(): Boolean {
        return try {
            testClipEnumerationAndPlaybackLookup()
            testAutomationEnvelopeLookupAndEvaluation()
            testGzippedAbletonFile()
            testSampleFileMapper()
            testTrackVolumeAnalysis()
            testPitchAnalysisFromMidiSidecar()
            testDebugFLines()
            println("AbletonSet smoke tests passed")
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    private fun testClipEnumerationAndPlaybackLookup() {
        val xml = Files.createTempFile("ableton-set", ".xml")
        Files.writeString(xml, fixtureXml())

        val set = AbletonSet.read(xml.toFile())
        require(set.tempoMap.defaultBpm == 120.0) { "Expected 120 BPM, got ${set.tempoMap.defaultBpm}" }
        require(set.tracks.size == 1) { "Expected one track, got ${set.tracks.size}" }

        val track = set.tracks.single()
        require(track.name == "Woven") { "Expected track name Woven, got ${track.name}" }
        require(track.clips.size == 2) { "Expected two clips, got ${track.clips.size}" }

        val atStart = set.clipsPlayingAtBeat(0.0).single()
        require(atStart.clip.name == "Scene 1")
        require(close(atStart.sourceSeconds, 0.0)) {
            "Expected source start 0.0s, got ${atStart.sourceSeconds}"
        }

        val scene2 = set.clipsPlayingAtSeconds(272.25).single()
        require(scene2.clip.name == "Scene 2")
        require(close(scene2.arrangementBeat, 544.5)) {
            "Expected beat 544.5, got ${scene2.arrangementBeat}"
        }
        require(close(scene2.sourceSeconds, 272.25)) {
            "Expected source 272.25s, got ${scene2.sourceSeconds}"
        }

        require(set.clipsPlayingAtBeat(543.9).isEmpty()) {
            "Expected gap before Scene 2"
        }
    }

    private fun testAutomationEnvelopeLookupAndEvaluation() {
        val xml = Files.createTempFile("ableton-set-automation", ".xml")
        Files.writeString(xml, fixtureXml())

        val set = AbletonSet.read(xml.toFile())
        val automation = set.tracks.single().automation.single()
        require(automation.target.name == "Chorus2.Rate") {
            "Expected Chorus2.Rate target, got ${automation.target.name}"
        }
        require(automation.target.manual == AbletonAutomationValue.FloatValue(0.9))
        require(automation.target.range == 0.1..15.0)
        require(automation.valueAt(-10.0) == AbletonAutomationValue.FloatValue(0.9))
        require(automation.valueAt(10.0) == AbletonAutomationValue.FloatValue(10.0))
        val middle = automation.valueAt(5.0).asDoubleOrNull()
        require(middle != null && close(middle, 5.45)) {
            "Expected interpolated automation value 5.45, got $middle"
        }
    }

    private fun testGzippedAbletonFile() {
        val als = Files.createTempFile("ableton-set", ".als")
        GZIPOutputStream(Files.newOutputStream(als)).bufferedWriter().use {
            it.write(fixtureXml())
        }

        val set = AbletonSet.read(als.toFile())
        require(set.tracks.single().clips.size == 2) {
            "Expected two clips from gzipped Ableton file, got ${set.tracks.single().clips.size}"
        }
        val scene2 = set.clipsPlayingAtSeconds(272.25).single()
        require(scene2.clip.name == "Scene 2")
    }

    private fun testSampleFileMapper() {
        AbletonSampleFileMapper.clearCache()
        val xml = Files.createTempFile("ableton-set-samples", ".xml")
        Files.writeString(xml, fixtureXml())
        val set = AbletonSet.read(xml.toFile())

        val searchRoot = Files.createTempDirectory("ableton-samples").toFile()
        val sample = searchRoot.resolve("Samples/Imported/Woven.wav")
        sample.parentFile.mkdirs()
        sample.writeText("not actually audio")

        val mapper = AbletonSampleFileMapper.forDirectory(searchRoot)
        val resolved = mapper.resolve(set.clips.first())
        require(resolved == sample.absoluteFile.normalize()) {
            "Expected mapper to resolve Woven.wav to $sample, got $resolved"
        }
        val playback = set.clipsPlayingAtBeat(0.0, mapper).single()
        require(playback.sourceFile == sample.absoluteFile.normalize()) {
            "Expected playback to carry resolved sample file, got ${playback.sourceFile}"
        }
        require(playback.sourcePath == sample.absoluteFile.normalize().absolutePath) {
            "Expected playback sourcePath to prefer resolved sample file, got ${playback.sourcePath}"
        }
        require(AbletonSampleFileMapper.forDirectory(searchRoot) === mapper) {
            "Expected mapper to be cached per search directory"
        }
        require(set.resolveSamples(searchRoot).values.toSet() == setOf(sample.absoluteFile.normalize())) {
            "Expected set-level sample resolution to use cached mapper"
        }
    }

    private fun testTrackVolumeAnalysis() {
        AbletonSampleFileMapper.clearCache()
        val xml = Files.createTempFile("ableton-set-volume", ".xml")
        Files.writeString(xml, fixtureXml())
        val set = AbletonSet.read(xml.toFile())

        val searchRoot = Files.createTempDirectory("ableton-volume-samples").toFile()
        val sample = searchRoot.resolve("Samples/Imported/Woven.wav")
        sample.parentFile.mkdirs()
        writeMonoSineWav(sample, sampleRate = 8000, seconds = 2.0, frequency = 110.0)

        val mapper = AbletonSampleFileMapper.forDirectory(searchRoot)
        val cacheConfig = AbletonSampleVolumeAnalysisConfig(
            windowSeconds = 0.050,
            hopSeconds = 0.010,
            lowCutoffHz = 250.0,
            highCutoffHz = 2_000.0,
            fftSize = 1024
        )
        val cache = AbletonSampleVolumeAnalysisCache(cacheConfig)

        val volumes = set.trackVolumesAtBeat(1.0, mapper, cache)
        require(volumes.size == 1) { "Expected one sounding track, got ${volumes.size}" }
        val volume = volumes.single()
        require(volume.track.name == "Woven") { "Expected Woven volume, got ${volume.track.name}" }
        require(volume.clips.size == 1) { "Expected one clip contribution, got ${volume.clips.size}" }
        require(close(volume.clips.single().sourceSeconds, 0.5)) {
            "Expected beat 1.0 to map to source 0.5s, got ${volume.clips.single().sourceSeconds}"
        }
        require(volume.bands.low > 0.01f) { "Expected low-band energy from 110Hz sine, got ${volume.bands}" }
        require(volume.bands.low > volume.bands.high) { "Expected low band above high band, got ${volume.bands}" }

        val bySeconds = set.trackVolumesAtSeconds(0.5, mapper, cache).single()
        require(close(bySeconds.arrangementBeat, 1.0)) {
            "Expected 0.5 arrangement seconds to map to beat 1.0, got ${bySeconds.arrangementBeat}"
        }

        val sidecars = sample.parentFile.listFiles { file ->
            file.name.startsWith("${sample.name}.field4-volume-") && file.name.endsWith(".bin")
        }.orEmpty()
        require(sidecars.size == 1) { "Expected one persisted volume sidecar, got ${sidecars.toList()}" }
        require(sidecars.single().lastModified() >= sample.lastModified()) {
            "Expected sidecar mtime to be at least sample mtime"
        }

        val diskBackedCache = AbletonSampleVolumeAnalysisCache(cacheConfig)
        val diskBackedVolume = set.trackVolumesAtBeat(1.0, mapper, diskBackedCache).single()
        require(close(diskBackedVolume.bands.low.toDouble(), volume.bands.low.toDouble())) {
            "Expected disk-backed low band ${volume.bands.low}, got ${diskBackedVolume.bands.low}"
        }
    }

    private fun testPitchAnalysisFromMidiSidecar() {
        AbletonSampleFileMapper.clearCache()
        val xml = Files.createTempFile("ableton-set-pitch", ".xml")
        Files.writeString(xml, fixtureXml())
        val set = AbletonSet.read(xml.toFile())

        val searchRoot = Files.createTempDirectory("ableton-pitch-samples").toFile()
        val sample = searchRoot.resolve("Samples/Imported/Woven.wav")
        sample.parentFile.mkdirs()
        writeMonoSineWav(sample, sampleRate = 8000, seconds = 2.0, frequency = 440.0)

        val midi = sample.parentFile.resolve("${sample.name}.field4-basic-pitch-onset0p9.mid")
        writePitchMidi(midi)
        midi.setLastModified(sample.lastModified().coerceAtLeast(System.currentTimeMillis()))

        val mapper = AbletonSampleFileMapper.forDirectory(searchRoot)
        val pitchCache = AbletonSamplePitchAnalysisCache(basicPitchExecutable = null)

        val samplePitch = pitchCache.dominantPitchAtSeconds(sample, 0.5)
        require(samplePitch?.midiPitch == 67) { "Expected sample pitch 67 at 0.5s, got $samplePitch" }
        require(samplePitch.velocity == 110) { "Expected dominant velocity 110, got ${samplePitch.velocity}" }

        val clipPitch = set.dominantPitchesAtSeconds(0.5, mapper, pitchCache).single()
        require(clipPitch.track.name == "Woven") { "Expected Woven pitch, got ${clipPitch.track.name}" }
        require(close(clipPitch.sourceSeconds, 0.5)) {
            "Expected arrangement 0.5s to map to source 0.5s, got ${clipPitch.sourceSeconds}"
        }
        require(clipPitch.pitch.midiPitch == 67) { "Expected clip pitch 67, got ${clipPitch.pitch}" }

        val trackPitch = set.dominantPitchesByTrackAtSeconds(0.5, mapper, pitchCache).single()
        require(trackPitch.track.name == "Woven") { "Expected Woven track pitch, got ${trackPitch.track.name}" }
        require(trackPitch.pitch.midiPitch == 67) { "Expected track pitch 67, got ${trackPitch.pitch}" }
        require(trackPitch.clips.single().clip.name == "Scene 1") {
            "Expected Scene 1 contribution, got ${trackPitch.clips}"
        }
    }

    private fun writePitchMidi(file: java.io.File) {
        val sequence = Sequence(Sequence.PPQ, 480)
        val track = sequence.createTrack()

        val tempo = MetaMessage()
        tempo.setMessage(0x51, byteArrayOf(0x07, 0xA1.toByte(), 0x20), 3)
        track.add(MidiEvent(tempo, 0))

        addNote(track, pitch = 60, velocity = 80, startTick = 0, endTick = 720)
        addNote(track, pitch = 67, velocity = 110, startTick = 240, endTick = 960)
        MidiSystem.write(sequence, 1, file)
    }

    private fun addNote(
        track: javax.sound.midi.Track,
        pitch: Int,
        velocity: Int,
        startTick: Long,
        endTick: Long
    ) {
        val on = ShortMessage()
        on.setMessage(ShortMessage.NOTE_ON, 0, pitch, velocity)
        track.add(MidiEvent(on, startTick))

        val off = ShortMessage()
        off.setMessage(ShortMessage.NOTE_OFF, 0, pitch, 0)
        track.add(MidiEvent(off, endTick))
    }

    private fun writeMonoSineWav(file: java.io.File, sampleRate: Int, seconds: Double, frequency: Double) {
        val frames = (sampleRate * seconds).toInt()
        val dataBytes = frames * 2
        val bytes = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII))
        bytes.putInt(36 + dataBytes)
        bytes.put("WAVE".toByteArray(Charsets.US_ASCII))
        bytes.put("fmt ".toByteArray(Charsets.US_ASCII))
        bytes.putInt(16)
        bytes.putShort(1)
        bytes.putShort(1)
        bytes.putInt(sampleRate)
        bytes.putInt(sampleRate * 2)
        bytes.putShort(2)
        bytes.putShort(16)
        bytes.put("data".toByteArray(Charsets.US_ASCII))
        bytes.putInt(dataBytes)
        for (i in 0 until frames) {
            val sample = (sin(2.0 * PI * frequency * i.toDouble() / sampleRate.toDouble()) * 0.6 * Short.MAX_VALUE)
                .toInt()
                .toShort()
            bytes.putShort(sample)
        }
        Files.write(file.toPath(), bytes.array())
    }

    private fun testDebugFLines() {
        val xml = Files.createTempFile("ableton-set-debug-lines", ".xml")
        Files.writeString(xml, fixtureXml())
        val set = AbletonSet.read(xml.toFile())

        val lines = set.toDebugFLines {
            pixelsPerBeat = 1.0
            automationSamples = 12
            includeGuides = true
            markAutomationEvents = true
        }
        require(lines.isNotEmpty()) { "Expected debug FLine output" }
        require(lines.any { it.properties["ableton.kind"] == "clip" && it.properties["ableton.clip.name"] == "Scene 1" }) {
            "Expected debug FLine output to include Scene 1 clip"
        }
        require(lines.any { it.properties["ableton.kind"] == "automation" && it.properties["ableton.automation.target"] == "Chorus2.Rate" }) {
            "Expected debug FLine output to include Chorus2.Rate automation"
        }
        require(lines.any { it.properties["ableton.kind"] == "automation-event" }) {
            "Expected debug FLine output to include automation event markers"
        }
        require(lines.all { it.nodes.isNotEmpty() }) {
            "Expected every debug FLine to contain geometry"
        }
    }

    private fun printSummary(path: String, sampleSearchRoot: String? = null) {
        val set = AbletonSet.read(path)
        val sampleMapper = sampleSearchRoot?.let { AbletonSampleFileMapper.forDirectory(java.io.File(it)) }
        println("Ableton XML: ${set.source.absolutePath}")
        sampleMapper?.let {
            println("sample search: ${it.searchDirectory.absolutePath}")
        }
        println(
            "tempo: ${set.tempoMap.defaultBpm} BPM, tracks: ${set.tracks.size}, " +
                "clips: ${set.clips.size}, automation: ${set.automationEnvelopes.size}"
        )
        for (track in set.tracks) {
            println("${track.index}: ${track.name} (${track.kind}) clips=${track.clips.size} automation=${track.automation.size}")
            track.clips.take(8).forEach { clip ->
                val startSeconds = set.tempoMap.beatToSeconds(clip.currentStartBeat)
                val endSeconds = set.tempoMap.beatToSeconds(clip.currentEndBeat)
                val source = clip.sample?.relativePath ?: clip.sample?.absolutePath ?: "no sample"
                val resolved = sampleMapper?.resolve(clip)?.absolutePath
                println(
                    "  ${clip.name}: beats ${clip.currentStartBeat}..${clip.currentEndBeat} " +
                        "seconds ${"%.3f".format(startSeconds)}..${"%.3f".format(endSeconds)} " +
                        "source=$source${resolved?.let { " resolved=$it" } ?: ""}"
                )
            }
            track.automation.take(4).forEach { envelope ->
                val first = envelope.events.firstOrNull()
                val last = envelope.events.lastOrNull()
                println(
                    "  automation ${envelope.target.name} target=${envelope.target.id} " +
                        "events=${envelope.events.size} time=${first?.time}..${last?.time} " +
                        "manual=${envelope.target.manual}"
                )
            }
            if (track.clips.size > 8) println("  ...")
            if (track.automation.size > 4) println("  ... automation")
        }
        set.mainTrack?.let { track ->
            println("${track.index}: ${track.name} (${track.kind}) clips=${track.clips.size} automation=${track.automation.size}")
            track.automation.take(4).forEach { envelope ->
                val first = envelope.events.firstOrNull()
                val last = envelope.events.lastOrNull()
                println(
                    "  automation ${envelope.target.name} target=${envelope.target.id} " +
                        "events=${envelope.events.size} time=${first?.time}..${last?.time} " +
                        "manual=${envelope.target.manual}"
                )
            }
        }
    }

    private fun close(a: Double, b: Double): Boolean = abs(a - b) < 1.0e-6

    private fun fixtureXml(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <Ableton>
          <LiveSet>
            <Tracks>
              <AudioTrack Id="8">
	                <Name>
	                  <EffectiveName Value="Woven" />
	                  <UserName Value="" />
	                </Name>
	                <Color Value="25" />
	                <AutomationEnvelopes>
	                  <Envelopes>
	                    <AutomationEnvelope Id="0">
	                      <EnvelopeTarget>
	                        <PointeeId Value="900" />
	                      </EnvelopeTarget>
	                      <Automation>
	                        <Events>
	                          <FloatEvent Id="0" Time="-63072000" Value="0.9" />
	                          <FloatEvent Id="1" Time="0" Value="0.9" />
	                          <FloatEvent Id="2" Time="10" Value="10" />
	                        </Events>
	                      </Automation>
	                    </AutomationEnvelope>
	                  </Envelopes>
	                </AutomationEnvelopes>
	                <DeviceChain>
	                  <Devices>
	                    <Chorus2 Id="1">
	                      <Rate>
	                        <Manual Value="0.9" />
	                        <MidiControllerRange>
	                          <Min Value="0.1" />
	                          <Max Value="15" />
	                        </MidiControllerRange>
	                        <AutomationTarget Id="900">
	                          <LockEnvelope Value="0" />
	                        </AutomationTarget>
	                      </Rate>
	                    </Chorus2>
	                  </Devices>
	                  <MainSequencer>
                    <Sample>
                      <ArrangerAutomation>
                        <Events>
                          <AudioClip Id="17" Time="0">
                            <CurrentStart Value="0" />
                            <CurrentEnd Value="540" />
                            <Loop>
                              <LoopStart Value="0" />
                              <LoopEnd Value="270" />
                              <StartRelative Value="0" />
                              <LoopOn Value="false" />
                              <OutMarker Value="770.1977291666667" />
                            </Loop>
                            <Name Value="Scene 1" />
                            <Disabled Value="false" />
                            <SampleRef>
                              <FileRef>
                                <RelativePath Value="Samples/Imported/Woven.wav" />
                                <Path Value="/tmp/Woven.wav" />
                                <OriginalFileSize Value="222992236" />
                                <OriginalCrc Value="1539" />
                              </FileRef>
                              <DefaultDuration Value="36969491" />
                              <DefaultSampleRate Value="48000" />
                            </SampleRef>
                            <IsWarped Value="false" />
                            <WarpMarkers>
                              <WarpMarker Id="1" SecTime="0" BeatTime="0" />
                              <WarpMarker Id="2" SecTime="0.015625" BeatTime="0.03125" />
                            </WarpMarkers>
                          </AudioClip>
                          <AudioClip Id="18" Time="544">
                            <CurrentStart Value="544" />
                            <CurrentEnd Value="741.25" />
                            <Loop>
                              <LoopStart Value="272" />
                              <LoopEnd Value="370.625" />
                              <StartRelative Value="0" />
                              <LoopOn Value="false" />
                            </Loop>
                            <Name Value="Scene 2" />
                            <SampleRef>
                              <FileRef>
                                <RelativePath Value="Samples/Imported/Woven.wav" />
                              </FileRef>
                            </SampleRef>
                            <IsWarped Value="false" />
                            <WarpMarkers>
                              <WarpMarker Id="3" SecTime="272" BeatTime="0" />
                              <WarpMarker Id="4" SecTime="272.015625" BeatTime="0.03125" />
                            </WarpMarkers>
                          </AudioClip>
                        </Events>
                      </ArrangerAutomation>
                    </Sample>
                  </MainSequencer>
                </DeviceChain>
              </AudioTrack>
            </Tracks>
            <MasterTrack Id="0">
              <AutomationEnvelopes>
                <Envelopes>
                  <AutomationEnvelope Id="1">
                    <Automation>
                      <Events>
                        <FloatEvent Id="0" Time="-63072000" Value="120" />
                      </Events>
                    </Automation>
                  </AutomationEnvelope>
                </Envelopes>
              </AutomationEnvelopes>
              <DeviceChain>
                <Mixer>
                  <Tempo>
                    <Manual Value="120" />
                  </Tempo>
                </Mixer>
              </DeviceChain>
            </MasterTrack>
          </LiveSet>
        </Ableton>
        """.trimIndent()
}
