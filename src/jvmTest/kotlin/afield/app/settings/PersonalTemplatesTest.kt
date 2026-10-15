package afield.app.settings

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.workspace.metadata
import afield.document.workspace.startingPoints
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.output.templates.templates

import afield.app.server.documents
import afield.document.workspace.openStartingPointIntoWorkspace
import afield.document.workspace.searchTemplateStartingPoints
import afield.document.workspace.templateStartingPoints
import afield.runtime.lifecycle.map
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class PersonalTemplatesTest {
    @Test fun personalTemplatesUseExistingListingSearchAndIndependentCopy() {
        val root = Files.createTempDirectory("personal-templates-").toFile()
        val properties = listOf("afield.templates.dir", "afield.personalTemplates.dir", "afield.startingPoints.dir", "user.dir")
        val old = properties.associateWith { System.getProperty(it) }
        fun document(parent: File, name: String, text: String): File = File(parent, name).apply {
            mkdirs()
            File(this, "metadata.edn").writeText("""{:version 2 :boxes [{:id "b" :properties {:name "$text"}}]}""")
            File(this, "files").mkdirs()
            File(this, "files/pinned.txt").writeText(text)
        }
        try {
            val bundled = File(root,"bundled").apply { mkdirs() }
            val personal = File(root,"personal").apply { mkdirs() }
            val workspace = File(root,"workspace").apply { mkdirs() }
            val local = File(root,"src/personal/templates").apply { mkdirs() }
            System.setProperty("user.dir",root.path)
            document(local,"checkout-starter","CheckoutPersonalNeedle")
            System.setProperty(properties[0], bundled.path)
            System.setProperty(properties[1], personal.path)
            System.setProperty(properties[2], File(root,"missing").path)
            document(bundled,"common","BundledWinner")
            document(personal,"common","HiddenCollision")
            val source = document(personal,"private-starter","UniquePersonalNeedle")
            assertEquals(setOf("common","private-starter","checkout-starter"), templateStartingPoints().map { it.filename }.toSet())
            assertEquals(listOf("private-starter"), searchTemplateStartingPoints("UniquePersonalNeedle").documents.map { it.filename })
            assertTrue(searchTemplateStartingPoints("HiddenCollision").documents.isEmpty())
            assertEquals(listOf("checkout-starter"),searchTemplateStartingPoints("CheckoutPersonalNeedle").documents.map{it.filename})
            assertEquals("template", searchTemplateStartingPoints("UniquePersonalNeedle").documents.single().kind)
            val copy = openStartingPointIntoWorkspace(workspace.path,"private-starter")
            assertEquals("private-starter",copy.filename)
            val copiedAsset = File(workspace,"${copy.filename}/files/pinned.txt")
            assertEquals("UniquePersonalNeedle",copiedAsset.readText())
            copiedAsset.writeText("edited")
            assertEquals("UniquePersonalNeedle",File(source,"files/pinned.txt").readText())
            assertEquals("private-starter-2",openStartingPointIntoWorkspace(workspace.path,"private-starter").filename)
            openStartingPointIntoWorkspace(workspace.path,"common")
            assertEquals("BundledWinner",File(workspace,"common/files/pinned.txt").readText())
            assertFailsWith<IllegalArgumentException> { openStartingPointIntoWorkspace(workspace.path,"../private-starter") }
            System.setProperty(properties[0],File(root,"no-bundled").path)
            assertTrue(templateStartingPoints().any { it.filename=="private-starter" })
            assertTrue(searchTemplateStartingPoints("UniquePersonalNeedle").documents.isNotEmpty())
        } finally {
            for ((key,value) in old) if(value==null) System.clearProperty(key) else System.setProperty(key,value)
            root.deleteRecursively()
        }
    }
}
