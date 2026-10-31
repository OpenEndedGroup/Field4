package afield.document.workspace

import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.document.graph.metaroot
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The shared box and the persistent preferences a workspace project gives every document in it. The
 * folder side of a project lives in `dir.kt`: a directory under the workspace's `_projects/` with a
 * `contents.edn` membership list, a `preferences.json` and a `files/` folder, listed by
 * `listWorkspaceProjects`. This file is the part of it that is live in a running host. Each
 * [WorkspaceProjectScope] owns an invisible [Box] whose id is `project:` followed by a hash of the
 * project directory, and `attachRootToMetaroot` parents a document's root box beneath the scopes it
 * belongs to instead of directly beneath the global metaroot. A property set on that box is
 * therefore inherited by every box in every document of the project, which is how a kit shares
 * settings and runtime objects between its documents.
 *
 * From box code `projectScopes()` is the way in: `projectScopes().single().setPreference("theme",
 * "dark")` writes a preference that outlives the host, and [WorkspaceProjectScope.preferences]
 * reads a snapshot of them back. A project box never reaches a document's `metadata.edn`, because
 * persistence filters it and the metaroot out of the saved `parents` and `children`. A document
 * whose project is absent attaches to the metaroot instead, with nothing dangling in its saved
 * graph.
 *
 * [WorkspaceProjectScopes] keeps one scope per project directory for the life of the host, so two
 * documents in one project see the same box identity rather than two copies of it. Documents
 * created by [KitInstances.create] land in a `kit-<id>` project and ordinarily have exactly one
 * scope, but nothing stops a document from belonging to several.
 */

/** One workspace project's shared parent, persistent preferences and shared files. */
class WorkspaceProjectScope internal constructor(val directory: File) {
    private val mapper = jacksonObjectMapper()

    /** The project's name, which is its folder's name under the workspace's `_projects/`. */
    val name: String get() = directory.name

    /** The project's shared `files/` folder, created on first access, for assets its documents read. */
    val files: File get() = File(directory, "files").also { it.mkdirs() }

    /**
     * The invisible box every document of this project is parented beneath, so a property set here is
     * inherited by every box in all of them. Its id is `project:` followed by a hash of the project
     * directory's canonical path, and persistence keeps it out of a document's saved graph.
     */
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

    /**
     * Writes one preference to `preferences.json` and republishes the whole set on [root] as
     * `projectPreferences`. A null [value] removes the preference instead. A blank name is refused, and the
     * write replaces the file, so the last writer across two hosts on one workspace wins.
     */
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

    /**
     * The scope for a project of [workspace], made on first ask and the same instance afterwards, so every
     * document of the project shares one box identity. Refuses a project the workspace does not list.
     */
    fun get(workspace: File, name: String): WorkspaceProjectScope {
        require(listWorkspaceProjects(workspace.path).any { it.name == name }) { "Project does not exist: $name" }
        val directory = KitPackages.child(projectsDir(workspace.path), name)
        return scopes.computeIfAbsent(directory.canonicalPath) { WorkspaceProjectScope(directory) }
    }

    /**
     * The scopes a document belongs to, by project name, read from the membership lists of the workspace it
     * sits in. A kit instance ordinarily has one, an ordinary document none, and nothing stops several.
     */
    fun forDocument(document: File): List<WorkspaceProjectScope> =
        listWorkspaceProjects(document.parentFile.path).filter { document.name in it.documents }.sortedBy { it.name }
            .map { get(document.parentFile, it.name) }

    /**
     * Whether [box] is a live project scope's own box. Saving asks in order to drop it from a document's
     * stored `parents` and `children`, and reattaching a root asks in order to find the parents to replace.
     * Identity against the cache rather than the id alone, so a box whose id merely begins `project:` is not
     * mistaken for one.
     */
    fun isProjectRoot(box: Box): Boolean = box.id.startsWith("project:") && scopes.values.any { it.root === box }
}

/** Shared project scopes above this document's root; a kit instance ordinarily has exactly one. */
fun Box.projectScopes(): List<WorkspaceProjectScope> = if (inside.ephemeral) emptyList() else WorkspaceProjectScopes.forDocument(File(inside.filename))
