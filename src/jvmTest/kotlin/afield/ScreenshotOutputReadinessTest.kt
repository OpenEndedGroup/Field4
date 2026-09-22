package afield

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import kotlin.test.Test
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
}
