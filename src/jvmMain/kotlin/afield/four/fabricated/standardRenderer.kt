package afield.four.fabricated

import afield.Rect
import afield.four.Renderer
import afield.four.buffers.DynamicallyWriteable
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.Structure
import afield.four.buffers.defaultBuffer
import afield.four.modules.*
import afield.four.targets.RenderTarget
import afield.four.targets.RenderToTexture
import afield.four.texture.TextureSampler
import afield.four.util.Camera
import afield.four.util.Options
import afield.four.util.Projection
import afield.stdlib.Vec3
import afield.stdlib.fromViews
import afield.stdlib.vec
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.vulkan.VK10.*
import java.util.*
import kotlin.math.abs

/**
 * One-stop shop for a small deferred renderer: g-buffer, shadow map, AO, and a lighting
 * composite. Everything here is built on top of the existing afield.four primitives so users
 * can just feed meshes in and wire a RenderTarget around the passes.
 */
object StandardRenderer {

	data class Config(
		val renderWidth: Int = 0,
		val renderHeight: Int = 0,
		val shadowWidth: Int = 1024,
		val shadowHeight: Int = 1024,
		val hdrFormat: Int = VK_FORMAT_R16G16B16A16_SFLOAT,
		val normalFormat: Int = VK_FORMAT_R16G16B16A16_SFLOAT,
		val positionFormat: Int = VK_FORMAT_R16G16B16A16_SFLOAT,
		val motionFormat: Int = VK_FORMAT_R16G16_SFLOAT,
		val colorFormat: Int = VK_FORMAT_B8G8R8A8_SRGB
	)

		data class SceneDefaults(
			val camera: Camera,
			val projection: Projection,
			val lightDirection: Vector3f = Vector3f(DEFAULT_LIGHT_DIRECTION).normalize(),
			val lightTarget: Vector3f = Vector3f(0f, 0f, 0f),
		val lightColor: Vector3f = Vector3f(DEFAULT_LIGHT_COLOR),
		var lightDistance: Float = DEFAULT_LIGHT_DISTANCE,
		var lightIntensity: Float = DEFAULT_LIGHT_INTENSITY,
		var shadowBias: Float = DEFAULT_SHADOW_BIAS,
			var shadowExtent: Float = DEFAULT_SHADOW_EXTENT,
			var shadowNear: Float = DEFAULT_SHADOW_NEAR,
			var shadowFar: Float = DEFAULT_SHADOW_FAR,
			var shadowStrength: Float = DEFAULT_SHADOW_STRENGTH,
			var shadowSoftness: Float = DEFAULT_SHADOW_SOFTNESS,
			val ambientColor: Vector3f = Vector3f(DEFAULT_AMBIENT_COLOR),
			var aoStrength: Float = DEFAULT_AO_STRENGTH,
			var aoRadius: Float = DEFAULT_AO_RADIUS,
			var aoBias: Float = DEFAULT_AO_BIAS,
			var aoIntensity: Float = DEFAULT_AO_INTENSITY,
			var motionBlurStrength: Float = DEFAULT_MOTION_BLUR_STRENGTH,
			var motionBlurClamp: Float = DEFAULT_MOTION_BLUR_CLAMP,
			var motionBlurDepthReject: Float = DEFAULT_MOTION_BLUR_DEPTH_REJECT,
			var aaBlend: Float = DEFAULT_AA_BLEND,
			var fxaaEdgeThreshold: Float = DEFAULT_FXAA_EDGE_THRESHOLD,
			var fxaaEdgeThresholdMin: Float = DEFAULT_FXAA_EDGE_THRESHOLD_MIN,
			var fxaaSpanMax: Float = DEFAULT_FXAA_SPAN_MAX
		) {
		companion object {
			fun create(renderer: Renderer): SceneDefaults {
				val camera = Camera(renderer)
				camera.at.set(0f, 0f, -4f)
				camera.target.set(0f, 0f, 0f)
				camera.up.set(0f, 1f, 0f)
				return SceneDefaults(camera = camera, projection = Projection(renderer))
			}
		}

		fun lightViewProjection(): Matrix4f {
			val direction = Vector3f(lightDirection)
			if (direction.lengthSquared() < 1e-6f) {
				direction.set(DEFAULT_LIGHT_DIRECTION)
			}
			direction.normalize()
			val up = if (abs(direction.y) > 0.95f) Vector3f(0f, 0f, 1f) else Vector3f(0f, 1f, 0f)
			val eye = Vector3f(lightTarget).sub(Vector3f(direction).mul(lightDistance))
			val view = Matrix4f().lookAt(eye, Vector3f(lightTarget), up)
			val projection = Matrix4f().ortho(
				-shadowExtent,
				shadowExtent,
				-shadowExtent,
				shadowExtent,
				shadowNear,
				shadowFar,
				true
			)
			return projection.mul(view)
		}
	}

	private data class ResolvedConfig(
		val renderWidth: Int,
		val renderHeight: Int,
		val shadowWidth: Int,
		val shadowHeight: Int
	)

	private fun resolveConfig(renderer: Renderer, config: Config): ResolvedConfig {
		val extent = renderer.swapChain.swapChainExtent
		val renderWidth = if (config.renderWidth > 0) config.renderWidth else extent.width()
		val renderHeight = if (config.renderHeight > 0) config.renderHeight else extent.height()
		val shadowWidth = if (config.shadowWidth > 0) config.shadowWidth else 1024
		val shadowHeight = if (config.shadowHeight > 0) config.shadowHeight else 1024
		return ResolvedConfig(renderWidth, renderHeight, shadowWidth, shadowHeight)
	}

