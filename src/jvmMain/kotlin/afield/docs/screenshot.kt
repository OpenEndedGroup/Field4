package afield

import afield.logger.lprintln
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.Clip
import com.microsoft.playwright.options.ScreenshotType
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.awt.image.Raster
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import javax.imageio.ImageIO

private const val SCREENSHOT_PREP_TIMEOUT_MS = 12_000
private const val SCREENSHOT_POLL_INTERVAL_MS = 60
private const val SCREENSHOT_OUTPUT_GRACE_MS = 2_500
private const val PLAYWRIGHT_SCREENSHOT_TIMEOUT_MS = 15_000.0

data class UniformEdgeTrimResult(
    val trimmed: Boolean,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

private fun isUniformSamples(samples: IntArray, bands: Int): Boolean {
    if (samples.size <= bands) {
        return true
    }
    for (index in bands until samples.size step bands) {
        for (band in 0 until bands) {
            if (samples[index + band] != samples[band]) {
                return false
            }
        }
    }
    return true
}

private fun isUniformRow(raster: Raster, y: Int, left: Int, right: Int): Boolean {
    if (right <= left) {
        return true
    }
    val samples = raster.getPixels(left, y, right - left + 1, 1, null as IntArray?)
    return isUniformSamples(samples, raster.numBands)
}

private fun isUniformColumn(raster: Raster, x: Int, top: Int, bottom: Int): Boolean {
    if (bottom <= top) {
        return true
    }
    val samples = raster.getPixels(x, top, 1, bottom - top + 1, null as IntArray?)
    return isUniformSamples(samples, raster.numBands)
}

fun trimUniformEdgePixels(path: Path): UniformEdgeTrimResult {
    val filename = path.fileName?.toString()?.lowercase() ?: return UniformEdgeTrimResult(trimmed = false)
    if (!filename.endsWith(".png")) {
        return UniformEdgeTrimResult(trimmed = false)
    }

    val image = ImageIO.read(path.toFile()) ?: return UniformEdgeTrimResult(trimmed = false)
    if (image.width <= 1 || image.height <= 1) {
        return UniformEdgeTrimResult(trimmed = false)
    }
    val raster = image.raster

    var left = 0
    var right = image.width - 1
    var top = 0
    var bottom = image.height - 1

    while (top <= bottom && isUniformRow(raster, top, left, right)) {
        top += 1
    }
    while (bottom >= top && isUniformRow(raster, bottom, left, right)) {
        bottom -= 1
    }
    while (left <= right && top <= bottom && isUniformColumn(raster, left, top, bottom)) {
        left += 1
    }
    while (right >= left && top <= bottom && isUniformColumn(raster, right, top, bottom)) {
        right -= 1
    }

    if (left == 0 && top == 0 && right == image.width - 1 && bottom == image.height - 1) {
        return UniformEdgeTrimResult(trimmed = false)
    }
    if (left > right || top > bottom) {
        return UniformEdgeTrimResult(trimmed = false)
    }

    val trimmedWidth = right - left + 1
    val trimmedHeight = bottom - top + 1
    val trimmedImage = image.getSubimage(left, top, trimmedWidth, trimmedHeight)
    if (!ImageIO.write(trimmedImage, "png", path.toFile())) {
        return UniformEdgeTrimResult(trimmed = false)
    }

    return UniformEdgeTrimResult(
        trimmed = true,
        left = left,
        top = top,
        right = image.width - 1 - right,
        bottom = image.height - 1 - bottom
    )
}

private fun postProcessScreenshot(path: Path) {
    runCatching {
        trimUniformEdgePixels(path)
    }.onSuccess { result ->
        if (result.trimmed) {
            lprintln(
                "info,screenshot",
                "Trimmed uniform border from $path (left=${result.left}, top=${result.top}, right=${result.right}, bottom=${result.bottom})"
            )
        }
    }.onFailure {
        lprintln("warning,screenshot", "Uniform-edge trim failed for $path: ${it.message}")
    }
}

private fun BoxScreenshotRequest.toPlaywrightArg(): Map<String, Any?> = mapOf(
    "uid" to uid,
    "padding" to padding,
    "panIntoView" to panIntoView,
    "hideMonaco" to hideMonaco,
    "openOutputs" to openOutputs
)

private fun WindowScreenshotRequest.toPlaywrightArg(): Map<String, Any?> = mapOf(
    "uid" to uid,
    "property" to property,
    "panIntoView" to panIntoView,
    "showEditor" to showEditor,
    "openOutputs" to openOutputs,
    "editorOnly" to editorOnly,
    "padding" to padding
)

private fun requestBoxScreenshot(page: Page, req: BoxScreenshotRequest): BoxScreenshotResponse {
    val raw = page.evaluate(
        """
        async (request) => {
          const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
          const nextFrame = () => new Promise((resolve) => requestAnimationFrame(() => resolve(null)));
          const settle = async (frames) => {
            for (let index = 0; index < frames; index += 1) {
              await nextFrame();
            }
          };
          const findBox = (uid) => {
            const boxes = Array.from((document.body && document.body.getElementsByClassName("box")) || []);
            return boxes.find((element) => {
              const candidate = element.uid || element.getAttribute("uid") || element.getAttribute("data-uid");
              return candidate === uid;
            }) || null;
          };
          const pageReady = () => {
            const currentUrl = String(window.location && window.location.href ? window.location.href : "");
            return !!document.body &&
              !!document.getElementById("overlaycanvas") &&
              !currentUrl.startsWith("about:") &&
              window.fieldClientOpen === true &&
              window.fieldBackdoorDisconnectedOverlayActive !== true &&
              window.fieldBackdoorUiReady === true &&
              typeof window.fieldBackdoorPrepareBoxScreenshot === "function";
          };
          const decode = (value) => {
            if (typeof value !== "string") {
              return null;
            }
            try {
              return JSON.parse(value);
            } catch (error) {
              return null;
            }
          };
          const blockedStatusText = (value) => {
            const text = String(value || "").trim().toLowerCase();
            if (!text) {
              return false;
            }
            return text.includes("connecting") ||
              text.includes("loading") ||
              text.includes("disconnected") ||
              text.includes("waiting for audio") ||
              text.includes("waiting for state");
          };
          const iframeReady = (iframe) => {
            try {
              const doc = iframe.contentDocument;
              if (!doc || doc.readyState !== "complete") {
                return false;
              }
              const statusEl = doc.getElementById("status");
              return !blockedStatusText(statusEl && statusEl.textContent);
            } catch (error) {
              return true;
            }
          };
          const outputsReady = (root) => {
            if (!root) {
              return false;
            }
            const outputArea = root.querySelector(".outputArea");
            if (!outputArea) {
              return false;
            }
            const iframes = Array.from(outputArea.querySelectorAll("iframe"));
            if (iframes.some((iframe) => !iframeReady(iframe))) {
              return false;
            }
            const statusTexts = Array.from(outputArea.querySelectorAll("svg text, .status, #status"))
              .map((node) => String(node.textContent || "").trim())
              .filter((text) => text.length > 0);
            return !statusTexts.some(blockedStatusText);
          };
          const deadline = Date.now() + ${SCREENSHOT_PREP_TIMEOUT_MS};
          let lastMessage = "Timed out waiting for box screenshot prep";
          while (Date.now() < deadline) {
            if (!pageReady()) {
              lastMessage = "Field page is not connected yet";
              await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
              continue;
            }
            const box = findBox(request.uid);
            if (!box) {
              lastMessage = `box ${'$'}{request.uid} not found`;
              await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
              continue;
            }
            if (request.openOutputs) {
              const outputDeadline = Date.now() + ${SCREENSHOT_OUTPUT_GRACE_MS};
              while (Date.now() < outputDeadline) {
                if (!pageReady()) {
                  break;
                }
                const currentBox = findBox(request.uid);
                if (currentBox && outputsReady(currentBox)) {
                  break;
                }
                await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
              }
            }
            await settle(request.openOutputs ? 3 : 1);
            const fn = window.fieldBackdoorPrepareBoxScreenshot;
            if (typeof fn !== "function") {
              lastMessage = "fieldBackdoorPrepareBoxScreenshot not ready";
              await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
              continue;
            }
            const prepared = await Promise.resolve(fn(request));
            const decoded = decode(prepared);
            if (decoded && decoded.success) {
              await settle(request.openOutputs ? 2 : 1);
              if (!pageReady()) {
                lastMessage = "Field page disconnected during screenshot prep";
                await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
                continue;
              }
              return JSON.stringify(decoded);
            }
            const message = decoded && typeof decoded.message === "string"
              ? decoded.message
              : "Box screenshot preparation failed";
            lastMessage = message;
            const transient = message.includes("not found") ||
              message.includes("no geometry") ||
              message.includes("invalid clip");
            if (!transient) {
              return typeof prepared === "string"
                ? prepared
                : JSON.stringify({
                    uid: request && request.uid ? request.uid : "",
                    success: false,
                    message
                  });
            }
            await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
          }
          return JSON.stringify({
            uid: request && request.uid ? request.uid : "",
            success: false,
            message: lastMessage
          });
        }
        """.trimIndent(),
        req.toPlaywrightArg()
    ) as? String ?: error("Box screenshot preparation returned a non-string payload")
    return Json.decodeFromString(raw)
}

private fun requestWindowScreenshot(page: Page, req: WindowScreenshotRequest): WindowScreenshotResponse {
    val raw = page.evaluate(
        """
        async (request) => {
          const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
          const nextFrame = () => new Promise((resolve) => requestAnimationFrame(() => resolve(null)));
          const settle = async (frames) => {
            for (let index = 0; index < frames; index += 1) {
              await nextFrame();
            }
          };
          const findBox = (uid) => {
            const boxes = Array.from((document.body && document.body.getElementsByClassName("box")) || []);
            return boxes.find((element) => {
              const candidate = element.uid || element.getAttribute("uid") || element.getAttribute("data-uid");
              return candidate === uid;
            }) || null;
          };
          const pageReady = () => {
            const currentUrl = String(window.location && window.location.href ? window.location.href : "");
            return !!document.body &&
              !!document.getElementById("overlaycanvas") &&
              !currentUrl.startsWith("about:") &&
              window.fieldClientOpen === true &&
              window.fieldBackdoorDisconnectedOverlayActive !== true &&
              window.fieldBackdoorUiReady === true &&
              typeof window.fieldBackdoorPrepareWindowScreenshot === "function";
          };
          const decode = (value) => {
            if (typeof value !== "string") {
              return null;
            }
            try {
              return JSON.parse(value);
            } catch (error) {
              return null;
            }
          };
          const blockedStatusText = (value) => {
            const text = String(value || "").trim().toLowerCase();
            if (!text) {
              return false;
            }
            return text.includes("connecting") ||
              text.includes("loading") ||
              text.includes("disconnected") ||
              text.includes("waiting for audio") ||
              text.includes("waiting for state");
          };
          const iframeReady = (iframe) => {
            try {
              const doc = iframe.contentDocument;
              if (!doc || doc.readyState !== "complete") {
                return false;
              }
              const statusEl = doc.getElementById("status");
              return !blockedStatusText(statusEl && statusEl.textContent);
            } catch (error) {
              return true;
            }
          };
          const outputsReady = (root) => {
            if (!root) {
              return false;
            }
            const outputArea = root.querySelector(".outputArea");
            if (!outputArea) {
              return false;
            }
            const iframes = Array.from(outputArea.querySelectorAll("iframe"));
            if (iframes.some((iframe) => !iframeReady(iframe))) {
              return false;
            }
            const statusTexts = Array.from(outputArea.querySelectorAll("svg text, .status, #status"))
              .map((node) => String(node.textContent || "").trim())
              .filter((text) => text.length > 0);
            return !statusTexts.some(blockedStatusText);
          };
          if (request.showEditor && !request.uid) {
            return JSON.stringify({
              uid: null,
              success: false,
              message: "showEditor requires a target box"
            });
          }
          const deadline = Date.now() + ${SCREENSHOT_PREP_TIMEOUT_MS};
          let lastMessage = "Timed out waiting for window screenshot prep";
          while (Date.now() < deadline) {
            if (!pageReady()) {
              lastMessage = "Field page is not connected yet";
              await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
              continue;
            }
            if (request.uid) {
              const box = findBox(request.uid);
              if (!box) {
                lastMessage = `box ${'$'}{request.uid} not found`;
                await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
                continue;
              }
              if (request.openOutputs) {
                const outputDeadline = Date.now() + ${SCREENSHOT_OUTPUT_GRACE_MS};
                while (Date.now() < outputDeadline) {
                  if (!pageReady()) {
                    break;
                  }
                  const currentBox = findBox(request.uid);
                  if (currentBox && outputsReady(currentBox)) {
                    break;
                  }
                  await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
                }
              }
            }
            await settle(request.openOutputs || request.showEditor ? 5 : 1);
            const fn = window.fieldBackdoorPrepareWindowScreenshot;
            if (typeof fn !== "function") {
              lastMessage = "fieldBackdoorPrepareWindowScreenshot not ready";
              await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
              continue;
            }
            const prepared = await Promise.resolve(fn(request));
            const decoded = decode(prepared);
            if (decoded && decoded.success) {
              await settle(request.openOutputs || request.showEditor ? 3 : 1);
              if (!pageReady()) {
                lastMessage = "Field page disconnected during screenshot prep";
                await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
                continue;
              }
              return prepared;
            }
            const message = decoded && typeof decoded.message === "string"
              ? decoded.message
              : "Window screenshot preparation failed";
            lastMessage = message;
            const transient = message.includes("not found") ||
              message.includes("not visible") ||
              message.includes("not synced yet") ||
              message.includes("no geometry") ||
              message.includes("invalid clip");
            if (!transient) {
              return typeof prepared === "string"
                ? prepared
                : JSON.stringify({
                    uid: request && request.uid ? request.uid : null,
                    success: false,
                    message
                  });
            }
            await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
          }
          return JSON.stringify({
            uid: request && request.uid ? request.uid : null,
            success: false,
            message: lastMessage
          });
        }
        """.trimIndent(),
        req.toPlaywrightArg()
    ) as? String ?: error("Window screenshot preparation returned a non-string payload")
    return Json.decodeFromString(raw)
}

suspend fun Box.screenshot(
    fn: String,
    padding: Double = 12.0,
    panIntoView: Boolean = true,
    hideMonaco: Boolean = true,
    openOutputs: Boolean = false
): Boolean {
    val page = globalPlaywriteContext ?: run {
        lprintln("warning,screenshot", "Playwright is not active; cannot screenshot ${id}")
        return false
    }

    val response = runCatching {
        requestBoxScreenshot(
            page,
            BoxScreenshotRequest(
                uid = id,
                padding = padding,
                panIntoView = panIntoView,
                hideMonaco = hideMonaco,
                openOutputs = openOutputs
            )
        )
    }.getOrElse {
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
        postProcessScreenshot(outputPath)
        true
    }.onFailure {
        lprintln("warning,screenshot", "Playwright screenshot failed for ${id}: ${it.message}")
    }.getOrElse { false }
}

private fun failedAgentScreenshot(
    info: SessionInformation,
    kind: String,
    path: String,
    uid: String? = null,
    message: String
) = AgentScreenshotResponse(
    ok = false,
    kind = kind,
    path = path,
    uid = uid,
    documentName = info.documentName,
    scratch = info.ephemeral,
    sessionKey = info.filename,
    message = message
)

suspend fun captureWindowScreenshot(info: SessionInformation, request: AgentWindowScreenshot): AgentScreenshotResponse {
    val kind = if (request.editorOnly) "editor" else "window"
    val page = globalPlaywriteContext ?: return failedAgentScreenshot(
        info = info,
        kind = kind,
        path = request.path,
        uid = request.uid,
        message = "Playwright is not active"
    )

    val response = runCatching {
        requestWindowScreenshot(
            page,
            WindowScreenshotRequest(
                uid = request.uid,
                property = request.property,
                panIntoView = request.panIntoView,
                showEditor = request.showEditor,
                openOutputs = request.openOutputs,
                editorOnly = request.editorOnly,
                padding = request.padding
            )
        )
    }.getOrElse {
        lprintln("warning,screenshot", "Timed out during $kind screenshot prep for ${request.uid ?: "<viewport>"}: ${it.message}")
        return failedAgentScreenshot(
            info = info,
            kind = kind,
            path = request.path,
            uid = request.uid,
            message = "Timed out waiting for window screenshot prep: ${it.message}"
        )
    }

    if (!response.success) {
        return failedAgentScreenshot(
            info = info,
            kind = kind,
            path = request.path,
            uid = request.uid,
            message = response.message
        )
    }

    val clip = response.clip
    if (request.editorOnly && clip == null) {
        return failedAgentScreenshot(
            info = info,
            kind = kind,
            path = request.path,
            uid = request.uid,
            message = "Editor screenshot prep did not return a clip"
        )
    }

    val outputPath = Path.of(request.path)
    outputPath.parent?.let { Files.createDirectories(it) }

    return runCatching {
        val options = Page.ScreenshotOptions()
            .setPath(outputPath)
            .setTimeout(PLAYWRIGHT_SCREENSHOT_TIMEOUT_MS)
        if (clip != null) {
            options.setClip(Clip(clip.x, clip.y, clip.w, clip.h))
        }
        page.screenshot(options)
        postProcessScreenshot(outputPath)
        AgentScreenshotResponse(
            ok = true,
            kind = kind,
            path = outputPath.toString(),
            uid = request.uid,
            documentName = info.documentName,
            scratch = info.ephemeral,
            sessionKey = info.filename,
            clip = clip,
            viewport = response.viewport
        )
    }.onFailure {
        lprintln("warning,screenshot", "Window screenshot failed for ${request.path}: ${it.message}")
    }.getOrElse {
        failedAgentScreenshot(
            info = info,
            kind = kind,
            path = outputPath.toString(),
            uid = request.uid,
            message = it.message ?: "Playwright screenshot failed"
        )
    }
}

suspend fun openEditorForCapture(info: SessionInformation, request: AgentOpenEditorRequest): AgentOpenEditorResponse {
    val page = globalPlaywriteContext ?: return AgentOpenEditorResponse(
        ok = false,
        uid = request.uid,
        property = request.property,
        documentName = info.documentName,
        scratch = info.ephemeral,
        sessionKey = info.filename,
        message = "Playwright is not active"
    )

    val response = runCatching {
        requestWindowScreenshot(
            page,
            WindowScreenshotRequest(
                uid = request.uid,
                property = request.property,
                panIntoView = request.panIntoView,
                showEditor = true,
                openOutputs = request.openOutputs,
                editorOnly = false,
                padding = request.padding
            )
        )
    }.getOrElse {
        return AgentOpenEditorResponse(
            ok = false,
            uid = request.uid,
            property = request.property,
            documentName = info.documentName,
            scratch = info.ephemeral,
            sessionKey = info.filename,
            message = "Timed out waiting for editor prep: ${it.message}"
        )
    }

    return AgentOpenEditorResponse(
        ok = response.success,
        uid = request.uid,
        property = request.property,
        documentName = info.documentName,
        scratch = info.ephemeral,
        sessionKey = info.filename,
        message = response.message
    )
}

suspend fun captureBoxScreenshot(info: SessionInformation, request: AgentBoxScreenshot): AgentScreenshotResponse {
    val page = globalPlaywriteContext ?: return failedAgentScreenshot(
        info = info,
        kind = "box",
        path = request.path,
        uid = request.uid,
        message = "Playwright is not active"
    )

    val response = runCatching {
        requestBoxScreenshot(
            page,
            BoxScreenshotRequest(
                uid = request.uid,
                padding = request.padding,
                panIntoView = request.panIntoView,
                hideMonaco = true,
                openOutputs = request.openOutputs
            )
        )
    }.getOrElse {
        lprintln("warning,screenshot", "Timed out during box screenshot prep for ${request.uid}: ${it.message}")
        return failedAgentScreenshot(
            info = info,
            kind = "box",
            path = request.path,
            uid = request.uid,
            message = "Timed out waiting for box screenshot prep: ${it.message}"
        )
    }

    val clip = response.clip
    if (!response.success || clip == null) {
        return failedAgentScreenshot(
            info = info,
            kind = "box",
            path = request.path,
            uid = request.uid,
            message = response.message.ifEmpty { "Screenshot prep failed for ${request.uid}" }
        )
    }

    val outputPath = Path.of(request.path)
    outputPath.parent?.let { Files.createDirectories(it) }

    return runCatching {
        page.screenshot(
            Page.ScreenshotOptions()
                .setPath(outputPath)
                .setTimeout(PLAYWRIGHT_SCREENSHOT_TIMEOUT_MS)
                .setClip(Clip(clip.x, clip.y, clip.w, clip.h))
        )
        postProcessScreenshot(outputPath)
        AgentScreenshotResponse(
            ok = true,
            kind = "box",
            path = outputPath.toString(),
            uid = request.uid,
            documentName = info.documentName,
            scratch = info.ephemeral,
            sessionKey = info.filename,
            clip = clip,
            viewport = response.viewport
        )
    }.onFailure {
        lprintln("warning,screenshot", "Box screenshot failed for ${request.uid}: ${it.message}")
    }.getOrElse {
        failedAgentScreenshot(
            info = info,
            kind = "box",
            path = outputPath.toString(),
            uid = request.uid,
            message = it.message ?: "Playwright screenshot failed"
        )
    }
}

// ---------------------------------------------------------------------------
// Video recording via CDP screencast → ffmpeg H.264 MP4
// ---------------------------------------------------------------------------

private const val VIDEO_POLL_INTERVAL_MS = 10L
private const val VIDEO_MAX_DIMENSION = 1920

private fun failedAgentVideo(
    info: SessionInformation,
    kind: String,
    path: String,
    uid: String? = null,
    message: String,
) = AgentVideoResponse(
    ok = false,
    kind = kind,
    path = path,
    uid = uid,
    documentName = info.documentName,
    scratch = info.ephemeral,
    sessionKey = info.filename,
    message = message
)

/**
 * Record a window-level video (full page or editor-only clip)
 * using CDP Page.startScreencast, then encode the JPEG frames
 * to an H.264 MP4 via ffmpeg.
 */
suspend fun captureWindowVideo(info: SessionInformation, request: AgentWindowVideo): AgentVideoResponse {
    val kind = if (request.editorOnly) "editor" else "window"
    val page = globalPlaywriteContext ?: return failedAgentVideo(
        info = info, kind = kind, path = request.path, uid = request.uid,
        message = "Playwright is not active"
    )

    // Prepare the view (select box, open editor, pan, etc.) using the same
    // prep logic as screenshots.
    val prepResponse = runCatching {
        requestWindowScreenshot(
            page,
            WindowScreenshotRequest(
                uid = request.uid,
                property = request.property,
                panIntoView = request.panIntoView,
                showEditor = request.showEditor,
                openOutputs = request.openOutputs,
                editorOnly = request.editorOnly,
                padding = request.padding
            )
        )
    }.getOrElse {
        return failedAgentVideo(
            info = info, kind = kind, path = request.path, uid = request.uid,
            message = "Timed out waiting for video prep: ${it.message}"
        )
    }

    if (!prepResponse.success) {
        return failedAgentVideo(
            info = info, kind = kind, path = request.path, uid = request.uid,
            message = prepResponse.message
        )
    }

    val clip = prepResponse.clip
    if (request.editorOnly && clip == null) {
        return failedAgentVideo(
            info = info, kind = kind, path = request.path, uid = request.uid,
            message = "Editor video prep did not return a clip"
        )
    }

    val outputPath = Path.of(request.path)
    outputPath.parent?.let { Files.createDirectories(it) }

    return runCatching {
        val result = recordScreencastToMp4(
            page = page,
            outputPath = outputPath,
            clip = clip,
            durationSec = request.durationSec,
            fps = request.fps
        )
        AgentVideoResponse(
            ok = true,
            kind = kind,
            path = outputPath.toString(),
            uid = request.uid,
            documentName = info.documentName,
            scratch = info.ephemeral,
            sessionKey = info.filename,
            durationSec = request.durationSec,
            frameCount = result.frameCount,
            clip = clip,
            viewport = prepResponse.viewport
        )
    }.onFailure {
        lprintln("warning,video", "Window video failed for ${request.path}: ${it.message}")
    }.getOrElse {
        failedAgentVideo(
            info = info, kind = kind, path = outputPath.toString(), uid = request.uid,
            message = it.message ?: "Video recording failed"
        )
    }
}

/**
 * Record a box-level video (clipped to the box region).
 */
suspend fun captureBoxVideo(info: SessionInformation, request: AgentBoxVideo): AgentVideoResponse {
    val page = globalPlaywriteContext ?: return failedAgentVideo(
        info = info, kind = "box", path = request.path, uid = request.uid,
        message = "Playwright is not active"
    )

    val prepResponse = runCatching {
        requestBoxScreenshot(
            page,
            BoxScreenshotRequest(
                uid = request.uid,
                padding = request.padding,
                panIntoView = request.panIntoView,
                hideMonaco = true,
                openOutputs = request.openOutputs
            )
        )
    }.getOrElse {
        return failedAgentVideo(
            info = info, kind = "box", path = request.path, uid = request.uid,
            message = "Timed out waiting for box video prep: ${it.message}"
        )
    }

    val clip = prepResponse.clip
    if (!prepResponse.success || clip == null) {
        return failedAgentVideo(
            info = info, kind = "box", path = request.path, uid = request.uid,
            message = prepResponse.message.ifEmpty { "Video prep failed for ${request.uid}" }
        )
    }

    val outputPath = Path.of(request.path)
    outputPath.parent?.let { Files.createDirectories(it) }

    return runCatching {
        val result = recordScreencastToMp4(
            page = page,
            outputPath = outputPath,
            clip = clip,
            durationSec = request.durationSec,
            fps = request.fps
        )
        AgentVideoResponse(
            ok = true,
            kind = "box",
            path = outputPath.toString(),
            uid = request.uid,
            documentName = info.documentName,
            scratch = info.ephemeral,
            sessionKey = info.filename,
            durationSec = request.durationSec,
            frameCount = result.frameCount,
            clip = clip,
            viewport = prepResponse.viewport
        )
    }.onFailure {
        lprintln("warning,video", "Box video failed for ${request.uid}: ${it.message}")
    }.getOrElse {
        failedAgentVideo(
            info = info, kind = "box", path = outputPath.toString(), uid = request.uid,
            message = it.message ?: "Video recording failed"
        )
    }
}

private data class ScreencastResult(val frameCount: Int)

/**
 * Use CDP Page.startScreencast to capture JPEG frames from the Playwright
 * page, then pipe them into ffmpeg to produce an H.264/MP4 file.
 *
 * When [clip] is non-null the captured frames are cropped to that region
 * by ffmpeg's crop filter (coordinates are in CSS pixels, scaled by the
 * device-pixel-ratio reported by the page).
 */
private fun recordScreencastToMp4(
    page: Page,
    outputPath: Path,
    clip: Rect?,
    durationSec: Double,
    fps: Int,
): ScreencastResult {
    val frames = mutableListOf<ByteArray>()
  val safeFps = fps.coerceAtLeast(1)
  val targetFrames = maxOf(1, kotlin.math.ceil(durationSec * safeFps).toInt())
  val frameIntervalNs = (1_000_000_000.0 / safeFps.toDouble()).toLong().coerceAtLeast(1L)

  repeat(targetFrames) { index ->
    val frameStart = System.nanoTime()
    val screenshotOptions = Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(90)
    if (clip != null) {
      screenshotOptions.setClip(Clip(clip.x, clip.y, clip.w, clip.h))
    }
    val bytes = page.screenshot(screenshotOptions)
    synchronized(frames) {
      frames.add(bytes)
    }

    val elapsedNs = System.nanoTime() - frameStart
    val sleepNs = frameIntervalNs - elapsedNs
    if (sleepNs > 0 && index < targetFrames - 1) {
      val sleepMs = sleepNs / 1_000_000L
      val sleepRemainderNs = (sleepNs % 1_000_000L).toInt()
      Thread.sleep(sleepMs, sleepRemainderNs)
    }
  }

  val capturedFrames = synchronized(frames) { frames.toList() }
  if (capturedFrames.isEmpty()) {
    throw IllegalStateException("Video capture sampled 0 frames in ${durationSec}s")
  }

  lprintln("info,video", "Captured ${capturedFrames.size} sampled frames in ${durationSec}s")

    // Build ffmpeg command: pipe JPEG frames via stdin, encode to H.264 MP4
    val ffmpeg = FFMPEG

    val filterParts = mutableListOf<String>()
    // Ensure dimensions are even (required by yuv420p)
    filterParts.add("pad=ceil(iw/2)*2:ceil(ih/2)*2")
    // Normalize to a constant output frame rate for stable playback duration.
    filterParts.add("fps=$fps")

    val args = buildList {
        addAll(listOf(ffmpeg, "-y"))
        // Input: concatenated JPEGs from stdin
        addAll(listOf("-f", "image2pipe", "-vcodec", "mjpeg", "-framerate", String.format("%.2f", safeFps.toDouble()), "-i", "-"))
        // Video filter
        addAll(listOf("-vf", filterParts.joinToString(",")))
        // Encoding
        addAll(listOf(
            "-c:v", "libx264",
            "-crf", "23",
            "-preset", "fast",
            "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            "-an"
        ))
        add(outputPath.toString())
    }

      fun runFfmpegEncode(arguments: List<String>): Pair<Int, String> {
        val process = ProcessBuilder(arguments)
          .redirectErrorStream(true)
          .start()

        // Write all JPEG frames to ffmpeg stdin
        process.outputStream.use { stdin ->
          for (frame in capturedFrames) {
            stdin.write(frame)
          }
        }

        val log = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return exitCode to log
      }

      val (exitCode, log) = runFfmpegEncode(args)
      if (exitCode != 0) {
        throw IllegalStateException("ffmpeg encoding failed (exit=$exitCode):\n${args.joinToString(" ")}\n$log")
      }

    lprintln("info,video", "Encoded ${outputPath}: ${capturedFrames.size} source frames, ${durationSec}s, output ${fps} fps")

    return ScreencastResult(frameCount = capturedFrames.size)
}
