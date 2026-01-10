package afield.four.fabricated

import afield.four.Renderer
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.defaultBuffer
import afield.four.modules.*
import afield.four.targets.RenderTarget
import afield.four.targets.RenderToTexture
import afield.four.texture.TextureSampler
import afield.stdlib.fromViews
import afield.stdlib.vec
import org.lwjgl.vulkan.VK10.*

/**
 * One-stop shop for a small deferred renderer: g-buffer, shadow map, AO, and a lighting
 * composite. Everything here is built on top of the existing afield.four primitives so users
 * can just feed meshes in and wire a RenderTarget around the passes.
 */
object StandardRenderer {

	data class Config(
		val renderWidth: Int = 1920,
		val renderHeight: Int = 1080,
		val shadowWidth: Int = 1024,
		val shadowHeight: Int = 1024,
		val hdrFormat: Int = VK_FORMAT_R16G16B16A16_SFLOAT,
		val normalFormat: Int = VK_FORMAT_R16G16B16A16_SFLOAT,
		val positionFormat: Int = VK_FORMAT_R16G16B16A16_SFLOAT,
		val colorFormat: Int = VK_FORMAT_B8G8R8A8_SRGB
	)

	data class Bundle(
		val gBuffer: RenderToTexture,
		val shadowMap: RenderToTexture,
		val ao: RenderToTexture,
		val lighting: RenderToTexture,
		val gBufferModule: ModulePointer,
		val shadowModule: ModulePointer,
		val aoModule: ModulePointer,
		val lightingModule: ModulePointer
	) {
		/**
		 * Convenience: build one mesh per pass using the supplied factory and register them with the
		 * appropriate modules. Use this when you want the same vertex data drawn into both g-buffer
		 * and shadow passes.
		 */
		fun registerMesh(name: String, build: (ModulePointer) -> Mesh) {
			val g = build(gBufferModule)
			gBufferModule.current.meshes[name] = g
			val s = build(shadowModule)
			shadowModule.current.meshes[name] = s
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
			return rt
		}

//		/** Present the lighting RTT to the swapchain with optional grading and viewport. */
//		fun present(
//			renderer: Renderer = fromContext(),
//			target: RenderTarget = renderer.renderList,
//			name: String = "std-present",
//			viewport: Rect? = null,
//			configure: RenderToTexture.ColorGrade.() -> Unit = {}
//		): ModulePointer =
//			lighting.blitToScreen(renderer = renderer, target = target, name = name, viewport = viewport, configure = configure)


		data class PullingMeshPair(val gBuffer: PullingMesh, val shadow: PullingMesh)

		/**
		 * Register a pair of vertex-pulling meshes into g-buffer and shadow modules, sharing the same
		 * position/normal buffers so you can stream geometry once per frame.
		 */
		fun registerPullingMesh(
			name: String = "pulling",
			maxVertices: Int,
			renderer: Renderer = fromContext()
		): PullingMeshPair {
			val g = gBufferModule.makePullingMesh(name, maxVertices, renderer)
			val s = shadowModule.makePullingMesh(name, maxVertices, renderer, positionBuffer = g.position, normalBuffer = g.normal)
			return PullingMeshPair(g, s)
		}

		/** Simple test hook: builds a torus into shared pulling buffers and registers it. */
		fun registerTestTorus(
			name: String = "test-torus",
			slices: Int = 32,
			stacks: Int = 32,
			tubeRadius: Float = 0.25f,
			majorRadius: Float = 1f,
			renderer: Renderer = fromContext()
		): PullingMeshPair {
			// par_shapes torus: triangles ≈ slices * stacks * 2
			val triCount = slices * stacks * 2
			val maxVerts = triCount * 3
			val pulling = registerPullingMesh(name, maxVerts, renderer)

			StandardShapes.torus(
				positions = pulling.gBuffer.position,
				normals = pulling.gBuffer.normal,
				slices = slices,
				stacks = stacks,
				tubeRadius = tubeRadius,
				majorRadius = majorRadius,
				config = StandardShapes.WriteConfig(append = false)
			)

			return pulling
		}
	}

