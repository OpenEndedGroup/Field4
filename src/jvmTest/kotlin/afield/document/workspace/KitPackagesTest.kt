package afield.document.workspace

import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.document.graph.attachRootToMetaroot
import afield.document.graph.detachRootFromMetaroot
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.*

class KitPackagesTest {
    private lateinit var temp: File
    private val saved = mutableMapOf<String, String?>()
    private val mapper = jacksonObjectMapper()

    @BeforeTest fun setup() {
        temp = Files.createTempDirectory("field-kit-package-").toFile()
        listOf("afield.kits.dir", "afield.kit.packages", "afield.kit.revisions").forEach { saved[it] = System.getProperty(it); System.clearProperty(it) }
        System.setProperty("afield.kits.dir", File(temp, "registry").path)
        KitLifecycleFixture.events.clear()
    }
    @AfterTest fun finish() {
        KitRuntime.shutdown()
        saved.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        temp.deleteRecursively()
    }
    private fun packageAt(name: String = "source", lifecycle: String = ""): File {
        val source = File(temp, name).apply { mkdirs() }
        File("templates/presentation").copyRecursively(File(source, "seeds/talk"))
        File(source, "extensions/kotlin/demo/Extra.kt").apply { parentFile.mkdirs(); writeText("package afield.demo\nclass Extra") }
        File(source, "shared/reference.txt").apply { parentFile.mkdirs(); writeText("shared") }
        File(source, "guide.md").writeText("# An example kit")
        KitPackages.writeJson(File(source, KitPackages.MANIFEST), KitPackageManifest(id = "example", title = "Example", version = "1",
            guide = "guide.md", runtime = KitPackageRuntime(extensionDirs = listOf("extensions"), lifecycle = lifecycle),
            startingPoints = mapOf("talk" to KitStartingPoint("Talk", "seeds/talk", launch = listOf("Teaching guide", "Presentation"))),
            sharedResources = "shared", preferences = mapOf("theme" to "dark")))
        return source
    }
    private fun active(kit: InstalledKit) {
        System.setProperty("afield.kit.revisions", "${kit.id}@${kit.revision}")
        System.setProperty("afield.kit.packages", kit.directory)
    }
    private fun workspace() = File(temp, "workspace").apply { mkdirs() }
    private fun document(workspace: File, name: String): SessionInformation {
        val root = Box("root")
        return SessionInformation(File(workspace, name).path, name, root = root).also { root.inside = it; attachRootToMetaroot(root) }
    }

    @Test fun registrationSnapshotsEveryDeclaredPartWithoutExecutingIt() {
        val source = packageAt()
        val installed = KitPackages.install(source)
        assertEquals("restart-required", KitPackages.statuses().single().state)
        assertTrue(KitLifecycleFixture.events.isEmpty())
        File(source, "extensions/kotlin/demo/Extra.kt").writeText("changed")
        assertEquals("package afield.demo\nclass Extra", File(installed.directory, "extensions/kotlin/demo/Extra.kt").readText())
        active(installed)
        assertEquals("active", KitPackages.statuses().single().state)
        val properties = Properties().apply { File(KitPackages.directory, "runtime.properties").inputStream().use { load(it) } }
        assertEquals(File(installed.directory, "extensions").canonicalPath, properties.getProperty("kit.0.extensions.0"))
        assertEquals(installed, KitPackages.installed().single())
    }

    @Test fun sourceEditsProduceNewRevisionsAndKeepOldSnapshots() {
        val source = packageAt()
        val one = KitPackages.install(source)
        File(source, "seeds/talk/present_live_field_code_.kt").appendText("\n// revised example")
        val two = KitPackages.install(source)
        assertNotEquals(one.revision, two.revision)
        assertEquals(one.runtimeRevision, two.runtimeRevision)
        File(source, "extensions/kotlin/demo/Extra.kt").appendText("\n// revised runtime")
        val three = KitPackages.install(source)
        assertNotEquals(two.runtimeRevision, three.runtimeRevision)
        assertTrue(File(one.directory).isDirectory)
    }

    @Test fun traversalAndDynamicDependenciesAreRejectedBeforeRegistration() {
        val source = packageAt()
        val manifest = KitPackages.read(source)
        assertFailsWith<IllegalArgumentException> { KitPackages.child(source, "../outside") }
        KitPackages.writeJson(File(source, KitPackages.MANIFEST), manifest.copy(runtime = manifest.runtime.copy(dependencies = listOf("a:b:1.+"))))
        assertFailsWith<IllegalArgumentException> { KitPackages.install(source) }
        KitPackages.writeJson(File(source, KitPackages.MANIFEST), manifest.copy(runtime = manifest.runtime.copy(dependencies = listOf("a:b:latest.release:classifier"))))
        assertFailsWith<IllegalArgumentException> { KitPackages.install(source) }
        assertTrue(KitPackages.installed().isEmpty())
    }

