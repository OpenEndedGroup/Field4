@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * Kits: one machine, many documents. A kit is what every document of one kind receives: the code of the boxes that play its roles
 * (providers), the sources those boxes hold in other properties (a rule set, a recipe), and the
 * asset files they read from the document's `files/` folder. It is a directory with a `kit.json`
 * beside those files; `tools/field_kit.py` makes one from a document, rehashes it, installs it in
 * a document, migrates a document to a newer revision and publishes it as a Welcome starting point.
 * `public/docs/kits.md` is the user's page.
 *
 * ```json
 * {
 *   "schema": "field.kit.v1", "name": "rooms", "roleProperty": "kitRole",
 *   "launchOrder": ["rules", "plan"],
 *   "roles": {"rules": {"name": "Rules", "properties": {
 *       "code":       {"file": "roles/rules/code.kt",       "sha256": "…"},
 *       "roomRules":  {"file": "roles/rules/roomRules.json", "sha256": "…"},
 *       "rulesAudit": {"file": "roles/rules/roomRules.json", "sha256": "…", "frozen": true}}}},
 *   "assets": {"palette.json": "…"},
 *   "revision": "…"
 * }
 * ```
 *
 * A box plays a role when its [KitManifest.roleProperty] holds the role's key. Each property is
 * installed from `file` in the kit, and `sha256` is the hash of that file's bytes, which is also
 * the hash of the property's text ([SourceStudy.hash]). An asset `palette.json` comes from the
 * kit's `files/palette.json` and lands in the document's `files/palette.json`. The revision is the
 * hash of everything else, so two kits with the same revision install the same bytes.
 *
 * **The document owns a copy.** A document on a kit records the manifest it was last installed from in `files/kit.json` (its
 * *record*), with the kit's directory. Nothing changes a document's copy behind its back: a newer
 * kit is only a difference [inspect] reports, until a migration installs it. [inspect] compares
 * three hashes for every property and asset: the record's, the document's, and the kit's.
 * The document matching the kit is current; matching the record (and not the kit) means the kit
 * changed it, which a migration installs; matching neither means the document's own edit, which
 * a migration refuses to overwrite. A *frozen* property is a copy the document must not edit,
 * such as the baseline an audit checks candidates against; a migration replaces it whenever it
 * differs from the kit, and says so.
 */
object Kits {
    /** The record's path inside a document folder: `files/kit.json`, readable from a box as `"kit.json".file`. */
    const val RECORD = "files/kit.json"

    /** The role property a kit uses unless its manifest names another. Field persists it. */
    const val DEFAULT_ROLE_PROPERTY = "kitRole"

    private val mapper = jacksonObjectMapper()

    /** The record of the document in [documentDir], or null when the document is not on a kit. */
    fun record(documentDir: File): KitManifest? = File(documentDir, RECORD).takeIf { it.isFile }?.let(::read)

    /** Read a manifest: a kit's `kit.json`, a kit directory, or a document's record. */
    fun read(file: File): KitManifest {
        val manifest = if (file.isDirectory) File(file, "kit.json") else file
        require(manifest.isFile) { "No kit.json at ${file.path}" }
        val node = try { mapper.readTree(manifest) } catch (e: Exception) {
            throw IllegalArgumentException("${manifest.path} is not valid JSON: ${e.message}")
        }
        return KitManifest.from(node, if (file.isDirectory) file.absoluteFile else null)
    }

    /**
     * Have Field save every property [kit] installs, as sidecar files named by the kit file's
     * extension. A property Field does not know is neither autosaved nor written; inspecting a
     * document or opening one that holds a record calls this.
     */
    fun declare(kit: KitManifest) {
        Box.ioPersistentProperties.add(kit.roleProperty)
        kit.roles.values.flatMap { it.properties.entries }.forEach { (property, entry) ->
            if (property == "code" || Box.isNeverPersisted(property)) return@forEach
            Box.ioPersistentProperties.add(property)
            val extension = entry.file.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "txt"
            Box.ioSeparateProperties.putIfAbsent(property, extension)
        }
    }

    /** [declare] the record of the document in [documentDir], if it has one. Never throws. */
    internal fun declareRecorded(documentDir: File) {
        runCatching { record(documentDir)?.let(::declare) }
    }

