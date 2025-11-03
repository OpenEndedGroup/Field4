package afield

import kotlinx.browser.document
import kotlinx.css.*
import kotlinx.html.div
import kotlinx.html.dom.append
import kotlinx.html.js.onInputFunction
import kotlinx.html.js.onKeyUpFunction
import kotlinx.html.style
import kotlinx.html.textArea
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.url.URLSearchParams

val filename = URLSearchParams(document.location?.search).get("file") ?: "default"
val username = URLSearchParams(document.location?.search).get("user") ?: "everybody"


val body = document.body ?: error("No body")
val conversation = document.querySelector("#conversation") ?: error("No body")
lateinit var entry: HTMLElement

fun appendOperatorInput(s: String) {
    val div = body.append {
        div(classes = "operator-input-frozen") {
            style = CssBuilder().apply {
                backgroundColor = Color.black
                color = Color.white
                outline = Outline.none
                borderRight = Border(4.px, BorderStyle.solid, rgb(255, 255, 255, 0.2))
                borderTopLeftRadius = 10.px
                margin = Margin(5.px)
                padding = Padding(20.px)
                width = 500.px
                overflow = Overflow.hidden
                resize = Resize.none
                textAlign = TextAlign.right
            }.toString()
            +s
        }
    }.first()
    conversation.insertBefore(div, entry)
}

fun appendOperatorOutput(html: String = ""): HTMLElement {
    val div = body.append {
        div(classes = "operator-output") {
            style = CssBuilder().apply {
                padding = Padding(20.px)
                borderBottomRightRadius = 10.px
                maxWidth = 500.px
                borderLeft = Border(4.px, BorderStyle.solid, rgb(255, 255, 255, 0.2))
                backgroundColor = Color.black
                margin = Margin(5.px)
            }.toString()
        }
    }.first()

    div.innerHTML = html
    conversation.insertBefore(div, entry)
    return div
}

fun main() {
    console.log(" opening ?")
    val hello = Message("hello_chat").payload(Hello(filename, username))
    onOpen.add(hello)
    initializeClient()

    entry = conversation.append {
        div(classes = "operator-input") {
            textArea(rows = "1") {
                style = CssBuilder().apply {
                    backgroundColor = Color.black
                    color = Color.white
                    outline = Outline.none
                    border = Border.none
                    borderRight = Border(4.px, BorderStyle.solid, rgb(255, 255, 255, 0.2))
                    margin = Margin(5.px)
                    padding = Padding(20.px)
                    width = 500.px
                    overflow = Overflow.hidden
                    resize = Resize.none
                    display = Display.block
                    textAlign = TextAlign.right
                }.toString()

                placeholder = "operator..."

                onInputFunction = { event ->
                    val ta = event.target as HTMLTextAreaElement
                    // Reset height to auto so scrollHeight is accurate
                    ta.style.height = "auto"
                    // Then set to scrollHeight so it grows with content
                    ta.style.height = "${ta.scrollHeight - 40}px"
                }

                onKeyUpFunction = { event ->
                    val keyEvent = event as KeyboardEvent
                    if (keyEvent.key == "Enter") {
                        val text = (event.target as HTMLTextAreaElement).value
                        console.log("Enter pressed! Value: $text", event.target)
                        appendOperatorInput(text)
                        val d = appendOperatorOutput()
                        sendWithReply(
                            Message("chat_input").payload(Chat_input(value = text, phase = 0)), "input"
                        ) { message, payload ->
                            console.log("message return", message, payload)
                            val p = Json.decodeFromJsonElement<Chat_input>(message.payload!!)
                            if (p.phase == 1) {
                                d.innerHTML = p.value
                            } else if (p.phase == 20) {
                                d.innerHTML = p.value
                            }
                            Handled.yes
                        }
                        (event.target as HTMLTextAreaElement).value = ""
                        (event.target as HTMLTextAreaElement).style.height = "auto"
                        (event.target as HTMLTextAreaElement).style.height =
                            "${(event.target as HTMLTextAreaElement).scrollHeight - 40}px"
                    }
                }
            }
        }
    }.first()


}
