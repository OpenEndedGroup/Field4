import ARKit
import CompositorServices
import Foundation
import Metal
import simd

private let liveMetalBuffersInFlight = 3
private let liveMetalAlignedUniformSize = (MemoryLayout<LiveMetalFrameUniforms>.stride + 0xff) & -0x100

private extension LayerRenderer.Clock.Instant {
    /// Convert the compositor clock to the ARKit timestamp convention, matching Xcode's
    /// Compositor Services project template.
    var timeInterval: TimeInterval {
        let components = LayerRenderer.Clock.Instant.epoch.duration(to: self).components
        let nanoseconds = TimeInterval(components.attoseconds / 1_000_000_000)
        return TimeInterval(components.seconds) + nanoseconds / TimeInterval(NSEC_PER_SEC)
    }
}

private struct LiveMetalFrameUniforms {
    var viewProjection0 = matrix_identity_float4x4
    var viewProjection1 = matrix_identity_float4x4
    var modelMatrix = matrix_identity_float4x4
    /// x=time since this program started, y=delta, z=predicted presentation time, w=frame index.
    var timing = SIMD4<Float>(repeating: 0)
    var user0 = SIMD4<Float>(repeating: 0)
    var user1 = SIMD4<Float>(repeating: 0)
    var user2 = SIMD4<Float>(repeating: 0)
    var user3 = SIMD4<Float>(repeating: 0)
    var user4 = SIMD4<Float>(repeating: 0)
    var user5 = SIMD4<Float>(repeating: 0)
    var user6 = SIMD4<Float>(repeating: 0)
    var user7 = SIMD4<Float>(repeating: 0)
    var user8 = SIMD4<Float>(repeating: 0)
    var user9 = SIMD4<Float>(repeating: 0)
    var user10 = SIMD4<Float>(repeating: 0)
    var user11 = SIMD4<Float>(repeating: 0)
    var user12 = SIMD4<Float>(repeating: 0)
    var user13 = SIMD4<Float>(repeating: 0)
    var user14 = SIMD4<Float>(repeating: 0)
    var user15 = SIMD4<Float>(repeating: 0)

    mutating func setUsers(_ values: [SIMD4<Float>]) {
        guard values.count >= 16 else { return }
        user0 = values[0]; user1 = values[1]; user2 = values[2]; user3 = values[3]
        user4 = values[4]; user5 = values[5]; user6 = values[6]; user7 = values[7]
        user8 = values[8]; user9 = values[9]; user10 = values[10]; user11 = values[11]
        user12 = values[12]; user13 = values[13]; user14 = values[14]; user15 = values[15]
    }
}

/// The source ABI prepended to every Field shader. User source supplies `field_vertex` and
/// `field_fragment`; changing uniforms never recompiles the pipeline.
enum LiveMetalShaderABI {
    static let preamble = """
    #include <metal_stdlib>
    using namespace metal;

    struct FieldVertex {
        float4 position;
        float4 normal;
        float4 color;
    };

    struct FieldFrame {
        float4x4 viewProjection[2];
        float4x4 modelMatrix;
        float4 timing;
        float4 user[16];
    };

    struct FieldVarying {
        float4 position [[position]];
        float3 worldPosition;
        float3 normal;
        float4 color;
    };

    float fieldTime(constant FieldFrame &frame) { return frame.timing.x; }
    float fieldDeltaTime(constant FieldFrame &frame) { return frame.timing.y; }
    float fieldPredictedDisplayTime(constant FieldFrame &frame) { return frame.timing.z; }
    float4 fieldUniform(constant FieldFrame &frame, uint index) { return frame.user[min(index, 15u)]; }
    """