		data class Bundle(
			val gBuffer: RenderToTexture,
			val shadowMap: RenderToTexture,
			val ao: RenderToTexture,
			val lighting: RenderToTexture,
			val motionBlur: RenderToTexture,
			val antiAlias: RenderToTexture,
			val gBufferModule: ModulePointer,
			val shadowModule: ModulePointer,
			val aoModule: ModulePointer,
			val lightingModule: ModulePointer,
			val motionBlurModule: ModulePointer,
			val antiAliasModule: ModulePointer,
			val scene: SceneDefaults
		) {
		/**
		 * Convenience: build one mesh per pass using the supplied factory and register them with the
		 * appropriate modules. Use this when you want the same vertex data drawn into both g-buffer
		 * and shadow passes.
		 */
		data class MeshPair(val gBuffer: Mesh, val shadow: Mesh) {
			fun setModel(model: Matrix4f): MeshPair {
				gBuffer.setModelMatrix(model)
				shadow.setModelMatrix(model)
				return this
			}

			fun bindModel(model: () -> Matrix4f): MeshPair {
				gBuffer.bindModelMatrix(model)
				shadow.bindModelMatrix(model)
				return this
			}
		}

		fun registerMesh(name: String, build: (ModulePointer) -> Mesh): MeshPair {
			val g = build(gBufferModule)
			g.seedDefaultSurfaceState()
			gBufferModule.current.meshes[name] = g
			val s = build(shadowModule)
			s.seedDefaultSurfaceState()
			shadowModule.current.meshes[name] = s
			return MeshPair(g, s)
		}

		/**
		 * Add the off-screen passes to a renderer’s main RenderTarget so they run before the swapchain
		 * render. This simply wires the passes into preTargets in the right order.
		 */
			fun attachTo(renderer: Renderer): RenderTarget {
				val rt = renderer.renderList
				rt.preTargets["std-gbuffer"] = gBuffer
					rt.preTargets["std-shadow"] = shadowMap
					rt.preTargets["std-ao"] = ao
					rt.preTargets["std-lighting"] = lighting
					rt.preTargets["std-motion-blur"] = motionBlur
					rt.preTargets["std-aa"] = antiAlias
					return rt
				}

			/** Present the anti-aliased lighting RTT to the swapchain with optional grading and viewport. */
			fun present(
				renderer: Renderer = fromContext(),
				target: RenderTarget = renderer.renderList,
				name: String = "std-present",
				viewport: Rect? = null,
				configure: RenderToTexture.ColorGrade.() -> Unit = {}
			): ModulePointer =
				antiAlias.blitToScreen(renderer = renderer, target = target, name = name, viewport = viewport, configure = {
					offset = vec(0.01f, 0.01f, 0.01f, 0.01f)
					configure()
				})

		fun presentInternalBuffers(
			renderer: Renderer = fromContext(),
			target: RenderTarget = renderer.renderList,
			namePrefix: String = "std-debug",
			viewport: Rect? = null,
			columns: Int = 3,
			gap: Double = 8.0
		): Map<String, ModulePointer> {
			require(columns > 0) { "columns must be positive" }
			val extent = renderer.swapChain.swapChainExtent
			val bounds = viewport ?: Rect(0.0, 0.0, extent.width().toDouble(), extent.height().toDouble())
				val cells = debugGrid(bounds, count = 8, columns = columns, gap = gap)


				val modules = linkedMapOf<String, ModulePointer>()
				modules["lighting"] = lighting.blitToScreen(renderer = renderer, target = target, name = "$namePrefix-lighting", viewport = cells[0])
				modules["motionBlur"] = motionBlur.blitToScreen(renderer = renderer, target = target, name = "$namePrefix-motion-blur", viewport = cells[1])
				modules["albedoRoughness"] = gBuffer.blitToScreen(renderer = renderer, target = target, attachment = 0, name = "$namePrefix-albedo", viewport = cells[2])
				modules["normalMetallic"] = gBuffer.blitToScreen(renderer = renderer, target = target, attachment = 1, name = "$namePrefix-normal", viewport = cells[3])
				modules["positionDepth"] = gBuffer.blitToScreen(renderer = renderer, target = target, attachment = 2, name = "$namePrefix-position", viewport = cells[4])
				modules["motionVectors"] = gBuffer.blitMotionToScreen(renderer = renderer, target = target, attachment = 3, name = "$namePrefix-motion", viewport = cells[5])
				modules["ambientOcclusion"] = ao.blitToScreen(renderer = renderer, target = target, name = "$namePrefix-ao", viewport = cells[6])
				modules["shadowDepth"] = shadowMap.blitDepthToScreen(renderer = renderer, target = target, name = "$namePrefix-shadow", viewport = cells[7])
				return modules
			}

		data class PullingMeshPair(val gBuffer: PullingMesh, val shadow: PullingMesh) {
			fun setVertexCount(vertexCount: Int): PullingMeshPair {
				gBuffer.setVertexCount(vertexCount)
				shadow.setVertexCount(vertexCount)
				return this
			}

			fun syncVertexCountFromPositionCursor(strideScalars: Int = 4): PullingMeshPair {
				gBuffer.syncVertexCountFromPositionCursor(strideScalars)
				shadow.setVertexCount(gBuffer.mesh.numVert)
				return this
			}

			fun setModel(model: Matrix4f): PullingMeshPair {
				gBuffer.setModel(model)
				shadow.setModel(model)
				return this
			}

			fun bindModel(model: () -> Matrix4f): PullingMeshPair {
				gBuffer.bindModel(model)
				shadow.bindModel(model)
				return this
			}
		}

		/**
		 * Register a pair of vertex-pulling meshes into g-buffer and shadow modules, sharing the same
		 * position/normal buffers so you can stream geometry once per frame.
		 */
		fun registerPullingMesh(
			name: String = "pulling",
			maxVertices: Int = 64,
			renderer: Renderer = fromContext()
		): PullingMeshPair {
			val g = gBufferModule.makePullingMesh(name, maxVertices, renderer)
			val s = shadowModule.makePullingMesh(name, maxVertices, renderer, positionBuffer = g.position, normalBuffer = g.normal)
			return PullingMeshPair(g, s)
		}

		/**
		 * Register a shape written into shared pulling buffers, then size the mesh from the number of
		 * vertices actually emitted. The backing buffers can grow dynamically, so callers do not need
		 * to predict the final triangle count up front.
		 */
		fun registerStandardShape(
			name: String = "shape",
			initialMaxVertices: Int = 64,
			renderer: Renderer = fromContext(),
			writeShape: (positions: DynamicallyWriteable, normals: DynamicallyWriteable) -> StandardShapes.ShapeWriteResult
		): PullingMeshPair {
			val pulling = registerPullingMesh(name, initialMaxVertices, renderer)
			val write = writeShape(pulling.gBuffer.position, pulling.gBuffer.normal)
			pulling.setVertexCount(write.writtenVertices)
			return pulling
		}

		fun registerCube(
			name: String = "cube",
			size: Float = 1f,
			center: Vec3? = null,
			renderer: Renderer = fromContext()
		): PullingMeshPair =
			registerStandardShape(name = name, initialMaxVertices = 36, renderer = renderer) { positions, normals ->
				StandardShapes.cube(
					positions = positions,
					normals = normals,
					size = size,
					center = center,
					config = StandardShapes.WriteConfig(append = false)
				)
			}

		fun registerSphere(
			name: String = "sphere",
			radius: Float = 1f,
			slices: Int = 32,
			stacks: Int = 32,
			center: Vec3? = null,
			renderer: Renderer = fromContext()
		): PullingMeshPair =
			registerStandardShape(name = name, initialMaxVertices = slices * stacks * 6, renderer = renderer) { positions, normals ->
				StandardShapes.sphere(
					positions = positions,
					normals = normals,
					radius = radius,
					slices = slices,
					stacks = stacks,
					center = center,
					config = StandardShapes.WriteConfig(append = false)
				)
			}

		fun registerHemisphere(
			name: String = "hemisphere",
			radius: Float = 1f,
			slices: Int = 32,
			stacks: Int = 32,
			renderer: Renderer = fromContext()
		): PullingMeshPair =
			registerStandardShape(name = name, initialMaxVertices = slices * stacks * 6, renderer = renderer) { positions, normals ->
				StandardShapes.hemisphere(
					positions = positions,
					normals = normals,
					radius = radius,
					slices = slices,
					stacks = stacks,
					config = StandardShapes.WriteConfig(append = false)
				)
			}

		fun registerCylinder(
			name: String = "cylinder",
			radius: Float = 1f,
			height: Float = 1f,
			slices: Int = 32,
			stacks: Int = 1,
			renderer: Renderer = fromContext()
		): PullingMeshPair =
			registerStandardShape(name = name, initialMaxVertices = slices * stacks * 6, renderer = renderer) { positions, normals ->
				StandardShapes.cylinder(
					positions = positions,
					normals = normals,
					radius = radius,
					height = height,
					slices = slices,
					stacks = stacks,
					config = StandardShapes.WriteConfig(append = false)
				)
			}

		fun registerCone(
			name: String = "cone",
			radius: Float = 1f,
			height: Float = 1f,
			slices: Int = 32,
			stacks: Int = 1,
			renderer: Renderer = fromContext()
		): PullingMeshPair =
			registerStandardShape(name = name, initialMaxVertices = slices * stacks * 6, renderer = renderer) { positions, normals ->
				StandardShapes.cone(
					positions = positions,
					normals = normals,
					radius = radius,
					height = height,
					slices = slices,
					stacks = stacks,
					config = StandardShapes.WriteConfig(append = false)
				)
			}

		fun registerDisk(
			name: String = "disk",
			radius: Float = 1f,
			slices: Int = 32,
			stacks: Int = 1,
			renderer: Renderer = fromContext()
		): PullingMeshPair =
			registerStandardShape(name = name, initialMaxVertices = slices * stacks * 6, renderer = renderer) { positions, normals ->
				StandardShapes.disk(
					positions = positions,
					normals = normals,
					radius = radius,
					slices = slices,
					stacks = stacks,
					config = StandardShapes.WriteConfig(append = false)
				)
			}

		fun registerTorus(
			name: String = "torus",
			slices: Int = 32,
			stacks: Int = 32,
			tubeRadius: Float = 0.25f,
			majorRadius: Float = 1f,
			renderer: Renderer = fromContext()
		): PullingMeshPair =
			registerStandardShape(name = name, initialMaxVertices = slices * stacks * 6, renderer = renderer) { positions, normals ->
				StandardShapes.torus(
					positions = positions,
					normals = normals,
					slices = slices,
					stacks = stacks,
					tubeRadius = tubeRadius,
					majorRadius = majorRadius,
					config = StandardShapes.WriteConfig(append = false)
				)
			}

		fun registerTrefoilKnot(
			name: String = "trefoil-knot",
			slices: Int = 64,
			stacks: Int = 16,
			radius: Float = 1f,
			renderer: Renderer = fromContext()
		): PullingMeshPair =
			registerStandardShape(name = name, initialMaxVertices = slices * stacks * 6, renderer = renderer) { positions, normals ->
				StandardShapes.trefoilKnot(
					positions = positions,
					normals = normals,
					slices = slices,
					stacks = stacks,
					radius = radius,
					config = StandardShapes.WriteConfig(append = false)
				)
			}

		fun registerKleinBottle(
			name: String = "klein-bottle",
			slices: Int = 32,
			stacks: Int = 32,
			scale: Float = 0.1f,
			renderer: Renderer = fromContext()
		): PullingMeshPair =
			registerStandardShape(name = name, initialMaxVertices = slices * stacks * 6, renderer = renderer) { positions, normals ->
				StandardShapes.kleinBottle(
					positions = positions,
					normals = normals,
					slices = slices,
					stacks = stacks,
					scale = scale,
					config = StandardShapes.WriteConfig(append = false)
				)
			}

		fun registerRock(
			name: String = "rock",
			seed: Int = 1,
			subdivisions: Int = 4,
			radius: Float = 1f,
			renderer: Renderer = fromContext()
		): PullingMeshPair =
			registerStandardShape(name = name, initialMaxVertices = 256, renderer = renderer) { positions, normals ->
				StandardShapes.rock(
					positions = positions,
					normals = normals,
					seed = seed,
					subdivisions = subdivisions,
					radius = radius,
					config = StandardShapes.WriteConfig(append = false)
				)
			}

		/** Simple test hook: builds a torus into shared pulling buffers and registers it. */
		fun registerTestTorus(
			name: String = "test-torus",
			slices: Int = 32,
			stacks: Int = 32,
			tubeRadius: Float = 0.25f,
			majorRadius: Float = 1f,
			renderer: Renderer = fromContext()
		): PullingMeshPair =
			registerTorus(
				name = name,
				slices = slices,
				stacks = stacks,
				tubeRadius = tubeRadius,
				majorRadius = majorRadius,
				renderer = renderer
			)
	}

