package afield.ai.core

import afield.*
import afield.four.Renderer
import afield.four.util.Window
import afield.four.util.lastWindowToOpen
import afield.four.util.openWindowsSnapshot
import java.nio.file.Files
import java.util.Base64

internal fun addWindowsFromValue(value: Any?, windows: MutableList<Window>) {
    when (value) {
        is Window -> windows.add(value)
        is Renderer -> windows.addAll(value.windows)
    }
}

internal fun Box.hierarchyForChatScreenshot(): List<Box> {
    val seen = mutableSetOf<Box>()
    val queue = ArrayDeque<Box>()
    val ordered = mutableListOf<Box>()
    queue.add(this)
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        if (!seen.add(next)) continue
        ordered.add(next)
        next.parents.forEach { queue.add(it) }
    }
    return ordered
}

internal fun windowsVisibleFrom(box: Box?, globalFallback: Boolean = false): List<Window> {
    val windows = mutableListOf<Window>()
    if (box != null) {
        box.hierarchyForChatScreenshot().forEach { candidate ->
            candidate.properties.values.forEach { addWindowsFromValue(it, windows) }
            runCatching {
                candidate.ki.context.vars.values.forEach { addWindowsFromValue(it.value, windows) }
            }
        }
    }
    if (globalFallback) {
        openWindowsSnapshot().forEach { windows.add(it) }
        lastWindowToOpen?.let { windows.add(it) }
    }
    return windows.distinct()
}

internal fun chooseChatScreenshotWindow(box: Box?, target: String, globalFallback: Boolean = false): Window? {
    val windows = windowsVisibleFrom(box, globalFallback).filter { window ->
        val renderer = window.associatedRenderer
        renderer != null && renderer.supportsDirectWindowCapture(window)
    }
    if (windows.isEmpty()) return null
    val trimmed = target.trim()
    if (trimmed.isEmpty()) return windows.first()

    trimmed.toIntOrNull()?.let { index ->
        return windows.getOrNull(index - 1)
    }

    return windows.filter { it.title.contains(trimmed, ignoreCase = true) }.singleOrNull()
}

internal fun resolveChatScreenshotBox(uid: String, sessionInfo: SessionInformation?): Box? {
    val requestUid = uid.trim()
    if (requestUid.isBlank()) return sessionInfo?.root

    if (sessionInfo != null) {
        if (requestUid == "root" || requestUid == sessionInfo.root.id) return sessionInfo.root
        sessionInfo.boxes[requestUid]?.let { return it }
        return null
    }

    return runCatching { box(requestUid, createIfMissing = false) }.getOrNull()
}

internal suspend fun captureChatScreenshot(
    request: AiChatScreenshotRequest,
    sessionInfo: SessionInformation? = thisSessionInfo.get()
): AiChatScreenshotResponse {
    val requestUid = request.uid.trim()
    val box = resolveChatScreenshotBox(requestUid, sessionInfo)
    if (box == null && requestUid.isNotBlank()) {
        return AiChatScreenshotResponse(success = false, message = "Unknown box '$requestUid'; no fallback capture was taken.")
    }
    if (box == null && requestUid.isBlank() && openWindowsSnapshot().isEmpty() && lastWindowToOpen == null) {
        return AiChatScreenshotResponse(
            success = false,
            message = "No active Field document or capturable afield.four Window for screenshot."
        )
    }

    val window = chooseChatScreenshotWindow(box, request.target, globalFallback = requestUid.isBlank()) ?: return AiChatScreenshotResponse(
        success = false,
        message = if (requestUid.isNotBlank() && box == null) {
            "No capturable afield.four Window is visible; box '$requestUid' was not found in the active session."
        } else {
            "No capturable afield.four Window is visible from this box."
        }
    )
    val renderer = window.associatedRenderer ?: return AiChatScreenshotResponse(
        success = false,
        message = "The selected afield.four Window is not attached to a Renderer."
    )

    val out = Files.createTempFile("field4_ai_chat_screenshot_", ".jpg")
    try {
        renderer.captureWindowToFile(window, out.toString())
        val bytes = Files.readAllBytes(out)
        val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
        return AiChatScreenshotResponse(
            success = true,
            dataUrl = dataUrl,
            message = "Captured ${window.title} (${window.width} x ${window.height}; JPEG ${bytes.size / 1024} KiB)",
            title = window.title,
            width = window.width,
            height = window.height
        )
    } finally {
        runCatching { Files.deleteIfExists(out) }
    }
}