    static let fallback = """
    vertex FieldVarying field_vertex(
        uint vertexID [[vertex_id]],
        ushort viewID [[amplification_id]],
        const device FieldVertex *vertices [[buffer(0)]],
        constant FieldFrame &frame [[buffer(1)]])
    {
        FieldVertex v = vertices[vertexID];
        float phase = fieldTime(frame);
        float wobble = 0.025 * sin(phase * 2.0 + v.position.y * 12.0);
        float3 centre = float3(0.45, 0.0, -1.2);
        float3 p = v.position.xyz + v.normal.xyz * wobble - centre;
        float3 n = v.normal.xyz;
        float ay = phase * 0.35 + 0.55;
        float cy = cos(ay), sy = sin(ay);
        p.xz = float2(cy * p.x + sy * p.z, -sy * p.x + cy * p.z);
        n.xz = float2(cy * n.x + sy * n.z, -sy * n.x + cy * n.z);
        float ax = 0.35;
        float cx = cos(ax), sx = sin(ax);
        p.yz = float2(cx * p.y - sx * p.z, sx * p.y + cx * p.z);
        n.yz = float2(cx * n.y - sx * n.z, sx * n.y + cx * n.z);
        float4 world = frame.modelMatrix * float4(p + centre, 1.0);
        FieldVarying out;
        out.position = frame.viewProjection[min(uint(viewID), 1u)] * world;
        out.worldPosition = world.xyz;
        out.normal = normalize((frame.modelMatrix * float4(n, 0.0)).xyz);
        out.color = v.color;
        return out;
    }

    fragment half4 field_fragment(FieldVarying in [[stage_in]], constant FieldFrame &frame [[buffer(1)]])
    {
        float3 light = normalize(float3(-0.4, 0.8, 0.5));
        float diffuse = 0.2 + 0.8 * max(dot(normalize(in.normal), light), 0.0);
        float pulse = 0.8 + 0.2 * sin(fieldTime(frame) * 3.0);
        return half4(half3(in.color.rgb * diffuse * pulse), half(in.color.a));
    }
    """

    static func source(userSource: String, uid: String) -> String {
        let body = userSource.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? fallback : userSource
        let safeUID = uid.replacingOccurrences(of: "\"", with: "_")
        return preamble + "\n#line 1 \"field://visionmetal/\(safeUID).metal\"\n" + body
    }
}

private final class LiveMetalPipelineCompiler: @unchecked Sendable {
    struct Request: Sendable {
        let uid: String
        let revision: Int
        let source: String
    }

    struct Result: @unchecked Sendable {
        let request: Request
        let pipeline: MTLRenderPipelineState?
        let error: String?
    }

    private let device: MTLDevice
    private let colorFormat: MTLPixelFormat
    private let depthFormat: MTLPixelFormat
    private let viewCount: Int
    private let queue = DispatchQueue(label: "field4.live-metal.compiler", qos: .userInitiated)
    private let lock = NSLock()
    private var pending: Request?
    private var compiling = false
    private var result: Result?

    init(device: MTLDevice, colorFormat: MTLPixelFormat, depthFormat: MTLPixelFormat, viewCount: Int) {
        self.device = device
        self.colorFormat = colorFormat
        self.depthFormat = depthFormat
        self.viewCount = viewCount
    }

    func request(_ request: Request) {
        lock.lock()
        pending = request
        let shouldStart = !compiling
        if shouldStart { compiling = true }
        lock.unlock()
        if shouldStart { queue.async { [weak self] in self?.drain() } }
    }

    func takeResult() -> Result? {
        lock.lock()
        defer { lock.unlock() }
        let value = result
        result = nil
        return value
    }

    private func drain() {
        while true {
            lock.lock()
            guard let request = pending else {
                compiling = false
                lock.unlock()
                return
            }
            pending = nil
            lock.unlock()

            let compiled: Result
            do {
                let library = try device.makeLibrary(source: request.source, options: nil)
                guard let vertex = library.makeFunction(name: "field_vertex") else {
                    throw NSError(domain: "FieldVisionMetal", code: 1,
                                  userInfo: [NSLocalizedDescriptionKey: "Shader must define vertex function field_vertex"])
                }
                guard let fragment = library.makeFunction(name: "field_fragment") else {
                    throw NSError(domain: "FieldVisionMetal", code: 2,
                                  userInfo: [NSLocalizedDescriptionKey: "Shader must define fragment function field_fragment"])
                }
                let descriptor = MTLRenderPipelineDescriptor()
                descriptor.label = "Field live pipeline \(request.uid) r\(request.revision)"
                descriptor.vertexFunction = vertex
                descriptor.fragmentFunction = fragment
                descriptor.colorAttachments[0].pixelFormat = colorFormat
                descriptor.depthAttachmentPixelFormat = depthFormat
                descriptor.maxVertexAmplificationCount = viewCount
                let color = descriptor.colorAttachments[0]!
                color.isBlendingEnabled = true
                color.sourceRGBBlendFactor = .sourceAlpha
                color.destinationRGBBlendFactor = .oneMinusSourceAlpha
                color.sourceAlphaBlendFactor = .one
                color.destinationAlphaBlendFactor = .oneMinusSourceAlpha
                let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
                compiled = Result(request: request, pipeline: pipeline, error: nil)
            } catch {
                compiled = Result(request: request, pipeline: nil, error: error.localizedDescription)
            }

            lock.lock()
            // When another edit arrived during compilation, discard this obsolete result and compile
            // only the newest pending source. Otherwise publish one atomic frame-boundary result.
            if pending == nil { result = compiled }
            lock.unlock()
        }
    }
}

