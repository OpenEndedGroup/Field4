import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class UpdateInfo(
    val hash: String, val description: String, val url: String
)

val defaultUpdateUrl = "http://openendedgroup.com/field4/assets/latest.json"
val defaultLocalFile = "localVersion.json"

/**
 * Fetches update information from the given [updateUrl].
 * Suspends on I/O using [Dispatchers.IO].
 */
suspend fun fetchUpdateInfo(updateUrl: String = defaultUpdateUrl): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val connection = URL(updateUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.connect()

        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(response)
            val hash = jsonObject.getString("hash")
            val description = jsonObject.getString("description")
            val url = jsonObject.getString("url")
            UpdateInfo(hash, description, url)
        } else {
            println(" response code: ${connection.responseCode}")
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}


/**
 * Fetches update information from the given [updateUrl].
 * Suspends on I/O using [Dispatchers.IO].
 */
suspend fun checkUpdateVersion(localFile: String = defaultLocalFile): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val response = File(localFile).inputStream().bufferedReader().use { it.readText() }
        val jsonObject = JSONObject(response)
        val hash = jsonObject.getString("hash")
        val description = jsonObject.getString("description")
        val url = jsonObject.getString("url")
        UpdateInfo(hash, description, url)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun writeLocalVersion(info: UpdateInfo, localFile: String = defaultLocalFile) {
    val jsonObject = JSONObject().apply {
        put("hash", info.hash)
        put("description", info.description)
        put("url", info.url)
    }
    File(localFile).writeText(jsonObject.toString())
}


/**
 * Downloads a ZIP from [zipUrl] and unzips it into the "staging" folder.
 * Suspends on I/O using [Dispatchers.IO].
 */
suspend fun downloadAndUnzip(zipUrl: String): Boolean = withContext(Dispatchers.IO) {
    try {
        // Step 1: If staging directory exists, rename it to staging_n (using collision-free logic).
        val stagingDir = File("staging")
        if (stagingDir.exists()) {
            val backupDir = findCollisionFreeName(stagingDir)
            stagingDir.renameTo(backupDir)
        }

        // Step 2: Create a fresh staging directory.
        stagingDir.mkdirs()

        // Step 3: Download the .zip file.
        val connection = URL(zipUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.connect()
        if (connection.responseCode != 200) {
            return@withContext false
        }

        // Step 4: Unzip the contents into the staging directory.
        val zipInputStream = ZipInputStream(connection.inputStream)
        var zipEntry = zipInputStream.nextEntry
        val buffer = ByteArray(1024)

        while (zipEntry != null) {
            if (!zipEntry.isDirectory) {
                val outFile = File(stagingDir, zipEntry.name)
                // Ensure parent directories exist.
                outFile.parentFile?.mkdirs()

                FileOutputStream(outFile).use { fos ->
                    var len: Int
                    while (zipInputStream.read(buffer).also { len = it } > 0) {
                        fos.write(buffer, 0, len)
                    }
                }

                if (outFile.name.startsWith("gradlew")) {
                    // Apply chmod 777 to the file
                    val result = outFile.setExecutable(true, false) &&
                            outFile.setReadable(true, false) &&
                            outFile.setWritable(true, false)

                    if (result) {
                        println("Permissions (777) successfully applied to file: ${outFile.name}")
                    } else {
                        println("Failed to apply permissions to file: ${outFile.name}")
                    }
                }

            } else {
                // If it's a directory, create it.
                val dir = File(stagingDir, zipEntry.name)
                dir.mkdirs()
            }

            zipInputStream.closeEntry()
            zipEntry = zipInputStream.nextEntry
        }
        zipInputStream.close()
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/**
 * Moves the content of "staging" into "live".
 * If a file in "live" would be overwritten, it is renamed to avoid collisions.
 */
fun promoteStagingToLive(): Boolean {
    return try {
        val stagingDir = File("staging/field4")
        val liveDir = File(".")

        if (!stagingDir.exists()) {
            // Nothing to move if staging doesn't exist.
            return true
        }
        if (!liveDir.exists()) {
            liveDir.mkdirs()
        }

        // Recursively move the contents from staging to live.
        stagingDir.listFiles()?.forEach { item ->
            moveRecursively(item, liveDir)
        }

        // Remove the now-empty staging directory.
        stagingDir.delete()
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/**
 * Moves [source] (file or directory) into [destinationDir].
 * If [destinationDir]/[source.name] already exists, it is renamed using [findCollisionFreeName].
 */
private fun moveRecursively(source: File, destinationDir: File) {
    val target = File(destinationDir, source.name)

    // If the target already exists, rename the existing item to avoid collision.
    if (target.exists()) {
        val nonColliding = findCollisionFreeName(target)
        target.renameTo(nonColliding)
    }

    println("Moving ${source.name} to ${target.name}")
    // Finally, rename (move) the source item to the target location.
    source.renameTo(target)
}

/**
 * Returns a collision-free [File] by appending "_1", "_2", etc. to the existing file name
 * until it doesn't exist in the file system.
 */
private fun findCollisionFreeName(file: File): File {
    val parent = file.parentFile
    val baseName = file.name

    var i = 1
    while (true) {
        val newFile = File(parent, "${baseName}_$i")
        if (!newFile.exists()) {
            return newFile
        }
        i++
    }
}
