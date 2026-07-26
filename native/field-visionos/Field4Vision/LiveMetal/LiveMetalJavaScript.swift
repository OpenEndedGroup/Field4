import Foundation
import JavaScriptCore
import simd

@objc protocol LiveMetalJSBridgeExports: JSExport {
    func setUniform(_ name: String, _ x: Double, _ y: Double, _ z: Double, _ w: Double)
    func setGeometry(_ vertices: JSValue, _ indices: JSValue)
    func log(_ value: JSValue)
}

private final class LiveMetalJSBridge: NSObject, LiveMetalJSBridgeExports {
    var setValue: (String, SIMD4<Float>) -> Void = { _, _ in }
    var setMesh: (JSValue, JSValue) -> Void = { _, _ in }
    var output: (String) -> Void = { _ in }

    func setUniform(_ name: String, _ x: Double, _ y: Double, _ z: Double, _ w: Double) {
        setValue(name, SIMD4(Float(x), Float(y), Float(z), Float(w)))
    }

    func setGeometry(_ vertices: JSValue, _ indices: JSValue) {
        setMesh(vertices, indices)
    }

    func log(_ value: JSValue) {
        output(value.toString() ?? "undefined")
    }
}

/// Geometry the script has built but the renderer has not yet swapped in.
struct LiveMetalScriptGeometry {
    var vertices: [LiveMetalVertex]
    var indices: [UInt32]
}

/// A deliberately tiny JavaScriptCore host. Launch installs one cleanup-capable lifecycle scope;
/// Execute leaves the lifecycle functions inert.
///
/// Two things cross this bridge: named uniform writes, and whole-mesh geometry uploads. Nothing
/// per-vertex crosses it at frame rate — `geometry { … }` builds a packed `Float32Array` on the JS
/// side and hands it over in one call.
final class LiveMetalJavaScript {
    private var context: JSContext?
    private var bridge: LiveMetalJSBridge?
    private var tickFunction: JSValue?
    private var uniformIndex: [String: Int] = [:]
    private(set) var uniformValues = Array(repeating: SIMD4<Float>(repeating: 0), count: LiveMetalWire.maxUniforms)
    private var lastRuntimeError = ""
    private var frameNumber = 0

    /// Written by the bridge and drained by the render loop. Both run on the render executor — the
    /// script only ever executes from `install` or `tick` — so this needs no lock.
    private var pendingGeometry: LiveMetalScriptGeometry?

    var report: (String, Bool, String) -> Void = { _, _, _ in }

    /// Take any mesh the script uploaded since the last call.
    func takePendingGeometry() -> LiveMetalScriptGeometry? {
        defer { pendingGeometry = nil }
        return pendingGeometry
    }

