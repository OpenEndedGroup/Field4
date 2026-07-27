package afield

import java.io.File
import java.nio.file.Files

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