	fun build(renderer: Renderer = fromContext(), config: Config = Config()): Bundle {
		val scene = SceneDefaults.create(renderer)
		val resolved = resolveConfig(renderer, config)

			val gBuffer = RenderToTexture {
				width = resolved.renderWidth
				height = resolved.renderHeight
				colorAttachments = 4
				colorFormats = listOf(config.hdrFormat, config.normalFormat, config.positionFormat, config.motionFormat)
				useDepth = true
				depthSampled = true
				colorSampled = true
				clearColor = vec(0f, 0f, 0f, 1f)
			}

		val shadow = RenderToTexture {
			width = resolved.shadowWidth
			height = resolved.shadowHeight
			colorAttachments = 1 // simple depth-only pass with a throwaway color attachment
			colorFormats = listOf(config.positionFormat)
			useDepth = true
			depthSampled = true
			colorSampled = false
			clearColor = vec(1f, 1f, 1f, 1f)
		}

		val ao = RenderToTexture {
			width = resolved.renderWidth
			height = resolved.renderHeight
			colorAttachments = 1
			format = VK_FORMAT_R8_UNORM
			colorSampled = true
			useDepth = false
			clearColor = vec(1f, 1f, 1f, 1f)
		}

				val lighting = RenderToTexture {
					width = resolved.renderWidth
					height = resolved.renderHeight
					colorAttachments = 1
					format = config.hdrFormat
				colorSampled = true
					useDepth = false
					clearColor = vec(0f, 0f, 0f, 1f)
				}

				val motionBlur = RenderToTexture {
					width = resolved.renderWidth
					height = resolved.renderHeight
					colorAttachments = 1
					format = config.hdrFormat
					colorSampled = true
					useDepth = false
					clearColor = vec(0f, 0f, 0f, 1f)
				}

				val antiAlias = RenderToTexture {
					width = resolved.renderWidth
					height = resolved.renderHeight
					colorAttachments = 1
				format = config.hdrFormat
				colorSampled = true
				colorTransferSrc = true
				useDepth = false
				clearColor = vec(0f, 0f, 0f, 1f)
			}

		val gBufferModule = Module(parameters {
			name = "prefab-gbuffer"
			gBuffer.apply()
			blend = false
			vertex = gBufferVertex
			fragment = gBufferFragment
		}).pointer()
		gBuffer += "gbuffer" to gBufferModule

		val shadowModule = Module(parameters {
			name = "prefab-shadow"
			shadow.apply()
			blend = false
			vertex = shadowVertex
			fragment = shadowFragment
			colorFormats = shadow.parameters.colorFormats
			depthFormat = shadow.parameters.depthFormat
		}).pointer()
		shadow += "shadow" to shadowModule

				val gBufferSampler = TextureSampler(renderer, gBuffer.parameters.mipLevels, true)
				val shadowSampler = TextureSampler(renderer, shadow.parameters.mipLevels, false)
				val aoSampler = TextureSampler(renderer, ao.parameters.mipLevels, false)
				val lightingSampler = TextureSampler(renderer, lighting.parameters.mipLevels, false)
				val motionBlurSampler = TextureSampler(renderer, motionBlur.parameters.mipLevels, false)

		val aoModule = Module(parameters {
			name = "prefab-ao"
			ao.apply()
			blend = false
			vertex = fullscreenVertex
			fragment = aoFragment
			depth = 0
			color = ao.parameters.colorAttachments
			textureResolver = { tex ->
				when (tex.name) {
					"gPositionDepth" -> fromViews(listOf(gBuffer.imageViewArray[2]), tex.name, gBufferSampler)
					"gNormalMetallic" -> fromViews(listOf(gBuffer.imageViewArray[1]), tex.name, gBufferSampler)
					else -> null
				}
			}
		}).pointer()
		ao += "ao" to aoModule
		val aoQuad = aoModule.ensureFullscreenTriangle("aoQuad")
		aoQuad.seedDefaultAoState(scene)

		val lightingModule = Module(parameters {
			name = "prefab-lighting"
			lighting.apply()
			blend = false
			vertex = fullscreenVertex
			fragment = lightingFragment
			depth = 0
			color = lighting.parameters.colorAttachments
			textureResolver = { tex ->
				when (tex.name) {
					"gAlbedoRoughness" -> fromViews(listOf(gBuffer.imageViewArray[0]), tex.name, gBufferSampler)
					"gNormalMetallic" -> fromViews(listOf(gBuffer.imageViewArray[1]), tex.name, gBufferSampler)
					"gPositionDepth" -> fromViews(listOf(gBuffer.imageViewArray[2]), tex.name, gBufferSampler)
					"shadowMap" -> (shadow.depthSampledImageView ?: shadow.depthImageView)?.let {
						fromViews(listOf(it), tex.name, shadowSampler)
					}
					"aoTex" -> fromViews(listOf(ao.imageViewArray[0]), tex.name, aoSampler)
					else -> null
				}
			}
		}).pointer()
				lighting += "lighting" to lightingModule
				val lightingQuad = lightingModule.ensureFullscreenTriangle("lightingQuad")
				lightingQuad.seedDefaultLightingState(scene)

				val motionBlurModule = Module(parameters {
					name = "prefab-motion-blur"
					motionBlur.apply()
					blend = false
					vertex = fullscreenVertex
					fragment = motionBlurFragment
					depth = 0
					color = motionBlur.parameters.colorAttachments
					textureResolver = { tex ->
						when (tex.name) {
							"sourceTex" -> fromViews(listOf(lighting.imageViewArray[0]), tex.name, lightingSampler)
							"motionTex" -> fromViews(listOf(gBuffer.imageViewArray[3]), tex.name, gBufferSampler)
							"gPositionDepth" -> fromViews(listOf(gBuffer.imageViewArray[2]), tex.name, gBufferSampler)
							else -> null
						}
					}
				}).pointer()
				motionBlur += "motion-blur" to motionBlurModule
				val motionBlurQuad = motionBlurModule.ensureFullscreenTriangle("motionBlurQuad")
				motionBlurQuad.seedDefaultMotionBlurState(scene)

				val antiAliasModule = Module(parameters {
					name = "prefab-anti-alias"
					antiAlias.apply()
					blend = false
					vertex = fullscreenVertex
				fragment = fxaaFragment
				depth = 0
					color = antiAlias.parameters.colorAttachments
					textureResolver = { tex ->
						when (tex.name) {
							"sourceTex" -> fromViews(listOf(motionBlur.imageViewArray[0]), tex.name, motionBlurSampler)
							else -> null
						}
					}
				}).pointer()
				antiAlias += "anti-alias" to antiAliasModule
			val antiAliasQuad = antiAliasModule.ensureFullscreenTriangle("antiAliasQuad")
			antiAliasQuad.seedDefaultAntiAliasState(scene)

			gBufferModule.seedDefaultCameraUniforms(scene)
			aoModule.seedDefaultCameraUniforms(scene)
			shadowModule.seedDefaultLightUniforms(scene)
			lightingModule.seedDefaultLightUniforms(scene)

				return Bundle(gBuffer, shadow, ao, lighting, motionBlur, antiAlias, gBufferModule, shadowModule, aoModule, lightingModule, motionBlurModule, antiAliasModule, scene)
			}

