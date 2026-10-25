package afield.ai.models

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.services.blocking.MessageService
import java.lang.reflect.Proxy

/**
 * An [AnthropicClient] whose `messages().create(params)` is [create], for driving [AnthropicBridge] without a network.
 * `withOptions` hands back the same service: the bridge calls it on every request to turn the SDK's retries off.
 * Any other call fails with its method name, so a new SDK call in the bridge shows up here first.
 */
internal fun fakeAnthropicClient(create: (MessageCreateParams) -> Message): AnthropicClient {
    val loader = AnthropicBridge::class.java.classLoader
    val messages = Proxy.newProxyInstance(loader, arrayOf(MessageService::class.java)) { service, method, args ->
        when {
            method.name == "create" && args?.firstOrNull() is MessageCreateParams -> create(args[0] as MessageCreateParams)
            method.name == "withOptions" -> service
            else -> throw UnsupportedOperationException(method.name)
        }
    } as MessageService
    return Proxy.newProxyInstance(loader, arrayOf(AnthropicClient::class.java)) { _, method, _ ->
        if (method.name == "messages") messages else throw UnsupportedOperationException(method.name)
    } as AnthropicClient
}