    /**
     * What the document in [documentDir], holding [boxes], runs and how it differs from its kit.
     * [kit] is the kit to compare against (a directory or its `kit.json`); null means the one the
     * record names. A document with no record compares against [kit] alone, as before installing it.
     */
    fun inspect(boxes: Collection<Box>, documentDir: File, kit: File? = null): KitInspection {
        val recorded = record(documentDir)
        val kitDirectory = kit?.let { if (it.name == "kit.json" && it.isFile) it.parentFile else it }?.absoluteFile
            ?: recorded?.directory?.let(::File)
        val current = kitDirectory?.takeIf { File(it, "kit.json").isFile }?.let(::read)
        val problems = mutableListOf<String>()
        if (recorded == null && current == null) {
            return KitInspection(null, null, null, kitDirectory?.path, false, emptyList(),
                listOf(if (kitDirectory != null) "No kit.json in ${kitDirectory.path}" else "This document is not on a kit"))
        }
        if (kitDirectory != null && current == null) problems += "The kit is not at ${kitDirectory.path}; " +
            "compared with the record only. Pass the kit's directory to compare with it."
        if (recorded != null && current != null && recorded.name != current.name) {
            problems += "The document is on kit '${recorded.name}', not '${current.name}'"
        }
        val manifest = current ?: recorded!!
        declare(manifest)
        recorded?.let(::declare)
        val roleProperty = manifest.roleProperty
        val live = boxes.filter { it.getPropertyRaw("_tombstone") != true }
        val byRole = live.mapNotNull { box -> (box.getPropertyRaw(roleProperty) as? String)?.let { it to box } }.groupBy({ it.first }, { it.second })
        val items = mutableListOf<KitItem>()
        val roles = (recorded?.roles?.keys.orEmpty() + current?.roles?.keys.orEmpty()).distinct()
        for (role in roles) {
            val claimed = byRole[role].orEmpty()
            if (claimed.size > 1) problems += "${claimed.size} boxes play role '$role' (" + claimed.joinToString { it.name.ifBlank { it.id } } +
                "); clear $roleProperty on all but one"
            val box = claimed.singleOrNull()
            val inRecord = recorded?.roles?.get(role)
            val inKit = current?.roles?.get(role)
            for (property in (inRecord?.properties?.keys.orEmpty() + inKit?.properties?.keys.orEmpty()).distinct()) {
                val text = box?.getPropertyRaw(property) as? String
                val frozen = (inKit ?: inRecord)?.properties?.get(property)?.frozen == true
                items += KitItem.compare(role, property, null, box?.id, box?.name?.ifBlank { null } ?: (inKit ?: inRecord)?.name,
                    inRecord?.properties?.get(property)?.sha256, text?.let { SourceStudy.hash(it) },
                    inKit?.properties?.get(property)?.sha256, frozen, kitKnown = current != null, inKit = inKit != null)
            }
        }
        val files = File(documentDir, "files")
        for (asset in (recorded?.assets?.keys.orEmpty() + current?.assets?.keys.orEmpty()).distinct()) {
            val file = File(files, asset)
            items += KitItem.compare(null, null, asset, null, null, recorded?.assets?.get(asset),
                file.takeIf { it.isFile }?.let(::fileHash),
                current?.assets?.get(asset), frozen = false, kitKnown = current != null, inKit = current?.assets?.containsKey(asset) == true)
        }
        return KitInspection(manifest.name, recorded?.revision, current?.revision, kitDirectory?.path, current != null, items, problems,
            roleProperty, current?.launchOrder ?: recorded!!.launchOrder)
    }

    /** SHA-256 of a file's bytes, the hash a kit records for it. */
    fun fileHash(file: File): String = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}

/** One property a kit installs on a role's box: the kit [file] it comes from, that file's hash, and whether it is a [frozen] copy. */
data class KitProperty(val file: String, val sha256: String, val frozen: Boolean = false)

/** A role: the box [name] a new document gives it, and the [properties] the kit installs on that box. */
data class KitRole(val name: String, val properties: Map<String, KitProperty>)

