package afield.document.workspace

import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.document.graph.metaroot
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What several documents in one workspace project share: a parent box, a preferences file and a
 * folder of files.
 *
 * A project is a directory under the workspace's `_projects`, listed by [listWorkspaceProjects].
 * [WorkspaceProjectScope] gives it a [Box] that is invisible on the canvas and parented to
 * [afield.document.graph.metaroot], so a property set there is inherited by every document attached
 * to the project, exactly as any other parent's would be. The box's id comes from the project
 * directory's canonical path and [WorkspaceProjectScopes] hands out one scope per directory, so
 * reattaching a document finds the same box rather than a new one.
 *
 * [WorkspaceProjectScope.preferences] reads `preferences.json` in that directory and
 * [WorkspaceProjectScope.setPreference] writes it, which is the part that survives a restart; the
 * same map is mirrored onto the project box as `projectPreferences`, where box code reaches it
 * through inheritance. [WorkspaceProjectScope.files] is a folder for assets several documents want.
 * From a box, [projectScopes] lists the scopes above it, ordinarily one for a document made from a
 * kit package.
 *
 * A project root is canvas furniture rather than document content, so persistence drops it from the
 * parent and child sets it writes, asking [WorkspaceProjectScopes.isProjectRoot].
 */

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