final class LiveMetalRenderTaskExecutor: TaskExecutor {
    static let shared = LiveMetalRenderTaskExecutor()
    private let queue = DispatchQueue(label: "field4.live-metal.render", qos: .userInteractive)

    func enqueue(_ job: UnownedJob) {
        queue.async { job.runSynchronously(on: self.asUnownedSerialExecutor()) }
    }

    nonisolated func asUnownedSerialExecutor() -> UnownedTaskExecutor {
        UnownedTaskExecutor(ordinary: self)
    }
}

/// Autonomous compositor renderer. Network traffic only replaces program/resources; JavaScript and
/// uniform updates happen on this dedicated render executor.
final class LiveMetalRenderer {
    typealias Reporter = @Sendable (_ uid: String, _ revision: Int, _ phase: String, _ ok: Bool, _ message: String) -> Void

    private let layerRenderer: LayerRenderer
    private let device: MTLDevice
    private let commandQueue: MTLCommandQueue
    private let mailbox: LiveMetalMailbox
    private let reporter: Reporter
    private let compiler: LiveMetalPipelineCompiler
    private let depthState: MTLDepthStencilState
    private let inFlight = DispatchSemaphore(value: liveMetalBuffersInFlight)
    private let worldTracking = WorldTrackingProvider()
    private let javaScript = LiveMetalJavaScript()

    private var pipeline: MTLRenderPipelineState
    private var vertexBuffer: MTLBuffer
    private var indexBuffer: MTLBuffer
    private var indexCount: Int
    private var drawableTargets: [LayerRenderer.Drawable.Target: DrawableTarget] = [:]
    private var appliedGeneration = 0
    private var activeUID = "builtin"
    private var activeRevision = 0
    private var programStart = LayerRenderer.Clock().now
    private var previousTime: TimeInterval?

    init(layerRenderer: LayerRenderer, mailbox: LiveMetalMailbox, reporter: @escaping Reporter) throws {
        self.layerRenderer = layerRenderer
        self.device = layerRenderer.device
        self.mailbox = mailbox
        self.reporter = reporter
        guard let commandQueue = device.makeCommandQueue() else {
            throw NSError(domain: "FieldVisionMetal", code: 3,
                          userInfo: [NSLocalizedDescriptionKey: "Unable to create Metal command queue"])
        }
        self.commandQueue = commandQueue
        self.compiler = LiveMetalPipelineCompiler(device: device,
                                                  colorFormat: layerRenderer.configuration.colorFormat,
                                                  depthFormat: layerRenderer.configuration.depthFormat,
                                                  viewCount: layerRenderer.properties.viewCount)
        self.depthState = Self.makeDepthState(device: device)
        self.pipeline = try Self.makePipeline(device: device, layerRenderer: layerRenderer,
                                              source: LiveMetalShaderABI.source(userSource: "", uid: "builtin"))
        let vertices = LiveMetalGeometry.defaultVertices
        let indices = LiveMetalGeometry.defaultIndices
        self.vertexBuffer = device.makeBuffer(bytes: vertices,
                                              length: MemoryLayout<LiveMetalVertex>.stride * vertices.count,
                                              options: .storageModeShared)!
        self.indexBuffer = device.makeBuffer(bytes: indices,
                                             length: MemoryLayout<UInt32>.stride * indices.count,
                                             options: .storageModeShared)!
        self.indexCount = indices.count
        javaScript.report = { [reporter] phase, ok, message in
            reporter("javascript", 0, phase, ok, message)
        }
    }

    @MainActor
    static func start(_ layerRenderer: LayerRenderer, mailbox: LiveMetalMailbox, reporter: @escaping Reporter) {
        Task(executorPreference: LiveMetalRenderTaskExecutor.shared) {
            do {
                let renderer = try LiveMetalRenderer(layerRenderer: layerRenderer, mailbox: mailbox, reporter: reporter)
                let session = ARKitSession()
                try? await session.run([renderer.worldTracking])
                renderer.renderLoop()
            } catch {
                reporter("runtime", 0, "renderer", false, error.localizedDescription)
            }
        }
    }

    private static func makeDepthState(device: MTLDevice) -> MTLDepthStencilState {
        let descriptor = MTLDepthStencilDescriptor()
        descriptor.depthCompareFunction = .greater
        descriptor.isDepthWriteEnabled = true
        return device.makeDepthStencilState(descriptor: descriptor)!
    }

