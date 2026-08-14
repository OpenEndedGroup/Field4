package afield.stdlib.noir.audioai

import afield.four.synth.Param
import afield.four.synth.SineOsc
import afield.four.synth.pat
import afield.userFacingThrowable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The rendered-audio analysis seam, driven end to end against [FieldAudioServiceStub].
 *
 * `RenderedAudioAnalysisTest` covers the *renderers* — that Pat and Dough write real RIFF bytes.
 * Everything after that (import, region arithmetic, fan-out, the progress socket, the cache, a leg
 * that fails, a service that is not there) had no coverage at all, which is the same shape of gap
 * that let Field's security tests be green while the guards were uninstalled: the helpers were
 * tested, the route was not. So these tests call the production entry point,
 * [analyzeRenderedAudio], through a real [FieldAudioClient] over a real socket, and assert on what
 * the *service* saw as well as on what the box got back.
 */
class RenderedAudioSeamTest {

    private fun tone(directory: Path, name: String = "tone.wav", seconds: Double = 1.0) =
        renderDoughWav(directory.resolve(name), SineOsc(Param(220f)), seconds = seconds)

    private fun temp(tag: String): Path = Files.createTempDirectory("field-audio-seam-$tag")

    // ---- the round trip ----------------------------------------------------------------------

    @Test
    fun `a full three-leg analysis round trips through the real client`() {
        FieldAudioServiceStub().use { stub ->
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("roundtrip"))
            val progress = Collections.synchronizedList(mutableListOf<String>())
            val accepted = Collections.synchronizedList(mutableListOf<String>())

            val bundle = client.analyzeRenderedAudio(
                render,
                onAccepted = { request, job -> accepted += "${request.label}=${job.state}" },
                onProgress = { request, _, event -> progress += "${request.label}=${event.state}" },
            ).get(60, TimeUnit.SECONDS)

            assertEquals(
                listOf("beats", "monophonic pitch", "polyphonic pitch"),
                bundle.analyses.map { it.label },
            )
            assertEquals(
                setOf("analysis.beats", "analysis.pitch_mono", "analysis.pitch_poly"),
                stub.submittedOperations.map { it.operation }.toSet(),
            )
            assertEquals(emptyList(), stub.protocolViolations)
            assertEquals(1, stub.importCount, "one render is one upload")

            // The window came from the service's report of the WAV, which the stub read out of the
            // header by hand — so this also cross-checks renderDoughWav's own duration claim.
            assertEquals(0.0, bundle.region.startSeconds)
            assertEquals(assertNotNull(render.durationSeconds), bundle.region.durationSeconds, 1e-9)
            assertEquals(48_000, bundle.sourceRepresentation.sampleRate)
            assertEquals(1, bundle.sourceRepresentation.channels)

            // A fresh import has no `canonical_project` representation (assets.py defaults to
            // role="original"), so the seam must fall through to the plain-WAV branch.
            assertEquals("original", bundle.sourceRepresentation.role)

            // One socket per leg, and every leg's terminal event actually arrived over it.
            assertEquals(3, stub.eventSocketsOpened)
            for (label in listOf("beats", "monophonic pitch", "polyphonic pitch")) {
                assertContains(accepted, "$label=QUEUED")
                assertContains(progress, "$label=QUEUED")
                assertContains(progress, "$label=ANALYSING")
                assertContains(progress, "$label=COMPLETE")
            }
        }
    }

    @Test
    fun `nothing blocks the caller`() {
        FieldAudioServiceStub().use { stub ->
            stub.defaultScript = FieldAudioServiceStub.ScriptedJob(stepDelayMillis = 120)
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("nonblocking"))
            val callingThread = Thread.currentThread()
            val progressThreads = Collections.synchronizedSet(mutableSetOf<String>())

            val pending = client.analyzeRenderedAudio(
                render,
                onProgress = { _, _, _ -> progressThreads += Thread.currentThread().name },
            )
            // Three legs of three 120 ms steps cannot possibly have finished by now.
            assertTrue(!pending.isDone, "analyzeRenderedAudio returned a completed future")

            pending.get(60, TimeUnit.SECONDS)
            assertTrue(progressThreads.isNotEmpty())
            assertTrue(
                callingThread.name !in progressThreads,
                "progress ran on the calling thread: $progressThreads",
            )
        }
    }

    @Test
    fun `an identical second analysis is served from the service cache`() {
        FieldAudioServiceStub().use { stub ->
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("cache"))

            val first = client.analyzeRenderedAudio(render).get(60, TimeUnit.SECONDS)
            assertTrue(first.analyses.none { it.accepted.cacheHit })

            val second = client.analyzeRenderedAudio(render).get(60, TimeUnit.SECONDS)
            assertTrue(
                second.analyses.all { it.accepted.cacheHit },
                "second pass was not a cache hit: " +
                    second.analyses.joinToString { "${it.label}=${it.accepted.cacheHit}" },
            )
            // A cache hit is a *new* job that was born complete, and it still has a result.
            assertEquals(3, second.analyses.size)
            assertTrue(first.analyses.map { it.accepted.jobId } != second.analyses.map { it.accepted.jobId })
            assertEquals(
                first.analyses.map { it.result.id },
                second.analyses.map { it.result.id },
                "the cache should return the stored analysis, not a new one",
            )
            assertEquals(6, stub.eventSocketsOpened, "a cache hit is still watched")
        }
    }

    // ---- region arithmetic at the boundaries -------------------------------------------------

    @Test
    fun `a start at or past the end of the render is refused by name`() {
        FieldAudioServiceStub().use { stub ->
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("start"), seconds = 0.5)
            val total = assertNotNull(render.durationSeconds)

            val failure = assertFails {
                client.analyzeRenderedAudio(render, startSeconds = total).get(60, TimeUnit.SECONDS)
            }
            val visible = userFacingThrowable(failure)
            assertIs<IllegalArgumentException>(visible)
            assertContains(visible.message.orEmpty(), "is outside")
            assertContains(visible.message.orEmpty(), "render")
            assertEquals(0, stub.submittedOperations.size, "nothing should have been submitted")
        }
    }

    @Test
    fun `a window that overruns the render is refused before submission`() {
        FieldAudioServiceStub().use { stub ->
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("overrun"), seconds = 0.5)

            val failure = assertFails {
                client.analyzeRenderedAudio(render, startSeconds = 0.25, durationSeconds = 0.4)
                    .get(60, TimeUnit.SECONDS)
            }
            val visible = userFacingThrowable(failure)
            assertIs<IllegalArgumentException>(visible)
            assertContains(visible.message.orEmpty(), "exceeds")
            assertEquals(0, stub.submittedOperations.size)
        }
    }

    /**
     * The `1e-6` slack in `analyzeRenderedAudio` is wider than the service's own `1e-9`, so a window
     * that just squeezes past the Kotlin guard would be an `INVALID_TIME_RANGE` if it were sent
     * verbatim. It is not sent verbatim — the region is clamped with `coerceAtMost` — and this is
     * the test that says so, at the service rather than at the caller.
     */
    @Test
    fun `a window inside the floating-point slack is clamped to exactly the render`() {
        FieldAudioServiceStub().use { stub ->
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("slack"), seconds = 0.5)
            val total = assertNotNull(render.durationSeconds)

            val bundle = client
                .analyzeRenderedAudio(render, startSeconds = 0.1, durationSeconds = total - 0.1 + 5e-7)
                .get(60, TimeUnit.SECONDS)

            assertEquals(total - 0.1, bundle.region.durationSeconds, 0.0)
            assertEquals(3, stub.submittedOperations.size)
            for (operation in stub.submittedOperations) {
                val region = operation.inputs.single()
                assertEquals(total, region.startSeconds + region.durationSeconds, 0.0)
            }
            assertEquals(emptyList(), stub.protocolViolations)
        }
    }

    @Test
    fun `an empty request list is refused`() {
        FieldAudioServiceStub().use { stub ->
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("empty"))

            val failure = assertFails {
                client.analyzeRenderedAudio(render, requests = { emptyList() }).get(60, TimeUnit.SECONDS)
            }
            assertContains(
                userFacingThrowable(failure).message.orEmpty(),
                "at least one analysis request",
            )
            assertEquals(0, stub.submittedOperations.size)
        }
    }

    // ---- failure paths ------------------------------------------------------------------------

    @Test
    fun `a failing leg surfaces the service's structured error`() {
        FieldAudioServiceStub().use { stub ->
            stub.script(
                "analysis.pitch_poly",
                FieldAudioServiceStub.ScriptedJob(
                    outcome = JobState.FAILED,
                    failure = StructuredError(
                        code = "ANALYSIS_FAILED",
                        message = "basic pitch worker died",
                        details = buildJsonObject { put("exception_type", "RuntimeError") },
                    ),
                ),
            )
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("failleg"))

            val failure = assertFails {
                client.analyzeRenderedAudio(render).get(60, TimeUnit.SECONDS)
            }
            val visible = userFacingThrowable(failure)
            assertIs<FieldAudioJobException>(visible)
            assertEquals(JobState.FAILED, visible.state)
            assertEquals("ANALYSIS_FAILED", visible.error?.code)
            assertEquals("ANALYSIS_FAILED: basic pitch worker died", visible.message)
        }
    }

    /**
     * A leg that fails must be reported at once, and must take its siblings down with it.
     *
     * The fan-out used to be a bare `CompletableFuture.allOf`, and `allOf` does not short-circuit:
     * `BiRelay.tryFire` needs both of its sources done, so it goes on waiting for every future after
     * one has failed. With one leg failed and two wedged, the future a box holds therefore never
     * completed at all — neither a result nor an error. This test first ran for 65.5 s, and the only
     * reason it looked like a socket-leak failure is that `assertFails` had happily swallowed the
     * `TimeoutException` from `get(60, SECONDS)`. Hence the elapsed-time floor and the explicit
     * exception type below: a timeout must never again read as a pass.
     *
     * The leak was real too, underneath. `analyzeRenderedAudio` discarded the
     * `CompletableFuture<WebSocket>` that `watch` returns, so nothing would ever have closed the two
     * abandoned sockets, and their `await` loops would have polled `GET /v1/jobs/{id}` every 200 ms
     * for as long as Field ran. Both are asserted at the service, the only place they are visible.
     */
    @Test
    fun `a failing leg releases its siblings' polls and sockets`() {
        FieldAudioServiceStub().use { stub ->
            stub.script(
                "analysis.beats",
                FieldAudioServiceStub.ScriptedJob(neverFinishes = true, stepDelayMillis = 5),
            )
            stub.script(
                "analysis.pitch_mono",
                FieldAudioServiceStub.ScriptedJob(neverFinishes = true, stepDelayMillis = 5),
            )
            stub.script(
                "analysis.pitch_poly",
                FieldAudioServiceStub.ScriptedJob(
                    outcome = JobState.FAILED,
                    failure = StructuredError("ANALYSIS_FAILED", "worker died"),
                    stepDelayMillis = 5,
                ),
            )
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("release"))

            val started = System.nanoTime()
            val failure = assertFails { client.analyzeRenderedAudio(render).get(60, TimeUnit.SECONDS) }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
            val visible = userFacingThrowable(failure)
            assertIs<FieldAudioJobException>(visible, "expected the failed leg, got $failure")
            assertEquals("ANALYSIS_FAILED", visible.error?.code)
            // Measured at about 60 ms with these 5 ms steps; the floor is loose enough for a busy
            // machine and tight enough that waiting on the two wedged siblings cannot pass.
            assertTrue(elapsedMillis < 5_000, "the failure took ${elapsedMillis}ms to surface")
            assertEquals(3, stub.eventSocketsOpened)

            awaitOrFail(
                5_000,
                { "openEventSockets=${stub.openEventSockets} of ${stub.eventSocketsOpened} opened" },
            ) { stub.openEventSockets == 0 }
            val abandoned = assertNotNull(stub.jobIdFor("analysis.beats"))
            Thread.sleep(1_000)
            val settled = stub.jobPolls(abandoned)
            Thread.sleep(1_500)
            assertEquals(
                settled,
                stub.jobPolls(abandoned),
                "the abandoned sibling is still being polled",
            )
        }
    }

    /**
     * The only lever a box has over an analysis whose jobs will never finish. Observed red before
     * the fix the same way: the sockets stayed open, and cancelling the returned future could not
     * reach the legs because it was `importWav(...).thenCompose { … }` and cancelling a composed
     * stage says nothing about what it was composed onto.
     */
    @Test
    fun `cancelling the returned future releases every leg`() {
        FieldAudioServiceStub().use { stub ->
            stub.defaultScript =
                FieldAudioServiceStub.ScriptedJob(neverFinishes = true, stepDelayMillis = 5)
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("cancel"))

            val pending = client.analyzeRenderedAudio(render)
            awaitOrFail(10_000) { stub.eventSocketsOpened == 3 }
            pending.cancel(false)

            awaitOrFail(5_000) { stub.openEventSockets == 0 }
            val legs = stub.submittedOperations.mapNotNull { stub.jobIdFor(it.operation) }
            assertEquals(3, legs.size)
            Thread.sleep(1_000)
            val settled = legs.map { stub.jobPolls(it) }
            Thread.sleep(1_500)
            assertEquals(settled, legs.map { stub.jobPolls(it) }, "a cancelled analysis is still polling")
        }
    }

    @Test
    fun `a completed job with no analysis result is not silently accepted`() {
        FieldAudioServiceStub().use { stub ->
            stub.defaultScript = FieldAudioServiceStub.ScriptedJob(withoutResult = true)
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("noresult"))

            val failure = assertFails { client.analyzeRenderedAudio(render).get(60, TimeUnit.SECONDS) }
            assertContains(
                userFacingThrowable(failure).message.orEmpty(),
                "completed analysis job has no result",
            )
        }
    }

    @Test
    fun `an import with no readable duration says so`() {
        FieldAudioServiceStub().use { stub ->
            stub.omitImportedDuration = true
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("noduration"))

            val failure = assertFails { client.analyzeRenderedAudio(render).get(60, TimeUnit.SECONDS) }
            assertContains(userFacingThrowable(failure).message.orEmpty(), "no duration")
        }
    }

    @Test
    fun `an import with no WAV representation says so`() {
        FieldAudioServiceStub().use { stub ->
            stub.omitImportedWavRepresentation = true
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("norepresentation"))

            val failure = assertFails { client.analyzeRenderedAudio(render).get(60, TimeUnit.SECONDS) }
            assertContains(
                userFacingThrowable(failure).message.orEmpty(),
                "has no WAV representation",
            )
        }
    }

    /**
     * What a box sees when the service is simply not running.
     *
     * This is the common case on a fresh machine — the service is a separate Python submodule with
     * its own virtual environments, and neither is installed here — so the answer has to be legible.
     * Observed red before the fix as, verbatim, `what a box would see:
     * java.net.ConnectException: java.net.ConnectException` against `CharSequence <>`: the message
     * was **empty**. Not the address, not the port, not the word audio.
     */
    @Test
    fun `an absent service is reported legibly, with the address that refused`() {
        val port = ServerSocket(0).use { it.localPort }
        val client = FieldAudioClient("http://127.0.0.1:$port")
        val render = tone(temp("absent"))

        val failure = assertFails { client.analyzeRenderedAudio(render).get(60, TimeUnit.SECONDS) }
        val visible = userFacingThrowable(failure)
        val unhelpful = "what a box would see: ${visible::class.qualifiedName}: $visible"
        assertIs<FieldAudioTransportException>(visible, unhelpful)
        assertContains(visible.message.orEmpty(), "Field Audio", false, unhelpful)
        assertContains(visible.message.orEmpty(), "127.0.0.1:$port", false, unhelpful)
        assertContains(visible.message.orEmpty(), "services/field-audio-ai", false, unhelpful)
        assertEquals("/v1/assets/import", visible.requestPath)
        // A refused loopback connection carries no text at all — not on the ConnectException and
        // not on any cause of it, measured — so the sentence has to be supplied. Observed in a live
        // Field on port 9492 as the difference between "…: ConnectException." and
        // "…: ConnectException: nothing accepted the connection."
        assertContains(visible.message.orEmpty(), "nothing accepted the connection", false, unhelpful)
    }

    @Test
    fun `a malformed service reply names the request that could not be read`() {
        FieldAudioServiceStub().use { stub ->
            stub.importResponseOverride = """{"id":"sha256:not-an-asset""""
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("malformed"))

            val failure = assertFails { client.analyzeRenderedAudio(render).get(60, TimeUnit.SECONDS) }
            val visible = userFacingThrowable(failure)
            // Before the fix this was a bare kotlinx `SerializationException` whose whole message was
            // `Expected end of the object '}', but had 'EOF' instead at path: $` — true, and useless.
            assertIs<FieldAudioTransportException>(visible, "what a box would see: $visible")
            assertContains(visible.message.orEmpty(), "Field Audio")
            assertContains(visible.message.orEmpty(), "/v1/assets/import")
            assertContains(visible.message.orEmpty(), "sha256:not-an-asset")
        }
    }

    /** The same legibility, on a later endpoint: the decode guard is in `send`, not in `importWav`. */
    @Test
    fun `a malformed analysis result names its own endpoint`() {
        FieldAudioServiceStub().use { stub ->
            stub.analysisResponseOverride = "not json at all"
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("malformedresult"))

            val failure = assertFails { client.analyzeRenderedAudio(render).get(60, TimeUnit.SECONDS) }
            val visible = userFacingThrowable(failure)
            assertIs<FieldAudioTransportException>(visible, "what a box would see: $visible")
            assertContains(visible.requestPath, "/v1/analysis-results/")
            assertContains(visible.message.orEmpty(), "not json at all")
        }
    }

    // ---- the client's own polling contract ---------------------------------------------------

    /**
     * `FieldAudioClient.await` re-armed its poll from inside `whenComplete` without ever asking
     * whether anyone was still listening, so a cancelled `await` future kept issuing
     * `GET /v1/jobs/{id}` every 200 ms for the lifetime of the JVM. Observed red before the fix as
     * `a cancelled await is still polling expected:<10> but was:<17>` — seven more polls in the
     * 1.5 s after the future had been cancelled.
     */
    @Test
    fun `a cancelled await stops polling`() {
        FieldAudioServiceStub().use { stub ->
            stub.defaultScript =
                FieldAudioServiceStub.ScriptedJob(neverFinishes = true, stepDelayMillis = 5)
            val client = FieldAudioClient(stub.baseUrl)
            val render = tone(temp("awaitcancel"))
            val asset = client.importWav(render.path).get(30, TimeUnit.SECONDS)
            val region = AssetRegion(asset.id, 0.0, assertNotNull(asset.durationSeconds))
            val accepted = client.submit(multiScaleBeatAnalysis(region)).get(30, TimeUnit.SECONDS)

            val polling = client.await(accepted.jobId)
            awaitOrFail(5_000) { stub.jobPolls(accepted.jobId) >= 3 }
            polling.cancel(false)

            Thread.sleep(1_000)
            val settled = stub.jobPolls(accepted.jobId)
            Thread.sleep(1_500)
            assertEquals(settled, stub.jobPolls(accepted.jobId), "a cancelled await is still polling")
        }
    }

    // ---- what the render says about itself ---------------------------------------------------

    /**
     * `RenderedAudioFile.existing` used to return null `sampleRate`, `channels` and
     * `durationSeconds` for a WAV whose header was sitting on disk in front of it, so a box that
     * wrapped a file another facility had rendered got a provenance line that knew nothing.
     *
     * The header is cross-checked three ways: against the renderer's own claim, and against
     * [wavHeader], which is the stub's independent hand-rolled RIFF parse rather than the production
     * one, so agreement here is evidence and not a tautology.
     */
    @Test
    fun `an existing WAV reports what its own header says`() {
        val directory = temp("existing")
        val mono = tone(directory, seconds = 0.25)
        val stereo = renderPatWav(directory.resolve("pat.wav"), pat("60 64 67"), cycles = 1, cps = 2.0)

        val wrappedMono = RenderedAudioFile.existing(mono.path, "Test")
        assertEquals(48_000, wrappedMono.sampleRate)
        assertEquals(1, wrappedMono.channels)
        assertEquals(assertNotNull(mono.durationSeconds), assertNotNull(wrappedMono.durationSeconds), 1e-9)

        val wrappedStereo = RenderedAudioFile.existing(stereo.path, "Test")
        assertEquals(2, wrappedStereo.channels)
        assertEquals(assertNotNull(stereo.sampleRate), wrappedStereo.sampleRate)
        assertEquals(assertNotNull(stereo.durationSeconds), assertNotNull(wrappedStereo.durationSeconds), 1e-9)

        for ((rendered, wrapped) in listOf(mono to wrappedMono, stereo to wrappedStereo)) {
            val independent = assertNotNull(wavHeader(Files.readAllBytes(rendered.path)))
            assertEquals(independent.sampleRate, wrapped.sampleRate)
            assertEquals(independent.channels, wrapped.channels)
            assertEquals(
                independent.frames.toDouble() / independent.sampleRate,
                assertNotNull(wrapped.durationSeconds),
                1e-12,
            )
        }
    }

    @Test
    fun `a file that is not really a WAV leaves the metadata unclaimed rather than wrong`() {
        val path = temp("garbage").resolve("garbage.wav")
        Files.write(path, ByteArray(512) { 0x7 })

        val wrapped = RenderedAudioFile.existing(path, "Test")
        assertEquals(null, wrapped.sampleRate)
        assertEquals(null, wrapped.channels)
        assertEquals(null, wrapped.durationSeconds)
    }

    /** And the wrapped file still analyses: the header read has to be on the production path. */
    @Test
    fun `an existing WAV analyses over the window its header declares`() {
        FieldAudioServiceStub().use { stub ->
            val client = FieldAudioClient(stub.baseUrl)
            val rendered = tone(temp("existinganalysis"), seconds = 0.75)
            val wrapped = RenderedAudioFile.existing(rendered.path, "Elsewhere")

            val bundle = client.analyzeRenderedAudio(wrapped).get(60, TimeUnit.SECONDS)
            assertEquals(assertNotNull(wrapped.durationSeconds), bundle.region.durationSeconds, 1e-9)
            assertEquals("Elsewhere", bundle.render.generator)
        }
    }

    /**
     * `JobMonitor.kt` says of its iframe: "Same-origin through the `/field-audio` prefix, so nothing
     * here reaches across origins." True of the default, and only of the default — the parameter will
     * build a cross-origin iframe if you hand it an absolute URL. Both halves asserted, so the claim
     * has a stated scope rather than being taken on trust.
     */
    @Test
    fun `the job monitor is same-origin by default and not by construction`() {
        val relative = fieldAudioJobMonitor()
        assertContains(relative, """src="/field-audio/monitor"""")
        assertTrue("http://" !in relative && "https://" !in relative, relative)

        val absolute = fieldAudioJobMonitor("http://127.0.0.1:8765")
        assertContains(absolute, """src="http://127.0.0.1:8765/monitor"""")
    }

    // ---- the shape of the wire ---------------------------------------------------------------

    /**
     * The stub encodes with `FieldAudioClient.json`, so on its own it could not detect a wrong
     * `SerialName`. This decodes bodies transcribed by hand from the service's own Pydantic models
     * (`services/field-audio-ai/src/field_audio/protocol/models.py`) instead, which is the one
     * comparison the stub cannot make for itself.
     */
    @Test
    fun `protocol names match the service's Pydantic models`() {
        val asset = FieldAudioClient.json.decodeFromString(
            AudioAsset.serializer(),
            """
            {"id":"sha256:${"a".repeat(64)}","byte_size":88244,
             "created_at":"2026-08-14T03:00:00+00:00",
             "representations":[{"id":"rep_1","asset_id":"sha256:${"a".repeat(64)}",
               "role":"original","media_type":"audio/wav","sample_rate":48000,"channels":1,
               "frame_count":48000,"duration_seconds":1.0,"native_sample_rate":null,
               "resampling_method":null,"binary_url":"/v1/representations/rep_1/content",
               "metadata":{"original_filename":"tone.wav"}}],
             "metadata":{}}
            """.trimIndent(),
        )
        assertEquals(88_244L, asset.byteSize)
        assertEquals(1.0, asset.durationSeconds)
        assertEquals(48_000, asset.sampleRate)
        assertEquals("/v1/representations/rep_1/content", asset.representations.single().binaryUrl)

        val accepted = FieldAudioClient.json.decodeFromString(
            JobAccepted.serializer(),
            """{"job_id":"job_1","state":"queued","request_hash":"sha256:x",
                "resolved_backend":"analysis.librosa","cache_hit":true}""",
        )
        assertEquals("job_1", accepted.jobId)
        assertEquals(JobState.QUEUED, accepted.state)
        assertTrue(accepted.cacheHit)

        val record = FieldAudioClient.json.decodeFromString(
            JobRecord.serializer(),
            """{"id":"job_1","operation_id":"op_1","request_hash":"sha256:x","state":"failed",
                "resolved_backend":"analysis.basic_pitch","progress":1.0,
                "created_at":"2026-08-14T03:00:00+00:00","updated_at":"2026-08-14T03:00:01+00:00",
                "error":{"code":"ANALYSIS_FAILED","message":"boom","retryable":false,
                         "details":{"exception_type":"RuntimeError"}},
                "candidate_set_id":null,"codec_result_id":null,
                "analysis_result_id":"analysis_1","cache_hit":false}""",
        )
        assertEquals(JobState.FAILED, record.state)
        assertEquals("analysis_1", record.analysisResultId)
        assertEquals("ANALYSIS_FAILED", record.error?.code)

        val event = FieldAudioClient.json.decodeFromString(
            ProgressEvent.serializer(),
            """{"sequence":3,"job_id":"job_1","state":"writing_assets","progress":0.86,
                "message":"writing analysis assets","candidate_index":null,
                "at":"2026-08-14T03:00:00.500000+00:00"}""",
        )
        assertEquals(3L, event.sequence)
        assertEquals(JobState.WRITING_ASSETS, event.state)

        // And the other direction: what the client sends must be the request the service accepts.
        val sent = FieldAudioClient.json.encodeToString(
            ModelOperation.serializer(),
            multiScaleBeatAnalysis(AssetRegion("sha256:" + "a".repeat(64), 0.5, 2.0)),
        )
        for (key in listOf(
            "\"operation\"", "\"inputs\"", "\"asset_id\"", "\"start_seconds\"", "\"duration_seconds\"",
            "\"parameters\"", "\"seeds\"", "\"candidate_count\"", "\"backend_preference\"",
            "\"force_regenerate\"",
        )) {
            assertContains(sent, key)
        }
        // `extra="forbid"` on the service side: an explicit null for an absent optional is an
        // unknown-shaped field there, so `explicitNulls = false` is load-bearing.
        assertTrue("null" !in sent, "the client must not send explicit nulls: $sent")
    }


    // ---- helpers ------------------------------------------------------------------------------

    private fun awaitOrFail(
        millis: Long,
        describe: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + millis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("condition was still false after $millis ms: ${describe()}")
    }
}
