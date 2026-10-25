package afield.ai.models

import afield.document.workspace.LocalAiConnectionRequest
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalAiProviderTest {
    @Test fun monitorAddressUsesRemoteInferenceHostAndSupportsOverride() {
        assertEquals("http://gpu-host:8082/status", localAiMonitorUri("http://gpu-host:8081/v1").toString())
        assertEquals("http://[::1]:8082/status", localAiMonitorUri("http://[::1]:8081/v1").toString())
        assertEquals("https://monitor/status", localAiMonitorUri("http://gpu-host:8081/v1", "https://monitor/status").toString())
        assertFailsWith<IllegalArgumentException> { localAiMonitorUri("http://host:65535/v1") }
        assertFailsWith<IllegalArgumentException> { localAiMonitorUri("http://host/v1", "file:///tmp/private") }
    }

    @Test fun feedbackNeverContactsMonitorForHostedProvidersOrSendsModelCredentials() = isolated { dir ->
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        var response = """{"schemaVersion":1,"sampledAtMs":1000,"state":"ready","gpus":[]}"""
        server.createContext("/status") { exchange ->
            requests++
            assertNull(exchange.requestHeaders.getFirst("Authorization"))
            val body = response.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val monitor = "http://127.0.0.1:${server.address.port}/status"
            for (provider in listOf("openai", "openrouter", "anthropic")) {
                dir.resolve("ai-provider.txt").writeText("$provider\n")
                assertFalse(mapper.readTree(localAiFeedback(monitor)).path("enabled").asBoolean())
            }
            assertEquals(0, requests)
            save("http://gpu-host:8081/v1", key = "private-model-key")
            val result = mapper.readTree(localAiFeedback(monitor))
            assertTrue(result.path("available").asBoolean())
            assertEquals("ready", result.path("sample").path("state").asText())
            assertEquals(1, requests)
            response = "{}"
            assertFalse(mapper.readTree(localAiFeedback(monitor)).path("available").asBoolean())
            server.stop(0)
            assertFalse(mapper.readTree(localAiFeedback(monitor)).path("available").asBoolean())
        } finally { server.stop(0) }
    }

    private fun isolated(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("field-local-ai-").toFile()
        val prior = System.getProperty("afield.ai.configDir")
        System.setProperty("afield.ai.configDir", dir.absolutePath)
        try { block(dir) } finally {
            if (prior == null) System.clearProperty("afield.ai.configDir") else System.setProperty("afield.ai.configDir", prior)
            dir.deleteRecursively()
        }
    }

    private fun save(base: String, model: String = "qwen3.8-27b", key: String? = null) = saveAiSettings(
        AiSettingsSaveRequest("local", null, null, null, model, "", "", base, key)
    )

    @Test fun savesLocalWithoutCloudCredentialsAndUsesAgentForAllRoles() = isolated { dir ->
        val revision = aiProviderConfigRevision()
        save("http://gpu-host:8081/")
        assertEquals("local", configuredAiProvider())
        assertEquals("http://gpu-host:8081/v1", configuredAiBaseUrl())
        val runtime = activeAiProviderRuntimeConfig()
        assertTrue(runtime.usesStatelessResponses)
        assertFalse(runtime.isOpenRouter)
        assertEquals("local-no-key-required", runtime.apiKey)
        for (role in listOf(Ai4ModelDefaults.FLAGSHIP_MODEL, Ai4ModelDefaults.FAST_MODEL, Ai4ModelDefaults.FRONTIER_FALLBACK_MODEL)) {
            assertEquals("qwen3.8-27b", Ai4ModelDefaults.configuredModelForDefault(role))
        }
        assertEquals(revision + 1, aiProviderConfigRevision())
        assertFalse(dir.resolve("openai-platform-key.txt").exists())
        assertFalse(dir.resolve("openrouter-api-key.txt").exists())
    }

    @Test fun rejectsInvalidLocalSaveBeforeChangingWorkingSettings() = isolated {
        save("http://gpu-host:8081/v1")
        for (bad in listOf("", "file:///tmp/model", "https://key@host/v1", "http://host/v1?key=secret", "http://host/v1#fragment")) {
            assertFailsWith<IllegalArgumentException> { save(bad) }
            assertEquals("http://gpu-host:8081/v1", configuredAiBaseUrl())
        }
        assertFailsWith<IllegalArgumentException> { save("http://other-host:8081/v1", "") }
        assertEquals("qwen3.8-27b", configuredAiModelSettings().flagshipModel)
    }

    @Test fun retainsSeparateProviderModelsAndCredentials() = isolated { dir ->
        dir.resolve("openai-platform-key.txt").writeText("cloud-key\n")
        dir.resolve("ai-models.properties").writeText("openai.flagshipModel=cloud-model\nopenrouter.flagshipModel=router-model\n")
        save("https://my-inference-host/v1", key = "local-key")
        assertEquals("local-key", activeAiProviderRuntimeConfig().apiKey)
        assertEquals("cloud-key\n", dir.resolve("openai-platform-key.txt").readText())
        assertEquals("cloud-model", configuredAiModelSettings("openai").flagshipModel)
        assertEquals("router-model", configuredAiModelSettings("openrouter").flagshipModel)
        saveAiSettings(AiSettingsSaveRequest("openai", "cloud-key", null, null, "cloud-model", "", ""))
        assertEquals("qwen3.8-27b", configuredAiModelSettings("local").flagshipModel)
        assertEquals("https://my-inference-host/v1", configuredAiBaseUrl("local"))
    }

    @Test fun savedLocalKeyStaysWithItsEndpoint() = isolated {
        save("https://first-host/v1", key = "first-key")
        save("https://first-host/v1")
        assertEquals("first-key", readLocalAiCredential()?.first)
        save("https://second-host/v1")
        assertNull(readLocalAiCredential())
        assertEquals("local-no-key-required", activeAiProviderRuntimeConfig().apiKey)
    }

    @Test fun switchingToLocalMigratesLegacyCloudModelSettings() = isolated { dir ->
        dir.resolve("ai-provider.txt").writeText("openai\n")
        dir.resolve("ai-models.properties").writeText("flagshipModel=prior-cloud-model\nfastModel=prior-fast-model\n")
        save("http://gpu-host:8081/v1")
        assertEquals("prior-cloud-model", configuredAiModelSettings("openai").flagshipModel)
        assertEquals("prior-fast-model", configuredAiModelSettings("openai").fastModel)
        assertEquals("qwen3.8-27b", configuredAiModelSettings("local").flagshipModel)
    }

    @Test fun discoversModelsWithoutCloudKeysAndChecksSelectedModel() = isolated {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorization: String? = "not-requested"
        server.createContext("/v1/models") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"data":[{"id":"qwen3.8-27b"},{"id":"other"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val result = checkLocalAiConnection(LocalAiConnectionRequest(base, model = "qwen3.8-27b"))
            assertTrue(result.connected)
            assertTrue(result.modelAvailable)
            assertEquals(listOf("qwen3.8-27b", "other"), result.models)
            assertNull(authorization)
            assertFalse(checkLocalAiConnection(LocalAiConnectionRequest(base, model = "missing")).modelAvailable)
            checkLocalAiConnection(LocalAiConnectionRequest(base, apiKey = "endpoint-key"))
            assertEquals("Bearer endpoint-key", authorization)
            save("http://different-host/v1", key = "must-not-leak")
            checkLocalAiConnection(LocalAiConnectionRequest(base))
            assertNull(authorization)
        } finally { server.stop(0) }
    }

    @Test fun localDefaultsNeverSelectAHostedFallback() = isolated {
        assertFailsWith<IllegalStateException> {
            Ai4ModelDefaults.configuredModelForDefault(Ai4ModelDefaults.FAST_MODEL, AiModelSettings(), "local")
        }
    }

    @Test fun discoveryReportsAuthenticationAndInvalidModelList() = isolated {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var responseCode = 401
        server.createContext("/v1/models") { exchange ->
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(responseCode, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val request = LocalAiConnectionRequest("http://127.0.0.1:${server.address.port}/v1")
            assertTrue(assertFailsWith<IllegalArgumentException> { checkLocalAiConnection(request) }.message.orEmpty().contains("401"))
            responseCode = 200
            assertTrue(assertFailsWith<IllegalArgumentException> { checkLocalAiConnection(request) }.message.orEmpty().contains("model list"))
        } finally { server.stop(0) }
    }
}