    private static func makePipeline(device: MTLDevice, layerRenderer: LayerRenderer,
                                     source: String) throws -> MTLRenderPipelineState {
        let library = try device.makeLibrary(source: source, options: nil)
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.label = "Field built-in live pipeline"
        descriptor.vertexFunction = library.makeFunction(name: "field_vertex")
        descriptor.fragmentFunction = library.makeFunction(name: "field_fragment")
        descriptor.colorAttachments[0].pixelFormat = layerRenderer.configuration.colorFormat
        descriptor.depthAttachmentPixelFormat = layerRenderer.configuration.depthFormat
        descriptor.maxVertexAmplificationCount = layerRenderer.properties.viewCount
        let color = descriptor.colorAttachments[0]!
        color.isBlendingEnabled = true
        color.sourceRGBBlendFactor = .sourceAlpha
        color.destinationRGBBlendFactor = .oneMinusSourceAlpha
        color.sourceAlphaBlendFactor = .one
        color.destinationAlphaBlendFactor = .oneMinusSourceAlpha
        return try device.makeRenderPipelineState(descriptor: descriptor)
    }

    private func applyNewestProgram() {
        if let next = mailbox.take(after: appliedGeneration) {
            appliedGeneration = next.generation
            let program = next.program
            activeUID = program.uid
            activeRevision = program.revision
            programStart = LayerRenderer.Clock().now
            previousTime = nil

            let vertices = program.decodedVertices()
            let indices = program.decodedIndices(vertexCount: vertices.count)
            if let vb = device.makeBuffer(bytes: vertices,
                                          length: MemoryLayout<LiveMetalVertex>.stride * vertices.count,
                                          options: .storageModeShared),
               let ib = device.makeBuffer(bytes: indices,
                                          length: MemoryLayout<UInt32>.stride * indices.count,
                                          options: .storageModeShared) {
                vertexBuffer = vb
                indexBuffer = ib
                indexCount = indices.count
            }

            javaScript.stop()
            javaScript.report = { [reporter, uid = program.uid, revision = program.revision] phase, ok, message in
                reporter(uid, revision, phase, ok, message)
            }
            javaScript.install(script: program.script,
                               sourceURL: URL(string: "field://visionmetal/\(program.uid).js")!,
                               uniformNames: program.uniforms,
                               launchable: program.isLaunchable)
            compiler.request(.init(uid: program.uid, revision: program.revision,
                                   source: LiveMetalShaderABI.source(userSource: program.shader, uid: program.uid)))
            reporter(program.uid, program.revision, "program", true, "Program received; compiling shader")
        }

        if let result = compiler.takeResult() {
            if let newPipeline = result.pipeline {
                pipeline = newPipeline
                reporter(result.request.uid, result.request.revision, "shader", true, "Shader installed")
            } else {
                reporter(result.request.uid, result.request.revision, "shader", false,
                         result.error ?? "Unknown Metal compiler error")
            }
        }
    }

    private func renderLoop() {
        while true {
            switch layerRenderer.state {
            case .invalidated:
                javaScript.stop()
                reporter(activeUID, activeRevision, "renderer", true, "Immersive renderer stopped")
                return
            case .paused:
                layerRenderer.waitUntilRunning()
            default:
                autoreleasepool { renderFrame() }
            }
        }
    }

    private func renderFrame() {
        guard let frame = layerRenderer.queryNextFrame() else { return }
        inFlight.wait()
        frame.startUpdate()
        applyNewestProgram()
        frame.endUpdate()

        guard let timing = frame.predictTiming() else {
            inFlight.signal()
            return
        }
        LayerRenderer.Clock().wait(until: timing.optimalInputTime)
        guard let commandBuffer = commandQueue.makeCommandBuffer() else {
            inFlight.signal()
            return
        }
        commandBuffer.addCompletedHandler { [inFlight] _ in inFlight.signal() }

        let drawables = frame.queryDrawables()
        guard !drawables.isEmpty else {
            inFlight.signal()
            return
        }
        let presentationTime = drawables[0].frameTiming.presentationTime
        let predictedTime = presentationTime.timeInterval
        let elapsed = programStart.duration(to: presentationTime).components
        let time = TimeInterval(elapsed.seconds) + TimeInterval(elapsed.attoseconds) / 1.0e18
        let delta = max(0, min(0.1, previousTime.map { time - $0 } ?? 0))
        previousTime = time
        javaScript.tick(time: time, deltaTime: delta, predictedDisplayTime: predictedTime)

        frame.startSubmission()
        for drawable in drawables {
            render(drawable: drawable, commandBuffer: commandBuffer, frameIndex: frame.frameIndex,
                   time: time, delta: delta)
        }
        commandBuffer.commit()
        frame.endSubmission()
    }

