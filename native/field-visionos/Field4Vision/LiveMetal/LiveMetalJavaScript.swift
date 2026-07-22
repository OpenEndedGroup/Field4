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

/// A deliberately tiny JavaScriptCore host. Scripts install one `onFrame` callback and write named
/// float4 uniforms. Geometry and rendering never cross the JS bridge at frame rate.
final class LiveMetalJavaScript {
    private var context: JSContext?
    private var bridge: LiveMetalJSBridge?
    private var tickFunction: JSValue?
    private var uniformIndex: [String: Int] = [:]
    private(set) var uniformValues = Array(repeating: SIMD4<Float>(repeating: 0), count: 16)
    private var lastRuntimeError = ""

    var report: (String, Bool, String) -> Void = { _, _, _ in }

    func install(script: String, sourceURL: URL, uniformNames: [String]) {
        uniformIndex = Dictionary(uniqueKeysWithValues: uniformNames.prefix(16).enumerated().map { ($0.element, $0.offset) })
        uniformValues = Array(repeating: SIMD4<Float>(repeating: 0), count: 16)
        lastRuntimeError = ""

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

        let preamble = """
        "use strict";
        var __fieldFrame = null;
        function onFrame(fn) { if (typeof fn !== "function") throw new TypeError("onFrame expects a function"); __fieldFrame = fn; }
        function uniform(name, x, y = 0, z = 0, w = 0) {
            if (Array.isArray(x) || ArrayBuffer.isView(x)) __field.setUniform(String(name), Number(x[0] || 0), Number(x[1] || 0), Number(x[2] || 0), Number(x[3] || 0));
            else __field.setUniform(String(name), Number(x || 0), Number(y || 0), Number(z || 0), Number(w || 0));
        }
        const uniforms = new Proxy(Object.create(null), {
            set(_target, name, value) { uniform(String(name), value); return true; }
        });
        const print = value => __field.log(value);
        """
        context.evaluateScript(preamble, withSourceURL: URL(string: "field-vision-runtime.js")!)
        context.evaluateScript(script, withSourceURL: sourceURL)
        tickFunction = context.objectForKeyedSubscript("__fieldFrame")
        self.context = context
        self.bridge = bridge

        if context.exception == nil {
            report("javascript", true, "JavaScript installed")
        }
    }

    func tick(time: Double, deltaTime: Double, predictedDisplayTime: Double) {
        guard let tickFunction, !tickFunction.isUndefined, !tickFunction.isNull else { return }
        lastRuntimeError = ""
        tickFunction.call(withArguments: [time, deltaTime, predictedDisplayTime])
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
