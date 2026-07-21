console.log("Field RemoteJS is booting");

var _field = {};
var frame = 0;
var thatImageBuffer;
var SCENE;
var CAMERA;
var _r;
var globlSourceCache = {};
var FIELD;
var globalCodeName = "unknown";

__beginService();

(function (global) {
    "use strict";

    const outgoing = [];
    const MAX_QUEUED_MESSAGES = 256;
    let reconnectAttempt = 0;
    let reconnectTimer = null;
    let manuallyClosed = false;

    function remoteHead() {
        return global.__fieldRemoteHead || null;
    }

    function setConnectionState(state, detail) {
        const head = remoteHead();
        if (head && typeof head.setConnectionState === "function") {
            head.setConnectionState(state, detail || {});
        }
    }

    function socketURL() {
        const page = new URL(global.location.href);
        const override = page.searchParams.get("field");
        if (!override) {
            page.protocol = page.protocol === "https:" ? "wss:" : "ws:";
            page.pathname = "/ws";
            page.search = "";
            page.hash = "";
            return page.toString();
        }

        let value = override.trim();
        if (!/^[a-z]+:\/\//i.test(value)) {
            value = (page.protocol === "https:" ? "wss://" : "ws://") + value;
        }
        const target = new URL(value);
        if (target.protocol === "http:") target.protocol = "ws:";
        if (target.protocol === "https:") target.protocol = "wss:";
        if (target.protocol !== "ws:" && target.protocol !== "wss:") {
            throw new Error("The ?field= address must use http(s) or ws(s)");
        }
        target.pathname = target.pathname === "/" ? "/ws" : target.pathname;
        target.search = "";
        target.hash = "";
        return target.toString();
    }

    function flushOutgoing() {
        while (_field.socket && _field.socket.readyState === WebSocket.OPEN && outgoing.length) {
            _field.socket.send(outgoing.shift());
        }
    }

    _field.send = function (address, payload) {
        const encoded = JSON.stringify({address, payload, from: _field.id});
        if (_field.socket && _field.socket.readyState === WebSocket.OPEN) {
            _field.socket.send(encoded);
            return true;
        }
        if (outgoing.length >= MAX_QUEUED_MESSAGES) outgoing.shift();
        outgoing.push(encoded);
        return false;
    };

    _field.log = function (value) {
        _field.send("log", value);
    };

    _field.error = function (value) {
        _field.send("error", String(value));
    };

    function safe(value, maxLength) {
        if (typeof value === "undefined") return value;
        const string = String(value);
        if (string.length <= maxLength) return "<json>" + string;
        return "[... " + string.substring(0, Math.min(500, maxLength)) + " +" + string.length + " characters long ...]";
    }

    function installPrinting() {
        const print = function (value) {
            const returnTo = global.__sandbox__ && global.__sandbox__.__returnTo
                ? global.__sandbox__.__returnTo
                : _field.globalReturnTo;
            _field.send(returnTo, {
                message: safe(betterPretty(value, 2, "JSON", false), 120000),
                kind: "SUCCESS"
            });
        };
        __superglobal.print = print;
        __superglobal.log = print;
        global.print = print;
        global.log = print;
    }

    function sendExecutionError(error, envelope) {
        const detail = FieldRemoteErrors.describe(error, {
            codeName: envelope.codeName,
            fallbackLine: envelope.lineOffset || -1
        });
        _field.send(envelope.returnTo, Object.assign({kind: "ERROR"}, detail));
        const head = remoteHead();
        if (head && typeof head.recordExecution === "function") head.recordExecution("error", envelope, detail);
        console.error("RemoteJS execution failed", detail, error);
    }

    function taskForGenerator(generator, envelope) {
        return new Task(
            envelope.codeName,
            function () { return "SKIP"; },
            generator.next.bind(generator),
            function () {},
            function () {
                _field.send(envelope.returnTo, {message: "OK", kind: "RUN-STOP", altName: ""});
            },
            function (error) { sendExecutionError(error, envelope); }
        );
    }

    function configureTaskTime(task, envelope) {
        if (task && typeof envelope.timeStart !== "undefined") {
            task.configureTimeRemapping(envelope.timeStart, envelope.timeEnd);
        }
    }

    async function executeEnvelope(envelope) {
        if (!envelope || typeof envelope.code !== "string" || !envelope.code.trim()) return;

        _field.globalReturnTo = envelope.returnTo;
        _field.__var0 = envelope.__var0;
        _r = undefined;
        __superglobal._r = undefined;
        __superglobal._field = _field;

        globalCodeName = envelope.codeName || "remotejs";
        global.globalCodeName = globalCodeName;
        const code = envelope.code + "\n//# sourceURL=" + globalCodeName + "\n";
        globlSourceCache = {[globalCodeName]: envelope.code};
        const context = globalCodeName.indexOf("|") === -1 ? "root" : globalCodeName.split("|").pop();
        let result;

        try {
            if (envelope.noSandbox) {
                result = global.eval(code);
            } else if (envelope.code.trim().split("\n").length < 2) {
                result = runInSandbox(context, code);
            } else {
                const factory = runInSandbox_generator(context, code);
                if (factory) {
                    const generator = factory();
                    const first = generator.next();
                    result = first.value;
                    if (!first.done) {
                        const task = taskForGenerator(generator, envelope);
                        configureTaskTime(task, envelope);
                        _field.send(envelope.returnTo, {message: "OK", kind: "RUN-START", altName: ""});
                    }
                }
            }

            if (result && typeof result.then === "function") result = await result;
            if (typeof result === "undefined") {
                _field.send(envelope.returnTo, {message: "OK", kind: "SUCCESS"});
            } else {
                _field.send(envelope.returnTo, {
                    message: "<json>" + betterPretty(result, 2, "JSON", false),
                    kind: "SUCCESS"
                });
            }

            if (typeof _r !== "undefined" && envelope.launchable) {
                let task;
                if (Array.isArray(_r) && _r.length === 3) {
                    task = new Task(
                        envelope.codeName,
                        _r[0],
                        _r[1],
                        _r[2],
                        function () {
                            _field.send(envelope.returnTo, {message: "OK", kind: "RUN-STOP", altName: ""});
                        },
                        function (error) { sendExecutionError(error, envelope); }
                    );
                } else {
                    task = launch(_r, "", false);
                }
                configureTaskTime(task, envelope);
                _field.send(envelope.returnTo, {message: "OK", kind: "RUN-START", altName: ""});
            }

            const head = remoteHead();
            if (head && typeof head.recordExecution === "function") head.recordExecution("success", envelope, result);
            return result;
        } catch (error) {
            sendExecutionError(error, envelope);
            return undefined;
        }
    }

    function updateBinaryGeometry(buffer) {
        const view = new DataView(buffer);
        if (view.byteLength < 128) return;
        const dim = view.getUint32(0, true);
        const typeCode = String.fromCharCode(view.getUint16(4, true));
        const nameLength = view.getUint8(6, true);
        const elementDim = view.getUint32(7, true);
        let name = "";
        for (let index = 0; index < nameLength; index += 1) {
            name += String.fromCharCode(view.getUint16(11 + index * 2, true));
        }
        updateBuffer(buffer, view, name, typeCode, dim, elementDim, 128);
    }

    function scheduleReconnect(id, onupdate) {
        if (manuallyClosed || reconnectTimer) return;
        const delay = Math.min(10000, 500 * Math.pow(2, reconnectAttempt++));
        setConnectionState("reconnecting", {delay});
        reconnectTimer = setTimeout(function () {
            reconnectTimer = null;
            _field.evalRequestWS(id, onupdate);
        }, delay);
    }

    _field.evalRequestWS = function (id, onupdate) {
        _field.id = id;
        manuallyClosed = false;

        let url;
        try {
            url = socketURL();
        } catch (error) {
            setConnectionState("error", FieldRemoteErrors.describe(error));
            throw error;
        }

        if (_field.socket && (_field.socket.readyState === WebSocket.OPEN || _field.socket.readyState === WebSocket.CONNECTING)) {
            return _field.socket;
        }

        FIELD = new URL(url).host;
        setConnectionState("connecting", {url});
        const socket = new WebSocket(url);
        _field.socket = socket;
        socket.binaryType = "arraybuffer";

        socket.onopen = function () {
            reconnectAttempt = 0;
            _field.isOpen = true;
            setConnectionState("connected", {url});
            _field.send("initialize", {
                id,
                protocol: 2,
                userAgent: navigator.userAgent,
                aframe: global.AFRAME && global.AFRAME.version,
                three: global.THREE && global.THREE.REVISION,
                webxr: !!navigator.xr,
                nativeARKit: !!global.ARKitBridge
            });
            flushOutgoing();
        };

        socket.onmessage = async function (event) {
            if (event.data instanceof ArrayBuffer) {
                try {
                    updateBinaryGeometry(event.data);
                } catch (error) {
                    console.error("RemoteJS binary geometry update failed", error);
                }
                return;
            }

            let envelope;
            try {
                envelope = JSON.parse(event.data);
            } catch (error) {
                console.error("RemoteJS received malformed JSON", error, event.data);
                return;
            }
            await executeEnvelope(envelope);
            if (onupdate) onupdate(envelope);
        };

        socket.onclose = function (event) {
            if (socket !== _field.socket) return;
            _field.isOpen = false;
            document.title = "Field Remote / disconnected";
            setConnectionState("disconnected", {code: event.code, reason: event.reason});
            scheduleReconnect(id, onupdate);
        };

        socket.onerror = function () {
            _field.isOpen = false;
            setConnectionState("error", {url});
        };

        return socket;
    };

    _field.disconnect = function () {
        manuallyClosed = true;
        if (reconnectTimer) clearTimeout(reconnectTimer);
        reconnectTimer = null;
        if (_field.socket) _field.socket.close(1000, "Remote head closed");
    };

    _field.executeEnvelopeForTest = executeEnvelope;
    installPrinting();
})(window);

var betterPretty = function (value, indent, type, showFunctions) {
    if (typeof value === "undefined") return "(undefined)";
    try {
        if (value && typeof value.description === "function") return value.description();
    } catch (error) {
        console.warn("RemoteJS description() failed", error);
    }
    try {
        return pretty(value, indent, type, showFunctions).replace(/circular reference to \[object Object\]/g, "'[circular reference]'");
    } catch (error) {
        try {
            return JSON.stringify(value);
        } catch (_) {
            return String(value);
        }
    }
};

window.addEventListener("error", function (event) {
    if (!_field || typeof _field.send !== "function") return;
    const detail = FieldRemoteErrors.describe(event.error || event.message, {
        codeName: event.filename || globalCodeName,
        fallbackLine: event.lineno
    });
    _field.send("(handler error)", Object.assign({kind: "ERROR"}, detail));
});

window.addEventListener("unhandledrejection", function (event) {
    if (!_field || typeof _field.send !== "function") return;
    const detail = FieldRemoteErrors.describe(event.reason, {codeName: globalCodeName});
    _field.send("(handler error)", Object.assign({kind: "ERROR"}, detail));
});

function getQueryVariable(variable) {
    return new URL(window.location.href).searchParams.get(variable) || undefined;
}
