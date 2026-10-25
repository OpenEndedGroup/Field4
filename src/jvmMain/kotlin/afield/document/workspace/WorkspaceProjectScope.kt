package afield.document.workspace

import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.document.graph.metaroot
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One workspace project's shared parent, persistent preferences and shared files. */
class WorkspaceProjectScope internal constructor(val directory: File) {
    private val mapper = jacksonObjectMapper()
    val name: String get() = directory.name
    val files: File get() = File(directory, "files").also { it.mkdirs() }
    val root: Box = Box("project:" + KitPackages.hashText(directory.canonicalPath)).apply {
        name = directory.name
        visible = false
        inside = SessionInformation(directory.path, directory.name, root = this, ephemeral = true)
        parents.add(metaroot)
        metaroot.children.add(this)
        setPropertyRaw("projectPreferences", preferences(), doResync = false)
    }

    /** A snapshot; write a preference with [setPreference] so it survives a host restart. */
    @Synchronized
    fun preferences(): Map<String, Any?> = File(directory, "preferences.json").takeIf { it.isFile }?.let {
        @Suppress("UNCHECKED_CAST")
        (mapper.readValue(it, Map::class.java) as Map<String, Any?>)
    }.orEmpty()

    @Synchronized
    fun setPreference(name: String, value: Any?) {
        require(name.isNotBlank()) { "A preference needs a name" }
        val next = preferences().toMutableMap()
        if (value == null) next.remove(name) else next[name] = value
        KitPackages.writeJson(File(directory, "preferences.json"), next)
        root.setPropertyRaw("projectPreferences", next, doResync = false)
    }
}

/** Stable scope identity per workspace/project, rebuilt from project membership on document attachment. */
object WorkspaceProjectScopes {
    private val scopes = ConcurrentHashMap<String, WorkspaceProjectScope>()
    fun get(workspace: File, name: String): WorkspaceProjectScope {
        require(listWorkspaceProjects(workspace.path).any { it.name == name }) { "Project does not exist: $name" }
        val directory = KitPackages.child(projectsDir(workspace.path), name)
        return scopes.computeIfAbsent(directory.canonicalPath) { WorkspaceProjectScope(directory) }
    }

    fun forDocument(document: File): List<WorkspaceProjectScope> =
        listWorkspaceProjects(document.parentFile.path).filter { document.name in it.documents }.sortedBy { it.name }
            .map { get(document.parentFile, it.name) }

    fun isProjectRoot(box: Box): Boolean = box.id.startsWith("project:") && scopes.values.any { it.root === box }
}

/** Shared project scopes above this document's root; a kit instance ordinarily has exactly one. */
fun Box.projectScopes(): List<WorkspaceProjectScope> = if (inside.ephemeral) emptyList() else WorkspaceProjectScopes.forDocument(File(inside.filename))
