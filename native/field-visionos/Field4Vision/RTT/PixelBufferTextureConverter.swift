import Foundation
import Metal
import CoreVideo
import RealityKit

/// Converts decoded NV12 `CVPixelBuffer`s into a RealityKit `LowLevelTexture` (RGBA) with a small
/// Metal compute pass — the `WIRE_FORMAT.md` "present" step. The `LowLevelTexture` is wrapped once
/// as a `TextureResource`; every frame re-encodes into its backing `MTLTexture`, so the material
/// sampling it updates in place with no per-frame allocation.
///
/// This is the mono / flat path (full frame → one texture). The stereo dock adds a Camera Index
/// Switch node in a ShaderGraphMaterial to sample the SBS left/right halves per eye — see
/// `ImmersiveStreamView` for where that hooks in; the texture produced here is identical either way
/// (SBS is just a wider frame, no protocol difference).
@MainActor
final class PixelBufferTextureConverter {
    private let device: MTLDevice
    private let queue: MTLCommandQueue
    private let pipeline: MTLComputePipelineState
    private var textureCache: CVMetalTextureCache?

    private(set) var lowLevelTexture: LowLevelTexture?
    private(set) var textureResource: TextureResource?
    private var textureSize: (width: Int, height: Int) = (0, 0)

    init?() {
        guard let device = MTLCreateSystemDefaultDevice(),
              let queue = device.makeCommandQueue(),
              let library = try? device.makeLibrary(source: Self.kernelSource, options: nil),
              let fn = library.makeFunction(name: "yuvBT709VideoRangeToRGBA"),
              let pipeline = try? device.makeComputePipelineState(function: fn)
        else {
            shellLog.error("RTT: Metal converter unavailable")
            return nil
        }
        self.device = device
        self.queue = queue
        self.pipeline = pipeline
        CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, device, nil, &textureCache)
    }

    /// Update the output texture from a decoded frame. Returns the `TextureResource` to bind (stable
    /// across frames once created), or nil on the first frame before allocation completes.
    @discardableResult
    func update(with pixelBuffer: CVPixelBuffer) -> TextureResource? {
        guard CVPixelBufferGetPixelFormatType(pixelBuffer) == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
              let cache = textureCache else { return textureResource }

        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        guard width > 0, height > 0 else { return textureResource }

        if lowLevelTexture == nil || textureSize.width != width || textureSize.height != height {
            allocateOutput(width: width, height: height)
        }
        guard let lowLevelTexture else { return textureResource }

        guard let yTex = plane(pixelBuffer, cache: cache, plane: 0, format: .r8Unorm),
              let cbcrTex = plane(pixelBuffer, cache: cache, plane: 1, format: .rg8Unorm),
              let commandBuffer = queue.makeCommandBuffer()
        else { return textureResource }

        let outTex = lowLevelTexture.replace(using: commandBuffer)
        guard let encoder = commandBuffer.makeComputeCommandEncoder() else { return textureResource }
        encoder.setComputePipelineState(pipeline)
        encoder.setTexture(yTex, index: 0)
        encoder.setTexture(cbcrTex, index: 1)
        encoder.setTexture(outTex, index: 2)

        let w = pipeline.threadExecutionWidth
        let h = max(1, pipeline.maxTotalThreadsPerThreadgroup / w)
        let threadsPerGroup = MTLSize(width: w, height: h, depth: 1)
        let groups = MTLSize(width: (width + w - 1) / w,
                             height: (height + h - 1) / h,
                             depth: 1)
        encoder.dispatchThreadgroups(groups, threadsPerThreadgroup: threadsPerGroup)
        encoder.endEncoding()
        commandBuffer.commit()

        return textureResource
    }

    private func allocateOutput(width: Int, height: Int) {
        var desc = LowLevelTexture.Descriptor()
        desc.textureType = .type2D
        desc.pixelFormat = .rgba8Unorm
        desc.width = width
        desc.height = height
        desc.depth = 1
        desc.mipmapLevelCount = 1
        desc.arrayLength = 1
        desc.textureUsage = [.shaderRead, .shaderWrite]
        do {
            let llt = try LowLevelTexture(descriptor: desc)
            lowLevelTexture = llt
            textureResource = try TextureResource(from: llt)
            textureSize = (width, height)
        } catch {
            shellLog.error("RTT: LowLevelTexture allocation failed: \(error.localizedDescription)")
            lowLevelTexture = nil
            textureResource = nil
        }
    }

    /// Compiled at runtime (`makeLibrary(source:)`) so the build needs no offline Metal toolchain.
    /// NV12 (biplanar 4:2:0, video-range) → RGBA, BT.709 — the "copy into a LowLevelTexture" step.
    private static let kernelSource = """
    #include <metal_stdlib>
    using namespace metal;

    kernel void yuvBT709VideoRangeToRGBA(texture2d<float, access::read>  yTex     [[texture(0)]],
                                         texture2d<float, access::read>  cbcrTex  [[texture(1)]],
                                         texture2d<float, access::write> outTex   [[texture(2)]],
                                         uint2 gid [[thread_position_in_grid]])
    {
        if (gid.x >= outTex.get_width() || gid.y >= outTex.get_height()) { return; }

        float  y    = yTex.read(gid).r;
        float2 cbcr = cbcrTex.read(gid / 2).rg;   // chroma is half resolution in both axes

        // Video-range expansion (Y in [16,235], CbCr in [16,240] over 8-bit).
        y = (y - 16.0 / 255.0) * (255.0 / 219.0);
        float cb = (cbcr.x - 128.0 / 255.0) * (255.0 / 224.0);
        float cr = (cbcr.y - 128.0 / 255.0) * (255.0 / 224.0);

        float r = y + 1.5748 * cr;
        float g = y - 0.1873 * cb - 0.4681 * cr;
        float b = y + 1.8556 * cb;

        outTex.write(float4(clamp(float3(r, g, b), 0.0, 1.0), 1.0), gid);
    }
    """

    private func plane(_ pixelBuffer: CVPixelBuffer, cache: CVMetalTextureCache,
                       plane: Int, format: MTLPixelFormat) -> MTLTexture? {
        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, plane)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, plane)
        var cvTexture: CVMetalTexture?
        let st = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, cache, pixelBuffer, nil,
            format, width, height, plane, &cvTexture)
        guard st == kCVReturnSuccess, let cvTexture,
              let metalTexture = CVMetalTextureGetTexture(cvTexture) else { return nil }
        return metalTexture
    }
}