	/** Quick fullscreen triangle helper to avoid having to allocate vertex buffers for post passes. */
	fun ModulePointer.ensureFullscreenTriangle(name: String = "fullscreen"): Mesh {
		val existing = current.meshes[name]
		if (existing != null) return existing

		val mesh = Mesh(current.parameters.renderer, this)
		mesh.resize(3, 0, this)
		current.meshes[name] = mesh
		return mesh
	}

	/**
	 * Build a mesh wired for vertex-pulling: creates per-frame position/normal buffers and writes
	 * their GPU addresses into the mesh push constants. Buffers are host-visible + addressable so
	 * you can stream data each frame using `DynamicallyWriteable` semantics.
	 */
	data class PullingMesh(
		val mesh: Mesh,
		val position: FrameBufferGroup,
		val normal: FrameBufferGroup
	) {
		fun setVertexCount(vertexCount: Int): PullingMesh {
			require(vertexCount >= 0) { "vertexCount must be non-negative" }
			mesh.resize(vertexCount, 0, mesh.inside)
			return this
		}

		fun syncVertexCountFromPositionCursor(strideScalars: Int = 4): PullingMesh {
			require(strideScalars > 0) { "strideScalars must be positive" }
			return setVertexCount(position.cursor / strideScalars)
		}

		fun setModel(model: Matrix4f): PullingMesh {
			mesh.setModelMatrix(model)
			return this
		}

		fun bindModel(model: () -> Matrix4f): PullingMesh {
			mesh.bindModelMatrix(model)
			return this
		}
	}

	fun ModulePointer.makePullingMesh(
		name: String = "pulling-mesh",
		maxVertices: Int = 10,
		renderer: Renderer = current.parameters.renderer,
		positionBuffer: FrameBufferGroup? = null,
		normalBuffer: FrameBufferGroup? = null
	): PullingMesh {
		val mesh = Mesh(renderer, this)
		mesh.resize(0, 0, this) // no vertex inputs; we pull from SSBOs

		val position = positionBuffer ?: defaultBuffer(name="positionBuffer") {
			perFrame = true
			size = maxVertices.vec4s
		}.buffer

		val normal = normalBuffer ?: defaultBuffer(name="normalBuffer") {
			perFrame = true
			size = maxVertices.vec4s
		}.buffer

		mesh.push.bind("positionBuffer") { position }
		mesh.push.bind("normalBuffer") { normal }
		mesh.seedDefaultSurfaceState()

		current.meshes[name] = mesh
		return PullingMesh(mesh, position, normal)
	}

	private fun Structure.seedDefault(name: String, value: () -> Any) {
		if (offsets.containsKey(name) && name !in values && name !in bindings) {
			set(name, value())
		}
	}

	private fun debugGrid(bounds: Rect, count: Int, columns: Int, gap: Double): List<Rect> {
		val rows = ((count + columns - 1) / columns).coerceAtLeast(1)
		val totalGapX = gap * (columns - 1)
		val totalGapY = gap * (rows - 1)
		val cellWidth = ((bounds.w - totalGapX) / columns).coerceAtLeast(1.0)
		val cellHeight = ((bounds.h - totalGapY) / rows).coerceAtLeast(1.0)

		return List(count) { index ->
			val col = index % columns
			val row = index / columns
			Rect(
				x = bounds.x + col * (cellWidth + gap),
				y = bounds.y + (rows - row - 1) * (cellHeight + gap),
				w = cellWidth,
				h = cellHeight
			)
		}
	}

	private fun RenderToTexture.blitDepthToScreen(
		renderer: Renderer = this.renderer,
		target: RenderTarget = renderer.renderList,
		name: String,
		viewport: Rect? = null
	): ModulePointer {
		val depthView = requireNotNull(depthSampledImageView ?: depthImageView) {
			"RenderToTexture $name has no depth image to blit"
		}
		val sampler = TextureSampler(renderer, 1, false)

		val module = Module(parameters(renderer) {
			this.name = name
			color = 1
			depth = 1
			depthFormat = Options.defaultDepthFormat
			depthTest = false
			blend = false
			this.viewport = viewport
			vertex = fullscreenVertex
			fragment = depthBlitFragment
			textureResolver = { tex ->
				if (tex.name == "sourceTex") fromViews(listOf(depthView), tex.name, sampler) else null
			}
		}).pointer()

		val mesh = Mesh(renderer, module)
		mesh.resize(3, 0, module)
		module.current.meshes[name] = mesh
		target += name to module
		return module
	}

	private fun RenderToTexture.blitMotionToScreen(
		renderer: Renderer = this.renderer,
		target: RenderTarget = renderer.renderList,
		attachment: Int,
		name: String,
		viewport: Rect? = null,
		scale: Float = 12f
	): ModulePointer {
		require(attachment in imageViewArray.indices) {
			"RenderToTexture $name attachment $attachment is out of range (attachments=${imageViewArray.size})"
		}
		val sourceView = imageViewArray[attachment]
		val sampler = TextureSampler(renderer, parameters.mipLevels, false)

		val module = Module(parameters(renderer) {
			this.name = name
			color = 1
			depth = 1
			depthFormat = Options.defaultDepthFormat
			depthTest = false
			blend = false
			this.viewport = viewport
			vertex = fullscreenVertex
			fragment = motionBlitFragment
			textureResolver = { tex ->
				if (tex.name == "sourceTex") fromViews(listOf(sourceView), tex.name, sampler) else null
			}
		}).pointer()

		val mesh = Mesh(renderer, module)
		mesh.resize(3, 0, module)
		if (mesh.push.offsets.containsKey("scale")) {
			mesh.push["scale"] = scale
		}
		module.current.meshes[name] = mesh
		target += name to module
		return module
	}