	fun build(renderer: Renderer = fromContext(), config: Config = Config()): Bundle {
		val gBuffer = RenderToTexture {
			width = config.renderWidth
			height = config.renderHeight
			colorAttachments = 3
			colorFormats = listOf(config.hdrFormat, config.normalFormat, config.positionFormat)
			useDepth = true
			depthSampled = true
			colorSampled = true
			clearColor = vec(0f, 0f, 0f, 1f)
		}

		val shadow = RenderToTexture {
			width = config.shadowWidth
			height = config.shadowHeight
			colorAttachments = 1 // simple depth-only pass with a throwaway color attachment
			colorFormats = listOf(config.positionFormat)
			useDepth = true
			depthSampled = true
			colorSampled = false
			clearColor = vec(1f, 1f, 1f, 1f)
		}

		val ao = RenderToTexture {
			width = config.renderWidth
			height = config.renderHeight
			colorAttachments = 1
			format = VK_FORMAT_R8_UNORM
			colorSampled = true
			useDepth = false
			clearColor = vec(1f, 1f, 1f, 1f)
		}

		val lighting = RenderToTexture {
			width = config.renderWidth
			height = config.renderHeight
			colorAttachments = 1
			format = config.hdrFormat
			colorSampled = true
			useDepth = false
			clearColor = vec(0f, 0f, 0f, 1f)
		}

		val gBufferModule = Module(parameters {
			name = "prefab-gbuffer"
			gBuffer.apply()
			vertex = gBufferVertex
			fragment = gBufferFragment
		}).pointer()
		gBuffer += "gbuffer" to gBufferModule

		val shadowModule = Module(parameters {
			name = "prefab-shadow"
			shadow.apply()
			vertex = shadowVertex
			fragment = shadowFragment
			colorFormats = shadow.parameters.colorFormats
			depthFormat = shadow.parameters.depthFormat
		}).pointer()
		shadow += "shadow" to shadowModule

		val gBufferSampler = TextureSampler(renderer, gBuffer.parameters.mipLevels, true)
		val shadowSampler = TextureSampler(renderer, shadow.parameters.mipLevels, false)
		val aoSampler = TextureSampler(renderer, ao.parameters.mipLevels, false)

		val aoModule = Module(parameters {
			name = "prefab-ao"
			ao.apply()
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
		aoModule.ensureFullscreenTriangle("aoQuad")

		val lightingModule = Module(parameters {
			name = "prefab-lighting"
			lighting.apply()
			vertex = fullscreenVertex
			fragment = lightingFragment
			depth = 0
			color = lighting.parameters.colorAttachments
			textureResolver = { tex ->
				when (tex.name) {
					"gAlbedoRoughness" -> fromViews(listOf(gBuffer.imageViewArray[0]), tex.name, gBufferSampler)
					"gNormalMetallic" -> fromViews(listOf(gBuffer.imageViewArray[1]), tex.name, gBufferSampler)
					"gPositionDepth" -> fromViews(listOf(gBuffer.imageViewArray[2]), tex.name, gBufferSampler)
					"shadowMap" -> shadow.depthImageView?.let { fromViews(listOf(it), tex.name, shadowSampler) }
					"aoTex" -> fromViews(listOf(ao.imageViewArray[0]), tex.name, aoSampler)
					else -> null
				}
			}
		}).pointer()
		lighting += "lighting" to lightingModule
		lightingModule.ensureFullscreenTriangle("lightingQuad")

		return Bundle(gBuffer, shadow, ao, lighting, gBufferModule, shadowModule, aoModule, lightingModule)
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
	)

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

		current.meshes[name] = mesh
		return PullingMesh(mesh, position, normal)
	}


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
			vec3 cameraPos;
		} uCamera;

