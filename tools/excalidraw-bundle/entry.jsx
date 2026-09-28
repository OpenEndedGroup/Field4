// Field's drawing layer: an Excalidraw scene laid over the box canvas.
//
// This is the only React in Field. It is bundled by build.mjs into
// src/jsMain/resources/excalidraw/field-excalidraw.js and loaded lazily by
// src/jsMain/kotlin/afield/drawingLayer.kt, which owns everything Field-specific: the
// camera, the mode toggle, and persistence of the root box's `drawing` property. This file only
// adapts Excalidraw to the small surface that Kotlin drives.
//
// Coordinates: Excalidraw draws scene point p at (p + scroll) * zoom inside its container. The
// container covers the window from (0, 0), and Field draws world point p at
// p * mainZoom + mainTranslation, so the scene *is* Field's world space, Excalidraw's zoom is
// mainZoom, and its scroll is mainTranslation / mainZoom. Kotlin does that conversion; this file
// only speaks Excalidraw's (scrollX, scrollY, zoom).

import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
    CaptureUpdateAction,
    Excalidraw,
    reconcileElements,
    restoreElements,
} from "@excalidraw/excalidraw";
import "@excalidraw/excalidraw/index.css";

const SCENE_DEBOUNCE_MS = 250;

/** Cheap change detector: Excalidraw bumps `version` on every element mutation. */
function sceneVersion(elements) {
    let sum = elements.length;
    for (const e of elements) sum += e.version;
    return sum;
}

function parseScene(json) {
    if (!json) return [];
    try {
        const parsed = JSON.parse(json);
        return restoreElements(Array.isArray(parsed?.elements) ? parsed.elements : [], null);
    } catch (e) {
        console.warn("[drawing] could not parse drawing scene", e);
        return [];
    }
}

/**
 * The persisted form is a complete `.excalidraw` file, so the sidecar opens on excalidraw.com.
 * Deleted elements are kept: they are the tombstones that carry a deletion to other clients.
 */
function serializeScene(elements) {
    return JSON.stringify({
        type: "excalidraw",
        version: 2,
        source: "field",
        elements,
        appState: { viewBackgroundColor: "transparent", gridSize: null },
        files: {},
    });
}

/**
 * Mounts the layer into [host] and returns the handle Kotlin drives.
 *
 * opts.initialScene   `.excalidraw` JSON or null
 * opts.initialCamera        [scrollX, scrollY, zoom] in Excalidraw's terms
 * opts.onCamera(sx, sy, zoom) the user panned or zoomed inside Excalidraw
 * opts.onScene(json)  the elements changed locally (debounced, and held back mid-gesture)
 * opts.onSelection(ids) the selection changed
 */
