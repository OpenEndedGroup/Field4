package afield.graphics

import org.graalvm.polyglot.Context
import kotlin.test.Test
import kotlin.test.assertTrue

class ThreePanelCameraStateTest {
    @Test fun payloadUpdatesKeepTheOrbitReferenceUntilTheLayoutChanges() {
        val source = ViewerJavaScript.VIEWER.readText()
            .substringAfter("function applyScenePanels(payload) {")
            .substringBefore("/** Panel titles/captions")
        Context.create("js").use { js ->
            js.eval("js", """
                const panelState = {panels: [], columns: 1, cameras: [], base: null};
                const THREE = {PerspectiveCamera: function() {}};
                const document = {getElementById: () => ({classList: {toggle() {}}})};
                function clearPanelLabels() {}
                function renderPanelChrome() {}
                function panelViewport() {return {w: 400, h: 400};}
                function fitPointsForPanel() {return [];}
                function applyCameraSpec() {}
                function applyScenePanels(payload) {$source
            """.trimIndent())
            assertTrue(js.eval("js", """
                (() => {
                    const payload = {columns: 3, panels: [{}, {}, {}]};
                    applyScenePanels(payload);
                    if (panelState.base !== null) return false;
                    const reference = {polar: 1, azimuth: .6, distance: 20};
                    panelState.base = reference;
                    // Selection, solved geometry and captions can change repeatedly while orbiting.
                    for (let i = 0; i < 5; i++) {
                        applyScenePanels({...payload, panels: [{title: 'Updated '+i}, {}, {}]});
                        if (panelState.base !== reference) return false;
                    }
                    applyScenePanels({...payload, columns: 2});
                    if (panelState.base !== null) return false;
                    panelState.base = reference;
                    applyScenePanels({panels: [{}]});
                    return panelState.base === null;
                })()
            """.trimIndent()).asBoolean())
        }
    }
}
