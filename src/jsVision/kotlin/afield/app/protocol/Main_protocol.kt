package afield.app.protocol

import afield.app.canvas.invoke
import afield.app.client.client

import kotlinx.browser.document

/**
 * Minimal `actual` for the shared `expect fun _send` in commonMain (Messages.kt). The real
 * wss transport comes over when we fork jsMain's client; for now this only needs to exist so
 * the vision target satisfies the expect/actual contract and compiles.
 */
actual fun _send(message: Message) {
    console.log("[vision] _send (stub):", message.name)
}