	private fun Structure.seedDefaultBinding(name: String, value: () -> Any) {
		if (offsets.containsKey(name) && name !in values && name !in bindings) {
			bind(name, value)
		}
	}

	private class FrameStableHistory<T>(
		private val renderer: Renderer,
		initialSource: () -> T,
		private val copier: (T) -> T
	) {
		private var currentSource: () -> T = initialSource
		private var initialized = false
		private var lastFrameSeen = Long.MIN_VALUE
		private var previousValue: T? = null
		private var currentValue: T? = null

		@Synchronized
		fun source(source: () -> T) {
			currentSource = source
		}

		@Synchronized
		private fun advanceToCurrentFrame() {
			val frame = renderer.frameCounter
			if (!initialized) {
				val initial = copier(currentSource())
				previousValue = copier(initial)
				currentValue = copier(initial)
				lastFrameSeen = frame
				initialized = true
			} else if (frame != lastFrameSeen) {
				previousValue = copier(currentValue ?: copier(currentSource()))
				currentValue = copier(currentSource())
				lastFrameSeen = frame
			}
		}

		@Synchronized
		fun current(): T {
			advanceToCurrentFrame()
			return copier(currentValue ?: copier(currentSource()))
		}

		@Synchronized
		fun previous(): T {
			advanceToCurrentFrame()
			return copier(previousValue ?: copier(currentSource()))
		}
	}

	private class ModelMotionState(renderer: Renderer) {
		private val history = FrameStableHistory(renderer, { Matrix4f().identity() }) { Matrix4f(it) }

		fun set(source: () -> Matrix4f) {
			history.source { Matrix4f(source()) }
		}

		fun current(): Matrix4f = history.current()
		fun previous(): Matrix4f = history.previous()
	}

	private val modelMotionStates = WeakHashMap<Mesh, ModelMotionState>()

	private fun Mesh.installModelBindings(source: () -> Matrix4f): Mesh {
		if (push.offsets.isEmpty()) {
			initializePushConstantsFromModule(inside)
		}
		val state = modelMotionStates.getOrPut(this) { ModelMotionState(renderer) }
		state.set(source)
		if (push.offsets.containsKey("model")) {
			push.bind("model") { state.current() }
		}
		if (push.offsets.containsKey("prevModel")) {
			push.bind("prevModel") { state.previous() }
		}
		return this
	}

	private fun Mesh.seedDefaultSurfaceState(): Mesh {
		installModelBindings { Matrix4f().identity() }
		push.seedDefault("baseColor") { Vector4f(DEFAULT_BASE_COLOR) }
		push.seedDefault("metallic") { DEFAULT_METALLIC }
		push.seedDefault("roughness") { DEFAULT_ROUGHNESS }
		push.seedDefault("ao") { DEFAULT_MESH_AO }
		return this
	}

	private fun Mesh.setModelMatrix(model: Matrix4f): Mesh {
		return installModelBindings { Matrix4f(model) }
	}

	private fun Mesh.bindModelMatrix(model: () -> Matrix4f): Mesh {
		return installModelBindings { Matrix4f(model()) }
	}

	private fun Mesh.seedDefaultAoState(scene: SceneDefaults): Mesh {
		push.seedDefaultBinding("radius") { scene.aoRadius }
		push.seedDefaultBinding("bias") { scene.aoBias }
		push.seedDefaultBinding("intensity") { scene.aoIntensity }
		return this
	}

		private fun Mesh.seedDefaultLightingState(scene: SceneDefaults): Mesh {
			push.seedDefaultBinding("cameraPos") { Vector3f(scene.camera.at) }
			push.seedDefaultBinding("shadowStrength") { scene.shadowStrength }
			push.seedDefaultBinding("ambientColor") { Vector3f(scene.ambientColor) }
			push.seedDefaultBinding("aoStrength") { scene.aoStrength }
			push.seedDefaultBinding("shadowSoftness") { scene.shadowSoftness }
			return this
		}

			private fun Mesh.seedDefaultAntiAliasState(scene: SceneDefaults): Mesh {
				push.seedDefaultBinding("aaBlend") { scene.aaBlend }
				push.seedDefaultBinding("edgeThreshold") { scene.fxaaEdgeThreshold }
				push.seedDefaultBinding("edgeThresholdMin") { scene.fxaaEdgeThresholdMin }
				push.seedDefaultBinding("spanMax") { scene.fxaaSpanMax }
				return this
			}

			private fun Mesh.seedDefaultMotionBlurState(scene: SceneDefaults): Mesh {
				push.seedDefaultBinding("strength") { scene.motionBlurStrength }
				push.seedDefaultBinding("clampRadius") { scene.motionBlurClamp }
				push.seedDefaultBinding("depthReject") { scene.motionBlurDepthReject }
				return this
			}

	private fun ModulePointer.seedDefaultCameraUniforms(scene: SceneDefaults) {
		current.liveUniformStructures["Camera"]?.let { camera ->
			val renderer = current.parameters.renderer
			val viewHistory = FrameStableHistory(renderer, { Matrix4f(scene.camera.modelView()) }) { Matrix4f(it) }
			val projHistory = FrameStableHistory(renderer, { Matrix4f(scene.projection.evaluateUniform()) }) { Matrix4f(it) }
			val cameraPosHistory = FrameStableHistory(renderer, { Vector3f(scene.camera.at) }) { Vector3f(it) }
			camera.seedDefaultBinding("view") { viewHistory.current() }
			camera.seedDefaultBinding("proj") { projHistory.current() }
			camera.seedDefaultBinding("prevView") { viewHistory.previous() }
			camera.seedDefaultBinding("prevProj") { projHistory.previous() }
			camera.seedDefaultBinding("cameraPos") { cameraPosHistory.current() }
		}
	}

	private fun ModulePointer.seedDefaultLightUniforms(scene: SceneDefaults) {
		current.liveUniformStructures["LightData"]?.let { light ->
			light.seedDefaultBinding("lightViewProj") { scene.lightViewProjection() }
			light.seedDefaultBinding("lightDir") { Vector3f(scene.lightDirection).normalize() }
			light.seedDefaultBinding("shadowBias") { scene.shadowBias }
			light.seedDefaultBinding("lightColor") { Vector3f(scene.lightColor) }
			light.seedDefaultBinding("lightIntensity") { scene.lightIntensity }
		}
	}

