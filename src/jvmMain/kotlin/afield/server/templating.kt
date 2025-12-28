package afield.server

import afield.Directory
import afield.Handled
import afield.Message
import afield.RunsAt
import afield.SaveAsTemplate
import afield.SavingContext
import afield.app
import afield.box
import afield.handlers
import afield.home
import afield.installHandlers
import afield.notify
import afield.payload
import afield.thisSessionInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

@RunsAt(installHandlers)
fun templating() {
    handlers["request-templates"] = mutableSetOf({ message, reply ->

        val exec = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val templatesHome = SavingContext().listTemplates("$home/templates")
        val templatesApp = SavingContext().listTemplates("$app/templates")

        val templates = (templatesHome + templatesApp).distinct()
        reply(Message("list").payload(Directory(command = "list", value = templates)))

        Handled.yes
    })

    handlers["insert-template"] = mutableSetOf({ message, reply ->

        val exec = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val templatesHome = SavingContext().listTemplates("$home/templates")
        val templatesApp = SavingContext().listTemplates("$app/templates")

        val si = thisSessionInfo.get()

        exec.value.flatMap {
            when {
                templatesHome.contains(it) -> SavingContext().cloneInto(resolveTemplatePath("$home/templates", it))
                templatesApp.contains(it) -> SavingContext().cloneInto(resolveTemplatePath("$app/templates", it))
                else -> emptyList()
            }
        }.forEach {
            it.resyncAll()
        }
        si.topologyGeneration++

        Handled.yes
    })

    handlers["save-as-template"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<SaveAsTemplate>(message.payload!!)
        
        val boxes = request.uids.mapNotNull { uid -> box(uid) }.toSet()
        if (boxes.isEmpty()) {
            notify("No valid boxes found for template", "error")
        } else {
            try {
                val templatesDir = "$home/templates"
                File(templatesDir).mkdirs()
                SavingContext().saveAsTemplate(request.templateName, boxes, templatesDir)
                notify("Template saved: ${request.templateName}", "success")
            } catch (e: Exception) {
                notify("Failed to save template: ${e.message}", "error")
            }
        }
        Handled.yes
    })
}

private fun resolveTemplatePath(baseDir: String, name: String): String {
    val base = baseDir.trimEnd('/')
    val withExt = "$base/$name.edn"
    return if (File(withExt).isFile) withExt else "$base/$name"
}

