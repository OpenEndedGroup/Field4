package afield.morpho

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The guard for [MorphoRuntime]'s unpack list.
 *
 * `MorphoRuntime.VENDORED` does two jobs at once, and both of them need it to be complete. It is
 * the exact set of resources a packaged run copies out of the jar into a temp directory, and it is
 * the probe — `VENDORED.all { dev.resolve(it).exists() }` — that decides a checkout is a dev tree
 * and can be read in place. A path that the runtime asks for but the list omits is therefore
 * invisible twice over: the dev probe never asks about the missing file because the file is not in
 * the list, and a packaged run unpacks a tree with a hole in it.
 *
 * That is exactly what happened to `morpho-field-layout.mjs` (fixed 2026-09-14). A packaged run
 * unpacked seven of the eight files the guest needed, `module("morpho-field-layout.mjs")` failed to
 * resolve, and 3D force layout broke while compilation, simulation and drawing all kept working —
 * a partial failure, in the part hardest to notice from a stack trace.
 *
 * No behavioural test can catch this, and that is not an oversight in the tests. `jvmTest` always
 * runs from a dev checkout, so `materialize()` returns the `src/jvmMain/resources` path, the copy
 * branch is never taken, and every file is present whether or not anybody listed it. So this is
 * structural, in the shape of
 * [afield.docs.SmallLanguageAnatomyTest.everyMainLanguagesCodeLanguageHasAMonacoGrammar]: a list
 * that can silently disagree with the code, held against the code by scraping the code.
 *
 * Two halves, pinning the two ways the list goes wrong:
 *
 * - [everyPathTheRuntimeAsksForIsOnTheUnpackList] derives the demand — every path the Kotlin side
 *   names, plus every relative specifier the guest modules import from each other — and requires
 *   the list to cover it. This is the half that was red before the fix.
 * - [everyVendoredEntryIsReachableAsAClasspathResource] checks the supply: the one thing the
 *   packaged branch depends on and the dev branch never touches. A list entry that is not a
 *   classpath resource is an `error("morpho resource missing from the classpath")` at first use in
 *   a packaged run, and nothing at all in a dev one.
 *
 * When one goes red the fix is a line in `VENDORED`, not a loosening here.
 */