    @Test fun symlinkEscapesAreRejected() {
        val source = packageAt()
        val outside = File(temp, "private.txt").apply { writeText("outside") }
        Files.createSymbolicLink(File(source, "shared/leak.txt").toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { KitPackages.install(source) }
        assertTrue(KitPackages.installed().isEmpty())
    }

    @Test fun changingAnInstalledSnapshotCannotBeBlessedByAnotherRegistration() {
        val first = KitPackages.install(packageAt())
        File(first.directory, "extensions/kotlin/demo/Extra.kt").appendText("\n// accidental edit")
        val other = packageAt("other")
        KitPackages.writeJson(File(other, KitPackages.MANIFEST), KitPackages.read(other).copy(id = "other"))
        assertFailsWith<IllegalArgumentException> { KitPackages.install(other) }
        assertEquals(listOf(first), KitPackages.installed())
    }

    @Test fun runtimeConflictsAreRejectedWithoutReplacingTheRegistry() {
        val source = packageAt()
        val manifest = KitPackages.read(source)
        KitPackages.writeJson(File(source, KitPackages.MANIFEST), manifest.copy(runtime = manifest.runtime.copy(dependencies = listOf("example:library:1.0"))))
        val first = KitPackages.install(source)
        val other = packageAt("other")
        val second = KitPackages.read(other).copy(id = "other")
        KitPackages.writeJson(File(other, KitPackages.MANIFEST), second.copy(runtime = second.runtime.copy(dependencies = listOf("example:library:2.0"))))
        assertFailsWith<IllegalArgumentException> { KitPackages.install(other) }
        assertEquals(listOf(first), KitPackages.installed())
    }

    @Test fun createRequiresRestartThenRecordsSeedAndProjectMembership() {
        val kit = KitPackages.install(packageAt())
        val workspace = workspace()
        assertFailsWith<IllegalArgumentException> { KitInstances.create(workspace, kit.id, "talk", "lecture") }
        active(kit)
        val created = KitInstances.create(workspace, kit.id, "talk", "lecture")
        val record = assertNotNull(KitInstances.record(File(workspace, created.filename)))
        assertEquals(kit.revision, record.revision)
        assertEquals(listOf("lecture"), listWorkspaceProjects(workspace.path).single().documents)
        assertEquals(mapOf("theme" to "dark"), WorkspaceProjectScopes.get(workspace, record.project).preferences())
        assertTrue(File(workspace, "lecture/${KitInstances.FIRST_OPEN}").isFile)
        assertEquals("shared", KitInstances.sharedResources(File(workspace, "lecture"))!!.resolve("reference.txt").readText())
    }

    @Test fun updateIsThreeWayAndRefusesAllWritesWhenAnyFileConflicts() {
        val source = packageAt(); val first = KitPackages.install(source); active(first)
        val workspace = workspace(); KitInstances.create(workspace, first.id, "talk", "lecture")
        val document = File(workspace, "lecture")
        File(document, "present_live_field_code_.kt").writeText("local edit")
        File(source, "seeds/talk/present_live_field_code_.kt").writeText("upstream edit")
        File(source, "seeds/talk/files/new.txt").writeText("new asset")
        val next = KitPackages.install(source); active(next)
        val plan = KitInstances.inspect(document)
        assertEquals(listOf("present_live_field_code_.kt"), plan.conflicts)
        assertFailsWith<IllegalArgumentException> { KitInstances.migrate(document) }
        assertFalse(File(document, "files/new.txt").exists())
        assertEquals(first.revision, KitInstances.record(document)!!.revision)
    }

    @Test fun safeUpdatePreservesLocalOnlyFilesAndUnrelatedEdits() {
        val source = packageAt(); val first = KitPackages.install(source); active(first)
        val workspace = workspace(); KitInstances.create(workspace, first.id, "talk", "lecture")
        val document = File(workspace, "lecture")
        File(document, "present_live_field_code_.kt").writeText("local edit")
        File(document, "my-notes.md").writeText("my material")
        File(source, "seeds/talk/files/new.txt").writeText("new asset")
        val next = KitPackages.install(source); active(next)
        KitInstances.migrate(document)
        assertEquals("local edit", File(document, "present_live_field_code_.kt").readText())
        assertEquals("my material", File(document, "my-notes.md").readText())
        assertEquals("new asset", File(document, "files/new.txt").readText())
        assertEquals(next.revision, KitInstances.record(document)!!.revision)
        assertTrue(File(document, "backups").listFiles().orEmpty().isNotEmpty())
    }

    @Test fun aRemovedKitOrStartingPointRemainsInspectableWithoutChangingTheDocument() {
        val source = packageAt(); val first = KitPackages.install(source); active(first)
        val workspace = workspace(); KitInstances.create(workspace, first.id, "talk", "lecture")
        val document = File(workspace, "lecture")
        val before = File(document, KitInstances.RECORD).readBytes()
        val manifest = KitPackages.read(source)
        KitPackages.writeJson(File(source, KitPackages.MANIFEST), manifest.copy(startingPoints = mapOf("replacement" to manifest.startingPoints.getValue("talk"))))
        KitPackages.install(source)
        assertTrue(KitInstances.inspect(document).conflicts.single().contains("starting point"))
        KitPackages.remove(first.id)
        assertTrue(KitInstances.inspect(document).conflicts.single().contains("not installed"))
        assertContentEquals(before, File(document, KitInstances.RECORD).readBytes())
    }

    @Test fun projectParentsShareStateAndPreferencesWithoutLeakingAcrossProjects() {
        val workspace = workspace()
        for (name in listOf("a", "b", "c")) File("templates/presentation").copyRecursively(File(workspace, name))
        createWorkspaceProject(workspace.path, "one", listOf("a", "b")); createWorkspaceProject(workspace.path, "two", listOf("c"))
        val a = document(workspace, "a"); val b = document(workspace, "b"); val c = document(workspace, "c")
        try {
            val shared = WorkspaceProjectScopes.get(workspace, "one")
            assertEquals("one", a.root.projectScopes().single().name)
            assertTrue(shared.root in a.root.parents && shared.root in b.root.parents)
            assertFalse(shared.root in c.root.parents)
            shared.root.setPropertyRaw("sharedValue", 42, doResync = false)
            assertEquals(42, b.root.parents.single().getPropertyRaw("sharedValue"))
            shared.setPreference("accent", "white")
            assertEquals("white", mapper.readTree(File(shared.directory, "preferences.json")).path("accent").asText())
        } finally { listOf(a,b,c).forEach { detachRootFromMetaroot(it.root) } }
    }

    @Test fun lifecycleIsOwnedByHostAndDocumentAndCleanupIsIdempotent() {
        val kit = KitPackages.install(packageAt(lifecycle = KitLifecycleFixture::class.java.name)); active(kit)
        val workspace = workspace(); KitInstances.create(workspace, kit.id, "talk", "lecture")
        KitRuntime.start()
        val info = document(workspace, "lecture")
        try {
            KitRuntime.activate(info); KitRuntime.activate(info)
            KitRuntime.deactivate(info); KitRuntime.deactivate(info)
            KitRuntime.activate(info); KitRuntime.deactivate(info)
            KitRuntime.shutdown(); KitRuntime.shutdown()
            assertEquals(listOf("startup", "activate:lecture", "deactivate:lecture", "activate:lecture", "deactivate:lecture", "cleanup"), KitLifecycleFixture.events)
        } finally { detachRootFromMetaroot(info.root) }
    }

    @Test fun aFailedStartupCleansItsPartialResourcesAndReportsTheError() {
        val kit = KitPackages.install(packageAt(lifecycle = KitFailedStartupFixture::class.java.name)); active(kit)
        KitRuntime.start()
        assertEquals(listOf("startup-failed", "cleanup-failed"), KitLifecycleFixture.events)
        assertEquals("fixture startup failed", KitRuntime.errors[kit.id])
        assertEquals("error", KitPackages.statuses(KitRuntime.errors).single().state)
        KitRuntime.shutdown()
        assertEquals(2, KitLifecycleFixture.events.size)
    }

    @Test fun existingProjectLauncherIsRegisteredWithoutLaunchingOrRewritingItsFiles() {
        val source = packageAt()
        File(source, "start.sh").writeText("#!/bin/sh\ntouch must-not-exist\n")
        val before = File(source, "start.sh").readBytes()
        val manifest = KitPackages.read(source)
        KitPackages.writeJson(File(source, KitPackages.MANIFEST), manifest.copy(host = KitDedicatedHost("start.sh", "http://127.0.0.1:19835", "workspace")))
        val kit = KitPackages.install(source)
        assertFalse(File(source, "must-not-exist").exists())
        assertContentEquals(before, File(source, "start.sh").readBytes())
        val profile = Properties().apply { File(KitPackages.directory, "runtime.properties").inputStream().use { load(it) } }
        assertEquals("0", profile.getProperty("kit.0.extensions.count"))
        assertEquals("start.sh", KitPackages.read(File(kit.directory)).host!!.launcher)
    }
}

class KitLifecycleFixture : KitLifecycle {
    companion object { val events = mutableListOf<String>() }
    override fun startup(context: KitHostContext) { events += "startup" }
    override fun activate(context: KitDocumentContext) { events += "activate:${context.document.documentName}" }
    override fun deactivate(context: KitDocumentContext) { events += "deactivate:${context.document.documentName}" }
    override fun cleanup(context: KitHostContext) { events += "cleanup" }
}

class KitFailedStartupFixture : KitLifecycle {
    override fun startup(context: KitHostContext) {
        KitLifecycleFixture.events += "startup-failed"
        error("fixture startup failed")
    }
    override fun cleanup(context: KitHostContext) { KitLifecycleFixture.events += "cleanup-failed" }
}