export function mountDrawingLayer(host, opts) {
    const root = document.createElement("div");
    root.className = "field-drawing-root";
    host.appendChild(root);

    let api = null;
    let setActiveState = null;
    let lastVersion = -1;
    let sendTimer = null;
    let pendingActive = false;
    const [initialX, initialY, initialZoom] = opts.initialCamera ?? [0, 0, 1];
    const initialElements = parseScene(opts.initialScene);
    lastVersion = sceneVersion(initialElements);

    // Excalidraw's keyboard handling (handleKeyboardGlobally=false) listens on this container,
    // which is the one element in the layer with a tabIndex.
    const focusCanvas = () => host.querySelector(".excalidraw-container")?.focus();

    // A scene is held back while a gesture is still going: a stroke being drawn, a shape being
    // dragged or resized, text being typed. The server's reactions then see finished marks — an
    // `onStroke` handler gets the whole stroke, not the first 250 ms of it.
    const midGesture = (s) => !!(s.newElement || s.multiElement || s.resizingElement || s.editingTextElement ||
        s.selectedElementsAreBeingDragged || s.isResizing || s.isRotating || s.editingLinearElement?.isDragging);
    let sendPending = false;
    let lastSelection = "";
    let selectionTimer = null;

    const onChange = (elements, appState) => {
        const v = sceneVersion(elements);
        if (v !== lastVersion) {
            lastVersion = v;
            sendPending = true;
        }
        if (sendPending && !midGesture(appState)) {
            clearTimeout(sendTimer);
            sendTimer = setTimeout(() => {
                if (!api || midGesture(api.getAppState())) return;
                sendPending = false;
                opts.onScene(serializeScene(api.getSceneElementsIncludingDeleted()));
            }, SCENE_DEBOUNCE_MS);
        }
        const selection = Object.keys(appState.selectedElementIds || {}).filter((id) => appState.selectedElementIds[id]).sort().join(",");
        if (selection !== lastSelection && opts.onSelection) {
            lastSelection = selection;
            clearTimeout(selectionTimer);
            selectionTimer = setTimeout(() => opts.onSelection(selection ? selection.split(",") : []), 120);
        }
    };

    const onScrollChange = (x, y, zoom) => opts.onCamera(x, y, zoom.value);

    function DrawingLayer() {
        const [active, setActive] = useState(pendingActive);
        useEffect(() => {
            setActiveState = setActive;
        }, []);
        return (
            <Excalidraw
                excalidrawAPI={(a) => {
                    api = a;
                    // The first toggle mounts us and asks for focus before the API exists.
                    if (pendingActive) requestAnimationFrame(focusCanvas);
                }}
                initialData={{
                    elements: initialElements,
                    appState: {
                        viewBackgroundColor: "transparent",
                        scrollX: initialX,
                        scrollY: initialY,
                        zoom: { value: initialZoom },
                    },
                    scrollToContent: false,
                }}
                theme="dark"
                viewModeEnabled={!active}
                zenModeEnabled={!active}
                handleKeyboardGlobally={false}
                detectScroll={false}
                onChange={onChange}
                onScrollChange={onScrollChange}
                UIOptions={{
                    canvasActions: {
                        changeViewBackgroundColor: false,
                        clearCanvas: true,
                        export: false,
                        loadScene: false,
                        saveToActiveFile: false,
                        saveAsImage: true,
                        toggleTheme: null,
                    },
                    tools: { image: false },
                }}
            />
        );
    }

    const reactRoot = createRoot(root);
    reactRoot.render(<DrawingLayer />);

    return {
        /** Field panned or zoomed: follow it. A no-op when Excalidraw is already there. */
        setCamera(x, y, zoom) {
            if (!api) return;
            const s = api.getAppState();
            if (Math.abs(s.scrollX - x) < 0.01 && Math.abs(s.scrollY - y) < 0.01 && Math.abs(s.zoom.value - zoom) < 1e-6) return;
            api.updateScene({
                appState: { scrollX: x, scrollY: y, zoom: { value: zoom } },
                captureUpdate: CaptureUpdateAction.NEVER,
            });
        },

        setActive(active) {
            pendingActive = active;
            if (setActiveState) setActiveState(active);
            if (!api) return;
            if (active) {
                requestAnimationFrame(focusCanvas);
            } else {
                api.setActiveTool({ type: "selection" });
                api.updateScene({
                    appState: { selectedElementIds: {}, editingTextElement: null },
                    captureUpdate: CaptureUpdateAction.NEVER,
                });
                if (host.contains(document.activeElement)) document.activeElement.blur();
            }
        },

        /** Another client (or a sidecar edit) changed the scene. Merged by element version. */
        applyRemoteScene(json) {
            if (!api) return;
            const remote = parseScene(json);
            const merged = reconcileElements(api.getSceneElementsIncludingDeleted(), remote, api.getAppState());
            lastVersion = sceneVersion(merged);
            api.updateScene({ elements: merged, captureUpdate: CaptureUpdateAction.NEVER });
        },

        /** For tests and the backdoor. */
        state() {
            if (!api) return { ready: false };
            const s = api.getAppState();
            return {
                ready: true,
                active: pendingActive,
                scrollX: s.scrollX,
                scrollY: s.scrollY,
                zoom: s.zoom.value,
                tool: s.activeTool.type,
                elementCount: api.getSceneElements().length,
            };
        },

        api() {
            return api;
        },

        unmount() {
            clearTimeout(sendTimer);
            reactRoot.unmount();
            root.remove();
        },
    };
}
