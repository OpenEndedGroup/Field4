import Foundation
import JavaScriptCore
import simd

@objc protocol LiveMetalJSBridgeExports: JSExport {
    func setUniform(_ name: String, _ x: Double, _ y: Double, _ z: Double, _ w: Double)
    func log(_ value: JSValue)
}

private final class LiveMetalJSBridge: NSObject, LiveMetalJSBridgeExports {
    var setValue: (String, SIMD4<Float>) -> Void = { _, _ in }
    var output: (String) -> Void = { _ in }

    func setUniform(_ name: String, _ x: Double, _ y: Double, _ z: Double, _ w: Double) {
        setValue(name, SIMD4(Float(x), Float(y), Float(z), Float(w)))
    }

    func log(_ value: JSValue) {
        output(value.toString() ?? "undefined")
    }
}

/// A deliberately tiny JavaScriptCore host. Launch installs one cleanup-capable lifecycle scope;
/// Execute leaves the lifecycle functions inert. Geometry and rendering never cross the JS bridge
/// at frame rate.
final class LiveMetalJavaScript {
    private var context: JSContext?
    private var bridge: LiveMetalJSBridge?
    private var tickFunction: JSValue?
    private var uniformIndex: [String: Int] = [:]
    private(set) var uniformValues = Array(repeating: SIMD4<Float>(repeating: 0), count: 16)
    private var lastRuntimeError = ""
    private var frameNumber = 0

    var report: (String, Bool, String) -> Void = { _, _, _ in }

    func install(script: String, sourceURL: URL, uniformNames: [String], launchable: Bool) {
        uniformIndex = Dictionary(uniqueKeysWithValues: uniformNames.prefix(16).enumerated().map { ($0.element, $0.offset) })
        uniformValues = Array(repeating: SIMD4<Float>(repeating: 0), count: 16)
        lastRuntimeError = ""
        frameNumber = 0

        let context = JSContext()!
        let bridge = LiveMetalJSBridge()
        bridge.setValue = { [weak self] name, value in self?.setUniform(name, value) }
        bridge.output = { [weak self] text in self?.report("javascript", true, text) }
        context.setObject(bridge, forKeyedSubscript: "__field" as NSString)
        context.exceptionHandler = { [weak self] _, exception in
            let message = exception?.toString() ?? "Unknown JavaScript exception"
            self?.lastRuntimeError = message
            self?.report("javascript", false, message)
        }

        let launchLiteral = launchable ? "true" : "false"
        let preamble = """
        "use strict";
        const __fieldLaunchable = \(launchLiteral);
        var __fieldLifecycleActive = false;
        var __fieldFrameCallbacks = [];
        var __fieldStopCallbacks = [];
        function __fieldOnStart(fn) {
            if (!__fieldLaunchable) return;
            if (typeof fn !== "function") throw new TypeError("onStart expects a function");
            __fieldLifecycleActive = true;
            fn();
        }
        function __fieldOnStop(fn) {
            if (!__fieldLaunchable) return;
            if (typeof fn !== "function") throw new TypeError("onStop expects a function");
            __fieldLifecycleActive = true;
            __fieldStopCallbacks.push(fn);
        }
        function __fieldOnFrame(fn) {
            if (!__fieldLaunchable) return;
            if (typeof fn !== "function") throw new TypeError("onFrame expects a function");
            __fieldLifecycleActive = true;
            __fieldFrameCallbacks.push(fn);
        }
        function __fieldKeepAlive() {
            if (__fieldLaunchable) __fieldLifecycleActive = true;
        }
        function __fieldStopLifecycle() {
            if (!__fieldLifecycleActive) return false;
            __fieldLifecycleActive = false;
            const callbacks = __fieldStopCallbacks.slice().reverse();
            __fieldStopCallbacks.length = 0;
            __fieldFrameCallbacks.length = 0;
            let firstError = null;
            for (const callback of callbacks) {
                try { callback(); } catch (error) { if (firstError === null) firstError = error; }
            }
            if (firstError !== null) throw firstError;
            return true;
        }
        function __fieldTick(time, deltaTime, frameNumber, predictedDisplayTime) {
            if (!__fieldLifecycleActive) return;
            const callbacks = __fieldFrameCallbacks.slice();
            for (const callback of callbacks) callback(time, deltaTime, frameNumber, predictedDisplayTime);
        }
        const onStart = __fieldOnStart;
        const onStop = __fieldOnStop;
        const onFrame = __fieldOnFrame;
        const keepAlive = __fieldKeepAlive;
        function uniform(name, x, y = 0, z = 0, w = 0) {
            if (Array.isArray(x) || ArrayBuffer.isView(x)) __field.setUniform(String(name), Number(x[0] || 0), Number(x[1] || 0), Number(x[2] || 0), Number(x[3] || 0));
            else __field.setUniform(String(name), Number(x || 0), Number(y || 0), Number(z || 0), Number(w || 0));
        }
        const uniforms = new Proxy(Object.create(null), {
            set(_target, name, value) { uniform(String(name), value); return true; }
        });
        const print = value => __field.log(value);
        """
        self.context = context
        self.bridge = bridge
        context.evaluateScript(preamble, withSourceURL: URL(string: "field-vision-runtime.js")!)
        context.evaluateScript(script, withSourceURL: sourceURL)
        guard context.exception == nil else {
            stop(reportLifecycle: false)
            return
        }

        tickFunction = context.objectForKeyedSubscript("__fieldTick")
        report("javascript", true, "JavaScript installed")
        if launchable {
            let active = context.objectForKeyedSubscript("__fieldLifecycleActive")?.toBool() ?? false
            report("lifecycle", true, active ? "RUN-START" : "RUN-STOP")
        }
    }

    func tick(time: Double, deltaTime: Double, predictedDisplayTime: Double) {
        guard let tickFunction, !tickFunction.isUndefined, !tickFunction.isNull else { return }
        lastRuntimeError = ""
        context?.exception = nil
        tickFunction.call(withArguments: [time, deltaTime, frameNumber, predictedDisplayTime])
        frameNumber += 1
        if context?.exception != nil {
            stop()
        }
    }

    func stop() {
        stop(reportLifecycle: true)
    }

    private func stop(reportLifecycle: Bool) {
        guard let context else { return }
        let wasActive = context.objectForKeyedSubscript("__fieldLifecycleActive")?.toBool() ?? false
        if wasActive {
            context.objectForKeyedSubscript("__fieldStopLifecycle")?.call(withArguments: [])
        }
        tickFunction = nil
        self.context = nil
        bridge = nil
        if reportLifecycle && wasActive {
            report("lifecycle", true, "RUN-STOP")
        }
    }

    private func setUniform(_ name: String, _ value: SIMD4<Float>) {
        guard let index = uniformIndex[name] else {
            let message = "Unknown uniform '\(name)'; declared names: \(uniformIndex.keys.sorted().joined(separator: ", "))"
            if message != lastRuntimeError {
                lastRuntimeError = message
                report("javascript", false, message)
            }
            return
        }
        uniformValues[index] = value
    }
}
