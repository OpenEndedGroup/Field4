package afield

import afield.logger.lprintln
import afield.stdlib._fieldProbe
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.Clip
import com.microsoft.playwright.options.ScreenshotType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import java.awt.image.Raster
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.UUID
import javax.imageio.ImageIO

private const val SCREENSHOT_PREP_TIMEOUT_MS = 12_000
private const val SCREENSHOT_POLL_INTERVAL_MS = 60
private const val SCREENSHOT_OUTPUT_GRACE_MS = 2_500

/**
 * How long to wait for a box's output layout to STOP MOVING before measuring and shooting.
 *
 * `wait=` in a fence is an author's guess at how long content takes to appear, and a guess that is
 * too short produces two failures at once: the picture is of a half-built widget, and the clip is
 * measured from a container that had not finished growing. Both shipped -- a waveform published as a
 * blur, and a signal-panel capture with a thousand pixels of empty black below its content.
 */
private const val SCREENSHOT_STABLE_TIMEOUT_MS = 8_000

/** Consecutive equal geometry samples that count as "layout has settled". */
private const val SCREENSHOT_STABLE_SAMPLES = 3
private const val PLAYWRIGHT_SCREENSHOT_TIMEOUT_MS = 15_000.0
private const val PLAYWRIGHT_VIDEO_FRAME_TIMEOUT_MS = 120_000.0

/** Fraction of a recording's consecutive frame pairs that must differ materially. */
private const val MIN_VIDEO_MOVED_FRACTION = 0.20

/**
 * Thumbnail width used to compare frames, chosen by measurement rather than taste.
 *
 * Too coarse and the change vanishes: an inline visualizer decoration occupies a tiny part of a
 * 1680x2440 editor capture, and at 32 wide a replica of this metric scored the *fixed* recordings
 * 0 of 35 -- it would have failed every video. At 128 the same replica separates cleanly, scoring
 * the broken recordings 3 of 35 and the fixed ones 15-17 of 35 against a floor of 7.
 */
private const val SIGNATURE_WIDTH = 128

/** Per-cell grey difference that counts as a change, out of 255. */
private const val SIGNATURE_CELL_TOLERANCE = 8

/** Fraction of thumbnail cells that must change for a frame pair to count as moved. */
private const val SIGNATURE_MOVED_FRACTION = 0.005

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

  private fun responseString(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    else -> value.toString()
  }

  private fun responseDouble(map: Map<*, *>, key: String): Double? =
    (map[key] as? Number)?.toDouble()

  private fun locateBoxPoint(page: Page, uid: String): Pair<Double, Double> {
    val raw = page.evaluate(
      """
      async (uid) => {
        const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
        const findBox = (targetUid) => {
        const boxes = Array.from((document.body && document.body.getElementsByClassName("box")) || []);
        return boxes.find((element) => {
          const candidate = element.uid || element.getAttribute("uid") || element.getAttribute("data-uid");
          return candidate === targetUid;
        }) || null;
        };
        const deadline = Date.now() + ${SCREENSHOT_PREP_TIMEOUT_MS};
        while (Date.now() < deadline) {
        const box = findBox(uid);
        if (box) {
          const rect = box.getBoundingClientRect();
          if (rect.width > 0 && rect.height > 0) {
          return {
            x: rect.left + rect.width / 2,
            y: rect.top + rect.height / 2,
          };
          }
        }
        await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
        }
        return null;
      }
      """.trimIndent(),
      uid
    )
    val point = raw as? Map<*, *> ?: error("Box $uid was not found in the current page")
    val x = responseDouble(point, "x") ?: error("Box $uid did not provide an x coordinate")
    val y = responseDouble(point, "y") ?: error("Box $uid did not provide a y coordinate")
    return x to y
  }

  private fun moveMouseInSteps(page: Page, startX: Double, startY: Double, endX: Double, endY: Double, steps: Int) {
    val safeSteps = maxOf(1, steps)
    for (step in 1..safeSteps) {
      val t = step.toDouble() / safeSteps.toDouble()
      val x = startX + (endX - startX) * t
      val y = startY + (endY - startY) * t
      page.mouse().move(x, y)
      page.waitForTimeout(16.0)
    }
  }

  private fun focusFieldPage(page: Page) {
    page.evaluate(
      """
      () => {
        if (window.focus) {
          window.focus();
        }
        const active = document.activeElement;
        const hasSpecificFocus = active &&
          active !== document.body &&
          active !== document.documentElement;
        if (!hasSpecificFocus && document.body && document.body.focus) {
          document.body.focus();
        }
        return true;
      }
      """.trimIndent()
    )
  }

  private fun evaluatePageScript(page: Page, request: AgentPlaywrightInputRequest): Pair<JsonElement, String> {
    val source = request.script ?: error("pageEval requires script")
    val mode = request.scriptMode.ifBlank { "expression" }
    if (mode !in setOf("expression", "body")) {
      error("pageEval scriptMode must be 'expression' or 'body'")
    }

    val raw = page.evaluate(
      """
      async ({ source, mode }) => {
        const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
        const replacer = (_key, value) => {
          if (typeof value === "bigint") return value.toString();
          if (typeof value === "function") return `[Function ${'$'}{value.name || "anonymous"}]`;
          if (value instanceof Element) {
            return {
              tagName: value.tagName,
              id: value.id || "",
              className: typeof value.className === "string" ? value.className : "",
              textContent: value.textContent || ""
            };
          }
          return value;
        };

        const value = mode === "body"
          ? await new AsyncFunction(source).call(window)
          : await (0, eval)(source);

        if (value === undefined) {
          return { ok: true, json: "null", message: "Evaluation returned undefined" };
        }

        const json = JSON.stringify(value, replacer);
        if (json === undefined) {
          return { ok: true, json: "null", message: `Evaluation returned ${'$'}{typeof value}` };
        }
        return { ok: true, json, message: "" };
      }
      """.trimIndent(),
      mapOf("source" to source, "mode" to mode)
    )
    val result = raw as? Map<*, *> ?: error("pageEval returned an invalid response")
    if (result["ok"] != true) {
      error(responseString(result["message"]).ifBlank { "pageEval failed" })
    }
    val json = responseString(result["json"]).ifBlank { "null" }
    val message = responseString(result["message"])
    return Json.parseToJsonElement(json) to message
  }

  private fun performWindowAction(page: Page, request: AgentPlaywrightInputRequest): Pair<JsonElement?, String> {
    when (request.action) {
      "keyPress" -> {
        val key = request.key ?: error("keyPress requires a key")
        val selector = request.selector
        if (!selector.isNullOrBlank()) page.locator(selector).press(key) else page.keyboard().press(key)
      }
      "keyDown" -> {
        val key = request.key ?: error("keyDown requires a key")
        val selector = request.selector
        if (!selector.isNullOrBlank()) {
          page.locator(selector).focus()
        }
        page.keyboard().down(key)
      }
      "keyUp" -> {
        val key = request.key ?: error("keyUp requires a key")
        val selector = request.selector
        if (!selector.isNullOrBlank()) {
          page.locator(selector).focus()
        }
        page.keyboard().up(key)
      }
      "typeText" -> {
        // Type a run of characters through the real keyboard so that per-key
        // handlers (autocomplete, etc.) fire exactly as they do for a human.
        val text = request.script ?: request.key ?: error("typeText requires text (script or key)")
        val selector = request.selector
        if (!selector.isNullOrBlank()) page.locator(selector).focus()
        page.keyboard().type(text)
      }
      "clickBox" -> {
        val uid = request.uid ?: error("clickBox requires a uid")
        val (x, y) = locateBoxPoint(page, uid)
        page.mouse().move(x, y)
        page.mouse().click(x, y)
      }
      "clickSelector" -> {
        val selector = request.selector ?: error("clickSelector requires a selector")
        page.locator(selector).click()
      }
      "hover" -> {
        val selector = request.selector
        val uid = request.uid
        when {
          !selector.isNullOrBlank() -> page.locator(selector).hover()
          !uid.isNullOrBlank() -> {
            val (x, y) = locateBoxPoint(page, uid)
            page.mouse().move(x, y)
          }
          else -> error("hover requires a selector or uid")
        }
      }
      "dragBox" -> {
        val uid = request.uid ?: error("dragBox requires a uid")
        val (startX, startY) = locateBoxPoint(page, uid)
        val endX = startX + request.dx
        val endY = startY + request.dy
        page.mouse().move(startX, startY)
        page.mouse().down()
        moveMouseInSteps(page, startX, startY, endX, endY, request.steps)
        if (!request.hold) {
          page.mouse().up()
        }
      }
      "mouseUp" -> page.mouse().up()
      "showHistory" -> {
        val raw = page.evaluate(
          """
          (filename) => {
            const target = filename || window.currentDocumentFilename || "default";
            if (typeof window.switchToHistoryForFile !== "function") {
            return { ok: false, message: "switchToHistoryForFile is unavailable" };
            }
            window.switchToHistoryForFile(target);
            return { ok: true, message: target };
          }
          """.trimIndent(),
          request.filename
        )
        val result = raw as? Map<*, *> ?: error("showHistory returned an invalid response")
        if (result["ok"] != true) {
          error(responseString(result["message"]).ifBlank { "showHistory failed" })
        }
      }
      "pageEval" -> {
        val result = evaluatePageScript(page, request)
        if (request.waitMs > 0.0) {
          page.waitForTimeout(request.waitMs)
        }
        return result
      }
      else -> error("Unsupported Playwright action '${request.action}'")
    }

    if (request.waitMs > 0.0) {
      page.waitForTimeout(request.waitMs)
    }
    return null to ""
  }

  fun performPlaywrightInput(info: SessionInformation, request: AgentPlaywrightInputRequest): AgentPlaywrightInputResponse {
    val page = globalPlaywriteContext ?: return AgentPlaywrightInputResponse(
      ok = false,
      action = request.action,
      key = request.key,
      uid = request.uid,
      documentName = info.documentName,
      scratch = info.ephemeral,
      sessionKey = info.filename,
      message = "Playwright is not active"
    )

    return runCatching {
      val (result, actionMessage) = performWindowAction(page, request)
      AgentPlaywrightInputResponse(
        ok = true,
        action = request.action,
        key = request.key,
        uid = request.uid,
        documentName = info.documentName,
        scratch = info.ephemeral,
        sessionKey = info.filename,
        result = result,
        message = actionMessage
      )
    }.getOrElse {
      AgentPlaywrightInputResponse(
        ok = false,
        action = request.action,
        key = request.key,
        uid = request.uid,
        documentName = info.documentName,
        scratch = info.ephemeral,
        sessionKey = info.filename,
        message = it.message ?: "Playwright input failed"
      )
    }
  }

