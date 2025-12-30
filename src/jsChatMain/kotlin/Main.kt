package afield

import kotlinx.browser.document
import kotlinx.css.*
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.w3c.dom.*
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.url.URLSearchParams
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get
import react.dom.onPaste

val filename = URLSearchParams(document.location?.search).get("file") ?: "default"
val username = URLSearchParams(document.location?.search).get("user") ?: "everybody"


val body = document.body ?: error("No body")
val conversation = document.querySelector("#conversation") ?: error("No body")
lateinit var entry: HTMLElement

// Store attached images as base64 strings
val attachedImages = mutableListOf<String>()

fun appendOperatorInput(s: String, images: List<String> = emptyList()) {
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

    // Add images if any
    if (images.isNotEmpty()) {
        val imagesDiv = body.append {
            div(classes = "operator-images") {
                style = CssBuilder().apply {
                    backgroundColor = Color.black
                    borderRight = Border(4.px, BorderStyle.solid, rgb(255, 255, 255, 0.2))
                    margin = Margin(5.px)
                    padding = Padding(10.px)
                    width = 500.px
                    textAlign = TextAlign.right
                }.toString()
            }
        }.first()

        images.forEach { imageData ->
            val img = document.createElement("img") as HTMLImageElement
            img.src = imageData
            img.style.apply {
                maxWidth = "100%"
                maxHeight = "200px"
                marginTop = "5px"
                borderRadius = "5px"
            }
            imagesDiv.appendChild(img)
        }
        conversation.insertBefore(imagesDiv, entry)
    }

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
            // Image preview area
            div {
                id = "image-preview-area"
                style = CssBuilder().apply {
                    padding = Padding(10.px)
                    display = Display.flex
                    flexWrap = FlexWrap.wrap
                }.toString()
            }

            // File input for image upload (hidden)
            input(type = InputType.file) {
                id = "image-upload-input"
                accept = "image/*"
                multiple = true
                style = CssBuilder().apply {
                    display = Display.none
                }.toString()

                onChangeFunction = { event ->
                    val input = event.target as HTMLInputElement
                    val files = input.files
                    if (files != null) {
                        for (i in 0 until files.length) {
                            val file = files[i]
                            if (file != null && file.type.startsWith("image/")) {
                                readImageFile(file)
                            }
                        }
                    }
                }
            }

            // Upload button
            button {
                +"📎"
                style = CssBuilder().apply {
                    backgroundColor = Color.transparent
                    color = Color.white
                    border = Border.none
                    fontSize = 20.px
                    cursor = Cursor.pointer
                    padding = Padding(5.px, 10.px)
                }.toString()

                onClickFunction = {
                    (document.getElementById("image-upload-input") as HTMLInputElement).click()
                }
            }

            textArea(rows = "1") {
                id = "chat-input"
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

                // Handle paste events for images
                onPaste = { event ->
                    val clipboardEvent = event as ClipboardEvent
                    val items = clipboardEvent.clipboardData?.items
                    if (items != null) {
                        for (i in 0 until items.length) {
                            val item = items[i]
                            if (item != null && item.type.startsWith("image/")) {
                                event.preventDefault()
                                val file = item.getAsFile()
                                if (file != null) {
                                    readImageFile(file)
                                }
                            }
                        }
                    }
                }

                onKeyUpFunction = { event ->
                    val keyEvent = event as KeyboardEvent
                    if (keyEvent.key == "Enter") {
                        val text = (event.target as HTMLTextAreaElement).value
                        console.log("Enter pressed! Value: $text", event.target)
                        sendChatMessage(text)
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

fun readImageFile(file: File) {
    val reader = FileReader()
    reader.onload = { event ->
        val result = (event.target.unsafeCast<FileReader>()).result as String
        attachedImages.add(result)
        displayImagePreview(result)
    }
    reader.readAsDataURL(file)
}

fun displayImagePreview(imageData: String) {
    val previewArea = document.getElementById("image-preview-area") as? HTMLElement ?: return

    val container = document.createElement("div") as HTMLDivElement
    container.style.apply {
        position = "relative"
        display = "inline-block"
    }

    val img = document.createElement("img") as HTMLImageElement
    img.src = imageData
    img.style.apply {
        maxWidth = "100px"
        maxHeight = "100px"
        borderRadius = "5px"
        border = "2px solid rgba(255, 255, 255, 0.2)"
    }

    // Add remove button
    val removeBtn = document.createElement("button") as HTMLButtonElement
    removeBtn.innerHTML = "×"
    removeBtn.style.apply {
        position = "absolute"
        top = "-5px"
        right = "-5px"
        backgroundColor = "red"
        color = "white"
        border = "none"
        borderRadius = "50%"
        width = "20px"
        height = "20px"
        cursor = "pointer"
        fontSize = "14px"
        lineHeight = "14px"
    }

    removeBtn.onclick = {
        attachedImages.remove(imageData)
        previewArea.removeChild(container)
        null
    }

    container.appendChild(img)
    container.appendChild(removeBtn)
    previewArea.appendChild(container)
}

fun sendChatMessage(text: String) {
    val images = attachedImages.toList()
    appendOperatorInput(text, images)
    val d = appendOperatorOutput()

    sendWithReply(
        Message("chat_input").payload(Chat_input(value = text, phase = 0, images = images)), "input"
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

    // Clear attached images
    attachedImages.clear()
    val previewArea = document.getElementById("image-preview-area") as? HTMLElement
    previewArea?.innerHTML = ""
}
