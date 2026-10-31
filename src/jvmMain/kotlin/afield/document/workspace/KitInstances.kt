package afield.document.workspace

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A document copied from a kit's starting point, the record of what it received, and the three-way
 * comparison that updates it. [KitInstances.create] copies a starting point into the workspace,
 * puts the new document in a `kit-<id>` workspace project, and writes `.field-kit-instance.json`:
 * the kit id, the package and runtime revisions, the starting point, the project, and a hash of
 * every file the copy began from. Those hashes are the baseline; the document's files are its own
 * from then on.
 *
 * [KitInstances.inspect] reads three hashes for each path (the baseline, the document's file now,
 * and the same path in the installed starting point) and labels the difference `update`,
 * `edited-here`, `remove` or `conflict`, a conflict being both sides changed since the baseline.
 * [KitInstances.migrate] then applies that plan or none of it. It refuses outright while any path
 * conflicts, copies every file it is about to write or delete into `backups/kit-<uuid>` first,
 * restores from those copies if a write fails partway, and rewrites the record last, so a failed
 * update leaves the document on the revision it already had.
 *
 * A document reads shared resources from the package revision it recorded rather than the newest
 * installed one ([KitInstances.sharedResources]), which is also why `.field-kit-instance.json` has
 * to travel with the document's other files: without it a host has no kit to compare the document
 * against, and it opens as an ordinary workspace document. The equivalent for a role kit inside a
 * single document is [Kits].
 */

/** The seed revision and file baselines this document actually received. */
@Serializable
data class KitInstanceRecord(
    /** The kit id this document came from, which must still be installed for an update to be offered. */
    val kit: String,
    /**
     * The package revision the copy was taken from. Shared resources and the starting point are read back
     * through this revision, so a newer installed one does not change an existing document.
     */
    val revision: String,
    /** The runtime revision the copy was taken against; [KitRuntime.activate] refuses any other. */
    val runtimeRevision: String,
    /** Which of the kit's starting points was copied, by its key in [KitPackageManifest.startingPoints]. */
    val startingPoint: String,
    /** The `kit-<id>` workspace project this document was added to. */
    val project: String,
    /**
     * Path to content hash for every file the copy began from, which is the baseline arm of the three-way
     * comparison in [KitInstances.inspect]. [KitInstances.migrate] rewrites it on a successful update.
     */
    val files: Map<String, String>
)

/** One path and what an update would do to it: `update`, `edited-here`, `remove` or `conflict`. */
@Serializable
data class KitInstanceChange(
    /** The document-relative path, which is also its path inside the starting point. */
    val path: String,
    /**
     * `update` for a file only the kit changed, `edited-here` for one only the document changed, `remove`
     * for one absent from the installed starting point, and `conflict` for one both changed since the
     * baseline.
     */
    val state: String
)

/** An update preview: the revision on offer, every path that differs, and the conflicts that block it. */
@Serializable
data class KitInstanceInspection(
    /** The document folder's name. */
    val document: String,
    /** The record as it stands, so a caller can compare [KitInstanceRecord.revision] with [availableRevision]. */
    val record: KitInstanceRecord,
    /** The installed revision an update would move to, and empty when the kit is not installed at all. */
    val availableRevision: String,
    /**
     * Every path that differs, sorted, and empty both when the document already matches the installed
     * starting point and when there was nothing to compare it against.
     */
    val changes: List<KitInstanceChange>,
    /**
     * What blocks an update, which [KitInstances.migrate] refuses while this is non-empty. Ordinarily these
     * are the `conflict` paths from [changes]; when the kit is not installed, or the recorded starting point
     * is gone from the installed revision, it is instead a single sentence saying so.
     */
    val conflicts: List<String>
)

/** Starting-point copies and all-or-nothing three-way file migration. Runtime snapshots remain immutable. */
object KitInstances {
    /**
     * The filename of the record, in the document folder beside `metadata.edn`. A document carrying one is a
     * kit instance everywhere Field asks, so it has to travel with the document's other files.
     */
    const val RECORD = ".field-kit-instance.json"

