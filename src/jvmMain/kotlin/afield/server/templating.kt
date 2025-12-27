package afield.server

import afield.Directory
import afield.Handled
import afield.Message
import afield.RunsAt
import afield.SavingContext
import afield.app
import afield.handlers
import afield.home
import afield.installHandlers
import afield.payload
import afield.thisSessionInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

@RunsAt(installHandlers)
fun templating() {
    handlers["request-templates"] = mutableSetOf({ message, reply ->

        val exec = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val templates2 = SavingContext().listTemplates(app + "/templates/")
        val templates = SavingContext().listTemplates(home)
        reply(Message("list").payload(Directory(command = "list", value = templates + templates2)))

        Handled.yes
    })

    handlers["insert-template"] = mutableSetOf({ message, reply ->

        val exec = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val templates = SavingContext().listTemplates(home)
        val templates2 = SavingContext().listTemplates(app + "/templates/")

        val si = thisSessionInfo.get()

        exec.value.flatMap {
            if (templates.contains(it)) SavingContext().cloneInto("$home/$it")
            else if (templates2.contains(it)) SavingContext().cloneInto("$app/templates/$it")
            else emptyList()
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
            Handled.yes
        } else {
            try {
                val templatesDir = "$home/templates"
                File(templatesDir).mkdirs()
                SavingContext().saveAsTemplate(request.templateName, boxes, templatesDir)
                notify("Template saved: ${request.templateName}", "success")
                Handled.yes
            } catch (e: Exception) {
                notify("Failed to save template: ${e.message}", "error")
                Handled.yes
            }
        }
    })
}

