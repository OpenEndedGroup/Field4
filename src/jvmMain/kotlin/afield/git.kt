package afield

import com.github.syari.kgit.KGit
import org.eclipse.jgit.errors.ObjectWritingException
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.internal.storage.file.LockFile
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RefWriter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File
import java.io.IOException


/**
 * we're doing a things here
 * 1. we're exposing the field workspace dir as a conventional webserver.
 * 2. individual boxes are now git repos
 * 3. we can clone from one server to another via http
 * 4. then the /dir can hand out autoclone urls to things when it isn't localhost
 */

val cached_git = mutableMapOf<String, KGit>()
fun ensureRepository(dir: String) = cached_git.computeIfAbsent(dir) {
    val rdir = File(dir)
    val git = try {
        // Try opening an existing repo
        KGit.open(rdir)
    } catch (e: RepositoryNotFoundException) {
        // If none exists, initialize a new one
        KGit.init {
            setDirectory(rdir)
            setBare(false)
            setInitialBranch("main")
        }
    }
    git
}

fun commit(dir: String, messageText: String?): RevCommit? {
    val r = ensureRepository(dir)
    val allFiles = File(dir).listFiles { _, name -> !name.startsWith("backups") && !name.startsWith(".git")}
    if (allFiles != null) {
        r.add {
            allFiles.map { it.name }.forEach {
                addFilepattern(it)
            }
        }
        return r.commit { message = messageText ?: "autosave" }
    }
    return null
}


@Throws(IOException::class, ObjectWritingException::class)
private fun writeFile(p: File?, bin: ByteArray?) {
    val lck = LockFile(p)
    if (!lck.lock()) throw ObjectWritingException("Can't write " + p)
    try {
        lck.write(bin)
    } catch (ioe: IOException) {
        throw ObjectWritingException("Can't write " + p, ioe)
    }
    if (!lck.commit()) throw ObjectWritingException("Can't write " + p)
}

/**
 * call this before serving up the .git dir over http, rewriting the url to point directly at the .git dir rather than the parent
 */
fun updateServerInfo(path : String) : String {
    // I assume that's a dir, not a .git dir
    val db = KGit.open(File(path)).repository
    if (db is FileRepository) {
        val fr = db
        val rw: RefWriter = object : RefWriter(fr.getRefDatabase().getRefs()) {
            @Throws(IOException::class)
            override fun writeFile(name: String, bin: ByteArray?) {
                val path = File(fr.getDirectory(), name)
                writeFile(path, bin)
            }
        }
        rw.writePackedRefs()
        rw.writeInfoRefs()

        val w = StringBuilder()
        for (p in fr.objectDatabase.packs) {
            w.append("P ")
            w.append(p.getPackFile().getName())
            w.append('\n')
        }
        writeFile(
            File(
                File(
                    fr.objectDatabase.directory,
                    "info"
                ), "packs"
            ), Constants.encodeASCII(w.toString())
        )
    }

    return path+"/.git/"
}
