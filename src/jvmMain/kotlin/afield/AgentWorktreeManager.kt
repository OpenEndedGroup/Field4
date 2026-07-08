package afield

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolutePathString
import kotlin.io.path.name

object AgentWorktreeManager {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val idFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    fun listProposals(projectRoot: File = File(".").canonicalFile): List<AgentWorktreeProposal> {
        val metadataDir = metadataDir(projectRoot)
        if (!Files.isDirectory(metadataDir)) return emptyList()
        val files = Files.list(metadataDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".json", ignoreCase = true) }
                .toList()
        }
        return files
            .mapNotNull { path ->
                runCatching {
                    json.decodeFromString<AgentWorktreeProposal>(
                        Files.readString(path, StandardCharsets.UTF_8)
                    )
                }.getOrNull()
            }
            .sortedWith(compareByDescending<AgentWorktreeProposal> { it.createdAtEpochMs }.thenBy { it.id })
    }

    fun createProposal(projectRoot: File, taskName: String, label: String = taskName): AgentWorktreeProposal {
        val canonicalRoot = projectRoot.canonicalFile
        val root = worktreeRoot(canonicalRoot)
        Files.createDirectories(root)
        Files.createDirectories(metadataDir(canonicalRoot))

        val now = Instant.now()
        val id = "${safeToken(taskName)}-${idFormatter.format(now)}-${Integer.toHexString(now.nano)}"
        val path = root.resolve(id).toAbsolutePath().normalize()
        val baseBranch = runGit(canonicalRoot, "rev-parse", "--abbrev-ref", "HEAD").trim()
        val baseCommit = runGit(canonicalRoot, "rev-parse", "HEAD").trim()
        val branch = "agent/${safeToken(taskName)}/$id"

        runGit(canonicalRoot, "worktree", "add", "-b", branch, path.toString(), "HEAD")

        val proposal = AgentWorktreeProposal(
            id = id,
            label = label,
            path = path.absolutePathString(),
            branch = branch,
            baseBranch = baseBranch,
            baseCommit = baseCommit,
            createdAtEpochMs = now.toEpochMilli(),
            updatedAtEpochMs = now.toEpochMilli(),
            status = "created",
            taskName = taskName,
            message = "Created generated worktree ${path.name}."
        )
        writeProposal(canonicalRoot, proposal)
        return proposal
    }

    fun updateProposal(
        projectRoot: File,
        id: String,
        status: String,
        message: String = "",
        reportPath: String = "",
        dryRunPatchPath: String = ""
    ): AgentWorktreeProposal? {
        val canonicalRoot = projectRoot.canonicalFile
        val proposal = readProposal(canonicalRoot, id) ?: return null
        val updated = proposal.copy(
            status = status,
            message = message.ifBlank { proposal.message },
            reportPath = reportPath.ifBlank { proposal.reportPath },
            dryRunPatchPath = dryRunPatchPath.ifBlank { proposal.dryRunPatchPath },
            updatedAtEpochMs = Instant.now().toEpochMilli()
        )
        writeProposal(canonicalRoot, updated)
        return updated
    }

    fun deleteProposal(projectRoot: File, id: String): AgentWorktreeActionResponse {
        val canonicalRoot = projectRoot.canonicalFile
        val proposal = readProposal(canonicalRoot, id)
            ?: return AgentWorktreeActionResponse(false, message = "Unknown generated worktree '$id'.")
        val path = Paths.get(proposal.path).toAbsolutePath().normalize()

        runCatching {
            if (Files.exists(path)) {
                runGit(canonicalRoot, "worktree", "remove", "--force", path.toString())
            }
        }.onFailure {
            return AgentWorktreeActionResponse(false, proposal, "Could not remove worktree: ${it.message}")
        }
        runCatching {
            if (proposal.branch.isNotBlank()) runGit(canonicalRoot, "branch", "-D", proposal.branch)
        }
        Files.deleteIfExists(metadataPath(canonicalRoot, id))
        return AgentWorktreeActionResponse(true, proposal, "Deleted generated worktree ${proposal.id}.")
    }

    fun mergeProposal(projectRoot: File, id: String): AgentWorktreeActionResponse {
        val canonicalRoot = projectRoot.canonicalFile
        var proposal = readProposal(canonicalRoot, id)
            ?: return AgentWorktreeActionResponse(false, message = "Unknown generated worktree '$id'.")
        val dirty = runGit(canonicalRoot, "status", "--porcelain").trim()
        if (dirty.isNotBlank()) {
            return AgentWorktreeActionResponse(
                false,
                proposal,
                "Refusing to merge into a dirty checkout. Commit, stash, or clean the main worktree first."
            )
        }
        if (proposal.branch.isBlank()) {
            return AgentWorktreeActionResponse(false, proposal, "Generated worktree has no branch recorded.")
        }
        val proposalPath = Paths.get(proposal.path).toAbsolutePath().normalize().toFile()
        if (!proposalPath.isDirectory) {
            return AgentWorktreeActionResponse(false, proposal, "Generated worktree is missing: ${proposal.path}")
        }
        val finalized = finalizeProposalChanges(canonicalRoot, proposal.id)
        if (!finalized.success) return finalized
        proposal = finalized.proposal ?: proposal

        val proposalHead = runGit(proposalPath, "rev-parse", "HEAD").trim()
        val branchHead = runGit(canonicalRoot, "rev-parse", proposal.branch).trim()
        if (proposalHead != branchHead) {
            return AgentWorktreeActionResponse(
                false,
                proposal,
                "Generated worktree branch ${proposal.branch} is not at the proposal worktree HEAD."
            )
        }
        return try {
            runGit(canonicalRoot, "merge", "--ff-only", proposal.branch)
            val updated = updateProposal(
                canonicalRoot,
                id,
                "merged",
                "Merged ${proposal.branch} into ${proposal.baseBranch}."
            ) ?: proposal
            AgentWorktreeActionResponse(true, updated, updated.message)
        } catch (t: Throwable) {
            AgentWorktreeActionResponse(false, proposal, "Merge failed: ${t.message}")
        }
    }

    fun finalizeProposalChanges(projectRoot: File, id: String): AgentWorktreeActionResponse {
        val canonicalRoot = projectRoot.canonicalFile
        val proposal = readProposal(canonicalRoot, id)
            ?: return AgentWorktreeActionResponse(false, message = "Unknown generated worktree '$id'.")
        val proposalPath = Paths.get(proposal.path).toAbsolutePath().normalize().toFile()
        if (!proposalPath.isDirectory) {
            return AgentWorktreeActionResponse(false, proposal, "Generated worktree is missing: ${proposal.path}")
        }

        return try {
            // Generated proposal tasks should only commit reviewable source edits.
            // Build outputs and reports stay as artifacts referenced from metadata.
            runGit(proposalPath, "add", "src", "dev_docs", "public", "tools", "build.gradle.kts", "settings.gradle.kts")
            val staged = runGitWithExit(proposalPath, "diff", "--cached", "--quiet").exitCode != 0
            if (!staged) {
                val cleanProposal = updateProposal(
                    canonicalRoot,
                    id,
                    "ready",
                    "Generated proposal has no source changes to commit."
                ) ?: proposal
                return AgentWorktreeActionResponse(true, cleanProposal, cleanProposal.message)
            }

            runGit(
                proposalPath,
                "commit",
                "-m",
                "Apply generated ${proposal.label.ifBlank { proposal.taskName.ifBlank { "agent proposal" } }}"
            )
            val updated = updateProposal(
                canonicalRoot,
                id,
                "ready",
                "Committed generated proposal changes to ${proposal.branch}."
            ) ?: proposal
            AgentWorktreeActionResponse(true, updated, updated.message)
        } catch (t: Throwable) {
            AgentWorktreeActionResponse(false, proposal, "Could not commit generated proposal changes: ${t.message}")
        }
    }

    fun proposal(projectRoot: File, id: String): AgentWorktreeProposal? =
        readProposal(projectRoot.canonicalFile, id)

    fun worktreeRoot(projectRoot: File): Path {
        val configured = System.getProperty("afield.agentWorktreeRoot")
            ?: System.getenv("AFIELD_AGENT_WORKTREE_ROOT")
        if (!configured.isNullOrBlank()) return Paths.get(configured).toAbsolutePath().normalize()
        val parent = projectRoot.toPath().toAbsolutePath().normalize().parent
            ?: projectRoot.toPath().toAbsolutePath().normalize()
        return parent.resolve("${projectRoot.name}-agent-worktrees").toAbsolutePath().normalize()
    }

    private fun metadataDir(projectRoot: File): Path =
        worktreeRoot(projectRoot).resolve(".metadata").toAbsolutePath().normalize()

    private fun metadataPath(projectRoot: File, id: String): Path =
        metadataDir(projectRoot).resolve("${safeToken(id)}.json")

    private fun readProposal(projectRoot: File, id: String): AgentWorktreeProposal? {
        val path = metadataPath(projectRoot, id)
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            json.decodeFromString<AgentWorktreeProposal>(Files.readString(path, StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun writeProposal(projectRoot: File, proposal: AgentWorktreeProposal) {
        val path = metadataPath(projectRoot, proposal.id)
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(proposal), StandardCharsets.UTF_8)
    }

    private fun safeToken(text: String): String =
        text.trim()
            .lowercase()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "proposal" }
            .take(80)

    private fun runGit(cwd: File, vararg args: String): String {
        val result = runGitWithExit(cwd, *args)
        if (result.exitCode != 0) {
            throw IllegalStateException("git ${args.joinToString(" ")} failed with ${result.exitCode}: ${result.output.trim()}")
        }
        return result.output
    }

    private fun runGitWithExit(cwd: File, vararg args: String): GitResult {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val exit = process.waitFor()
        return GitResult(exit, output)
    }

    private data class GitResult(val exitCode: Int, val output: String)
}
