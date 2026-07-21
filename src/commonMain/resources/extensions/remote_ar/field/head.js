(function (global) {
    "use strict";

    const state = {
        version: 2,
        connection: "booting",
        connectionDetail: {},
        ready: false,
        executions: [],
        startedAt: performance.now()
    };
    let readyResolve;
    const ready = new Promise((resolve) => { readyResolve = resolve; });

    function renderStatus() {
        const element = document.getElementById("field-remote-status");
        if (!element) return;
        element.dataset.state = state.connection;
        const lines = [state.connection];
        if (state.selfTest) lines.push("self-test " + (state.selfTest.ok ? "pass" : "fail"));
        if (global.AFRAME && global.THREE) lines.push("A-Frame " + global.AFRAME.version + " / THREE r" + global.THREE.REVISION);
        if (global.FieldRemoteXR) lines.push("XR " + global.FieldRemoteXR.state.platform);
        if (state.executions.length) {
            const last = state.executions[state.executions.length - 1];
            lines.push("executions " + state.executions.length + " / " + last.kind + (last.codeName ? " / " + last.codeName : ""));
        }
        element.textContent = lines.join("\n");
    }

    function setConnectionState(connection, detail) {
        state.connection = connection;
        state.connectionDetail = detail || {};
        renderStatus();
    }

    function recordExecution(kind, envelope, value) {
        const execution = {
            kind,
            codeName: envelope && envelope.codeName,
            returnTo: envelope && envelope.returnTo,
            value,
            at: performance.now()
        };
        state.executions.push(execution);
        if (state.executions.length > 50) state.executions.shift();
        const status = document.getElementById("field-remote-status");
        if (status) {
            status.dataset.executionCount = String(state.executions.length);
            status.dataset.lastExecutionKind = kind;
            status.dataset.lastExecutionCodeName = execution.codeName || "";
        }
        renderStatus();
    }

    function snapshot() {
        return {
            version: state.version,
            ready: state.ready,
            connection: state.connection,
            connectionDetail: state.connectionDetail,
            clientId: _field.id,
            websocketOpen: !!_field.isOpen,
            aframe: global.AFRAME && global.AFRAME.version,
            three: global.THREE && global.THREE.REVISION,
            xr: global.FieldRemoteXR ? Object.assign({}, global.FieldRemoteXR.state, {session: !!global.FieldRemoteXR.state.session}) : null,
            sceneChildren: global.SCENE ? global.SCENE.children.length : 0,
            taskCount: Object.keys(global.__tasks || {}).length,
            executionCount: state.executions.length
        };
    }

    async function selfTest() {
        const results = [];
        function check(name, condition, detail) {
            results.push({name, ok: !!condition, detail: detail == null ? "" : String(detail)});
        }

        check("aframe-loaded", !!global.AFRAME, global.AFRAME && global.AFRAME.version);
        check("three-loaded", !!global.THREE, global.THREE && global.THREE.REVISION);
        check("scene-ready", !!global.SCENE);

        const sandboxId = "remote-head-self-test";
        runInSandbox(sandboxId, "answer = 6 * 7");
        check("sandbox-write", sandboxTargets.get(sandboxId).answer === 42, sandboxTargets.get(sandboxId).answer);

        const completion = FieldRemoteCompletion.completeMe("THREE.Vec".length, "THREE.Vec");
        check("runtime-completion", completion.some((item) => item.title === "Vector3"), completion.map((item) => item.title).join(","));

        let described;
        try {
            global.eval("throw new Error('self-test')\n//# sourceURL=box_remote_head_self_test");
        } catch (error) {
            described = FieldRemoteErrors.describe(error, {codeName: "box_remote_head_self_test"});
        }
        check(
            "error-location",
            described && described.message === "self-test" && described.filename === "box_remote_head_self_test" && described.line === 1,
            JSON.stringify(described)
        );

        const fline = new FLine();
        fline.moveTo(0, 0, 0).lineTo(1, 0, 0).lineTo(0, 1, 0);
        fline.bindToName_s("remote_head_self_test");
        check("fline-stroke", !!fline.stroke && fline.stroke.geometry.drawRange.count > 0, fline.stroke && fline.stroke.geometry.drawRange.count);

        const curved = new FLine();
        curved
            .moveTo(new THREE.Vector2(0, 0))
            .lineTo(new THREE.Vector2(1, 0))
            .cubicTo(new THREE.Vector2(1, 1), new THREE.Vector2(0, 1), new THREE.Vector2(0, 0));
        check("fline-segments", curved.buildSegments().length === 2, curved.buildSegments().length);
        check("fline-hit-callbacks", !!(curved.mouseDown && curved.mouseUp && curved.mouseMove && curved.dblClick), "down/up/move/dblclick");
        check("gltf-loader", typeof THREE.GLTFLoader === "function", typeof THREE.GLTFLoader);

        let taskTicks = 0;
        let taskStopped = false;
        new Task(
            "remote-head-self-test-task",
            function () { return "SKIP"; },
            function () { taskTicks += 1; return taskTicks < 2; },
            function () {},
            function () { taskStopped = true; },
            function (error) { throw error; },
            false
        );
        for (let index = 0; index < 12 && !taskStopped; index += 1) {
            await new Promise((resolve) => requestAnimationFrame(resolve));
        }
        check("aframe-task-service", taskTicks === 2 && taskStopped, "ticks=" + taskTicks + ", stopped=" + taskStopped);

        const report = {ok: results.every((item) => item.ok), results, snapshot: snapshot()};
        state.selfTest = report;
        document.documentElement.dataset.fieldRemoteSelfTest = report.ok ? "pass" : "fail";
        const status = document.getElementById("field-remote-status");
        if (status) status.dataset.selfTestReport = JSON.stringify(report);
        renderStatus();
        return report;
    }

    function markReady() {
        if (state.ready) return;
        state.ready = true;
        readyResolve(snapshot());
        if (new URL(global.location.href).searchParams.get("selftest") === "1") {
            selfTest()
                .then((report) => console.log("Field RemoteJS self-test", report))
                .catch((error) => {
                    const report = {
                        ok: false,
                        results: [{name: "self-test-exception", ok: false, detail: String(error && (error.stack || error.message) || error)}],
                        snapshot: snapshot()
                    };
                    state.selfTest = report;
                    document.documentElement.dataset.fieldRemoteSelfTest = "fail";
                    const status = document.getElementById("field-remote-status");
                    if (status) status.dataset.selfTestReport = JSON.stringify(report);
                    renderStatus();
                    console.error("Field RemoteJS self-test failed", error);
                });
        }
    }

    global.__fieldRemoteHead = {
        state,
        ready,
        setConnectionState,
        recordExecution,
        snapshot,
        selfTest,
        markReady
    };
})(window);