    /**
     * The filename of the marker [create] leaves in a fresh copy. The document-ready hook deletes it on the
     * first open, and decides what to launch from [RECORD] rather than from this.
     */
    const val FIRST_OPEN = ".field-kit-first-open"
    private val mapper = jacksonObjectMapper()
    private val skip = setOf(RECORD, FIRST_OPEN, KitFirstOpen.MARKER, "backups", "backup", ".git", "versions.edn", ".DS_Store", KitPackages.MANIFEST)

    /**
     * The kit record in [document], or null for an ordinary workspace document. A record whose kit id or
     * revision is not of the right shape throws rather than reading as absent.
     */
    fun record(document: File): KitInstanceRecord? = File(document, RECORD).takeIf { it.isFile }
        ?.let { mapper.readValue(it, KitInstanceRecord::class.java) }?.also {
            require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(it.kit) && Regex("[a-f0-9]{64}").matches(it.revision)) { "Invalid kit instance identity" }
        }

    /**
     * Relative path to content hash for every file under [seed] that a copy would carry, sorted by path and
     * leaving out backups, version history, the kit markers and the manifest itself. This is what a baseline
     * is made of and what a fresh starting point is compared against.
     */
    fun files(seed: File): Map<String, String> = seed.walkTopDown().onEnter { it == seed || it.name !in skip }
        .filter { it.isFile && it.name !in skip }.associate { file ->
            val relative = file.relativeTo(seed).invariantSeparatorsPath
            KitPackages.child(seed, relative)
            relative to KitPackages.hash(file)
        }.toSortedMap()

    /**
     * Copies one of an installed kit's starting points into [workspace] as a new document called [name],
     * falling back to the starting point's own key when that is blank. Creates the `kit-<id>` project and
     * seeds its preferences the first time the kit is used, then writes the record and the first-open
     * marker. Refuses a kit this host has not loaded, and a dedicated-host kit, whose documents are made in
     * its own host; a failure part way deletes the half-made document.
     */
    @Synchronized
    fun create(workspace: File, id: String, startingPoint: String, name: String): DuplicateDocumentResponse {
        val kit = KitPackages.installed().singleOrNull { it.id == id } ?: error("Kit is not installed: $id")
        require(KitPackages.active()[id] == kit.revision) { "Restart Field to load this kit revision before creating a document" }
        val manifest = KitPackages.read(File(kit.directory))
        require(manifest.host == null) { "Create this kit’s documents in its dedicated host" }
        val point = manifest.startingPoints[startingPoint] ?: error("Unknown starting point: $startingPoint")
        val seed = KitPackages.child(File(kit.directory), point.path)
        val project = "kit-${manifest.id}"
        if (listWorkspaceProjects(workspace.path).none { it.name == project }) {
            createWorkspaceProject(workspace.path, project)
            val preferences = File(projectsDir(workspace.path), "$project/preferences.json")
            KitPackages.writeJson(preferences, manifest.preferences)
        }
        val result = copyKitStartingPoint(workspace.path, name.ifBlank { startingPoint }, seed)
        val document = File(workspace, result.filename)
        try {
            File(document, KitPackages.MANIFEST).delete()
            KitPackages.writeJson(File(document, RECORD), KitInstanceRecord(id, kit.revision, kit.runtimeRevision, startingPoint, project, files(seed)))
            File(document, FIRST_OPEN).writeText("")
            addDocumentToWorkspaceProject(workspace.path, project, result.filename)
        } catch (error: Exception) { document.deleteRecursively(); throw error }
        return result
    }

    /**
     * What an update to the installed revision would do, without doing any of it. Compares three hashes per
     * path and labels each difference, reporting a path both sides changed as a conflict. Throws for a
     * document with no record; a kit that is not installed, or a recorded starting point the installed
     * revision does not have, comes back as a blocked inspection rather than an error.
     */
    fun inspect(document: File): KitInstanceInspection {
        val record = record(document) ?: error("This document has no kit package record")
        val kit = KitPackages.installed().singleOrNull { it.id == record.kit }
            ?: return KitInstanceInspection(document.name, record, "", emptyList(), listOf("Kit ${record.kit} is not installed"))
        val manifest = KitPackages.read(File(kit.directory))
        val point = manifest.startingPoints[record.startingPoint]
            ?: return KitInstanceInspection(document.name, record, kit.revision, emptyList(), listOf("This starting point is absent from the installed revision"))
        val next = files(KitPackages.child(File(kit.directory), point.path))
        val current = (record.files.keys + next.keys).associateWith { path ->
            KitPackages.child(document, path).takeIf { it.isFile }?.let(KitPackages::hash)
        }
        val changes = (record.files.keys + next.keys).sorted().mapNotNull { path ->
            val before = record.files[path]; val ours = current[path]; val theirs = next[path]
            when {
                ours == theirs -> null
                ours != before && theirs != before -> KitInstanceChange(path, "conflict")
                ours != before -> KitInstanceChange(path, "edited-here")
                theirs == null -> KitInstanceChange(path, "remove")
                else -> KitInstanceChange(path, "update")
            }
        }
        return KitInstanceInspection(document.name, record, kit.revision, changes, changes.filter { it.state == "conflict" }.map { it.path })
    }

    /** Caller must close/unload a live document first. Validate the whole plan before writing any file. */
    @Synchronized
    fun migrate(document: File): KitInstanceInspection {
        val plan = inspect(document)
        require(plan.conflicts.isEmpty()) { "Local edits conflict with this update: ${plan.conflicts.joinToString()}" }
        val kit = KitPackages.installed().single { it.id == plan.record.kit }
        require(KitPackages.active()[kit.id] == kit.revision) { "Restart Field before updating to this runtime revision" }
        val manifest = KitPackages.read(File(kit.directory))
        val seed = KitPackages.child(File(kit.directory), manifest.startingPoints.getValue(plan.record.startingPoint).path)
        val baseline = files(seed)
        // A complete backup allows recovery even when a filesystem write fails midway.
        val backup = File(document, "backups/kit-${java.util.UUID.randomUUID()}")
        backup.mkdirs()
        val writing = plan.changes.filter { it.state in setOf("update", "remove") }
        writing.forEach { change ->
            val file = KitPackages.child(document, change.path)
            if (file.isFile) { val copy = File(backup, change.path); copy.parentFile.mkdirs(); file.copyTo(copy) }
        }
        File(document, RECORD).copyTo(File(backup, RECORD))
        try {
            writing.forEach { change ->
                val file = KitPackages.child(document, change.path)
                if (change.state == "remove") require(file.delete()) { "Could not remove ${change.path}" }
                else { file.parentFile.mkdirs(); KitPackages.child(seed, change.path).copyTo(file, overwrite = true) }
            }
            KitPackages.writeJson(File(document, RECORD), plan.record.copy(revision = kit.revision, runtimeRevision = kit.runtimeRevision, files = baseline))
        } catch (error: Exception) {
            writing.forEach { change ->
                val previous = File(backup, change.path); val file = File(document, change.path)
                if (previous.isFile) previous.copyTo(file, overwrite = true) else file.delete()
            }
            File(backup, RECORD).copyTo(File(document, RECORD), overwrite = true)
            throw error
        }
        return inspect(document)
    }

    /**
     * The kit's shared resources folder as this document sees it, which is the one inside the package
     * revision the record names rather than the newest installed one. Null for a document with no record and
     * for a kit that declares no [KitPackageManifest.sharedResources].
     */
    fun sharedResources(document: File): File? {
        val record = record(document) ?: return null
        val snapshot = File(KitPackages.directory, "packages/${record.kit}/${record.revision}")
        val manifest = KitPackages.read(snapshot)
        return manifest.sharedResources.takeIf { it.isNotBlank() }?.let { KitPackages.child(snapshot, it) }
    }
}
