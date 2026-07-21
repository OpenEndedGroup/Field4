package afield.examples

import afield.Box
import afield.SavingContext
import afield.SessionInformation
import afield.remotejs.RemoteJSExectution
import afield.remotejs.topologyNeedsUpdating
import afield.runtime.isMainLanguage
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/*
FIELD_TEST
id: remotejs.template-runtime
runner: jvm-main
entrypoint: afield.examples.RemoteJSTemplateSmokeTest
tags: [smoke, remotejs, template, runtime]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "RemoteJSTemplateSmokeTest ok"
*/
object RemoteJSTemplateSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val repository = File(System.getProperty("user.dir"))
        val template = File(repository, "templates/remote")
        require(File(template, SavingContext.METADATA_JSON).isFile) {
            "RemoteJS template not found at ${template.absolutePath}"
        }

        val workspace = Files.createTempDirectory("afield-remotejs-template").toFile()
        val document = File(workspace, "doc").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")

        val documentRoot = Box("root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = documentRoot,
            ephemeral = true,
        )
        documentRoot.inside = session
        session.boxes[documentRoot.id] = documentRoot
        thisSessionInfo.set(session)

        try {
            val loaded = SavingContext().cloneInto(template.absolutePath)
            val languageRoot = loaded.single { it.name == "remote_js" }
            val helloWorld = loaded.single { it.name == "hello world" }

            require(languageRoot.isMainLanguage("remotejs")) {
                "Bundled RemoteJS root did not persist mainLanguage=remotejs"
            }
            require(helloWorld in languageRoot.children) {
                "Bundled hello-world box is not connected to its RemoteJS root"
            }

            topologyNeedsUpdating(session.boxes.values)
            require(helloWorld.ke is RemoteJSExectution) {
                "Javascript label and execution runtime diverged: ${helloWorld.ke::class.simpleName}"
            }

            println("RemoteJSTemplateSmokeTest ok")
        } finally {
            thisSessionInfo.remove()
            workspace.deleteRecursively()
        }
    }
}
