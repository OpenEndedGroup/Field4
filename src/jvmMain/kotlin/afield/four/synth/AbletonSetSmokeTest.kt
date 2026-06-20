package afield.four.synth

import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

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
