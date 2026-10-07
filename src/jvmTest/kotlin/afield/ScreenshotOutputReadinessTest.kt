package afield

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the production preparation scripts in Chromium; no fixture image is published. */
class ScreenshotOutputReadinessTest {
    private fun withPage(content: String, script: String = "", outputArea: Boolean = true, test: (Page) -> Unit) {
        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                browser.newPage().use { page ->
                    page.route("http://capture.test/**") { route ->
                        val body = if (outputArea) """<div class="outputArea">$content</div>""" else content
                        route.fulfill(Route.FulfillOptions().setContentType("text/html").setBody("""
                            <div id="overlaycanvas"></div><div class="box" data-uid="subject">
                            $body</div>
                        """.trimIndent()))
                    }
                    page.navigate("http://capture.test/")
                    page.evaluate("""() => {
                        window.fieldClientOpen = true;
                        window.fieldBackdoorUiReady = true;
                        const prepare = request => {
                            const area = document.querySelector('.outputArea');
                            if (area) area.style.visibility = 'visible';
                            return JSON.stringify({uid: request.uid, success: true});
                        };
                        window.fieldBackdoorPrepareBoxScreenshot = prepare;
                        window.fieldBackdoorPrepareWindowScreenshot = prepare;
                        $script
                    }""")
                    test(page)
                }
            }
        }
    }

    private fun assertBoth(
        page: Page,
        expected: Boolean,
        openOutputs: Boolean = true,
        expectOutputs: String = ExpectedOutputs.PANEL
    ) {
        // Failure fixtures need only exercise expiry; success fixtures allow loaded CI hosts room
        // to schedule Chromium. Neither assertion is a performance threshold.
        val timeout = if (expected) 5_000 else 700
        val box = requestBoxScreenshot(
            page, BoxScreenshotRequest("subject", openOutputs = openOutputs, expectOutputs = expectOutputs), timeout
        )
        val window = requestWindowScreenshot(
            page, WindowScreenshotRequest(uid = "subject", openOutputs = openOutputs, expectOutputs = expectOutputs), timeout
        )
        if (expected) {
            assertTrue(box.success, box.message)
            assertTrue(window.success, window.message)
        } else {
            assertFalse(box.success, "box capture unexpectedly accepted missing/unready content")
            assertFalse(window.success, "window capture unexpectedly accepted missing/unready content")
            assertTrue(box.message.contains("output"), box.message)
            assertTrue(window.message.contains("output"), window.message)
        }
    }

    @Test fun missingOutputIsNotSuccessfulContent() = withPage("") { assertBoth(it, false) }

    @Test fun emptyDisclosureIsNotSuccessfulContent() = withPage(
        "<div class='disclosure-content' style='width:300px;height:200px'></div>"
    ) { assertBoth(it, false) }

    @Test fun intentionalHandleOnlyCaptureIsAllowed() = withPage("") { assertBoth(it, true, openOutputs = false) }

    @Test fun titleWithoutLaidOutContentIsNotEnough() = withPage(
        "title<div class='disclosure-content' style='width:300px;height:0;overflow:hidden'>hidden body</div>"
    ) { assertBoth(it, false) }

    @Test fun smallRealOutputIsAllowedAndRevealedBeforeWaiting() = withPage(
        "<div class='disclosure-content' style='width:8px;height:8px'>x</div>",
        "document.querySelector('.outputArea').style.visibility = 'hidden';"
    ) { assertBoth(it, true) }

    @Test fun fieldZeroWidthOverflowShellDoesNotHideRealContent() = withPage(
        "<div class='disclosure-content' style='width:320px;height:180px'>real output</div>",
        "document.querySelector('.outputArea').style.width = '0px';"
    ) { assertBoth(it, true) }

    @Test fun inlineChannelValueIsRealOutput() = withPage(
        "<div class='disclosure'><div class='inline-value'>inline status</div><div class='disclosure-content' style='display:none'>inline status</div></div>"
    ) { assertBoth(it, true) }

    @Test fun continuouslyChangingGeometryFails() = withPage(
        "<div class='disclosure-content' style='width:300px;height:200px'>changing</div>",
        """let width = 300;
           document.querySelector('.outputArea').getBoundingClientRect = () => ({width: width++, height: 200});"""
    ) { assertBoth(it, false) }

    @Test fun busyOutputFailsForWindowAsWellAsBox() = withPage(
        "<div class='disclosure-content' style='width:300px;height:200px'><div aria-busy='true'>loading</div></div>"
    ) { assertBoth(it, false) }

    private fun withViewer(script: String, test: (Page) -> Unit) = withPage(
        """<div class="disclosure-content"><iframe srcdoc="<p>geometry</p>"></iframe></div>""",
        """const viewer = document.querySelector('iframe').contentWindow; $script""", test = test
    )

    @Test fun loadedViewerWithPendingToolsIsNotReady() = withViewer(
        "viewer.FieldViewer = {captureStatus: () => ({ready:false, error:null})};"
    ) { assertBoth(it, false) }

    @Test fun loadedViewerWithFailedToolsReportsTheCause() = withViewer(
        "viewer.FieldViewer = {captureStatus: () => ({ready:false, error:'Target tool could not connect'})};"
    ) { page ->
        val box = requestBoxScreenshot(page, BoxScreenshotRequest("subject", openOutputs = true), 700)
        val window = requestWindowScreenshot(page, WindowScreenshotRequest(uid = "subject", openOutputs = true), 700)
        assertFalse(box.success)
        assertFalse(window.success)
        assertTrue(box.message.contains("Target tool could not connect"), box.message)
        assertTrue(window.message.contains("Target tool could not connect"), window.message)
    }

    @Test fun captureWaitsForViewerToolsAfterDocumentLoad() = withViewer(
        "viewer.FieldViewer = {captureStatus: () => ({ready:viewer.toolReady === true, error:null})};"
    ) { page ->
        fun delayedTool() = page.evaluate("""() => {
            const viewer = document.querySelector('iframe').contentWindow;
            viewer.toolReady = false;
            setTimeout(() => { viewer.toolReady = true; }, 1600);
        }""")
        fun assertReady() = assertTrue(page.evaluate("() => document.querySelector('iframe').contentWindow.toolReady") == true)
        delayedTool()
        assertTrue(requestBoxScreenshot(page, BoxScreenshotRequest("subject", openOutputs = true), 5_000).success)
        assertReady()
        delayedTool()
        assertTrue(requestWindowScreenshot(page, WindowScreenshotRequest(uid = "subject", openOutputs = true), 5_000).success)
        assertReady()
    }

    // ---- what a fence declares its output to be (#115) -------------------------------------
    //
    // `open_outputs` defaults to true for all 655 runner fences, and once readiness demanded an
    // `.outputArea` that default made every `capture="editor"` page uncapturable: a box whose
    // output is chips beside the code has no panel, so the wait could never end. The fix is for a
    // fence to say which it is. These four pin both directions of that, because a declaration that
    // only ever relaxes the rule would be worth nothing.

    @Test fun inlineOutputIsReadyWithNoPanelAtAll() = withPage(
        "<div class='editor' style='width:400px;height:120px'>(println &quot;x&quot;) <span>4 edges</span></div>",
        outputArea = false
    ) { assertBoth(it, true, expectOutputs = ExpectedOutputs.INLINE) }

    @Test fun aPanelCaptureWithNoPanelStillFails() = withPage(
        "<div class='editor' style='width:400px;height:120px'>no panel here</div>",
        outputArea = false
    ) { assertBoth(it, false, expectOutputs = ExpectedOutputs.PANEL) }

    @Test fun declaringInlineDoesNotExcuseAStuckPanel() = withPage(
        "<div class='disclosure-content' style='width:300px;height:200px'><div aria-busy='true'>loading</div></div>"
    ) { assertBoth(it, false, expectOutputs = ExpectedOutputs.INLINE) }

    @Test fun inlineToleratesAPanelThatIsMerelyEmpty() = withPage(
        // Empty is not stuck. An empty panel beside laid-out editor content is exactly the shape of
        // an inline figure, and is the case `panel` rejects (missingOutputIsNotSuccessfulContent)
        // and `inline` must not. The editor div is what gives the box a measurable size: a 0x0 box
        // is never ready under either declaration, which is the unopened-channel trap.
        "<div class='editor' style='width:400px;height:120px'>(println &quot;x&quot;)</div><div class='outputArea'></div>",
        outputArea = false
    ) { assertBoth(it, true, expectOutputs = ExpectedOutputs.INLINE) }

    @Test fun noneIsAnIntentionalEmptyCapture() = withPage("", outputArea = false) {
        assertBoth(it, true, expectOutputs = ExpectedOutputs.NONE)
    }

    // ---- a page that never answers ---------------------------------------------------------
    //
    // `Page.evaluate` has no timeout, and one Playwright thread serves every capture in every
    // document. On the house host (2026-10-06) one prep script awaited a promise that never settled
    // and held that thread for 50 minutes. Each of these must come back inside the prep deadline
    // plus its margins, naming what it was waiting for. The bound is the property, not a speed.

    private val realOutput = "<div class='disclosure-content' style='width:300px;height:200px'>real output</div>"

    /** Fails inside the script's own hard deadline, so before the driver's wall clock would. */
    private fun assertStalls(page: Page, waitingFor: String, prepMs: Int) {
        val limit = prepMs + SCREENSHOT_PREP_HARD_MARGIN_MS + SCREENSHOT_PREP_DRIVER_MARGIN_MS
        for (capture in listOf<() -> String>(
            { requestBoxScreenshot(page, BoxScreenshotRequest("subject", openOutputs = true), prepMs).also { assertFalse(it.success) }.message },
            { requestWindowScreenshot(page, WindowScreenshotRequest(uid = "subject", openOutputs = true), prepMs).also { assertFalse(it.success) }.message }
        )) {
            val started = System.nanoTime()
            val message = capture()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < limit, "took $elapsedMs ms against a $limit ms limit: $message")
            assertTrue(message.startsWith(SCREENSHOT_PREP_STALLED), message)
            assertTrue(message.contains(waitingFor), message)
        }
    }

    @Test fun prepareHookThatNeverResolvesFailsWithinTheDeadline() = withPage(
        realOutput,
        """window.fieldBackdoorPrepareBoxScreenshot = () => new Promise(() => {});
           window.fieldBackdoorPrepareWindowScreenshot = () => new Promise(() => {});"""
    ) { page ->
        // The hook is the first await, so a short prep deadline is enough to reach it.
        assertStalls(page, "fieldBackdoorPrepare", prepMs = 700)
        // The page is still usable: a hook that answers again is captured normally.
        page.evaluate("""() => {
            const ok = request => JSON.stringify({uid: request.uid, success: true});
            window.fieldBackdoorPrepareBoxScreenshot = ok;
            window.fieldBackdoorPrepareWindowScreenshot = ok;
        }""")
        assertBoth(page, true)
    }

    @Test fun pageThatRendersNoFramesFailsWithinTheDeadline() = withPage(
        realOutput, "window.requestAnimationFrame = () => 0;"
    ) {
        // The first frame wait comes after readiness and settling, which a loaded host may be slow
        // to reach; the prep deadline leaves it room, as the success fixtures above do.
        assertStalls(it, "an animation frame", prepMs = 3_000)
    }

    @Test fun pageThatCannotRunTimersIsStoppedByTheDriver() = withPage(
        realOutput,
        """window.fieldBackdoorPrepareBoxScreenshot = () => new Promise(() => {});
           window.fieldBackdoorPrepareWindowScreenshot = () => new Promise(() => {});
           window.setTimeout = () => 0;"""
    ) { page ->
        // The script's own deadlines are timers, so here only the driver's wall clock can end it.
        val prepMs = 700
        val wallClock = prepMs + SCREENSHOT_PREP_HARD_MARGIN_MS + SCREENSHOT_PREP_DRIVER_MARGIN_MS
        for (capture in listOf<() -> Any>(
            { requestBoxScreenshot(page, BoxScreenshotRequest("subject", openOutputs = true), prepMs) },
            { requestWindowScreenshot(page, WindowScreenshotRequest(uid = "subject", openOutputs = true), prepMs) }
        )) {
            val started = System.nanoTime()
            val failure = assertFailsWith<IllegalStateException> { capture() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < wallClock + 10_000L, "took $elapsedMs ms: ${failure.message}")
            assertTrue(failure.message!!.contains("did not answer within $wallClock ms"), failure.message)
        }
    }

    // ---- the docs video path ----------------------------------------------------------------
    //
    // The video recorder waits for two painted frames after clearing a box's presentation and
    // before every movie frame, the latter with the Field runloop parked as well. Each wait used to
    // be a bare double-RAF evaluate, the same shape as the one that held the Playwright thread for
    // 50 minutes.

    @Test fun videoPaintWaitAnswersOnARenderingPage() = withPage(realOutput) { page ->
        // A loaded host may be slow to schedule Chromium; the timeout leaves it room.
        awaitPaintedFrames(page, "video paint", timeoutMs = 5_000)
    }

    @Test fun videoPaintWaitFailsOnAPageThatRendersNoFrames() = withPage(
        realOutput, "window.requestAnimationFrame = () => 0;"
    ) { page ->
        val timeoutMs = 700
        val started = System.nanoTime()
        val failure = assertFailsWith<IllegalStateException> { awaitPaintedFrames(page, "video paint", timeoutMs) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // The page's own timer answers, before the driver's wall clock would.
        assertTrue(elapsedMs < timeoutMs + SCREENSHOT_PREP_DRIVER_MARGIN_MS, "took $elapsedMs ms: ${failure.message}")
        assertTrue(
            failure.message!!.contains("video paint: the page rendered no frame within $timeoutMs ms"),
            failure.message
        )
        // The page still answers.
        assertTrue(page.evaluate("() => document.querySelector('.box') !== null") == true)
    }

    @Test fun videoPaintWaitOnAPageThatCannotRunTimersIsStoppedByTheDriver() = withPage(
        realOutput, "window.requestAnimationFrame = () => 0; window.setTimeout = () => 0;"
    ) { page ->
        val timeoutMs = 700
        val wallClock = timeoutMs + SCREENSHOT_PREP_DRIVER_MARGIN_MS
        val started = System.nanoTime()
        val failure = assertFailsWith<IllegalStateException> { awaitPaintedFrames(page, "video paint", timeoutMs) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < wallClock + 10_000L, "took $elapsedMs ms: ${failure.message}")
        assertTrue(failure.message!!.contains("video paint did not answer within $wallClock ms"), failure.message)
    }
}
