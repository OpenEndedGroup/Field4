import afield.Set_output
import afield.Set_string
import js.import.import
import js.import.importAsync
import kotlinx.browser.document
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.NodeList
import org.w3c.dom.asList
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import web.workers.WorkerType.Companion.module

external fun disclosureInterface(container: Element): dynamic
external fun aiChatUpdate(container: Element, uid: String, markdown: String)

fun insertHTMLWithScripts(container: Element, htmlString: String, isModule : Boolean = false) {
    // Create a document fragment from the HTML string
    val range = document.createRange()
    val fragment = range.createContextualFragment(htmlString)

    // Get all <script> elements in the fragment
    val scripts: NodeList = fragment.querySelectorAll("script")

    // Loop through each <script> and replace it with a new one to trigger execution
    for (i in 0 until scripts.length) {
        val oldScript = scripts.item(i) as? Element ?: continue
        val newScript = document.createElement("script")

        if (isModule) newScript.setAttribute("type", "module")
        // Copy over all attributes (e.g., src, type, etc.)
        val attributes = oldScript.attributes
        for (j in 0 until attributes.length) {
            val attr = attributes.item(j)
            if (attr != null) {
                newScript.setAttribute(attr.nodeName, attr.nodeValue ?: "")
            }
        }

        // Copy the script content
        newScript.textContent = oldScript.textContent

        // Replace the old script with the new one in the fragment
        oldScript.parentNode?.replaceChild(newScript, oldScript)
    }

    // Insert the fragment into the container at the desired location
    container.appendChild(fragment)
}

fun handleOutputAreaRequest(box: HTMLElement, set: Set_output) {

    val outputArea = box.getElementsByClassName("outputArea").asList()[0] as HTMLElement
    outputArea.style.visibility = "visible"

    val contain = outputArea.querySelector(".disclosure-group")
    val inter = disclosureInterface(contain!!)

    val channelId = set.channel
    val title = if (set.channel == "ai-chat") "AI Chat" else set.channel
    val contentRoot = (inter.upsertDisclosure as (String, String, String) -> Element).invoke(
        channelId,
        title,
        ""
    )
    if (set.channel == "ai-chat") {
        contentRoot.classList.add("ai-chat-content")
        aiChatUpdate(contentRoot, set.uid, set.value)
        (inter.open as (String) -> Unit).invoke(channelId)
    } else {
        insertHTMLWithScripts(contentRoot, set.value)
    }


}

fun handleCustomizationRequest(box: HTMLElement, code: Set_string) {
    console.log(" evaluating function trap")
    val f = eval(
        """
        new Function("customize", `${code.value}`)
    """.trimIndent()
    ).unsafeCast<dynamic>()
    console.log(" calling function trap", f)
    f.call(box)
    console.log(" completed ")
}

@OptIn(DelicateCoroutinesApi::class)
fun handleEvalRequest(box: HTMLElement, code: String) {
    console.log(" evaluating function trap", code)
    insertHTMLWithScripts(box, "<script type='module'>$code</script>", isModule = true)
}