/** A parsed `kit.json` or document record. [directory] is where the kit lives, when known. */
data class KitManifest(val name: String, val roleProperty: String, val launchOrder: List<String>,
    val roles: Map<String, KitRole>, val assets: Map<String, String>, val revision: String, val directory: String?) {
    internal companion object {
        fun from(node: JsonNode, directory: File?): KitManifest {
            fun text(n: JsonNode, key: String, where: String): String = n.path(key).takeIf { it.isTextual }?.asText()
                ?: throw IllegalArgumentException("$where needs \"$key\"")
            val schema = node.path("schema").asText("")
            require(schema == "field.kit.v1") { "Not a kit manifest (schema '$schema', expected field.kit.v1)" }
            val roles = node.path("roles").properties().asSequence().associate { (key, role) ->
                key to KitRole(role.path("name").asText(key), role.path("properties").properties().asSequence().associate { (property, entry) ->
                    property to KitProperty(text(entry, "file", "roles.$key.$property"), text(entry, "sha256", "roles.$key.$property"),
                        entry.path("frozen").asBoolean(false))
                })
            }
            val order = node.path("launchOrder").map { it.asText() }
            order.firstOrNull { it !in roles }?.let { throw IllegalArgumentException("launchOrder names '$it', which is not a role") }
            return KitManifest(text(node, "name", "kit.json"), node.path("roleProperty").asText(Kits.DEFAULT_ROLE_PROPERTY),
                order, roles, node.path("assets").properties().asSequence().associate { it.key to it.value.asText() },
                text(node, "revision", "kit.json"), directory?.path ?: node.path("directory").takeIf { it.isTextual }?.asText())
        }
    }
}

/** How a document's copy of one kit property or asset stands against its record and its kit. */
enum class KitItemState {
    /** The document holds what the kit holds. */
    CURRENT,
    /** The kit changed it since the record; the document still holds the recorded copy. A migration installs the kit's. */
    KIT_CHANGED,
    /** The document holds neither the recorded copy nor the kit's: its own edit. A migration refuses to overwrite it. */
    DOCUMENT_EDITED,
    /** A frozen copy that differs from the kit's. A migration replaces it, whatever it holds. */
    FROZEN_STALE,
    /** In the record but no longer in the kit. A migration leaves the document's copy alone and stops tracking it. */
    REMOVED_FROM_KIT,
}

/**
 * One property ([role] and [property]) or [asset], with the three hashes: [recorded], [document]
 * and [kit] (null when absent, or when the kit is not known).
 */
data class KitItem(val role: String?, val property: String?, val asset: String?, val box: String?, val boxName: String?,
    val recorded: String?, val document: String?, val kit: String?, val frozen: Boolean, val state: KitItemState) {
    /** `rules · roomRules`, or the asset's path. */
    val label: String get() = asset ?: "$role · $property"

    /** How a person names it: `Rules · roomRules` (the box's name), or `files/palette.txt`. */
    val display: String get() = asset?.let { "files/$it" } ?: "${boxName ?: role} · $property"

    internal companion object {
        fun compare(role: String?, property: String?, asset: String?, box: String?, boxName: String?, recorded: String?,
            document: String?, kit: String?, frozen: Boolean, kitKnown: Boolean, inKit: Boolean): KitItem {
            // Without the kit, the record is the only reference; a removed role or asset is the kit's decision.
            val reference = if (kitKnown) kit else recorded
            val state = when {
                kitKnown && !inKit -> KitItemState.REMOVED_FROM_KIT
                document == reference -> KitItemState.CURRENT
                frozen -> KitItemState.FROZEN_STALE
                kitKnown && document == recorded -> KitItemState.KIT_CHANGED
                else -> KitItemState.DOCUMENT_EDITED
            }
            return KitItem(role, property, asset, box, boxName, recorded, document, if (kitKnown) kit else null, frozen, state)
        }
    }
}

/**
 * What [Kits.inspect] found: the [kit]'s name, the [recordedRevision] the document runs, the
 * [kitRevision] its kit is at now (null when the kit was not found), every [items] entry, and
 * [problems] that stop a migration whatever the items say.
 */