private fun BoxScreenshotRequest.toPlaywrightArg(): Map<String, Any?> = mapOf(
    "uid" to uid,
    "padding" to padding,
    "panIntoView" to panIntoView,
    "hideMonaco" to hideMonaco,
    "openOutputs" to openOutputs,
    "preserveSelection" to preserveSelection,
    "includeNotifications" to includeNotifications
)

private fun WindowScreenshotRequest.toPlaywrightArg(): Map<String, Any?> = mapOf(
    "uid" to uid,
    "property" to property,
    "panIntoView" to panIntoView,
    "showEditor" to showEditor,
    "openOutputs" to openOutputs,
    "editorOnly" to editorOnly,
    "padding" to padding,
    "waitMs" to waitMs,
    "preserveSelection" to preserveSelection,
    "includeNotifications" to includeNotifications
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
          const pageDebug = () => {
            const boxes = Array.from((document.body && document.body.getElementsByClassName("box")) || []);
            return JSON.stringify({
              href: String(window.location && window.location.href ? window.location.href : ""),
              readyState: String(document.readyState || ""),
              fieldClientOpen: window.fieldClientOpen === true,
              disconnectedOverlayActive: window.fieldBackdoorDisconnectedOverlayActive === true,
              uiReady: window.fieldBackdoorUiReady === true,
              currentDocumentFilename: window.currentDocumentFilename || null,
              currentDocumentSessionKey: window.currentDocumentSessionKey || null,
              currentDocumentScratch: window.currentDocumentScratch === true,
              boxCount: boxes.length,
              boxes: boxes.slice(0, 8).map((element) => ({
                uid: element.uid || element.getAttribute("uid") || element.getAttribute("data-uid") || "",
                className: String(element.className || ""),
                text: String(element.textContent || "").trim().slice(0, 80)
              }))
            });
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
          const materialiseDeferredFrame = (iframe) => {
            // A `loading="lazy"` frame outside the viewport never fetches, and its untouched
            // about:blank document reports readyState "complete" -- so it reads as READY while
            // being empty, and the status checks never see the frame's own "connecting..."
            // because about:blank has no #status element. Promote it and let the browser start.
            // Everything after this is the existing readiness logic, which is right once reached.
            if (iframe.getAttribute("loading") === "lazy") {
              iframe.setAttribute("loading", "eager");
              try {
                iframe.scrollIntoView({ block: "center", inline: "nearest" });
              } catch (error) {
                /* scrollIntoView options are advisory */
              }
              const src = iframe.getAttribute("src");
              if (src) {
                iframe.setAttribute("src", src);
              }
            }
          };
          const iframeNavigated = (iframe) => {
            const src = iframe.getAttribute("src");
            if (!src || src === "about:blank") {
              return true;
            }
            try {
              const doc = iframe.contentDocument;
              if (!doc) {
                return true;
              }
              const url = String(doc.URL || "");
              return url !== "" && url !== "about:blank";
            } catch (error) {
              return true;
            }
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
          /**
           * A GEOMETRY signature of the laid-out output: sizes only, never pixel content.
           *
           * Sizes are what a clip is computed from, so this is exactly the quantity that must stop
           * changing before a shot is taken. Deliberately blind to pixels, so a widget that animates
           * inside a fixed-size canvas -- a meter, a scrolling history, a playhead -- reads as stable
           * and does not stall the capture waiting for motion that never stops.
           */
          const contentSignature = (root) => {
            const outputArea = root.querySelector(".outputArea");
            if (!outputArea) return null;
            const parts = [];
            const push = (el) => {
              const r = el.getBoundingClientRect();
              parts.push(Math.round(r.width) + "x" + Math.round(r.height));
            };
            push(outputArea);
            Array.from(outputArea.querySelectorAll(".disclosure, canvas, svg, img, iframe")).forEach(push);
            parts.push(outputArea.scrollWidth + "/" + outputArea.scrollHeight);
            return parts.join(",");
          };
          /**
           * The status text a READER can actually see, which is the only kind that should hold a shot.
           *
           * Two halves matter equally. The selector must include `[role="status"]` and
           * `[aria-busy="true"]`, because a widget that announces busy-ness the standard ARIA way was
           * invisible to a `.status`/`#status` scan -- that is how the recorded-audio capture shipped
           * as a blur while a "loading waveform" badge sat in the corner of the very same picture. And
           * the visibility filter is not optional: these badges stay in the DOM with `aria-hidden` set
           * once they finish, so matching their text alone would block every audio capture forever.
           */
          const visibleStatusTexts = (outputArea) => {
            const selector = 'svg text, .status, #status, [role="status"], [aria-busy="true"]';
            return Array.from(outputArea.querySelectorAll(selector))
              .filter(isVisibleNode)
              .map((node) => String(node.textContent || "").trim())
              .filter((text) => text.length > 0);
          };
          /** Is this node something a reader can actually see right now? */
          const isVisibleNode = (node) => {
            if (node.getAttribute && node.getAttribute("aria-hidden") === "true") return false;
            const rect = node.getBoundingClientRect ? node.getBoundingClientRect() : null;
            if (rect && rect.width < 1 && rect.height < 1) return false;
            const view = node.ownerDocument && node.ownerDocument.defaultView;
            const style = view && view.getComputedStyle ? view.getComputedStyle(node) : null;
            if (style && (style.display === "none" || style.visibility === "hidden" || style.opacity === "0")) {
              return false;
            }
            return true;
          };
          /** Has the output actually been laid out, as opposed to existing at zero size? */
          const contentLaidOut = (root) => {
            const outputArea = root.querySelector(".outputArea");
            if (!outputArea) return false;
            if (outputArea.style.visibility === "hidden") return true;
            const r = outputArea.getBoundingClientRect();
            if (r.width < 1 || r.height < 1) return false;
            const imgs = Array.from(outputArea.querySelectorAll("img"));
            if (imgs.some((img) => !img.complete || img.naturalWidth === 0)) return false;
            return true;
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
            iframes.forEach(materialiseDeferredFrame);
            if (iframes.some((iframe) => !iframeNavigated(iframe) || !iframeReady(iframe))) {
              return false;
            }
            // An element that declares itself busy blocks on that alone. Text matching cannot
            // distinguish "has not started loading yet" from "has finished", which is exactly how a
            // half-drawn waveform got captured: the badge only appears once the fetch begins, so a
            // check that ran a moment earlier saw nothing to wait for and shot anyway.
            if (Array.from(outputArea.querySelectorAll('[aria-busy="true"]')).some(isVisibleNode)) {
              return false;
            }
            return !visibleStatusTexts(outputArea).some(blockedStatusText);
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
              lastMessage = `box ${'$'}{request.uid} not found; page=${'$'}{pageDebug()}`;
              await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
              continue;
            }
            if (request.openOutputs) {
              // READINESS IS A PRECONDITION, NOT A GRACE PERIOD. This used to wait
              // SCREENSHOT_OUTPUT_GRACE_MS and then shoot regardless, so a correct "still loading"
              // signal could not stop a capture -- which is how the recorded-audio figure published
              // with a "loading waveform" badge sitting in the corner of the picture. An unready box
              // now drops back to the outer prep loop, which retries until SCREENSHOT_PREP_TIMEOUT_MS
              // and then FAILS naming what blocked it. A capture that cannot be taken correctly is
              // worth more as an error than as a blurred asset nobody re-checks.
              const notReadyBox = findBox(request.uid);
              if (notReadyBox && !outputsReady(notReadyBox)) {
                const area = notReadyBox.querySelector(".outputArea");
                const blocking = area ? visibleStatusTexts(area).filter(blockedStatusText) : [];
                lastMessage = blocking.length
                  ? "output not ready: " + blocking.join(" | ")
                  : "output not ready (an iframe has not navigated or finished loading)";
                await sleep(${SCREENSHOT_POLL_INTERVAL_MS});
                continue;
              }
              // Ready is not the same as settled. Wait for the GEOMETRY to stop changing before
              // anything is measured or shot, so the clip is computed from finished layout rather
              // than from a container still growing under it.
              const stableDeadline = Date.now() + ${SCREENSHOT_STABLE_TIMEOUT_MS};
              let previousSignature = null;
              let stableSamples = 0;
              while (Date.now() < stableDeadline) {
                if (!pageReady()) {
                  break;
                }
                const currentBox = findBox(request.uid);
                if (!currentBox) {
                  break;
                }
                const signature = contentSignature(currentBox);
                if (contentLaidOut(currentBox) && signature !== null && signature === previousSignature) {
                  stableSamples += 1;
                  if (stableSamples >= ${SCREENSHOT_STABLE_SAMPLES}) {
                    break;
                  }
                } else {
                  stableSamples = 0;
                }
                previousSignature = signature;
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
          const pageDebug = () => {
            const boxes = Array.from((document.body && document.body.getElementsByClassName("box")) || []);
            return JSON.stringify({
              href: String(window.location && window.location.href ? window.location.href : ""),
              readyState: String(document.readyState || ""),
              fieldClientOpen: window.fieldClientOpen === true,
              disconnectedOverlayActive: window.fieldBackdoorDisconnectedOverlayActive === true,
              uiReady: window.fieldBackdoorUiReady === true,
              currentDocumentFilename: window.currentDocumentFilename || null,
              currentDocumentSessionKey: window.currentDocumentSessionKey || null,
              currentDocumentScratch: window.currentDocumentScratch === true,
              boxCount: boxes.length,
              boxes: boxes.slice(0, 8).map((element) => ({
                uid: element.uid || element.getAttribute("uid") || element.getAttribute("data-uid") || "",
                className: String(element.className || ""),
                text: String(element.textContent || "").trim().slice(0, 80)
              }))
            });
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
          const materialiseDeferredFrame = (iframe) => {
            // A `loading="lazy"` frame outside the viewport never fetches, and its untouched
            // about:blank document reports readyState "complete" -- so it reads as READY while
            // being empty, and the status checks never see the frame's own "connecting..."
            // because about:blank has no #status element. Promote it and let the browser start.
            // Everything after this is the existing readiness logic, which is right once reached.
            if (iframe.getAttribute("loading") === "lazy") {
              iframe.setAttribute("loading", "eager");
              try {
                iframe.scrollIntoView({ block: "center", inline: "nearest" });
              } catch (error) {
                /* scrollIntoView options are advisory */
              }
              const src = iframe.getAttribute("src");
              if (src) {
                iframe.setAttribute("src", src);
              }
            }
          };
          const iframeNavigated = (iframe) => {
            const src = iframe.getAttribute("src");
            if (!src || src === "about:blank") {
              return true;
            }
            try {
              const doc = iframe.contentDocument;
              if (!doc) {
                return true;
              }
              const url = String(doc.URL || "");
              return url !== "" && url !== "about:blank";
            } catch (error) {
              return true;
            }
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
          /**
           * A GEOMETRY signature of the laid-out output: sizes only, never pixel content.
           *
           * Sizes are what a clip is computed from, so this is exactly the quantity that must stop
           * changing before a shot is taken. Deliberately blind to pixels, so a widget that animates
           * inside a fixed-size canvas -- a meter, a scrolling history, a playhead -- reads as stable
           * and does not stall the capture waiting for motion that never stops.
           */
          const contentSignature = (root) => {
            const outputArea = root.querySelector(".outputArea");
            if (!outputArea) return null;
            const parts = [];
            const push = (el) => {
              const r = el.getBoundingClientRect();
              parts.push(Math.round(r.width) + "x" + Math.round(r.height));
            };
            push(outputArea);
            Array.from(outputArea.querySelectorAll(".disclosure, canvas, svg, img, iframe")).forEach(push);
            parts.push(outputArea.scrollWidth + "/" + outputArea.scrollHeight);
            return parts.join(",");
          };
          /**
           * The status text a READER can actually see, which is the only kind that should hold a shot.
           *
           * Two halves matter equally. The selector must include `[role="status"]` and
           * `[aria-busy="true"]`, because a widget that announces busy-ness the standard ARIA way was
           * invisible to a `.status`/`#status` scan -- that is how the recorded-audio capture shipped
           * as a blur while a "loading waveform" badge sat in the corner of the very same picture. And
           * the visibility filter is not optional: these badges stay in the DOM with `aria-hidden` set
           * once they finish, so matching their text alone would block every audio capture forever.
           */
          const visibleStatusTexts = (outputArea) => {
            const selector = 'svg text, .status, #status, [role="status"], [aria-busy="true"]';
            return Array.from(outputArea.querySelectorAll(selector))
              .filter(isVisibleNode)
              .map((node) => String(node.textContent || "").trim())
              .filter((text) => text.length > 0);
          };
          /** Is this node something a reader can actually see right now? */
          const isVisibleNode = (node) => {
            if (node.getAttribute && node.getAttribute("aria-hidden") === "true") return false;
            const rect = node.getBoundingClientRect ? node.getBoundingClientRect() : null;
            if (rect && rect.width < 1 && rect.height < 1) return false;
            const view = node.ownerDocument && node.ownerDocument.defaultView;
            const style = view && view.getComputedStyle ? view.getComputedStyle(node) : null;
            if (style && (style.display === "none" || style.visibility === "hidden" || style.opacity === "0")) {
              return false;
            }
            return true;
          };
          /** Has the output actually been laid out, as opposed to existing at zero size? */
          const contentLaidOut = (root) => {
            const outputArea = root.querySelector(".outputArea");
            if (!outputArea) return false;
            if (outputArea.style.visibility === "hidden") return true;
            const r = outputArea.getBoundingClientRect();
            if (r.width < 1 || r.height < 1) return false;
            const imgs = Array.from(outputArea.querySelectorAll("img"));
            if (imgs.some((img) => !img.complete || img.naturalWidth === 0)) return false;
            return true;
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
            iframes.forEach(materialiseDeferredFrame);
            if (iframes.some((iframe) => !iframeNavigated(iframe) || !iframeReady(iframe))) {
              return false;
            }
            // An element that declares itself busy blocks on that alone. Text matching cannot
            // distinguish "has not started loading yet" from "has finished", which is exactly how a
            // half-drawn waveform got captured: the badge only appears once the fetch begins, so a
            // check that ran a moment earlier saw nothing to wait for and shot anyway.
            if (Array.from(outputArea.querySelectorAll('[aria-busy="true"]')).some(isVisibleNode)) {
              return false;
            }
            return !visibleStatusTexts(outputArea).some(blockedStatusText);
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
                lastMessage = `box ${'$'}{request.uid} not found; page=${'$'}{pageDebug()}`;
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
                // Ready is not settled -- see the box path. Wait for geometry to stop moving.
                const stableDeadline = Date.now() + ${SCREENSHOT_STABLE_TIMEOUT_MS};
                let previousSignature = null;
                let stableSamples = 0;
                while (Date.now() < stableDeadline) {
                  if (!pageReady()) {
                    break;
                  }
                  const currentBox = findBox(request.uid);
                  if (!currentBox) {
                    break;
                  }
                  const signature = contentSignature(currentBox);
                  if (contentLaidOut(currentBox) && signature !== null && signature === previousSignature) {
                    stableSamples += 1;
                    if (stableSamples >= ${SCREENSHOT_STABLE_SAMPLES}) {
                      break;
                    }
                  } else {
                    stableSamples = 0;
                  }
                  previousSignature = signature;
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
                padding = request.padding,
                waitMs = request.waitMs,
                preserveSelection = request.preserveSelection,
                includeNotifications = request.includeNotifications
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

    if (request.waitMs > 0.0) {
        page.waitForTimeout(request.waitMs)
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
                openOutputs = request.openOutputs,
                preserveSelection = request.preserveSelection,
                includeNotifications = request.includeNotifications
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

    val clip = if (request.frameSynchronous && request.editorOnly) {
        // A content-trimmed Monaco clip changes height as output view-zones arrive. Measuring it
        // after the warm-up run therefore makes the movie geometry depend on websocket/compiler
        // timing even when every simulated frame is identical. Frame-synchronous editor movies
        // use the fixed screenshot-layout container instead; changing output can change pixels,
        // but it cannot change the raster dimensions or crop between runs.
        withContext(playwrightDispatcher) {
            stableEditorVideoClip(page, request.padding)
        } ?: prepResponse.clip
    } else {
        prepResponse.clip
    }
    if (request.editorOnly && clip == null) {
        return failedAgentVideo(
            info = info, kind = kind, path = request.path, uid = request.uid,
            message = "Editor video prep did not return a clip"
        )
    }

    val outputPath = Path.of(request.path)
    outputPath.parent?.let { Files.createDirectories(it) }

    return runCatching {
        val result = if (request.frameSynchronous) {
            recordFrameSynchronousToMp4(
                info = info,
                page = page,
                outputPath = outputPath,
                clip = clip,
                durationSec = request.durationSec,
                fps = request.fps,
                visibleOutputUid = request.uid.takeIf { request.openOutputs && !request.editorOnly },
                prepare = videoCapturePreparation(
                    info = info,
                    page = page,
                    uid = request.uid,
                    property = request.property,
                    restartTarget = request.restartTarget
                )
            )
        } else {
            require(!request.restartTarget) { "restartTarget requires frameSynchronous capture" }
            recordScreencastToMp4(
                page = page,
                outputPath = outputPath,
                clip = clip,
                durationSec = request.durationSec,
                fps = request.fps
            )
        }
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
        lprintln("warning,video", "Window video failed for ${request.path}: ${it.videoFailureDescription()}")
        it.printStackTrace()
    }.getOrElse {
        failedAgentVideo(
            info = info, kind = kind, path = outputPath.toString(), uid = request.uid,
            message = it.videoFailureDescription()
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
        val result = if (request.frameSynchronous) {
            recordFrameSynchronousToMp4(
                info = info,
                page = page,
                outputPath = outputPath,
                clip = clip,
                durationSec = request.durationSec,
                fps = request.fps,
                visibleOutputUid = request.uid.takeIf { request.openOutputs },
                prepare = videoCapturePreparation(
                    info = info,
                    page = page,
                    uid = request.uid,
                    property = request.property,
                    restartTarget = request.restartTarget
                )
            )
        } else {
            require(!request.restartTarget) { "restartTarget requires frameSynchronous capture" }
            recordScreencastToMp4(
                page = page,
                outputPath = outputPath,
                clip = clip,
                durationSec = request.durationSec,
                fps = request.fps
            )
        }
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
        lprintln("warning,video", "Box video failed for ${request.uid}: ${it.videoFailureDescription()}")
        it.printStackTrace()
    }.getOrElse {
        failedAgentVideo(
            info = info, kind = "box", path = outputPath.toString(), uid = request.uid,
            message = it.videoFailureDescription()
        )
    }
}

/**
 * A failed video capture named so a docs build can act on it.
 *
 * `it.message` alone is not enough, and on 2026-08-21 that cost a nightly CI docs build its
 * diagnosis: `animated_rose.mp4` failed and the only trace anywhere — server log, post-mortem and
 * the `record-box` command result — was `Box video failed for 7377f8d9-...: null`, because the
 * throwable's message was null and both the log line and the response fell back to a constant. An
 * exception with no message is common (NPE, cancellation, several Playwright faults) and is exactly
 * the case where the *type* is the whole signal, so keep it. The cause chain matters for the same
 * reason: a Playwright timeout wrapped in a runtime exception says something different from an
 * `IllegalStateException` about frame counts.
 */
private fun Throwable.videoFailureDescription(): String {
    val chain = generateSequence(this) { previous -> previous.cause.takeIf { it !== previous } }
        .take(4)
        .joinToString(" <- ") { link ->
            val name = link::class.simpleName ?: link::class.java.name
            link.message?.takeIf { it.isNotBlank() }?.let { "$name: $it" } ?: name
        }
    return "Video recording failed ($chain)"
}

private data class ScreencastResult(val frameCount: Int)

private fun stableEditorVideoClip(page: Page, padding: Double): Rect? {
    val raw = page.evaluate(
        """
        padding => {
          const element = document.querySelector('.monaco-editor-container');
          if (!element) return null;
          const rect = element.getBoundingClientRect();
          if (rect.width <= 0 || rect.height <= 0) return null;
          const pad = Math.max(0, Number(padding) || 0);
          const viewportLeft = window.scrollX;
          const viewportTop = window.scrollY;
          const viewportRight = viewportLeft + window.innerWidth;
          const viewportBottom = viewportTop + window.innerHeight;
          const left = Math.max(viewportLeft, rect.left + window.scrollX - pad);
          const top = Math.max(viewportTop, rect.top + window.scrollY - pad);
          const right = Math.min(viewportRight, rect.right + window.scrollX + pad);
          const bottom = Math.min(viewportBottom, rect.bottom + window.scrollY + pad);
          if (right <= left || bottom <= top) return null;
          return { x: left, y: top, w: right - left, h: bottom - top };
        }
        """.trimIndent(),
        padding
    )
    val map = raw as? Map<*, *> ?: return null
    return Rect(
        x = (map["x"] as? Number)?.toDouble() ?: return null,
        y = (map["y"] as? Number)?.toDouble() ?: return null,
        w = (map["w"] as? Number)?.toDouble() ?: return null,
        h = (map["h"] as? Number)?.toDouble() ?: return null,
    )
}

private data class FrameCaptureGate(
    val ready: () -> Boolean,
    val resetAtFirstBoundary: suspend () -> Unit = {},
)

internal fun videoFrameCount(durationSec: Double, fps: Int): Int =
    maxOf(1, kotlin.math.ceil(durationSec * fps.coerceAtLeast(1)).toInt())

internal data class VideoOutputCaptureState(
    val boxFound: Boolean,
    val outputAreaFound: Boolean,
    val disclosureCount: Int,
    val openDisclosureCount: Int,
    val nonEmptyContentCount: Int,
    val imageCount: Int,
    val failedImageCount: Int,
)

internal fun videoOutputCaptureProblem(state: VideoOutputCaptureState): String? = when {
    !state.boxFound -> "the box is absent from the page"
    !state.outputAreaFound -> "the box has no output area"
    state.disclosureCount == 0 -> "the output area has no disclosures"
    state.openDisclosureCount == 0 -> "every output disclosure is closed"
    state.nonEmptyContentCount == 0 -> "every open output disclosure is empty"
    state.failedImageCount > 0 -> "${state.failedImageCount} of ${state.imageCount} output images failed to decode"
    else -> null
}

private fun videoOutputCaptureState(raw: Any?): VideoOutputCaptureState {
    val map = raw as? Map<*, *>
        ?: throw IllegalStateException("Browser returned no output-capture state")
    return VideoOutputCaptureState(
        boxFound = map["boxFound"] as? Boolean ?: false,
        outputAreaFound = map["outputAreaFound"] as? Boolean ?: false,
        disclosureCount = (map["disclosureCount"] as? Number)?.toInt() ?: 0,
        openDisclosureCount = (map["openDisclosureCount"] as? Number)?.toInt() ?: 0,
        nonEmptyContentCount = (map["nonEmptyContentCount"] as? Number)?.toInt() ?: 0,
        imageCount = (map["imageCount"] as? Number)?.toInt() ?: 0,
        failedImageCount = (map["failedImageCount"] as? Number)?.toInt() ?: 0,
    )
}

private fun visibleVideoOutputReady(page: Page, uid: String): Boolean {
    val raw = page.evaluate(
        """async uid => {
            const boxes = Array.from(document.getElementsByClassName('box'));
            const box = boxes.find(candidate => candidate.uid === uid);
            if (!box) return {
                boxFound: false, outputAreaFound: false, disclosureCount: 0,
                openDisclosureCount: 0, nonEmptyContentCount: 0,
                imageCount: 0, failedImageCount: 0
            };
            const outputArea = box.querySelector('.outputArea');
            if (!outputArea) return {
                boxFound: true, outputAreaFound: false, disclosureCount: 0,
                openDisclosureCount: 0, nonEmptyContentCount: 0,
                imageCount: 0, failedImageCount: 0
            };
            const disclosures = Array.from(outputArea.querySelectorAll('.disclosure'))
                .filter(disclosure => disclosure.__disclosureId !== 'nested');
            const visibleContent = disclosures
                .map(disclosure => disclosure.querySelector('.disclosure-content'))
                .filter(content => {
                    if (!content) return false;
                    const style = getComputedStyle(content);
                    const rect = content.getBoundingClientRect();
                    return style.display !== 'none' && style.visibility !== 'hidden' &&
                        Number(style.opacity || 1) > 0 && rect.width > 0 && rect.height > 0;
                });
            const images = visibleContent.flatMap(content => Array.from(content.querySelectorAll('img')));
            let failedImageCount = 0;
            await Promise.all(images.map(async image => {
                try {
                    if (typeof image.decode === 'function') {
                        await image.decode();
                    } else if (!image.complete) {
                        await new Promise((resolve, reject) => {
                            image.addEventListener('load', resolve, { once: true });
                            image.addEventListener('error', reject, { once: true });
                        });
                    }
                    if (!image.complete || image.naturalWidth <= 0 || image.naturalHeight <= 0) {
                        throw new Error('image has no decoded dimensions');
                    }
                } catch (_) {
                    failedImageCount += 1;
                }
            }));
            const nonEmptyContentCount = visibleContent.filter(content =>
                content.childElementCount > 0 || (content.textContent || '').trim().length > 0
            ).length;
            return {
                boxFound: true,
                outputAreaFound: true,
                disclosureCount: disclosures.length,
                openDisclosureCount: visibleContent.length,
                nonEmptyContentCount,
                imageCount: images.length,
                failedImageCount
            };
        }""".trimIndent(),
        uid
    )
    val state = videoOutputCaptureState(raw)
    val problem = videoOutputCaptureProblem(state) ?: return true

    // Some renderers enqueue their first display result only after a later GPU/runloop boundary.
    // An open, empty disclosure is therefore a valid short lead-in; the recorder still takes that
    // frame and advances virtual time, then requires visible content somewhere in the completed
    // clip. Missing/closed output is structural and fails immediately.
    val awaitingFirstContent = state.boxFound && state.outputAreaFound &&
        state.disclosureCount > 0 && state.openDisclosureCount > 0 &&
        state.nonEmptyContentCount == 0 && state.failedImageCount == 0
    if (awaitingFirstContent) return false

    throw IllegalStateException(
        "Frame-synchronous capture expected visible output for box $uid, but $problem " +
            "(disclosures=${state.disclosureCount}, open=${state.openDisclosureCount}, " +
            "nonEmpty=${state.nonEmptyContentCount}, images=${state.imageCount}, " +
            "failedImages=${state.failedImageCount})"
    )
}

private suspend fun clearVideoPresentation(
    info: SessionInformation,
    page: Page,
    uid: String?,
    clearOutputContents: Boolean = true,
) {
    uid?.let(info.boxes::get)?._fieldProbe?.resetFrameSynchronousCapture()
    withContext(playwrightDispatcher) {
        // A launch can enter its first frame after sending println/marker output but before the
        // browser has handled that websocket message. Clear only after all pre-boundary output
        // has crossed the browser paint ACK, or it can arrive just after the clear and move the
        // first visible output to a host-load-dependent movie frame.
        withTimeout(30_000) {
            while (!info.outputsDelivered()) delay(1)
        }
        page.evaluate(
            """request => {
                const uid = request.uid;
                const clear = window.clearEditorOutputsForBox;
                if (uid && typeof clear === 'function') clear(uid);
                if (request.clearOutputContents) {
                    const clearDisplays = window.clearOutputAreaContentsForBox;
                    if (uid && typeof clearDisplays !== 'function') {
                        throw new Error('clearOutputAreaContentsForBox is unavailable');
                    }
                    if (uid) clearDisplays(uid);
                }
                const refreshMarkers = window.refreshMarkerDecorations;
                if (typeof refreshMarkers === 'function') refreshMarkers();
            }""".trimIndent(),
            mapOf("uid" to uid, "clearOutputContents" to clearOutputContents)
        )
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
    }
}

/**
 * Return a callback that starts (or simply admits) frame-synchronous capture.
 *
 * Docs captures prepare their clip against the already-running box, then restart the
 * same property only after the runloop capture hook is installed. The readiness
 * predicate keeps compilation/setup frames out of the movie and opens on the first
 * active frame of the relaunched value.
 */
private fun videoCapturePreparation(
    info: SessionInformation,
    page: Page,
    uid: String?,
    property: String,
    restartTarget: Boolean,
): suspend () -> FrameCaptureGate = {
    if (!restartTarget) {
        val ready: () -> Boolean = { true }
        FrameCaptureGate(
            ready = ready,
            resetAtFirstBoundary = {
                // Multi-box captures may deliberately keep an ambient driver alive. They still
                // need a clean presentation at movie frame zero; otherwise a warm-up card or
                // marker history becomes the first frame and varies with setup duration.
                uid?.let(info.boxes::get)?.let { target ->
                    target.markers.clear()
                }
                clearVideoPresentation(info, page, uid)
            }
        )
    } else {
        require(uid != null) { "restartTarget requires a box uid" }
        val target = info.boxes[uid]
            ?: throw IllegalArgumentException("No box '$uid' in ${info.documentName}")
        val code = target.getPropertyRaw(property) as? String
            ?: throw IllegalArgumentException("Box '$uid' has no string property '$property'")
        require(code.isNotBlank()) { "Box '$uid' property '$property' is empty" }

        target.ke.stop(target)
        // The preparation run exists only to compile, size the editor/output surface and establish
        // the clip. A real restart must not inherit its marker histories or rendered editor output:
        // __m(...) markers deliberately outlive an ordinary relaunch, which otherwise makes a docs
        // movie begin with a machine-speed-dependent amount of pre-capture state.
        target.markers.clear()
        clearVideoPresentation(info, page, uid)
        val stoppedGeneration = target.runGeneration["default"] ?: -1
        target.ke.launchAll(
            target,
            Execute(
                uid = uid,
                type = property,
                line = 0,
                code = code,
                singleLine = false,
                property = property,
                strand = "default"
            ),
            code = code,
            strand = "default",
            reply = {}
        )

        val ready: () -> Boolean = {
            val state = target.currentRunningStates["default"]
            state != null && state.watermark > stoppedGeneration && state.mode != "launching"
        }
        FrameCaptureGate(
            ready = ready,
            resetAtFirstBoundary = {
                // Stop/relaunch becomes observable to the browser asynchronously. Reset once more
                // at the exact first active runloop boundary: the old launch can no longer publish,
                // and the new launch has not yet accumulated a machine-speed-dependent history.
                target.markers.clear()
                clearVideoPresentation(info, page, uid, clearOutputContents = false)
            }
        )
    }
}

/**
 * Refuse to publish a recording that barely moves.
 *
 * "0 frames" was the only failure this encoder could report, so a recording could be perfectly
 * frame-accurate and still be worthless: the three `visualizers-inline-*` movies each held 36 frames
 * and exactly **3** distinct pictures, because capture is one image per runloop frame and the demo
 * they recorded only pushed a value every 12th frame. A near-static mp4 looks like a still until you
 * watch it, so nobody noticed for months.
 *
 * **The obvious metric is vacuous and was very nearly shipped here.** Counting distinct JPEG *bytes*
 * passes the broken recordings: those 36 near-identical frames were all byte-distinct, because
 * encoder noise differs frame to frame even when the picture does not. So this decodes each frame to
 * a coarse [SIGNATURE_WIDTH]-wide grey thumbnail and counts CONSECUTIVE pairs that differ
 * materially. The thumbnail is what makes it cheap and what makes it robust: it discards exactly the
 * high-frequency noise that made the byte comparison lie.
 */
internal fun assertVideoMoved(frames: List<ByteArray>, outputPath: Path) {
    if (frames.size < 4) return
    val signatures = frames.mapNotNull { coarseFrameSignature(it) }
    if (signatures.size < 4) return
    var moved = 0
    for (index in 1 until signatures.size) {
        val a = signatures[index - 1]
        val b = signatures[index]
        var delta = 0
        for (cell in a.indices) {
            if (kotlin.math.abs(a[cell].toInt() - b[cell].toInt()) > SIGNATURE_CELL_TOLERANCE) delta += 1
        }
        if (delta > a.size * SIGNATURE_MOVED_FRACTION) moved += 1
    }
    val floor = maxOf(2, ((signatures.size - 1) * MIN_VIDEO_MOVED_FRACTION).toInt())
    if (moved < floor) {
        throw IllegalStateException(
            "Video capture of ${outputPath.fileName} has ${frames.size} frame(s) but only $moved " +
                "consecutive pair(s) changed materially, floor is $floor. The recording is very nearly " +
                "a still. Either the recorded box updates far more slowly than the capture rate -- a " +
                "`visualizerFrame % N` throttle against one-image-per-runloop-frame capture is the " +
                "usual cause -- or nothing animated at all."
        )
    }
}

/**
 * Decode one JPEG frame to a small grey thumbnail, or null if it will not decode.
 *
 * Cells are BOX-AVERAGED rather than point-sampled. Nearest-neighbour subsampling of a 1680-wide
 * frame skips 12 of every 13 columns, which can step straight over a one-pixel trace line that is
 * the only thing moving; averaging cannot miss it.
 */
private fun coarseFrameSignature(jpeg: ByteArray): IntArray? = runCatching {
    val image = ImageIO.read(jpeg.inputStream()) ?: return@runCatching null
    if (image.width < 1 || image.height < 1) return@runCatching null
    val cols = minOf(SIGNATURE_WIDTH, image.width)
    val rows = maxOf(1, (cols.toLong() * image.height / image.width).toInt())
    val out = IntArray(cols * rows)
    for (row in 0 until rows) {
        val y0 = (row.toLong() * image.height / rows).toInt()
        val y1 = maxOf(y0 + 1, ((row + 1).toLong() * image.height / rows).toInt())
        for (col in 0 until cols) {
            val x0 = (col.toLong() * image.width / cols).toInt()
            val x1 = maxOf(x0 + 1, ((col + 1).toLong() * image.width / cols).toInt())
            var sum = 0L
            var count = 0
            for (y in y0 until minOf(y1, image.height)) {
                for (x in x0 until minOf(x1, image.width)) {
                    val rgb = image.getRGB(x, y)
                    val r = (rgb shr 16) and 0xff
                    val g = (rgb shr 8) and 0xff
                    val b = rgb and 0xff
                    sum += (r * 299 + g * 587 + b * 114) / 1000
                    count += 1
                }
            }
            out[row * cols + col] = if (count == 0) 0 else (sum / count).toInt()
        }
    }
    out
}.getOrNull()

/**
 * Encode an ordered list of JPEG frames to an H.264/MP4 file via ffmpeg.
 *
 * Frames are piped over stdin as concatenated JPEGs (image2pipe/mjpeg) and
 * normalized to a constant [fps] so playback duration is stable. Shared by the
 * fixed-duration poll recorder and the interactive [DocsUiRecorder].
 */
internal fun encodeJpegFramesToMp4(frames: List<ByteArray>, outputPath: Path, fps: Int) {
    if (frames.isEmpty()) {
        throw IllegalStateException("Video capture sampled 0 frames")
    }
    assertVideoMoved(frames, outputPath)
    val safeFps = fps.coerceAtLeast(1)
    val ffmpeg = FFMPEG

    val filterParts = mutableListOf<String>()
    // Ensure dimensions are even (required by yuv420p)
    filterParts.add("pad=ceil(iw/2)*2:ceil(ih/2)*2")
    // Normalize to a constant output frame rate for stable playback duration.
    filterParts.add("fps=$safeFps")

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

    val process = ProcessBuilder(args)
        .redirectErrorStream(true)
        .start()
    process.outputStream.use { stdin ->
        for (frame in frames) {
            stdin.write(frame)
        }
    }
    val log = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw IllegalStateException("ffmpeg encoding failed (exit=$exitCode):\n${args.joinToString(" ")}\n$log")
    }
    lprintln("info,video", "Encoded ${outputPath}: ${frames.size} source frames, output ${safeFps} fps")
}

/**
 * Compute the crop rect for a `drive` recording/still, reusing the same view-prep
 * (select box, open editor, pan, open outputs) as the screenshot commands so the
 * recorded region matches what a plain screenshot of the same [capture] mode would
 * produce. Runs on the Playwright thread.
 */
internal fun computeDriveClip(page: Page, capture: String, uid: String?, padding: Double): Rect? = when (capture) {
    "box" -> {
        val u = uid ?: error("capture=box requires a uid")
        requestBoxScreenshot(page, BoxScreenshotRequest(uid = u, padding = padding, openOutputs = true)).clip
    }
    "editor", "window-editor" -> requestWindowScreenshot(
        page,
        WindowScreenshotRequest(uid = uid, showEditor = true, editorOnly = capture == "editor", padding = padding)
    ).clip
    else -> requestWindowScreenshot(page, WindowScreenshotRequest(uid = uid, padding = padding)).clip
}

/**
 * Compute a crop rect (CSS px, + [padding]) from a CSS selector's bounding box — used to
 * clip a `drive` capture to an overlay that is not a box (command palette, popups, AI
 * panels). Returns null if the selector matches nothing or has zero size. Runs on the
 * Playwright thread.
 */
internal fun computeSelectorClip(page: Page, selector: String, padding: Double): Rect? {
    val raw = page.evaluate(
        """
        (sel) => {
          const el = document.querySelector(sel);
          if (!el) return null;
          const r = el.getBoundingClientRect();
          if (r.width <= 0 || r.height <= 0) return null;
          return { x: r.left, y: r.top, w: r.width, h: r.height };
        }
        """.trimIndent(),
        selector
    )
    val m = raw as? Map<*, *> ?: return null
    val x = (m["x"] as? Number)?.toDouble() ?: return null
    val y = (m["y"] as? Number)?.toDouble() ?: return null
    val w = (m["w"] as? Number)?.toDouble() ?: return null
    val h = (m["h"] as? Number)?.toDouble() ?: return null
    return Rect((x - padding).coerceAtLeast(0.0), (y - padding).coerceAtLeast(0.0), w + 2 * padding, h + 2 * padding)
}

/** Take a clipped PNG of the current page at [clip] (CSS px) and trim uniform borders. */
internal fun captureClippedScreenshot(page: Page, outputPath: Path, clip: Rect) {
    outputPath.parent?.let { Files.createDirectories(it) }
    val opts = Page.ScreenshotOptions().setPath(outputPath).setClip(Clip(clip.x, clip.y, clip.w, clip.h))
    page.screenshot(opts)
    postProcessScreenshot(outputPath)
}

/**
 * Fixed-duration recorder: poll [page].screenshot() at [fps] for [durationSec]
 * and encode. Used by the standalone window/box video capture commands, which
 * record ambient animation with no interleaved input.
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
    val targetFrames = videoFrameCount(durationSec, safeFps)
    val frameIntervalNs = (1_000_000_000.0 / safeFps.toDouble()).toLong().coerceAtLeast(1L)

    repeat(targetFrames) { index ->
        val frameStart = System.nanoTime()
        val screenshotOptions = Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(90)
        if (clip != null) {
            screenshotOptions.setClip(Clip(clip.x, clip.y, clip.w, clip.h))
        }
        frames.add(page.screenshot(screenshotOptions))

        val elapsedNs = System.nanoTime() - frameStart
        val sleepNs = frameIntervalNs - elapsedNs
        if (sleepNs > 0 && index < targetFrames - 1) {
            Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
        }
    }

    lprintln("info,video", "Captured ${frames.size} sampled frames in ${durationSec}s")
    encodeJpegFramesToMp4(frames, outputPath, fps)
    return ScreencastResult(frameCount = frames.size)
}

/**
 * Capture exactly one JPEG after each completed Field runloop frame.
 *
 * The runloop is deliberately blocked while Playwright waits for the browser to
 * paint and takes the screenshot. Capture speed therefore changes build time only:
 * the simulation cannot advance a second frame before the current one has entered
 * the ordered JPEG stream. [prepare] runs after the hook is live; docs use it to
 * relaunch the target and return a predicate that admits the first active frame.
 */
private suspend fun recordFrameSynchronousToMp4(
    info: SessionInformation,
    page: Page,
    outputPath: Path,
    clip: Rect?,
    durationSec: Double,
    fps: Int,
    visibleOutputUid: String? = null,
    prepare: suspend () -> FrameCaptureGate,
): ScreencastResult {
    val frames = mutableListOf<ByteArray>()
    val safeFps = fps.coerceAtLeast(1)
    val targetFrames = videoFrameCount(durationSec, safeFps)
    val installed = CompletableDeferred<Unit>()
    val completed = CompletableDeferred<Unit>()
    val gate = AtomicReference(FrameCaptureGate(ready = { false }))
    var resetAtFirstBoundary = false
    var visibleOutputSeen = visibleOutputUid == null
    val hookName = "docs-video-${UUID.randomUUID()}"
    val readyDeadline = AtomicReference(Long.MAX_VALUE)
    val clockToken = AnimationClock.beginFrameIndexed(safeFps)

    val screenshotOptions = Page.ScreenshotOptions()
        .setType(ScreenshotType.JPEG)
        .setQuality(90)
        .setTimeout(PLAYWRIGHT_VIDEO_FRAME_TIMEOUT_MS)
    if (clip != null) {
        screenshotOptions.setClip(Clip(clip.x, clip.y, clip.w, clip.h))
    }
    withContext(playwrightDispatcher) {
        page.evaluate(
            """() => {
                let style = document.getElementById('field-frame-sync-stability');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'field-frame-sync-stability';
                    style.textContent =
                        '*, *::before, *::after { animation: none !important; transition: none !important; }' +
                        '.field-webgl-background { display: none !important; }' +
                        '.monaco-editor .cursor { visibility: hidden !important; }' +
                        '.monaco-editor textarea { caret-color: transparent !important; }';
                    document.head.appendChild(style);
                }
            }""".trimIndent()
        )
    }

    Runloop.runLoop.postRun[hookName] = sequence {
        installed.complete(Unit)
        while (frames.size < targetFrames && !completed.isCompleted) {
            val currentGate = gate.get()
            if (currentGate.ready()) {
                try {
                    if (!resetAtFirstBoundary) {
                        runBlocking { currentGate.resetAtFirstBoundary() }
                        resetAtFirstBoundary = true
                        yield(Unit)
                        continue
                    }
                    val frame = runBlocking(playwrightDispatcher) {
                        // `send(set-output)` is asynchronous. Without this barrier, frame N can
                        // contain output N in one run and output N-1 in another even though the
                        // simulation itself is identical. The browser ACK is scheduled only after
                        // its output handler has mutated the DOM; two RAFs below then guarantee a
                        // paint of that acknowledged state.
                        withTimeout(10_000) {
                            while (!info.outputsDelivered()) delay(1)
                        }
                        if (visibleOutputUid != null && visibleVideoOutputReady(page, visibleOutputUid)) {
                            visibleOutputSeen = true
                        }
                        // Let websocket output and layout reach a painted browser frame while
                        // Field itself remains parked at this exact runloop boundary.
                        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
                        page.screenshot(screenshotOptions)
                    }
                    frames += frame
                    AnimationClock.advance(clockToken)
                } catch (t: Throwable) {
                    completed.completeExceptionally(t)
                    break
                }
            } else if (System.nanoTime() > readyDeadline.get()) {
                completed.completeExceptionally(
                    IllegalStateException("Timed out waiting for relaunched video target to reach its first active frame")
                )
                break
            }
            yield(Unit)
        }
        if (!completed.isCompleted) {
            if (!visibleOutputSeen) {
                completed.completeExceptionally(
                    IllegalStateException(
                        "Frame-synchronous capture completed without visible output from box $visibleOutputUid"
                    )
                )
            } else {
                completed.complete(Unit)
            }
        }
    }

    try {
        installed.await()
        gate.set(prepare())
        readyDeadline.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(60))
        completed.await()
    } finally {
        Runloop.runLoop.postRun.remove(hookName)
        AnimationClock.endFrameIndexed(clockToken)
    }

    lprintln("info,video", "Captured ${frames.size} frame-synchronous frames")
    withContext(Dispatchers.IO) {
        encodeJpegFramesToMp4(frames, outputPath, safeFps)
    }
    return ScreencastResult(frameCount = frames.size)
}

/**
 * Interactive recorder. Unlike [recordScreencastToMp4] this exposes explicit
 * start/stop so a scripted sequence of UI actions can run *while* recording.
 *
 * The frame-grab loop runs as a coroutine on [playwrightScope] (the single
 * "afield-playwright" thread). It grabs a JPEG frame then `delay`s/`yield`s to
 * the next frame boundary — and because delay/yield free the dispatcher thread,
 * driver actions marshaled onto the same dispatcher (see [DocsUiDriver]) run in
 * the gaps between frames. So the recorded clip shows the keypresses/clicks as
 * they happen, on one thread, deterministically — no CDP screencast required.
 */
class DocsUiRecorder(
    private val page: Page,
    private val clip: Rect?,
    private val fps: Int,
) {
    private val frames = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var running = false
    private var job: Job? = null

    fun start() {
        if (running) return
        running = true
        val safeFps = fps.coerceAtLeast(1)
        val frameIntervalMs = (1000.0 / safeFps.toDouble()).toLong().coerceAtLeast(1L)
        val options = Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(90)
        if (clip != null) {
            options.setClip(Clip(clip.x, clip.y, clip.w, clip.h))
        }
        job = playwrightScope.launch {
            while (running) {
                val t0 = System.nanoTime()
                runCatching { frames.add(page.screenshot(options)) }
                    .onFailure { lprintln("warning,video", "frame grab failed: ${it.message}") }
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
                val sleepMs = frameIntervalMs - elapsedMs
                if (sleepMs > 0) delay(sleepMs) else yield()
            }
        }
    }

    /** Stop the loop, encode collected frames to [outputPath], return frame count. */
    suspend fun stopAndEncode(outputPath: Path): Int {
        running = false
        job?.join()
        val captured = frames.toList()
        outputPath.parent?.let { Files.createDirectories(it) }
        encodeJpegFramesToMp4(captured, outputPath, fps)
        return captured.size
    }

    val frameCount: Int get() = frames.size
}

/**
 * The single object a `drive` script touches. Every method marshals onto the
 * Playwright dispatcher and blocks the calling (main runloop) thread for the
 * result, so scripts never touch the thread-bound [Page] directly. `wait` sleeps
 * on the *calling* thread, leaving the Playwright thread free to keep grabbing
 * recorder frames.
 */
class DocsUiDriver internal constructor(
    private val pageProvider: () -> Page,
) {
    private fun run(request: AgentPlaywrightInputRequest): JsonElement? =
        runBlocking(playwrightDispatcher) {
            performWindowAction(pageProvider(), request).first
        }

    fun press(key: String) { run(AgentPlaywrightInputRequest(action = "keyPress", key = key)) }
    fun key(key: String) = press(key)
    fun down(key: String) { run(AgentPlaywrightInputRequest(action = "keyDown", key = key)) }
    fun up(key: String) { run(AgentPlaywrightInputRequest(action = "keyUp", key = key)) }
    fun type(text: String) { run(AgentPlaywrightInputRequest(action = "typeText", script = text)) }
    fun click(uid: String) { run(AgentPlaywrightInputRequest(action = "clickBox", uid = uid)) }
    fun clickSelector(selector: String) { run(AgentPlaywrightInputRequest(action = "clickSelector", selector = selector)) }
    fun hover(uid: String) { run(AgentPlaywrightInputRequest(action = "hover", uid = uid)) }
    fun hoverSelector(selector: String) { run(AgentPlaywrightInputRequest(action = "hover", selector = selector)) }
    fun drag(uid: String, dx: Double, dy: Double, steps: Int = 12, hold: Boolean = false) {
        run(AgentPlaywrightInputRequest(action = "dragBox", uid = uid, dx = dx, dy = dy, steps = steps, hold = hold))
    }
    fun mouseUp() { run(AgentPlaywrightInputRequest(action = "mouseUp")) }
    fun eval(script: String): JsonElement? =
        run(AgentPlaywrightInputRequest(action = "pageEval", script = script, scriptMode = "body"))

    /** Open (and pan to) a box's code editor, reusing the screenshot editor-prep path. */
    fun openEditor(uid: String, property: String = "code") {
        runBlocking(playwrightDispatcher) {
            requestWindowScreenshot(
                pageProvider(),
                WindowScreenshotRequest(uid = uid, property = property, showEditor = true, panIntoView = true)
            )
        }
    }

    /** Screenshot the whole page to [path] *now* (script-timed — use for transient states). */
    fun shot(path: String) {
        runBlocking(playwrightDispatcher) {
            val p = java.nio.file.Path.of(path)
            p.parent?.let { Files.createDirectories(it) }
            pageProvider().screenshot(Page.ScreenshotOptions().setPath(p))
            postProcessScreenshot(p)
        }
    }

    /**
     * Screenshot, clipped to a CSS selector's bounds, to [path] *now*. Use for overlays that
     * close on blur (command palette, popups, AI panels) — capture while still open. Falls
     * back to a full-page shot if the selector isn't present.
     */
    fun shotClip(path: String, selector: String, padding: Double = 8.0) {
        runBlocking(playwrightDispatcher) {
            val page = pageProvider()
            val p = java.nio.file.Path.of(path)
            val clip = computeSelectorClip(page, selector, padding)
            if (clip != null) {
                captureClippedScreenshot(page, p, clip)
            } else {
                p.parent?.let { Files.createDirectories(it) }
                page.screenshot(Page.ScreenshotOptions().setPath(p))
                postProcessScreenshot(p)
            }
        }
    }

    /**
     * Block until [selector] is visible (or [timeoutMs] elapses), so a capture waits for an
     * async panel to populate instead of guessing a fixed pause. E.g. wait for
     * `text=Creates a mutable` after Control+Space so the autocomplete docs have rendered.
     * Swallows the timeout — the caller captures whatever state exists.
     *
     * Polls (rather than one blocking `waitFor`) and pumps the runloop between polls via [idle],
     * so content that only appears once a main-runloop RPC resolves — the autocomplete popup and
     * its docs arrive from the `complete` handler on the main runloop — is not deadlocked by a
     * blocking wait on that same thread.
     */
    fun waitForSelector(selector: String, timeoutMs: Double = 15000.0) {
        val deadline = System.currentTimeMillis() + timeoutMs.toLong()
        while (System.currentTimeMillis() < deadline) {
            val visible = runCatching {
                runBlocking(playwrightDispatcher) {
                    pageProvider().locator(selector).first().isVisible()
                }
            }.getOrDefault(false)
            if (visible) return
            idle(120L)
        }
    }

    // Named `pause` rather than `wait` because `fun wait(Long)` would collide with
    // java.lang.Object.wait(long).
    fun pause(ms: Long) { idle(ms) }
    fun pause(ms: Int) = pause(ms.toLong())
    fun pause(ms: Double) = pause(ms.toLong())

    /**
     * Wait [ms] without freezing the main runloop. The drive fragment runs *on* the main runloop
     * thread (inside `Framer.frame()`), so a bare `Thread.sleep` would stall the loop and starve
     * everything queued on it — most importantly the `complete`/`resolve-completion` RPCs behind a
     * live Ctrl+Space. Pumping the runloop instead reproduces an idle loop: the state a real user
     * leaves the editor in after triggering autocomplete, which is when the popup populates. This
     * re-enters `frame()`, so any *heavy* main-runloop compile (the K1 completion bootstrap) must
     * already be done before we get here — the drive op warms it up front for exactly this reason.
     * Off the main thread (e.g. a recording-only drive) a plain sleep is correct and lets the
     * off-thread recorder keep grabbing frames.
     */
    private fun idle(ms: Long) {
        if (ms <= 0) return
        if (Runloop.isMainThread) {
            val deadline = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < deadline) {
                Runloop.runLoop.main.frame()
                Thread.sleep(2L)
            }
        } else {
            Thread.sleep(ms)
        }
    }
}
