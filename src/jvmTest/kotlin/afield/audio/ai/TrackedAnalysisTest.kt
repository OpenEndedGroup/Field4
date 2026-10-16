package afield.audio.ai

import afield.audio.engine.WavFloatArrayWriter
import afield.document.graph.Box
import afield.runtime.lifecycle.RunScope
import afield.runtime.lifecycle.runAsync
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TrackedAnalysisTest {
    private fun request(client: FieldAudioClient): ModelOperation {
        val wav = Files.createTempFile("tracked-analysis", ".wav")
        try {
            Files.write(wav, WavFloatArrayWriter.encodeStereo16(FloatArray(2400), FloatArray(2400), 24000))
            val asset = client.importWav(wav).get(10, TimeUnit.SECONDS)
            return multiScaleBeatAnalysis(AssetRegion(asset.id, 0.0, 0.1))
        } finally { Files.deleteIfExists(wav) }
    }

    private fun waitUntil(test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!test() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(test())
    }

    @Test fun successAndCachedRerunUseTheRealHttpAndWebSocketPaths() {
        FieldAudioServiceStub().use { stub ->
            val client = FieldAudioClient(stub.baseUrl)
            val request = request(client)
            val box = Box("tracked-success")
            val accepted = mutableListOf<JobAccepted>()
            val completed = mutableListOf<JobRecord>()
            repeat(2) {
                val scope = box.runScopes.begin("code", "test")
                val result = RunScope.withCurrent(scope) {
                    client.trackAnalysis(request,
                        onAccepted = { assertSame(box, Box.context.get()); accepted += it },
                        onCompleted = { completed += it })
                }.get(15, TimeUnit.SECONDS)
                assertTrue(result.id.isNotBlank())
                assertEquals(0, scope.liveCount)
            }
            assertFalse(accepted.first().cacheHit)
            assertTrue(accepted.last().cacheHit)
            assertTrue(completed.all { it.state == JobState.COMPLETE })
            waitUntil { stub.openEventSockets == 0 }
            assertEquals(2, stub.eventSocketsOpened)
            assertEquals(emptyList(), stub.protocolViolations)
            box.runScopes.closeAll()
        }
    }

    @Test fun stopClosesTheWatchAndSuppressesFurtherPublication() {
        FieldAudioServiceStub().use { stub ->
            stub.defaultScript = FieldAudioServiceStub.ScriptedJob(neverFinishes = true)
            val client = FieldAudioClient(stub.baseUrl)
            val request = request(client)
            val box = Box("tracked-stop")
            val scope = box.runScopes.begin("code", "test")
            val progress = CountDownLatch(1)
            var results = 0
            val pending = RunScope.withCurrent(scope) {
                client.trackAnalysis(request, onProgress = { _, _ -> progress.countDown() }, onResult = { results++ })
            }
            assertTrue(progress.await(10, TimeUnit.SECONDS))
            assertTrue(stub.openEventSockets > 0)
            box.runScopes.stopAll()
            assertTrue(pending.isCancelled)
            waitUntil { stub.openEventSockets == 0 }
            assertEquals(0, results)
            // The service owns the durable job; leaving the watch did not pretend to cancel it.
            val job = client.inspect(stub.jobIdFor(request.operation)!!).get(5, TimeUnit.SECONDS)
            assertTrue(job.state != JobState.CANCELLED)
            box.runScopes.closeAll()
        }
    }

    @Test fun aTrackedLegStartedByAFutureContinuationStillStopsWithItsOriginatingRun() {
        FieldAudioServiceStub().use { stub ->
            stub.defaultScript = FieldAudioServiceStub.ScriptedJob(neverFinishes = true)
            val client = FieldAudioClient(stub.baseUrl)
            val request = request(client)
            val box = Box("tracked-continuation")
            val scope = box.runScopes.begin("code", "test")
            val gate = CompletableFuture<Unit>()
            val progress = CountDownLatch(1)
            val operation = RunScope.withCurrent(scope) {
                runAsync(onError = { throw AssertionError(it) }) {
                    val origin = RunScope.current()
                    gate.thenCompose {
                        RunScope.withCurrent(origin) {
                            client.trackAnalysis(request, onProgress = { _, _ ->
                                assertSame(scope, RunScope.current())
                                assertSame(box, Box.context.get())
                                progress.countDown()
                            })
                        }
                    }
                }
            }
            CompletableFuture.runAsync { gate.complete(Unit) }.get(5, TimeUnit.SECONDS)
            assertTrue(progress.await(10, TimeUnit.SECONDS))
            box.runScopes.stopAll()
            assertTrue(operation.future.isCancelled)
            waitUntil { stub.openEventSockets == 0 }
            assertNotEquals(JobState.CANCELLED,
                client.inspect(stub.jobIdFor(request.operation)!!).get(5, TimeUnit.SECONDS).state)
            box.runScopes.closeAll()
        }
    }

    @Test fun anEndedRunCannotSubmitANewLegFromALateContinuation() {
        FieldAudioServiceStub().use { stub ->
            val client = FieldAudioClient(stub.baseUrl)
            val request = request(client)
            val box = Box("tracked-late-continuation")
            val scope = box.runScopes.begin("code", "test")
            box.runScopes.stopAll()
            val pending = CompletableFuture.supplyAsync {
                RunScope.withCurrent(scope) { client.trackAnalysis(request) }
            }.get(5, TimeUnit.SECONDS)
            assertTrue(pending.isCancelled)
            assertNull(stub.jobIdFor(request.operation))
            assertEquals(0, stub.eventSocketsOpened)
            box.runScopes.closeAll()
        }
    }

    @Test fun aRejectedProgressHandshakeFailsAndReleasesTheOperation() {
        FieldAudioServiceStub().use { stub ->
            stub.rejectEventSubscriptions = true
            stub.defaultScript = FieldAudioServiceStub.ScriptedJob(neverFinishes = true)
            val client = FieldAudioClient(stub.baseUrl)
            val request = request(client)
            val failure = assertFailsWith<ExecutionException> {
                client.trackAnalysis(request, onError = {}).get(10, TimeUnit.SECONDS)
            }
            assertIs<FieldAudioTransportException>(failure.cause)
            assertEquals(0, stub.openEventSockets)
        }
    }

    @Test fun aFailedServiceJobReportsTheOriginalFailure() {
        FieldAudioServiceStub().use { stub ->
            stub.defaultScript = FieldAudioServiceStub.ScriptedJob(outcome = JobState.FAILED)
            val client = FieldAudioClient(stub.baseUrl)
            val failure = assertFailsWith<ExecutionException> {
                client.trackAnalysis(request(client), onError = {}).get(10, TimeUnit.SECONDS)
            }
            assertIs<FieldAudioJobException>(failure.cause)
            waitUntil { stub.openEventSockets == 0 }
        }
    }
}
