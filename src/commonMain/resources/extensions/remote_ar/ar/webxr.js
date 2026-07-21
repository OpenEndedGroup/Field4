(function (global) {
    "use strict";

    const hitListeners = new Set();
    const selectListeners = new Set();
    const state = {
        platform: global.ARKitBridge ? "endrun-ios" : (navigator.xr ? "native-webxr" : "flat"),
        session: null,
        referenceSpace: null,
        viewerSpace: null,
        hitTestSource: null,
        latestHit: null,
        frameCount: 0
    };

    function callListeners(listeners, value) {
        listeners.forEach((listener) => {
            try {
                listener(value);
            } catch (error) {
                handleCallbackError(error);
            }
        });
    }

    function hitPayload(result, pose, frame) {
        const matrix = Array.from(pose.transform.matrix);
        const position = new THREE.Vector3().setFromMatrixPosition(new THREE.Matrix4().fromArray(matrix));
        return {result, pose, frame, matrix, position, time: performance.now()};
    }

    async function beginSession(session, renderer) {
        state.session = session;
        __superglobal.session = session;
        state.referenceSpace = await session.requestReferenceSpace("local-floor");
        state.viewerSpace = await session.requestReferenceSpace("viewer");
        if (typeof session.requestHitTestSource === "function") {
            try {
                state.hitTestSource = await session.requestHitTestSource({space: state.viewerSpace});
            } catch (error) {
                console.warn("WebXR hit-test source is unavailable", error);
            }
        }
        session.addEventListener("select", function (event) {
            callListeners(selectListeners, {event, hit: state.latestHit, session, renderer});
        });
    }

    function endSession() {
        if (state.hitTestSource && typeof state.hitTestSource.cancel === "function") state.hitTestSource.cancel();
        state.session = null;
        state.referenceSpace = null;
        state.viewerSpace = null;
        state.hitTestSource = null;
        state.latestHit = null;
        __superglobal.session = undefined;
    }

    AFRAME.registerSystem("field-remote-runtime", {
        init: function () {
            __setServiceDriver("aframe");
            const renderer = this.sceneEl.renderer;
            renderer.xr.addEventListener("sessionstart", () => {
                const session = renderer.xr.getSession();
                beginSession(session, renderer).catch((error) => {
                    console.error("RemoteJS WebXR session setup failed", error);
                    handleCallbackError(error);
                });
            });
            renderer.xr.addEventListener("sessionend", endSession);
        },

        tick: function (time, timeDelta) {
            frame += 1;
            state.frameCount = frame;
            if (mixer == null && global.SCENE) mixer = new THREE.AnimationMixer(global.SCENE);
            if (mixer != null) mixer.update(Math.max(0, timeDelta || 0) / 1000);
            __service();
        },

        remove: function () {
            endSession();
            __setServiceDriver("raf");
        }
    });

    AFRAME.registerComponent("field-xr-hit-test", {
        tick: function () {
            const xrFrame = this.el.sceneEl.frame;
            if (!xrFrame || !state.hitTestSource || !state.referenceSpace) return;
            const hits = xrFrame.getHitTestResults(state.hitTestSource);
            if (!hits.length) {
                this.el.object3D.visible = false;
                state.latestHit = null;
                return;
            }
            const pose = hits[0].getPose(state.referenceSpace);
            if (!pose) return;
            state.latestHit = hitPayload(hits[0], pose, xrFrame);
            this.el.object3D.visible = true;
            this.el.object3D.position.copy(state.latestHit.position);
            this.el.object3D.quaternion.setFromRotationMatrix(new THREE.Matrix4().fromArray(state.latestHit.matrix));
            callListeners(hitListeners, state.latestHit);
        }
    });

    global.FieldRemoteXR = {
        state,
        onHit: function (callback) { hitListeners.add(callback); return () => hitListeners.delete(callback); },
        onSelect: function (callback) { selectListeners.add(callback); return () => selectListeners.delete(callback); },
        latestHit: function () { return state.latestHit; }
    };
    __superglobal.onXRHit = global.FieldRemoteXR.onHit;
    __superglobal.onXRSelect = global.FieldRemoteXR.onSelect;
    __superglobal.xrHit = global.FieldRemoteXR.latestHit;
})(window);