		layout(push_constant) uniform ModelData {
			VertexPositions positionBuffer;
			VertexNormals normalBuffer;
			mat4 model;
			vec4 baseColor;
			float metallic;
			float roughness;
			float ao;
		} push;

		layout(location = 0) out vec3 vWorldPos;
		layout(location = 1) out vec3 vNormal;
		layout(location = 2) out vec2 vUv;

		void main() {
			vec3 pos = push.positionBuffer.positions[gl_VertexIndex].xyz;
			vec3 nrm = push.normalBuffer.normals[gl_VertexIndex].xyz;

			vec4 worldPos = push.model * vec4(pos, 1.0);
			vWorldPos = worldPos.xyz;
			vNormal = normalize(mat3(push.model) * nrm);
			vUv = vec2(0.0);
			gl_Position = uCamera.proj * uCamera.view * worldPos;
		}
	"""

	private const val gBufferFragment = """
		#version 450
		layout(location = 0) in vec3 vWorldPos;
		layout(location = 1) in vec3 vNormal;
		layout(location = 2) in vec2 vUv;

		layout(location = 0) out vec4 outAlbedoRoughness;
		layout(location = 1) out vec4 outNormalMetallic;
		layout(location = 2) out vec4 outPositionDepth;

		layout(push_constant) uniform ModelData {
			mat4 model;
			vec4 baseColor;
			float metallic;
			float roughness;
			float ao;
		} push;

		void main() {
			vec3 n = normalize(vNormal);
			outAlbedoRoughness = vec4(push.baseColor.rgb, clamp(push.roughness, 0.04, 1.0));
			outNormalMetallic = vec4(n, clamp(push.metallic, 0.0, 1.0));
			outPositionDepth = vec4(vWorldPos, gl_FragCoord.z);
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

	private const val aoFragment = """
		#version 450
		layout(location = 0) in vec2 vUv;
		layout(set = 0, binding = 0) uniform sampler2D gPositionDepth;
		layout(set = 0, binding = 1) uniform sampler2D gNormalMetallic;

		layout(push_constant) uniform AOSettings {
			float radius;
			float bias;
			float intensity;
		} push;

		layout(location = 0) out float outAO;

		const vec2 kernel[8] = vec2[](
			vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0),
			vec2(0.7, 0.7), vec2(-0.7, 0.7), vec2(0.7, -0.7), vec2(-0.7, -0.7)
		);

		void main() {
			vec3 pos = texture(gPositionDepth, vUv).xyz;
			vec3 normal = normalize(texture(gNormalMetallic, vUv).xyz);
			float occlusion = 0.0;
			vec2 texel = 1.0 / vec2(textureSize(gPositionDepth, 0));

			for (int i = 0; i < 8; ++i) {
				vec2 offsetUv = vUv + kernel[i] * texel * push.radius;
				vec3 samplePos = texture(gPositionDepth, offsetUv).xyz;
				float rangeCheck = smoothstep(0.0, 1.0, push.radius / abs(pos.z - samplePos.z + 1e-4));
				float delta = dot(normal, normalize(samplePos - pos));
				occlusion += (delta < push.bias ? 1.0 : 0.0) * rangeCheck;
			}

			occlusion = clamp(1.0 - (occlusion / 8.0) * push.intensity, 0.0, 1.0);
			outAO = occlusion;
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
			projCoords = projCoords * 0.5 + 0.5;
			if (projCoords.z > 1.0) return 0.0;

			vec2 texelSize = 1.0 / vec2(textureSize(shadowMap, 0));
			float shadow = 0.0;
			for (int x = -1; x <= 1; ++x) {
				for (int y = -1; y <= 1; ++y) {
					float pcfDepth = texture(shadowMap, projCoords.xy + vec2(x, y) * texelSize).r;
					shadow += projCoords.z - push.shadowStrength > pcfDepth + uLight.shadowBias ? 1.0 : 0.0;
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

