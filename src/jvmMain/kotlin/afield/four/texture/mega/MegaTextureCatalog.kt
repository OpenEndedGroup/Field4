package afield.four.texture.mega

import afield.logger.lprintln
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.extension

/**
 * The set of source images a [MegaTexture] can show, and the index each one answers to.
 *
 * An index is the only name the GPU ever knows: the feedback buffer, the page table and every
 * `megaTexture(id, uv)` call in a shader are all indexed by it. So the mapping has to be *stable
 * across runs* — otherwise the on-disk pyramid cache from the last run describes different
 * pictures than the catalog it is keyed to, and the wall silently rearranges itself.
 *
 * Stability comes from append-only ordering, not from sorting: index = order of first appearance
 * in [catalogFile]. A file added to the directory later is appended and gets a new index; a file
 * deleted leaves its index behind as a hole (resolving to the same path, which will simply fail to
 * decode). Re-sorting on every scan would have been tidier to read and would have invalidated the
 * cache every time the directory changed.
 *
 * Each entry records the source's `mtime` and `size` as it was when the cache was written. A
 * mismatch on load means that file changed under us, and [changedIndices] reports it so the
 * pyramid cache can drop the stale pages for exactly those indices rather than for everything.
 */
class MegaTextureCatalog private constructor(
    /** Where this catalog and its pyramid cache live. */
    val cacheDir: Path,
    private val entries: MutableList<Entry>,
    private val indexByPath: MutableMap<String, Int>,
    /**
     * Indices whose source file's mtime/size differ from what the catalog recorded — their cached
     * pages describe an older version of the picture and must be discarded.
     */
    val changedIndices: IntArray
) {
    class Entry(val path: Path, var mtime: Long, var size: Long)

    val count: Int get() = entries.size

    fun path(index: Int): Path? = entries.getOrNull(index)?.path

    fun indexOf(path: Path): Int? = indexByPath[path.toAbsolutePath().normalize().toString()]

    /** Every (index, path) pair, in index order. Useful for a warm-up pass over the whole set. */
    fun forEachIndexed(block: (Int, Path) -> Unit) {
        entries.forEachIndexed { index, entry -> block(index, entry.path) }
    }

    private val catalogFile: Path get() = cacheDir.resolve(CATALOG_NAME)

    /**
     * Rewrite [catalogFile] from the current entries. Called once after a scan; the format is
     * deliberately plain text (mtime, size, path — tab separated) so a wrong cache is diagnosable
     * with `head`, which mattered more here than the few hundred milliseconds a binary form would
     * save on a million-line file.
     */
    private fun save() {
        val tmp = cacheDir.resolve("$CATALOG_NAME.tmp")
        Files.newBufferedWriter(tmp).use { out ->
            out.write("$CATALOG_MAGIC\n")
            for (entry in entries) {
                out.write(entry.mtime.toString())
                out.write("\t")
                out.write(entry.size.toString())
                out.write("\t")
                out.write(entry.path.toString().replace('\n', ' '))
                out.write("\n")
            }
        }
        Files.move(tmp, catalogFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val CATALOG_NAME = "catalog.txt"
        private const val CATALOG_MAGIC = "#afield megatexture catalog v1"

        val DEFAULT_EXTENSIONS = setOf("jpg", "jpeg", "png", "tga", "bmp", "gif", "psd", "hdr")

        /** Where a source spec's cache lives when the caller does not name one. */
        fun defaultCacheRoot(): Path =
            Path.of(System.getProperty("user.home") ?: ".", ".afield", "megatexture")

        /**
         * Scan [roots] for images and load-or-create the catalog that names them.
         *
         * [cacheRoot] holds one subdirectory per *source spec* (the roots plus the extension
         * filter), so two different walls do not fight over one index space. Key by the spec and
         * not by the file list: the whole point is that adding a picture to the folder keeps the
         * indices — and therefore the cached pyramids — of everything already there.
         */
        fun scan(
            roots: List<Path>,
            extensions: Set<String> = DEFAULT_EXTENSIONS,
            recursive: Boolean = true,
            cacheRoot: Path = defaultCacheRoot(),
            maxImages: Int = Int.MAX_VALUE
        ): MegaTextureCatalog {
            val specKey = buildString {
                roots.forEach { append(it.toAbsolutePath().normalize().toString()).append('\n') }
                append("ext=").append(extensions.sorted().joinToString(",")).append('\n')
                append("recursive=").append(recursive).append('\n')
            }
            val cacheDir = cacheRoot.resolve(shortHash(specKey))
            Files.createDirectories(cacheDir)

            val entries = mutableListOf<Entry>()
            val indexByPath = mutableMapOf<String, Int>()
            loadExisting(cacheDir.resolve(CATALOG_NAME), entries, indexByPath)
            val knownBefore = entries.size

            val found = mutableListOf<Path>()
            for (root in roots) {
                if (!Files.isDirectory(root)) {
                    lprintln("warning,megatexture", "megatexture source '$root' is not a directory")
                    continue
                }
                val stream = if (recursive) Files.walk(root) else Files.list(root)
                stream.use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .filter { it.extension.lowercase() in extensions }
                        .forEach { found.add(it.toAbsolutePath().normalize()) }
                }
            }
            // Sort only the *newly discovered* files. Existing indices keep their order, so the
            // cache stays valid, while a first scan is still in a sensible (path) order.
            found.sort()

            val changed = mutableListOf<Int>()
            for (path in found) {
                val key = path.toString()
                val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
                val size = runCatching { Files.size(path) }.getOrDefault(0L)
                val existing = indexByPath[key]
                if (existing == null) {
                    if (entries.size >= maxImages) break
                    indexByPath[key] = entries.size
                    entries.add(Entry(path, mtime, size))
                } else {
                    val entry = entries[existing]
                    if (entry.mtime != mtime || entry.size != size) {
                        entry.mtime = mtime
                        entry.size = size
                        changed.add(existing)
                    }
                }
            }

            val catalog = MegaTextureCatalog(cacheDir, entries, indexByPath, changed.toIntArray())
            if (entries.size != knownBefore || changed.isNotEmpty()) catalog.save()
            lprintln(
                "info,megatexture",
                "megatexture catalog $cacheDir: ${entries.size} images " +
                        "(${entries.size - knownBefore} new, ${changed.size} changed)"
            )
            return catalog
        }

        /** An explicit, ordered list of files — for tests and for callers that build their own set. */
        fun of(paths: List<Path>, cacheDir: Path): MegaTextureCatalog {
            Files.createDirectories(cacheDir)
            val entries = mutableListOf<Entry>()
            val indexByPath = mutableMapOf<String, Int>()
            paths.forEach { raw ->
                val path = raw.toAbsolutePath().normalize()
                indexByPath[path.toString()] = entries.size
                entries.add(
                    Entry(
                        path,
                        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L),
                        runCatching { Files.size(path) }.getOrDefault(0L)
                    )
                )
            }
            return MegaTextureCatalog(cacheDir, entries, indexByPath, IntArray(0))
        }

        private fun loadExisting(
            file: Path,
            entries: MutableList<Entry>,
            indexByPath: MutableMap<String, Int>
        ) {
            if (!Files.isRegularFile(file)) return
            try {
                Files.newBufferedReader(file).use { reader ->
                    val header = reader.readLine() ?: return
                    if (header != CATALOG_MAGIC) {
                        lprintln("warning,megatexture", "ignoring catalog with unexpected header: $file")
                        return
                    }
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) continue
                        val a = line.indexOf('\t')
                        val b = line.indexOf('\t', a + 1)
                        if (a < 0 || b < 0) continue
                        val mtime = line.substring(0, a).toLongOrNull() ?: continue
                        val size = line.substring(a + 1, b).toLongOrNull() ?: continue
                        val path = Path.of(line.substring(b + 1))
                        indexByPath[path.toString()] = entries.size
                        entries.add(Entry(path, mtime, size))
                    }
                }
            } catch (e: IOException) {
                lprintln("warning,megatexture", "could not read catalog $file: ${e.message}")
                entries.clear()
                indexByPath.clear()
            }
        }

        internal fun shortHash(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return digest.take(10).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * The on-disk store of downsampled pages, one sparse file per level.
 *
 * Every page at a level is the same size, so a page's offset in `level<L>.bin` is just
 * `index * stride` — no index, no allocator, one `pread` to load. The files are left sparse, so
 * `level3.bin` for a million images is 16 GB of address space and only as many bytes as have
 * actually been written. That is why there is a separate presence bitmap rather than a
 * "non-zero means present" test: a legitimately black page is indistinguishable from a hole.
 *
 * A level is only cached at all if its fully-populated size fits [maxVirtualBytes]; above that the
 * pages are small in number and cheap to re-derive, and a 64 GB sparse file is a liability on any
 * filesystem or backup tool that does not understand holes.
 *
 * Presence bits are written back periodically, not on every page. Because a page's bytes are
 * written *before* its bit is set, an out-of-date bitmap can only ever under-report — which costs
 * a re-decode and never shows the wrong picture. The reverse ordering would have been a bug.
 */
class MegaTexturePyramidCache(
    val cacheDir: Path,
    val imageCount: Int,
    /** Where page sizes come from. Taken whole so the cache's stride cannot drift from the atlas's. */
    val layout: MegaTextureLayout,
    val maxLevelCached: Int,
    val maxVirtualBytes: Long = 32L shl 30
) : AutoCloseable {

    val levels: Int get() = layout.levels

    private class LevelStore(
        val level: Int,
        val stride: Int,
        val channel: FileChannel,
        val presentFile: Path,
        val present: LongArray
    ) {
        @Volatile
        var presentDirty = false
    }

    private val stores = arrayOfNulls<LevelStore>(levels)
    private val lock = Any()
    private var lastFlushNanos = System.nanoTime()

    /**
     * Source pixel dimensions, two ints per image, or 0 for "never decoded".
     *
     * A cached page is a fixed-size rectangle with the picture fitted into its top-left corner, so
     * the bytes alone do not say how much of the page is picture. Without this the aspect ratio
     * would be knowable only on a cache *miss*, and every warm start would show the page's shape
     * rather than the picture's.
     */
    private val dims = IntArray(maxOf(imageCount, 1) * 2)
    private val dimsFile = cacheDir.resolve("dims.bin")
    private var dimsDirty = false

    init {
        Files.createDirectories(cacheDir)
        loadDims()
        for (level in 0 until minOf(levels, maxLevelCached + 1)) {
            val stride = layout.pageBytes(level)
            val virtual = stride.toLong() * imageCount
            if (virtual > maxVirtualBytes) {
                lprintln(
                    "info,megatexture",
                    "megatexture cache skipping level $level " +
                            "(${layout.pageWidth(level)}x${layout.pageHeight(level)}): would need " +
                            "${virtual shr 30} GiB of sparse file, over the ${maxVirtualBytes shr 30} GiB cap"
                )
                continue
            }
            try {
                val blob = cacheDir.resolve("level$level.bin")
                val channel = FileChannel.open(
                    blob,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
                )
                val presentFile = cacheDir.resolve("level$level.present")
                stores[level] = LevelStore(level, stride, channel, presentFile, loadPresent(presentFile))
            } catch (e: IOException) {
                lprintln("warning,megatexture", "megatexture cache level $level unavailable: ${e.message}")
            }
        }
    }

    private fun loadDims() {
        if (!Files.isRegularFile(dimsFile)) return
        try {
            val buffer = ByteBuffer.wrap(Files.readAllBytes(dimsFile)).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (buffer.remaining() >= 4 && i < dims.size) dims[i++] = buffer.int
        } catch (e: IOException) {
            lprintln("warning,megatexture", "could not read $dimsFile: ${e.message}")
        }
    }

    /** The source image's pixel size, or null if it has never been decoded. */
    fun sourceSize(index: Int): Pair<Int, Int>? {
        if (index < 0 || index * 2 + 1 >= dims.size) return null
        val w = dims[index * 2]
        val h = dims[index * 2 + 1]
        return if (w > 0 && h > 0) w to h else null
    }

    /** Record the source image's pixel size, learned from a decode. */
    fun setSourceSize(index: Int, width: Int, height: Int) {
        if (index < 0 || index * 2 + 1 >= dims.size) return
        synchronized(lock) {
            if (dims[index * 2] == width && dims[index * 2 + 1] == height) return
            dims[index * 2] = width
            dims[index * 2 + 1] = height
            dimsDirty = true
        }
    }

    private fun loadPresent(file: Path): LongArray {
        val words = (imageCount + 63) / 64
        val bits = LongArray(maxOf(words, 1))
        if (!Files.isRegularFile(file)) return bits
        try {
            val bytes = Files.readAllBytes(file)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (buffer.remaining() >= 8 && i < bits.size) bits[i++] = buffer.long
        } catch (e: IOException) {
            lprintln("warning,megatexture", "could not read $file: ${e.message}")
        }
        return bits
    }

    /** True if level [level] has a page for [index] on disk. */
    fun has(index: Int, level: Int): Boolean {
        val store = stores.getOrNull(level) ?: return false
        if (index < 0 || index >= imageCount) return false
        return (store.present[index ushr 6] ushr (index and 63)) and 1L != 0L
    }

    /** Which levels this cache stores at all, cheapest first. */
    fun cachedLevels(): List<Int> = stores.mapIndexedNotNull { level, store -> level.takeIf { store != null } }

    /**
     * Read the cached page for (image, level) into [into], or return false if it is not there.
     * [into] must have `stride` bytes remaining; its position is left at the start of the page.
     */
    fun read(index: Int, level: Int, into: ByteBuffer): Boolean {
        val store = stores.getOrNull(level) ?: return false
        if (!has(index, level)) return false
        val start = into.position()
        require(into.remaining() >= store.stride) {
            "megatexture cache read needs ${store.stride} bytes, buffer has ${into.remaining()}"
        }
        val slice = into.duplicate().limit(start + store.stride) as ByteBuffer
        var read = 0
        val offset = store.stride.toLong() * index
        try {
            while (read < store.stride) {
                val n = store.channel.read(slice, offset + read)
                if (n <= 0) return false
                read += n
            }
        } catch (e: IOException) {
            lprintln("warning,megatexture", "megatexture cache read failed at level $level/$index: ${e.message}")
            return false
        }
        return true
    }

    /** Store the page for (image, level). Silently does nothing for an uncached level. */
    fun write(index: Int, level: Int, page: ByteBuffer) {
        val store = stores.getOrNull(level) ?: return
        if (index < 0 || index >= imageCount) return
        val source = page.duplicate()
        require(source.remaining() >= store.stride) {
            "megatexture cache write needs ${store.stride} bytes, got ${source.remaining()}"
        }
        source.limit(source.position() + store.stride)
        val offset = store.stride.toLong() * index
        try {
            var written = 0
            while (source.hasRemaining()) {
                val n = store.channel.write(source, offset + written)
                if (n <= 0) break
                written += n
            }
            if (written < store.stride) return
        } catch (e: IOException) {
            lprintln("warning,megatexture", "megatexture cache write failed at level $level/$index: ${e.message}")
            return
        }
        synchronized(lock) {
            store.present[index ushr 6] = store.present[index ushr 6] or (1L shl (index and 63))
            store.presentDirty = true
            val now = System.nanoTime()
            if (now - lastFlushNanos > 2_000_000_000L) {
                lastFlushNanos = now
                flushPresent()
            }
        }
    }

    /** Forget every cached level for these indices — their source files changed. */
    fun invalidate(indices: IntArray) {
        if (indices.isEmpty()) return
        synchronized(lock) {
            for (store in stores) {
                if (store == null) continue
                for (index in indices) {
                    if (index < 0 || index >= imageCount) continue
                    store.present[index ushr 6] = store.present[index ushr 6] and (1L shl (index and 63)).inv()
                }
                store.presentDirty = true
            }
            flushPresent()
        }
    }

    /** How many pages each cached level holds — the number reported by `stats`. */
    fun residentCounts(): IntArray = IntArray(levels) { level ->
        val store = stores.getOrNull(level) ?: return@IntArray 0
        store.present.sumOf { java.lang.Long.bitCount(it) }
    }

    private fun flushPresent() {
        if (dimsDirty) {
            try {
                val buffer = ByteBuffer.allocate(dims.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                dims.forEach { buffer.putInt(it) }
                Files.write(
                    dimsFile,
                    buffer.array(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
                dimsDirty = false
            } catch (e: IOException) {
                lprintln("warning,megatexture", "could not write $dimsFile: ${e.message}")
            }
        }
        for (store in stores) {
            if (store == null || !store.presentDirty) continue
            try {
                val buffer = ByteBuffer.allocate(store.present.size * 8).order(ByteOrder.LITTLE_ENDIAN)
                store.present.forEach { buffer.putLong(it) }
                Files.write(
                    store.presentFile,
                    buffer.array(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
                store.presentDirty = false
            } catch (e: IOException) {
                lprintln("warning,megatexture", "could not write ${store.presentFile}: ${e.message}")
            }
        }
    }

    override fun close() {
        synchronized(lock) { flushPresent() }
        stores.forEach { runCatching { it?.channel?.close() } }
    }
}
