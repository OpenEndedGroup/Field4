package afield.lang.fieldml

import afield.runtime.completion.KotlinSourceIndex
import afield.runtime.completion.KotlinSourceIndexDb
import java.nio.file.Files
import java.nio.file.Path

/**
 * FieldML's read of the shared Kotlin source index, and the staleness check that decides whether to
 * trust it.
 *
 * [KotlinSourceIndex] holds a database of every declaration in the checkout, which FieldML wants for
 * three lookups: the host functions a FieldML program may call ([FieldMLRuntimePrelude]), and the
 * Kotlin class names and function names its completion offers ([FieldMLCompletionEngine]). That
 * database is a snapshot, so it is handed back only when every path it records still resolves to a
 * file. When one does not, the host-function and function-name lookups walk `src/` themselves
 * instead, and the class-name lookup goes quiet rather than offering stale names.
 */

/** A moved or deleted source invalidates the cached layout; callers then scan this checkout. */
internal fun fieldMLSourceIndex(root: Path): KotlinSourceIndexDb? {
    val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return null
    val paths = (index.files.map { it.path } + index.definitions.map { it.filePath }).distinct()
    return index.takeIf { paths.all { Files.isRegularFile(root.resolve(it).normalize()) } }
}