data class KitInspection(val kit: String?, val recordedRevision: String?, val kitRevision: String?, val kitDirectory: String?,
    val kitFound: Boolean, val items: List<KitItem>, val problems: List<String>,
    val roleProperty: String = Kits.DEFAULT_ROLE_PROPERTY, val launchOrder: List<String> = emptyList()) {
    /** Items in [state]. */
    fun inState(state: KitItemState): List<KitItem> = items.filter { it.state == state }

    /** Whether a migration to the kit would go ahead: something to do, and no document edit or problem in the way. */
    val migrationRefusals: List<String> get() = problems + inState(KitItemState.DOCUMENT_EDITED).map { item ->
        val held = if (item.kit == null || item.kit == item.recorded) "the record and the kit hold ${short(item.recorded)}"
            else "the record holds ${short(item.recorded)} and the kit ${short(item.kit)}"
        if (item.document == null) "${item.display} was removed in this document ($held)"
        else "${item.display} was edited in this document (it holds ${short(item.document)}; $held)"
    }

    /** One paragraph, for the agent and the card. */
    fun summary(): String {
        if (kit == null) return problems.joinToString(". ").ifBlank { "This document is not on a kit" } + "."
        val runs = recordedRevision?.let { "This document runs kit '$kit' at revision ${short(it)}" } ?: "This document is not on kit '$kit' yet"
        val at = when {
            !kitFound -> ""
            kitRevision == recordedRevision -> "; the kit is at the same revision"
            else -> "; the kit is at revision ${short(kitRevision)}"
        }
        val groups = KitItemState.entries.filter { it != KitItemState.CURRENT }.mapNotNull { state ->
            inState(state).takeIf { it.isNotEmpty() }?.let { found -> "${STATE_WORDS.getValue(state)}: " + found.joinToString { it.display } + "." }
        }
        val differs = if (groups.isEmpty()) ". Nothing differs from the kit." else ". " + groups.joinToString(" ")
        return runs + at + differs + problems.joinToString("") { " $it." }
    }

    internal companion object {
        fun short(hash: String?): String = hash?.take(8) ?: "absent"
        val STATE_WORDS = mapOf(
            KitItemState.KIT_CHANGED to "Changed in the kit, which a migration installs",
            KitItemState.DOCUMENT_EDITED to "Edited in this document, which a migration refuses to overwrite",
            KitItemState.FROZEN_STALE to "Frozen copies that differ from the kit, which a migration replaces",
            KitItemState.REMOVED_FROM_KIT to "No longer in the kit, which a migration leaves alone",
        )
    }
}

/**
 * Show which kit this document runs, and every property and asset that differs from it, in a box
 * output ([channel], by default `kit`). [kit] is a kit directory to compare against; by default,
 * the one the document's record names.
 */
fun Box.showKit(kit: File? = null, channel: String = "kit"): KitInspection {
    val inspection = Kits.inspect(inside.boxes.values, File(inside.filename), kit)
    BoxStudies.show(this, channel, kitCardHtml(inspection))
    return inspection
}

internal fun kitCardHtml(inspection: KitInspection): String {
    val prefix = "kit-card"
    fun row(item: KitItem): String {
        val (word, colour) = when (item.state) {
            KitItemState.CURRENT -> "current" to "#999"
            KitItemState.KIT_CHANGED -> "changed in the kit" to "#55d4f2"
            KitItemState.DOCUMENT_EDITED -> if (item.document == null) "removed here" to "#ff6969" else "edited here" to "#ff6969"
            KitItemState.FROZEN_STALE -> "frozen copy out of date" to "#f5b748"
            KitItemState.REMOVED_FROM_KIT -> "no longer in the kit" to "#999"
        }
        return "<tr><td>${kitEscape(item.display)}</td><td style=\"color:$colour\">$word</td></tr>"
    }
    val title = inspection.kit?.let { "Kit $it" } ?: "No kit"
    val revision = inspection.recordedRevision?.let { " · revision ${KitInspection.short(it)}" }.orEmpty()
    val rows = inspection.items.sortedWith(compareBy({ it.asset != null }, { inspection.launchOrder.indexOf(it.role) }, { it.label }))
        .joinToString("", transform = ::row)
    return """
<style>
.$prefix{background:#000;color:#ddd;padding:16px;border-radius:12px;font:14px/1.45 Iosevka,monospace;max-width:640px;box-sizing:border-box}
.$prefix .kit-title{display:flex;gap:12px;user-select:none}.$prefix .kit-title span{color:#999}
.$prefix p{margin:8px 0 0;user-select:text;white-space:pre-wrap;overflow-wrap:anywhere}
.$prefix table{border-collapse:collapse;margin-top:12px;width:100%}.$prefix td{padding:3px 12px 3px 0;vertical-align:top}.$prefix td+td{text-align:right;padding-right:0}
</style><div class="$prefix" data-kit="${kitEscape(inspection.kit.orEmpty())}" data-afield-intrinsic-width="640">
<div class="kit-title">$title<span>${kitEscape(revision)}</span></div><p>${kitEscape(inspection.summary())}</p>
<table>$rows</table></div>
""".trimIndent()
}

private fun kitEscape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
