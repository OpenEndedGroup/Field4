package afield

import afield.logger.lprintln
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.Clip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

suspend fun Box.screenshot(
    fn: String,
    padding: Double = 12.0,
    panIntoView: Boolean = true,
    hideMonaco: Boolean = true
): Boolean {
    val page = globalPlaywriteContext ?: run {
        lprintln("warning,screenshot", "Playwright is not active; cannot screenshot ${id}")
        return false
    }

    val pending = CompletableDeferred<BoxScreenshotResponse>()
    val callbackName = "box-screenshot-response-${UUID.randomUUID()}"

    sendWithReply(
        Message("box-screenshot").payload(
            BoxScreenshotRequest(
                uid = id,
                padding = padding,
                panIntoView = panIntoView,
                hideMonaco = hideMonaco
            )
        ),
        callbackName
    ) { message, _ ->
        val response = Json.decodeFromJsonElement<BoxScreenshotResponse>(message.payload!!)
        pending.complete(response)
        Handled.yes_remove
    }

    val response = runCatching { withTimeout(5_000) { pending.await() } }.getOrElse {
        lprintln("warning,screenshot", "Timed out waiting for screenshot prep on ${id}: ${it.message}")
        return false
    }

    val clip = response.clip
    if (!response.success || clip == null) {
        lprintln("warning,screenshot", "Screenshot prep failed for ${id}: ${response.message}")
        return false
    }

    val outputPath = Path.of(fn)
    outputPath.parent?.let { Files.createDirectories(it) }

    return runCatching {
        page.screenshot(
            Page.ScreenshotOptions()
                .setPath(outputPath)
                .setClip(Clip(clip.x, clip.y, clip.w, clip.h))
        )
        true
    }.onFailure {
        lprintln("warning,screenshot", "Playwright screenshot failed for ${id}: ${it.message}")
    }.getOrElse { false }
}