	private val DEFAULT_BASE_COLOR = Vector4f(1f, 0.56f, 0.24f, 1f)
	private val DEFAULT_LIGHT_DIRECTION = Vector3f(-0.35f, -1f, -0.25f)
	private val DEFAULT_LIGHT_COLOR = Vector3f(1f, 0.97f, 0.92f)
	private val DEFAULT_AMBIENT_COLOR = Vector3f(0.08f, 0.09f, 0.11f)
	private const val DEFAULT_METALLIC = 0.08f
	private const val DEFAULT_ROUGHNESS = 0.42f
	private const val DEFAULT_MESH_AO = 1f
	private const val DEFAULT_LIGHT_DISTANCE = 8f
	private const val DEFAULT_LIGHT_INTENSITY = 4f
	private const val DEFAULT_SHADOW_BIAS = 0.0015f
	private const val DEFAULT_SHADOW_EXTENT = 4f
	private const val DEFAULT_SHADOW_NEAR = 0.1f
	private const val DEFAULT_SHADOW_FAR = 24f
	private const val DEFAULT_SHADOW_STRENGTH = 1f
	private const val DEFAULT_SHADOW_SOFTNESS = 1f
	private const val DEFAULT_AO_STRENGTH = 0.6f
	private const val DEFAULT_AO_RADIUS = 0.35f
	private const val DEFAULT_AO_BIAS = 0.02f
	private const val DEFAULT_AO_INTENSITY = 1f
	private const val DEFAULT_MOTION_BLUR_STRENGTH = 1f
	private const val DEFAULT_MOTION_BLUR_CLAMP = 0.03f
	private const val DEFAULT_MOTION_BLUR_DEPTH_REJECT = 0.0025f
	private const val DEFAULT_AA_BLEND = 1f
	private const val DEFAULT_FXAA_EDGE_THRESHOLD = 0.125f
	private const val DEFAULT_FXAA_EDGE_THRESHOLD_MIN = 0.0312f
	private const val DEFAULT_FXAA_SPAN_MAX = 8f


	// --- Shader text ---

	// Vertex pulling version (positions + normals) using GL_EXT_buffer_reference.
		private const val gBufferVertex = """
			#version 450
			#extension GL_EXT_buffer_reference : require
			#extension GL_EXT_scalar_block_layout : require

		layout(std430, buffer_reference) readonly buffer VertexPositions {
			vec4 positions[];
		};

		layout(std430, buffer_reference) readonly buffer VertexNormals {
			vec4 normals[];
		};

			layout(set = 0, binding = 0) uniform Camera {
				mat4 view;
				mat4 proj;
				mat4 prevView;
				mat4 prevProj;
				vec3 cameraPos;
			} uCamera;

			layout(push_constant) uniform ModelData {
				VertexPositions positionBuffer;
				VertexNormals normalBuffer;
				mat4 model;
				mat4 prevModel;
				vec4 baseColor;
				float metallic;
				float roughness;
				float ao;
			} push;

			layout(location = 0) out vec3 vWorldPos;
			layout(location = 1) out vec3 vNormal;
			layout(location = 2) out vec2 vUv;
			layout(location = 3) out vec4 vCurrentClip;
			layout(location = 4) out vec4 vPreviousClip;

			void main() {
				vec3 pos = push.positionBuffer.positions[gl_VertexIndex].xyz;
				vec3 nrm = push.normalBuffer.normals[gl_VertexIndex].xyz;

				vec4 worldPos = push.model * vec4(pos, 1.0);
				vec4 prevWorldPos = push.prevModel * vec4(pos, 1.0);
				vWorldPos = worldPos.xyz;
				vNormal = normalize(mat3(push.model) * nrm);
				vUv = vec2(0.0);
				vCurrentClip = uCamera.proj * uCamera.view * worldPos;
				vPreviousClip = uCamera.prevProj * uCamera.prevView * prevWorldPos;
				gl_Position = vCurrentClip;
			}
		"""

		private const val gBufferFragment = """
			#version 450
			layout(location = 0) in vec3 vWorldPos;
			layout(location = 1) in vec3 vNormal;
			layout(location = 2) in vec2 vUv;
			layout(location = 3) in vec4 vCurrentClip;
			layout(location = 4) in vec4 vPreviousClip;

			layout(location = 0) out vec4 outAlbedoRoughness;
			layout(location = 1) out vec4 outNormalMetallic;
			layout(location = 2) out vec4 outPositionDepth;
			layout(location = 3) out vec4 outMotionVectors;

			layout(push_constant) uniform ModelData {
				layout(offset = 16) mat4 model;
				layout(offset = 80) mat4 prevModel;
				layout(offset = 144) vec4 baseColor;
				layout(offset = 160) float metallic;
				layout(offset = 164) float roughness;
				layout(offset = 168) float ao;
			} push;

			void main() {
				vec3 n = normalize(vNormal);
				float currentW = max(abs(vCurrentClip.w), 1e-5);
				float previousW = max(abs(vPreviousClip.w), 1e-5);
				vec2 currentUv = (vCurrentClip.xy / currentW) * vec2(0.5, -0.5) + vec2(0.5);
				vec2 previousUv = (vPreviousClip.xy / previousW) * vec2(0.5, -0.5) + vec2(0.5);
				vec2 motion = currentUv - previousUv;
				outAlbedoRoughness = vec4(push.baseColor.rgb, clamp(push.roughness, 0.04, 1.0));
				outNormalMetallic = vec4(n, clamp(push.metallic, 0.0, 1.0));
				outPositionDepth = vec4(vWorldPos, gl_FragCoord.z);
				outMotionVectors = vec4(motion, 0.0, 1.0);
			}
		"""

	private const val shadowVertex = """
		#version 450
		#extension GL_EXT_buffer_reference : require
		#extension GL_EXT_scalar_block_layout : require

		layout(std430, buffer_reference) readonly buffer VertexPositions {
			vec4 positions[];
		};

		layout(push_constant) uniform ModelData {
			VertexPositions positionBuffer;
			mat4 model;
			vec4 baseColor;
			float metallic;
			float roughness;
			float ao;
		} push;

		layout(set = 0, binding = 5) uniform LightData {
			mat4 lightViewProj;
			vec3 lightDir;
			float shadowBias;
			vec3 lightColor;
			float lightIntensity;
		} uLight;

		void main() {
			vec3 pos = push.positionBuffer.positions[gl_VertexIndex].xyz;
			gl_Position = uLight.lightViewProj * push.model * vec4(pos, 1.0);
		}
	"""

	private const val shadowFragment = """
		#version 450
		layout(location = 0) out float outDepth;
		void main() {
			outDepth = gl_FragCoord.z;
		}
	"""

	private const val fullscreenVertex = """
		#version 450
		layout(location = 0) out vec2 vUv;
		const vec2 positions[3] = vec2[](
			vec2(-1.0, -1.0),
			vec2(3.0, -1.0),
			vec2(-1.0, 3.0)
		);
		void main() {
			vec2 pos = positions[gl_VertexIndex];
			vUv = pos * 0.5 + 0.5;
			gl_Position = vec4(pos, 0.0, 1.0);
		}
	"""

	private const val depthBlitFragment = """
		#version 450
		layout(location = 0) in vec2 vUv;
		layout(set = 0, binding = 0) uniform sampler2D sourceTex;
		layout(location = 0) out vec4 outColor;

		void main() {
			float depth = texture(sourceTex, vUv).r;
			outColor = vec4(vec3(depth), 1.0);
		}
	"""

	private const val motionBlitFragment = """
		#version 450
		layout(location = 0) in vec2 vUv;
		layout(set = 0, binding = 0) uniform sampler2D sourceTex;
		layout(push_constant) uniform MotionDebugPush {
			float scale;
		} push;
		layout(location = 0) out vec4 outColor;

		void main() {
			vec2 motion = texture(sourceTex, vUv).rg;
			float magnitude = clamp(length(motion) * push.scale, 0.0, 1.0);
			vec3 color = vec3(
				0.5 + motion.x * push.scale,
				0.5 + motion.y * push.scale,
				magnitude
			);
			outColor = vec4(clamp(color, 0.0, 1.0), 1.0);
		}
	"""

