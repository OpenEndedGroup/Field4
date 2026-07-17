package afield

import kotlinx.browser.document

/**
 * Experimental visionOS front end — skeleton entry point (DEFERRED SEED).
 *
 * NOT currently wired into any Gradle compilation. The Kotlin/JS fork is deferred (KMP won't
 * give a clean second bundle sharing only commonMain; the native shell targets /welcome +
 * /index.html first). This file is a seed kept so the eventual fork has an entry point and a
 * worked example of the two things the vision bundle must do — construct the shared
 * `commonMain` `Message` and provide the `actual fun _send`.
 *
 * When the fork happens, decide the mechanism (associate compilation vs. a shared contract
 * module — see dev_docs/vision_frontend.md), wire this source set into it, and grow the fork
 * of `jsMain` (editor, boxes, gestures) here. Guardrail: fork presentation freely, but keep
 * depending on the shared `commonMain` contract (Messages.kt + rpc.kt) — never fork the
 * protocol here.
 */
fun main() {
    // Referencing the shared commonMain `Message` here proves the contract is compiled into
    // the vision bundle (not just re-declared) — the whole point of the fork-at-commonMain rule.
    val seam = Message(name = "vision-seam-check")

    val root = document.getElementById("vision-root") ?: document.body
    root?.innerHTML = """
        <div style="font-family:-apple-system,system-ui,sans-serif;padding:2rem;color:#dcdcdc">
          <h1 style="margin:0 0 .5rem;font-weight:600">Field4 — vision front end</h1>
          <p style="margin:0;opacity:.7">
            <code>afield-vision.mjs</code> is live and sharing the <code>commonMain</code>
            contract (constructed <code>Message("${seam.name}")</code>).
          </p>
          <p style="margin:.75rem 0 0;opacity:.45;font-size:.85rem">
            Skeleton seam — the jsMain presentation fork grows from here.
          </p>
        </div>
    """.trimIndent()

    console.log("[vision] booted; shared commonMain contract OK:", seam.name)
}

/**
 * Minimal `actual` for the shared `expect fun _send` in commonMain (Messages.kt). The real
 * wss transport comes over when we fork jsMain's client; for now this only needs to exist so
 * the vision target satisfies the expect/actual contract and compiles.
 */
actual fun _send(message: Message) {
    console.log("[vision] _send (stub):", message.name)
}
