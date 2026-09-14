package afield

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where a test may write a "proof" artifact for a human to eyeball.
 *
 * Several tests render something visual — a sparkline SVG, a waveshaper transfer curve, a
 * spectrogram PNG — and inspect it programmatically, but also write it out so a person can look at
 * it. Those writes kept going to a **relative** path (`File("sparkline-proof")`,
 * `File("chebyshev-proof.svg")`) or to the detected worktree root, which resolves against whatever
 * tree the test runs in. The result was junk accumulating in the repository, and in every nightly
 * agent worktree, on every single run.
 *
 * The default is therefore a throwaway temp directory: the assertions that read the artifact back
 * still run, so the tests lose nothing, and nothing is left behind. Set `AFIELD_PROOF_DIR` to a
 * path when you actually want to collect the artifacts:
 *
 * ```
 * AFIELD_PROOF_DIR=/tmp/field-proofs ./gradlew jvmTest --tests 'afield.stdlib.SpectralTest'
 * ```
 *
 * @param name a subdirectory name, so proofs from different tests do not collide when
 *   `AFIELD_PROOF_DIR` points them all at one place.
 */
internal fun proofDir(name: String): File {
    val configured = System.getenv("AFIELD_PROOF_DIR")
    val dir = if (configured != null) File(configured, name)
    else Files.createTempDirectory("afield-proof-$name").toFile()
    dir.mkdirs()
    return dir
}

/**
 * Where a docs-capture harness may write the figures a published page embeds.
 *
 * This is [proofDir]'s problem one step further in. A capture harness is not a behavioural test —
 * it rasterizes real values through the real renderer so a docs page can embed the honest output —
 * so the *point* is to produce the committed PNGs under `public/docs/assets/…`. Writing them on
 * every `jvmTest` run is still wrong: the renderer legitimately improves, the freshly derived bytes
 * differ from the committed ones, and an ordinary full-suite run leaves a dirty tree that a
 * reviewer has to triage. `ValueVisualizerDocCaptureTest` and `VectorAlgebraDocCaptureTest` both
 * did exactly that until 2026-09-04, and the two swizzle figures they re-derived had been showing
 * up as unstaged modifications in the primary checkout.
 *
 * So the default is a throwaway temp directory — every assertion that reads a capture back still
 * runs, so the harness keeps all of its value as a smoke test of the render path — and refreshing
 * the committed assets is an explicit request:
 *
 * ```
 * AFIELD_DOCS_ASSETS=1 ./gradlew jvmTest --tests 'afield.VectorAlgebraDocCaptureTest'
 * ```
 *
 * The committed destination is resolved against the **repository root** rather than the process
 * working directory. `Paths.get("public", …)` only happened to land in the right place because
 * Gradle runs tests with the working directory at the module root; from anywhere else it silently
 * created a second `public/docs/assets` tree.
 *
 * @param relative the committed location, repo-root-relative, e.g. `public/docs/assets/vector_algebra`.
 * @param explicitDirEnv an optional harness-specific variable naming an explicit destination
 *   directory, for harnesses that documented one before this helper existed.
 */
internal fun docsAssetDir(relative: String, explicitDirEnv: String? = null): Path {
    val explicit = explicitDirEnv?.let { System.getenv(it) }?.takeIf { it.isNotBlank() }
    val label = relative.trimEnd('/').substringAfterLast('/')
    val dir = when {
        explicit != null -> Paths.get(explicit)
        System.getenv("AFIELD_DOCS_ASSETS") == "1" -> testRepoRoot().resolve(relative)
        else -> Files.createTempDirectory("afield-docs-asset-$label")
    }
    Files.createDirectories(dir)
    println("docs capture '$label' writing to ${dir.toAbsolutePath()}")
    return dir
}

/** The repository root, found by walking up to the `settings.gradle.kts` that defines the build. */
internal fun testRepoRoot(): Path {
    var dir = Paths.get("").toAbsolutePath()
    while (!Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
        dir = dir.parent ?: error("could not locate the repository root from ${Paths.get("").toAbsolutePath()}")
    }
    return dir
}