	private const val motionBlurFragment = """
		#version 450
		layout(location = 0) in vec2 vUv;
		layout(set = 0, binding = 0) uniform sampler2D sourceTex;
		layout(set = 0, binding = 1) uniform sampler2D motionTex;
		layout(set = 0, binding = 2) uniform sampler2D gPositionDepth;

		layout(push_constant) uniform MotionBlurPush {
			float strength;
			float clampRadius;
			float depthReject;
		} push;

		layout(location = 0) out vec4 outColor;

		void main() {
			const int dilationRadius = 4;
			vec2 inverseResolution = 1.0 / vec2(textureSize(sourceTex, 0));
			vec4 center = texture(sourceTex, vUv);
			float centerDepth = texture(gPositionDepth, vUv).a;

			vec2 blurMotion = texture(motionTex, vUv).rg * push.strength;
			float blurDepth = centerDepth;
			float bestMotionLength = length(blurMotion);

			for (int y = -dilationRadius; y <= dilationRadius; ++y) {
				for (int x = -dilationRadius; x <= dilationRadius; ++x) {
					if (x == 0 && y == 0) continue;
					vec2 sampleUv = clamp(vUv + vec2(x, y) * inverseResolution, vec2(0.0), vec2(1.0));
					float sampleDepth = texture(gPositionDepth, sampleUv).a;
					vec2 sampleMotion = texture(motionTex, sampleUv).rg * push.strength;
					float sampleMotionLength = length(sampleMotion);

					bool preferForeground = sampleDepth < blurDepth - push.depthReject;
					bool sameLayerFaster = abs(sampleDepth - blurDepth) <= push.depthReject * 4.0 && sampleMotionLength > bestMotionLength;
					bool seedAnyMotion = bestMotionLength <= 1e-5 && sampleMotionLength > bestMotionLength;

					if (sampleMotionLength > 1e-5 && (preferForeground || sameLayerFaster || seedAnyMotion)) {
						blurMotion = sampleMotion;
						blurDepth = sampleDepth;
						bestMotionLength = sampleMotionLength;
					}
				}
			}

			float motionLength = bestMotionLength;
			if (motionLength <= 1e-5 || push.strength <= 1e-5) {
				outColor = center;
				return;
			}

			float maxRadius = max(push.clampRadius, 1e-5);
			if (motionLength > maxRadius) {
				blurMotion *= maxRadius / motionLength;
				motionLength = maxRadius;
			}

			float centerWeight = max(
				0.15,
				1.0 - smoothstep(push.depthReject, push.depthReject * 4.0 + 1e-6, abs(centerDepth - blurDepth))
			);
			vec3 accum = center.rgb * centerWeight;
			float weightSum = centerWeight;

			for (int i = 1; i <= 4; ++i) {
				float t = float(i) / 4.0;
				float baseWeight = 1.0 - 0.12 * float(i - 1);
				vec2 offset = blurMotion * t;

				vec2 uvA = clamp(vUv - offset, vec2(0.0), vec2(1.0));
				vec2 uvB = clamp(vUv + offset, vec2(0.0), vec2(1.0));

				float depthA = texture(gPositionDepth, uvA).a;
				float depthB = texture(gPositionDepth, uvB).a;
				float depthWeightA = 1.0 - smoothstep(push.depthReject, push.depthReject * 4.0 + 1e-6, abs(depthA - blurDepth));
				float depthWeightB = 1.0 - smoothstep(push.depthReject, push.depthReject * 4.0 + 1e-6, abs(depthB - blurDepth));

				float weightA = baseWeight * depthWeightA;
				float weightB = baseWeight * depthWeightB;

				accum += texture(sourceTex, uvA).rgb * weightA;
				accum += texture(sourceTex, uvB).rgb * weightB;
				weightSum += weightA + weightB;
			}

			outColor = vec4(accum / max(weightSum, 1e-5), center.a);
		}
	"""

			private const val aoFragment = """
				#version 450
			layout(location = 0) in vec2 vUv;
			layout(set = 0, binding = 0) uniform sampler2D gPositionDepth;
			layout(set = 0, binding = 1) uniform sampler2D gNormalMetallic;
		layout(set = 0, binding = 2) uniform Camera {
			mat4 view;
			mat4 proj;
			vec3 cameraPos;
		} uCamera;

		layout(push_constant) uniform AOSettings {
			float radius;
			float bias;
			float intensity;
		} push;

		layout(location = 0) out float outAO;

		const vec3 kernel[16] = vec3[](
			vec3(0.5381, 0.1856, 0.4319),
			vec3(0.1379, 0.2486, 0.4430),
			vec3(0.3371, 0.5679, 0.1057),
			vec3(-0.6999, -0.0451, 0.1019),
			vec3(0.0689, -0.1598, 0.8547),
			vec3(0.0560, 0.0069, 0.1843),
			vec3(-0.0146, 0.1402, 0.0762),
			vec3(0.0100, -0.1924, 0.0344),
			vec3(-0.3577, -0.5301, 0.4358),
			vec3(-0.3169, 0.1063, 0.0158),
			vec3(0.0103, -0.5869, 0.0046),
			vec3(-0.0897, -0.4940, 0.3287),
			vec3(0.7119, -0.0154, 0.0918),
			vec3(-0.0533, 0.0596, 0.5411),
			vec3(0.0352, -0.0631, 0.5460),
			vec3(-0.4776, 0.2847, 0.0271)
		);

		float hash12(vec2 p) {
			vec3 p3 = fract(vec3(p.xyx) * 0.1031);
			p3 += dot(p3, p3.yzx + 33.33);
			return fract((p3.x + p3.y) * p3.z);
		}

		vec2 screenUvFromNdc(vec2 ndc) {
			return vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
		}

		void main() {
			vec2 screenUv = vec2(vUv.x, 1.0 - vUv.y);
			vec4 packedPos = texture(gPositionDepth, screenUv);
			if (packedPos.w <= 1e-5) {
				outAO = 1.0;
				return;
			}

			vec3 worldPos = packedPos.xyz;
			vec3 worldNormal = normalize(texture(gNormalMetallic, screenUv).xyz);
			vec3 fragPos = (uCamera.view * vec4(worldPos, 1.0)).xyz;
			vec3 normal = normalize(mat3(uCamera.view) * worldNormal);

			vec2 noiseSeed = gl_FragCoord.xy;
			vec3 randomVec = normalize(vec3(hash12(noiseSeed), hash12(noiseSeed.yx + 19.19), 0.0));
			vec3 tangent = normalize(randomVec - normal * dot(randomVec, normal));
			vec3 bitangent = cross(normal, tangent);
			mat3 tbn = mat3(tangent, bitangent, normal);

			float occlusion = 0.0;

			for (int i = 0; i < 16; ++i) {
				vec3 sampleDir = normalize(kernel[i]);
				float scale = float(i + 1) / 16.0;
				scale = mix(0.1, 1.0, scale * scale);
				vec3 samplePos = fragPos + (tbn * sampleDir) * (push.radius * scale);

				vec4 clipPos = uCamera.proj * vec4(samplePos, 1.0);
				if (clipPos.w <= 1e-5) continue;

				vec3 ndc = clipPos.xyz / clipPos.w;
				vec2 sampleUv = screenUvFromNdc(ndc.xy);
				if (sampleUv.x < 0.0 || sampleUv.x > 1.0 || sampleUv.y < 0.0 || sampleUv.y > 1.0) continue;

				vec4 samplePacked = texture(gPositionDepth, sampleUv);
				if (samplePacked.w <= 1e-5) continue;

				float sampleDepth = (uCamera.view * vec4(samplePacked.xyz, 1.0)).z;
				float rangeCheck = smoothstep(0.0, 1.0, push.radius / (abs(fragPos.z - sampleDepth) + 1e-4));
				occlusion += (sampleDepth >= samplePos.z + push.bias ? 1.0 : 0.0) * rangeCheck;
			}

			occlusion = clamp(1.0 - (occlusion / 16.0) * push.intensity, 0.0, 1.0);
				outAO = occlusion;
			}
		"""