    private func render(drawable: LayerRenderer.Drawable, commandBuffer: MTLCommandBuffer,
                        frameIndex: UInt64, time: TimeInterval, delta: TimeInterval) {
        // This per-drawable timing is newer and may differ between drawable targets. Sampling the
        // frame-level prediction here looks smooth but visibly trails head motion. Apple’s Xcode 26
        // Compositor Services template deliberately queries ARKit from this timestamp instead.
        let predictedTime = drawable.frameTiming.presentationTime.timeInterval
        let deviceAnchor = worldTracking.queryDeviceAnchor(atTimestamp: predictedTime)
        drawable.deviceAnchor = deviceAnchor
        let originFromAnchor = deviceAnchor?.originFromAnchorTransform ?? matrix_identity_float4x4

        let target = drawableTargets[drawable.target] ?? {
            let value = DrawableTarget(device: device)
            drawableTargets[drawable.target] = value
            return value
        }()
        var uniforms = LiveMetalFrameUniforms()
        for viewIndex in drawable.views.indices.prefix(2) {
            let view = drawable.views[viewIndex]
            let viewMatrix = (originFromAnchor * view.transform).inverse
            let viewProjection = drawable.computeProjection(viewIndex: viewIndex) * viewMatrix
            if viewIndex == 0 { uniforms.viewProjection0 = viewProjection }
            else { uniforms.viewProjection1 = viewProjection }
        }
        if drawable.views.count == 1 { uniforms.viewProjection1 = uniforms.viewProjection0 }
        uniforms.timing = SIMD4(Float(time), Float(delta), Float(predictedTime), Float(frameIndex))
        uniforms.setUsers(javaScript.uniformValues)
        let uniformOffset = target.write(uniforms, frameIndex: frameIndex)

        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = drawable.colorTextures[0]
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        pass.depthAttachment.texture = drawable.depthTextures[0]
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.storeAction = .store
        pass.depthAttachment.clearDepth = 0
        pass.rasterizationRateMap = drawable.rasterizationRateMaps.first
        if layerRenderer.configuration.layout == .layered {
            pass.renderTargetArrayLength = drawable.views.count
        }

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else { return }
        encoder.label = "Field live Metal"
        encoder.setRenderPipelineState(pipeline)
        encoder.setDepthStencilState(depthState)
        encoder.setCullMode(.back)
        encoder.setFrontFacing(.counterClockwise)
        encoder.setViewports(drawable.views.map { $0.textureMap.viewport })
        if drawable.views.count > 1 {
            var mappings = drawable.views.indices.map {
                MTLVertexAmplificationViewMapping(viewportArrayIndexOffset: UInt32($0),
                                                  renderTargetArrayIndexOffset: UInt32($0))
            }
            encoder.setVertexAmplificationCount(mappings.count, viewMappings: &mappings)
        }
        encoder.setVertexBuffer(vertexBuffer, offset: 0, index: 0)
        encoder.setVertexBuffer(target.uniformBuffer, offset: uniformOffset, index: 1)
        encoder.setFragmentBuffer(target.uniformBuffer, offset: uniformOffset, index: 1)
        encoder.drawIndexedPrimitives(type: .triangle, indexCount: indexCount, indexType: .uint32,
                                      indexBuffer: indexBuffer, indexBufferOffset: 0)
        encoder.endEncoding()
        drawable.encodePresent(commandBuffer: commandBuffer)
    }

    private final class DrawableTarget {
        let uniformBuffer: MTLBuffer

        init(device: MTLDevice) {
            uniformBuffer = device.makeBuffer(length: liveMetalAlignedUniformSize * liveMetalBuffersInFlight,
                                              options: .storageModeShared)!
            uniformBuffer.label = "Field live uniforms"
        }

        func write(_ uniforms: LiveMetalFrameUniforms, frameIndex: UInt64) -> Int {
            let offset = Int(frameIndex % UInt64(liveMetalBuffersInFlight)) * liveMetalAlignedUniformSize
            var value = uniforms
            _ = withUnsafeBytes(of: &value) { bytes in
                memcpy(uniformBuffer.contents() + offset, bytes.baseAddress!, bytes.count)
            }
            return offset
        }
    }
}