class MorphoVendoredResourcesTest {

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").isFile) {
            d = d.parentFile ?: error("no repo root above ${System.getProperty("user.dir")}")
        }
        return d
    }

    private val root = repoRoot()

    /** The directory `VENDORED` entries are relative to, in both of its lives. */
    private val resources = File(root, "src/jvmMain/resources").canonicalFile

    private fun sourceText(relative: String): File = File(root, relative).also {
        assertTrue(it.isFile, "$relative does not exist")
    }

    /**
     * Every path the *host* asks [MorphoRuntime.root] for: the `module("…")` entry points and the
     * `root.resolve("…")` reads, scraped out of the two Kotlin files that do either.
     *
     * Scraped rather than listed because a list here would be the same kind of object as the one
     * it is guarding, with the same failure mode. `module(fileName)` and `root.resolve(fileName)`
     * — the parameterised calls inside `MorphoRuntime` itself — carry no string literal and so
     * contribute nothing, which is right: their arguments arrive from the call sites below.
     */
    private fun hostRequestedPaths(): Set<String> {
        val found = sortedSetOf<String>()
        listOf("MorphoGraal.kt", "MorphoLayout3D.kt").forEach { name ->
            val text = sourceText("src/jvmMain/kotlin/afield/morpho/$name").readText()
            listOf(Regex("""\bmodule\("([^"]+)"\)"""), Regex("""\broot\.resolve\("([^"]+)"\)"""))
                .forEach { re -> re.findAll(text).forEach { found += it.groupValues[1] } }
        }
        return found
    }

    /** The guest modules that make up the vendored tree: the two entry points and morpho's own. */
    private fun guestModuleFiles(): List<File> = buildList {
        resources.listFiles { f: File -> f.isFile && f.extension == "mjs" }?.let { addAll(it) }
        File(resources, "morpho/js")
            .listFiles { f: File -> f.isFile && f.extension == "js" }?.let { addAll(it) }
    }.sortedBy { it.path }

    /**
     * Every relative specifier those modules import, resolved against the importing file and made
     * relative to the resources root — i.e. expressed in the same coordinates as `VENDORED`.
     *
     * Only relative specifiers. `utils.js` dynamically imports `'fs'` and `'path'`, which are
     * GraalJS built-ins and not files anyone can unpack.
     */
    private fun guestImportedPaths(): Set<String> {
        val files = guestModuleFiles()
        assertTrue(
            files.size >= 6,
            "the guest-module scrape looks broken — found ${files.map { it.name }} under ${resources.path}"
        )
        val specifier = Regex("""\b(?:from|import)\s*\(?\s*['"](\.{1,2}/[^'"]+)['"]""")
        val found = sortedSetOf<String>()
        files.forEach { f ->
            specifier.findAll(f.readText()).forEach { m ->
                val target = File(f.parentFile, m.groupValues[1]).canonicalFile
                val rel = target.relativeToOrNull(resources)?.invariantSeparatorsPath
                assertTrue(
                    rel != null && !rel.startsWith(".."),
                    "${f.name} imports '${m.groupValues[1]}', which resolves to ${target.path} — " +
                        "outside ${resources.path}, so no unpacked tree can ever satisfy it"
                )
                found += rel
            }
        }
        return found
    }

    /**
     * Everything the runtime reaches for has to be on the unpack list.
     *
     * Asserted as a subset rather than an equality: `morpho/LICENSE` and `morpho/PROVENANCE.md` are
     * on the list deliberately with nothing loading them, so that the unpacked tree says whose code
     * it is. Extra entries are cheap and harmless; a missing one is the bug.
     */
    @Test
    fun everyPathTheRuntimeAsksForIsOnTheUnpackList() {
        val host = hostRequestedPaths()
        assertTrue(
            host.size >= 4,
            "the module(\"…\") / root.resolve(\"…\") scrape looks broken — found $host"
        )
        val guest = guestImportedPaths()
        assertTrue(
            guest.size >= 4,
            "the guest import scrape looks broken — found $guest"
        )

        val listed = MorphoRuntime.VENDORED.toSet()
        val missing = (host + guest).filterNot { it in listed }.sorted()
        assertTrue(
            missing.isEmpty(),
            "these paths are reached at runtime but are not in MorphoRuntime.VENDORED, so a " +
                "packaged run unpacks a tree without them and the dev-checkout probe never asks " +
                "whether they are there:\n" +
                missing.joinToString("\n") { "  $it" } +
                "\nAdd each to VENDORED. Every entry is both unpacked and probed for, so the list " +
                "must name everything the guest needs as a real file."
        )
    }

    /**
     * Every list entry must actually be a classpath resource.
     *
     * This is the assertion with no dev-mode equivalent. `materialize()`'s copy branch reads each
     * entry with `getResourceAsStream("/$rel")` and calls `error(…)` when it comes back null, and
     * that branch never runs under `jvmTest` — so a typo in the list, or a resource that stops
     * being packaged, is a failure that only a packaged run can discover. Here it is discovered by
     * asking the same question through the same loader.
     */
    @Test
    fun everyVendoredEntryIsReachableAsAClasspathResource() {
        assertTrue(
            MorphoRuntime.VENDORED.size >= 8,
            "VENDORED has shrunk to ${MorphoRuntime.VENDORED.size} entries — the guest needs eight " +
                "files before attribution is counted"
        )
        val duplicated = MorphoRuntime.VENDORED.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(duplicated.isEmpty(), "VENDORED lists these twice: $duplicated")

        val unreachable = MorphoRuntime.VENDORED.filter { rel ->
            val stream = MorphoRuntime::class.java.getResourceAsStream("/$rel")
            stream?.close()
            stream == null
        }
        assertTrue(
            unreachable.isEmpty(),
            "these VENDORED entries are not on the classpath, so materialize() would fail with " +
                "\"morpho resource missing from the classpath\" the first time a packaged run " +
                "touched morpho:\n" + unreachable.joinToString("\n") { "  /$it" }
        )
    }

    /**
     * And every list entry must be a real file in `src/jvmMain/resources`.
     *
     * The other direction of the same coin. The dev probe is `all { dev.resolve(it).exists() }`, so
     * an entry naming a file that is not in the source tree makes every dev checkout fail the
     * probe and silently fall through to unpacking — which works, and costs a temp directory and
     * the live-editing that reading in place exists to provide.
     */
    @Test
    fun everyVendoredEntryIsAFileInTheDevTree() {
        val absent = MorphoRuntime.VENDORED.filterNot { File(resources, it).isFile }
        assertTrue(
            absent.isEmpty(),
            "these VENDORED entries do not exist under ${resources.path}, so MorphoRuntime's " +
                "dev-checkout probe fails and every developer run unpacks to a temp directory " +
                "instead of reading the tree in place:\n" + absent.joinToString("\n") { "  $it" }
        )
    }
}