		private const val fxaaFragment = """
			#version 450
			layout(location = 0) in vec2 vUv;
			layout(set = 0, binding = 0) uniform sampler2D sourceTex;

			layout(push_constant) uniform FxaaPush {
				float aaBlend;
				float edgeThreshold;
				float edgeThresholdMin;
				float spanMax;
			} push;

			layout(location = 0) out vec4 outColor;

			float luma(vec3 rgb) {
				return dot(rgb, vec3(0.299, 0.587, 0.114));
			}

			void main() {
				vec2 inverseResolution = 1.0 / vec2(textureSize(sourceTex, 0));
				vec4 center = texture(sourceTex, vUv);
				if (push.aaBlend <= 1e-5) {
					outColor = center;
					return;
				}

				vec3 rgbNW = texture(sourceTex, vUv + vec2(-1.0, -1.0) * inverseResolution).rgb;
				vec3 rgbNE = texture(sourceTex, vUv + vec2( 1.0, -1.0) * inverseResolution).rgb;
				vec3 rgbSW = texture(sourceTex, vUv + vec2(-1.0,  1.0) * inverseResolution).rgb;
				vec3 rgbSE = texture(sourceTex, vUv + vec2( 1.0,  1.0) * inverseResolution).rgb;
				vec3 rgbM = center.rgb;

				float lumaNW = luma(rgbNW);
				float lumaNE = luma(rgbNE);
				float lumaSW = luma(rgbSW);
				float lumaSE = luma(rgbSE);
				float lumaM = luma(rgbM);

				float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
				float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));
				float lumaRange = lumaMax - lumaMin;
				float threshold = max(push.edgeThresholdMin, lumaMax * push.edgeThreshold);
				if (lumaRange < threshold) {
					outColor = center;
					return;
				}

				vec2 dir;
				dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
				dir.y =  ((lumaNW + lumaSW) - (lumaNE + lumaSE));

				float dirReduce = max(
					(lumaNW + lumaNE + lumaSW + lumaSE) * 0.03125,
					push.edgeThresholdMin
				);
				float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
				dir = clamp(dir * rcpDirMin, vec2(-push.spanMax), vec2(push.spanMax)) * inverseResolution;

				vec3 rgbA = 0.5 * (
					texture(sourceTex, vUv + dir * (1.0 / 3.0 - 0.5)).rgb +
					texture(sourceTex, vUv + dir * (2.0 / 3.0 - 0.5)).rgb
				);
				vec3 rgbB = rgbA * 0.5 + 0.25 * (
					texture(sourceTex, vUv + dir * -0.5).rgb +
					texture(sourceTex, vUv + dir *  0.5).rgb
				);

				float lumaB = luma(rgbB);
				vec3 fxaaColor = ((lumaB < lumaMin) || (lumaB > lumaMax)) ? rgbA : rgbB;
				outColor = vec4(mix(rgbM, fxaaColor, clamp(push.aaBlend, 0.0, 1.0)), center.a);
			}
		"""

		private const val lightingFragment = """
			#version 450
			layout(location = 0) in vec2 vUv;
			layout(set = 0, binding = 0) uniform sampler2D gAlbedoRoughness;
		layout(set = 0, binding = 1) uniform sampler2D gNormalMetallic;
		layout(set = 0, binding = 2) uniform sampler2D gPositionDepth;
		layout(set = 0, binding = 3) uniform sampler2D shadowMap;
		layout(set = 0, binding = 4) uniform sampler2D aoTex;

		layout(set = 0, binding = 5) uniform LightData {
			mat4 lightViewProj;
			vec3 lightDir;
			float shadowBias;
			vec3 lightColor;
			float lightIntensity;
		} uLight;

		layout(push_constant) uniform LightPush {
			vec3 cameraPos;
			float shadowStrength;
			vec3 ambientColor;
			float aoStrength;
			float shadowSoftness;
		} push;

		layout(location = 0) out vec4 outColor;

		float distributionGGX(vec3 n, vec3 h, float roughness) {
			float a = roughness * roughness;
			float a2 = a * a;
			float nDotH = max(dot(n, h), 0.0);
			float nDotH2 = nDotH * nDotH;
			float denom = (nDotH2 * (a2 - 1.0) + 1.0);
			return a2 / max(3.14159 * denom * denom, 1e-4);
		}

		float geometrySchlickGGX(float nDotV, float roughness) {
			float r = (roughness + 1.0);
			float k = (r * r) / 8.0;
			float denom = nDotV * (1.0 - k) + k;
			return nDotV / denom;
		}

		float geometrySmith(vec3 n, vec3 v, vec3 l, float roughness) {
			float ggx1 = geometrySchlickGGX(max(dot(n, v), 0.0), roughness);
			float ggx2 = geometrySchlickGGX(max(dot(n, l), 0.0), roughness);
			return ggx1 * ggx2;
		}

		vec3 fresnelSchlick(float cosTheta, vec3 F0) {
			return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
		}

			float pcfShadow(vec3 worldPos) {
				vec4 lightSpace = uLight.lightViewProj * vec4(worldPos, 1.0);
				vec3 projCoords = lightSpace.xyz / lightSpace.w;
				projCoords = vec3(projCoords.x * 0.5 + 0.5, 0.5 - projCoords.y * 0.5, projCoords.z);
			if (projCoords.x < 0.0 || projCoords.x > 1.0 || projCoords.y < 0.0 || projCoords.y > 1.0) return 0.0;
			if (projCoords.z <= 0.0 || projCoords.z > 1.0) return 0.0;

			vec2 texelSize = 1.0 / vec2(textureSize(shadowMap, 0));
			vec2 filterTexel = texelSize * max(push.shadowSoftness, 0.0);
			float shadow = 0.0;
			for (int x = -1; x <= 1; ++x) {
				for (int y = -1; y <= 1; ++y) {
					float pcfDepth = texture(shadowMap, projCoords.xy + vec2(x, y) * filterTexel).r;
					shadow += projCoords.z > pcfDepth + uLight.shadowBias ? 1.0 : 0.0;
				}
			}
			shadow /= 9.0;
			return clamp(shadow, 0.0, 1.0);
		}

		void main() {
			vec4 albedoRough = texture(gAlbedoRoughness, vUv);
			vec4 normalMetallic = texture(gNormalMetallic, vUv);
			vec4 positionDepth = texture(gPositionDepth, vUv);
			vec3 albedo = albedoRough.rgb;
			float roughness = clamp(albedoRough.a, 0.04, 1.0);
			vec3 n = normalize(normalMetallic.xyz);
			float metallic = clamp(normalMetallic.w, 0.0, 1.0);
			vec3 worldPos = positionDepth.xyz;

			vec3 v = normalize(push.cameraPos - worldPos);
			vec3 l = normalize(-uLight.lightDir);
			vec3 h = normalize(v + l);

			vec3 F0 = mix(vec3(0.04), albedo, metallic);
			float NDF = distributionGGX(n, h, roughness);
			float G = geometrySmith(n, v, l, roughness);
			vec3 F = fresnelSchlick(max(dot(h, v), 0.0), F0);

			vec3 numerator = NDF * G * F;
			float denom = 4.0 * max(dot(n, v), 0.0) * max(dot(n, l), 0.0) + 1e-4;
			vec3 specular = numerator / denom;

			vec3 kS = F;
			vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);
			float nDotL = max(dot(n, l), 0.0);

			float shadow = pcfShadow(worldPos) * push.shadowStrength;
			float ao = mix(1.0, texture(aoTex, vUv).r, push.aoStrength);

			vec3 Lo = (kD * albedo / 3.14159 + specular) * uLight.lightColor * uLight.lightIntensity * nDotL * (1.0 - shadow);
			vec3 ambient = push.ambientColor * albedo * ao;
			vec3 color = ambient + Lo;
			outColor = vec4(color, 1.0);
		}
	"""
}
