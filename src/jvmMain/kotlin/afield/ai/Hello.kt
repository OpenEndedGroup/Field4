package afield.ai

import com.aallam.openai.api.chat.*
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds

class Hello {

    val config = OpenAIConfig(
        token = "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",
        timeout = Timeout(60.seconds)
    )

    fun main() = runBlocking {
        println(" -- here we go --")
        val openAI = OpenAI(config)

        println("\n> Create Chat Completion function call...")
        val modelId = ModelId("gpt-4o-mini")
        val chatMessages = mutableListOf(chatMessage {
            role = ChatRole.User
            content = "What's the weather like in San Francisco, Tokyo, and Paris?"
        })
        val request = chatCompletionRequest {
            model = modelId
            messages = chatMessages
            tools {
                function(
                    name = "currentWeather",
                    description = "Get the current weather in a given location",
                ) {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("location") {
                            put("type", "string")
                            put("description", "The city e.g. San Francisco")
                        }
                        putJsonObject("unit") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add("celsius")
                                add("fahrenheit")
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("location")
                    }
                }
            }
            toolChoice = ToolChoice.Auto // or ToolChoice.function("currentWeather")
        }

        val response = openAI.chatCompletion(request)
        val message = response.choices.first().message
        chatMessages.append(message)
        for (toolCall in message.toolCalls.orEmpty()) {
            require(toolCall is ToolCall.Function) { "Tool call is not a function" }
            val functionResponse = toolCall.execute()
            chatMessages.append(toolCall, functionResponse)
        }
        val secondResponse = openAI.chatCompletion(
            request = ChatCompletionRequest(model = modelId, messages = chatMessages)
        )
        print(secondResponse.choices.first().message.content.orEmpty())
    }

    /**
     * A map that associates function names with their corresponding functions.
     */
    private val availableFunctions = mapOf("currentWeather" to ::callCurrentWeather)

    /**
     * Example of a fake function for retrieving weather information based on location and temperature unit.
     * In a production scenario, this function could be replaced with an actual backend or external API call.
     */
    private fun callCurrentWeather(args: JsonObject): String {
        println("tool is being called ${args}")
        return when (val location = args.getValue("location").jsonPrimitive.content) {
            "San Francisco" -> """"{"location": "San Francisco", "temperature": "72", "unit": "fahrenheit"}"""
            "Tokyo" -> """{"location": "Tokyo", "temperature": "10", "unit": "celsius"}"""
            "Paris" -> """{"location": "Paris", "temperature": "999", "unit": "celsius"}"""
            else -> """{"location": "$location", "temperature": "unknown", "unit": "unknown"}"""
        }
    }

    /**
     * Executes a function call and returns its result.
     */
    private fun ToolCall.Function.execute(): String {
        val functionToCall = availableFunctions[function.name] ?: error("Function ${function.name} not found")
        val functionArgs = function.argumentsAsJson()
        return functionToCall(functionArgs)
    }

    /**
     * Appends a chat message to a list of chat messages.
     */
    private fun MutableList<ChatMessage>.append(message: ChatMessage) {
        add(
            ChatMessage(
                role = message.role,
                content = message.content.orEmpty(),
                toolCalls = message.toolCalls,
                toolCallId = message.toolCallId,
            )
        )
    }

    /**
     * Appends a function call and response to a list of chat messages.
     */
    private fun MutableList<ChatMessage>.append(toolCall: ToolCall.Function, functionResponse: String) {
        val message = ChatMessage(
            role = ChatRole.Tool,
            toolCallId = toolCall.id,
            name = toolCall.function.name,
            content = functionResponse
        )
        add(message)
    }
}