package afield.lang.fieldml

import afield.runtime.completion.KotlinSourceIndex
import afield.runtime.completion.KotlinSourceIndexDb
import java.nio.file.Files
import java.nio.file.Path

/** A moved or deleted source invalidates the cached layout; callers then scan this checkout. */
internal fun fieldMLSourceIndex(root: Path): KotlinSourceIndexDb? {
    val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return null
    val paths = (index.files.map { it.path } + index.definitions.map { it.filePath }).distinct()
    return index.takeIf { paths.all { Files.isRegularFile(root.resolve(it).normalize()) } }
}