    func install(script: String, sourceURL: URL, uniformNames: [String], launchable: Bool) {
        uniformIndex = Dictionary(
            uniqueKeysWithValues: uniformNames.prefix(LiveMetalWire.maxUniforms).enumerated().map { ($0.element, $0.offset) }
        )
        uniformValues = Array(repeating: SIMD4<Float>(repeating: 0), count: LiveMetalWire.maxUniforms)
        lastRuntimeError = ""
        frameNumber = 0
        pendingGeometry = nil

        let context = JSContext()!
        let bridge = LiveMetalJSBridge()
        bridge.setValue = { [weak self] name, value in self?.setUniform(name, value) }
        bridge.setMesh = { [weak self] vertices, indices in self?.setGeometry(vertices, indices) }
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
        // Kotlin/JS hands a real Float32Array/Int32Array straight through; plain arrays are coerced so
        // hand-written JavaScript can reach the same path.
        function __fieldSetGeometry(vertices, indices) {
            const v = vertices instanceof Float32Array ? vertices : new Float32Array(vertices || []);
            const i = indices instanceof Int32Array ? indices : new Int32Array(indices || []);
            __field.setGeometry(v, i);
        }
        const setGeometry = __fieldSetGeometry;
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

    /// Re-index the uniform slots without disturbing the installed script. Values are preserved by
    /// name, so a redeclaration that reorders slots keeps whatever the script last wrote.
    func redeclareUniforms(_ uniformNames: [String]) {
        var carried: [String: SIMD4<Float>] = [:]
        for (name, index) in uniformIndex where index < uniformValues.count {
            carried[name] = uniformValues[index]
        }
        uniformIndex = Dictionary(
            uniqueKeysWithValues: uniformNames.prefix(LiveMetalWire.maxUniforms).enumerated().map { ($0.element, $0.offset) }
        )
        uniformValues = Array(repeating: SIMD4<Float>(repeating: 0), count: LiveMetalWire.maxUniforms)
        for (name, index) in uniformIndex {
            if let value = carried[name] { uniformValues[index] = value }
        }
        lastRuntimeError = ""
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
        pendingGeometry = nil
        if reportLifecycle && wasActive {
            report("lifecycle", true, "RUN-STOP")
        }
    }

    private func setUniform(_ name: String, _ value: SIMD4<Float>) {
        guard let index = uniformIndex[name] else {
            let declared = uniformIndex.isEmpty
                ? "no uniforms are declared — add `uniforms=\(name)` to the box's // {{ visionmetal }} marker"
                : "declared names: \(uniformIndex.sorted { $0.value < $1.value }.map(\.key).joined(separator: ", "))"
            let message = "Unknown uniform '\(name)'; \(declared)"
            if message != lastRuntimeError {
                lastRuntimeError = message
                report("javascript", false, message)
            }
            return
        }
        uniformValues[index] = value
    }

    private func setGeometry(_ verticesValue: JSValue, _ indicesValue: JSValue) {
        guard let floats = Self.floats(from: verticesValue) else {
            reportGeometry(false, "geometry vertices must be a Float32Array")
            return
        }
        guard let rawIndices = Self.int32s(from: indicesValue) else {
            reportGeometry(false, "geometry indices must be an Int32Array")
            return
        }
        if floats.isEmpty && rawIndices.isEmpty {
            // An explicitly empty mesh is a legitimate way to draw nothing.
            pendingGeometry = LiveMetalScriptGeometry(vertices: [], indices: [])
            reportGeometry(true, "geometry cleared")
            return
        }
        guard let vertices = LiveMetalGeometryDecoding.vertices(from: floats) else {
            reportGeometry(false, "geometry needs a whole number of \(LiveMetalWire.vertexFloats)-float vertices, got \(floats.count) floats")
            return
        }
        guard rawIndices.allSatisfy({ $0 >= 0 }) else {
            reportGeometry(false, "geometry indices must not be negative")
            return
        }
        guard let indices = LiveMetalGeometryDecoding.indices(from: rawIndices.map(UInt32.init), vertexCount: vertices.count) else {
            reportGeometry(false, "geometry needs whole triangles indexing within \(vertices.count) vertices, got \(rawIndices.count) indices")
            return
        }
        pendingGeometry = LiveMetalScriptGeometry(vertices: vertices, indices: indices)
        // The status already carries phase "geometry"; don't repeat the word in the message.
        reportGeometry(true, "\(vertices.count) vertices / \(indices.count / 3) triangles built")
    }

    private func reportGeometry(_ ok: Bool, _ message: String) {
        if !ok {
            guard message != lastRuntimeError else { return }
            lastRuntimeError = message
        }
        report("geometry", ok, message)
    }

    /// Copy a Float32Array's backing store. `toArray()` would box every element; a mesh upload is the
    /// one place in this runtime where that cost would be visible.
    private static func floats(from value: JSValue) -> [Float]? {
        guard let buffer: [Float] = typedArray(from: value, expecting: kJSTypedArrayTypeFloat32Array) else {
            guard let array = value.toArray() as? [NSNumber] else { return nil }
            return array.map { $0.floatValue }
        }
        return buffer
    }

    private static func int32s(from value: JSValue) -> [Int32]? {
        guard let buffer: [Int32] = typedArray(from: value, expecting: kJSTypedArrayTypeInt32Array) else {
            guard let array = value.toArray() as? [NSNumber] else { return nil }
            return array.map { $0.int32Value }
        }
        return buffer
    }

    private static func typedArray<T>(from value: JSValue, expecting kind: JSTypedArrayType) -> [T]? {
        guard let context = value.context else { return nil }
        let ctx = context.jsGlobalContextRef
        var exception: JSValueRef?
        guard JSValueGetTypedArrayType(ctx, value.jsValueRef, &exception) == kind, exception == nil else { return nil }
        guard let object = JSValueToObject(ctx, value.jsValueRef, &exception), exception == nil else { return nil }
        let count = JSObjectGetTypedArrayLength(ctx, object, &exception)
        guard exception == nil else { return nil }
        if count == 0 { return [] }
        guard let pointer = JSObjectGetTypedArrayBytesPtr(ctx, object, &exception), exception == nil else { return nil }
        let bound = pointer.bindMemory(to: T.self, capacity: count)
        return Array(UnsafeBufferPointer(start: bound, count: count))
    }
}
