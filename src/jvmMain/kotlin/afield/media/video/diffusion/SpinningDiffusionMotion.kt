package afield.media.video.diffusion

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.CommandBuffer
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import afield.graphics.render.Module
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.shader.fromViews
import afield.graphics.texture.HasImageViewArray
import afield.graphics.texture.PreparesSampledImages
import org.lwjgl.vulkan.VK10.*

/** Renderer-derived correspondence for the demo ribbons; reference IDs reject hidden crossings. */
internal class SpinningDiffusionMotion(
    private val renderer: Renderer, private val source: RenderToTexture,
    private val positions: List<Float>, private val normals: List<Float>, private val ids: List<Float>,
    private val indices: List<Int>, private val angle: () -> Float
) : StreamDiffusionMotionProvider, AutoCloseable {
    private val angles = linkedMapOf<Long, Float>()
    private fun target() = RenderToTexture(renderer) {
        width = source.width; height = source.height; format = VK_FORMAT_R32G32B32A32_SFLOAT
        useDepth = false
    }.also { it.setDefaultSampler(anisotropyEnable = false, filter = VK_FILTER_NEAREST) }
    private val referenceIds = target()
    private val mapping = target()
    private val filledPaper = RenderToTexture(renderer) {
        width = source.width; height = source.height; format = source.colorFormats[0]; useDepth = false
    }.also { it.setDefaultSampler(anisotropyEnable = false) }
    private var styledReference = source
    private val modules = mutableListOf<Module>()
    private fun geometry(target: RenderToTexture, fragment: String, name: String): Mesh {
        val module = Module(parameters(renderer) {
            +target; vertex = VERTEX; this.fragment = fragment
            depthTest = false; depthWrite = false; blend = false
            textureResolver = { t -> if (t.name == "referenceIds") fromViews(referenceIds, t.name, referenceIds.defaultSampler!!) else null }
        })
        modules += module; check(module.valid) { "Spinning motion shader failed" }
        val pointer = module.pointer()
        val mesh = Mesh(renderer, pointer).resize(positions.size / 3, indices.size / 3)
        mesh.write("pos") { positions.forEach { put(it) } }
        mesh.write("tc") { normals.forEach { put(it) } }
        mesh.write("col") { ids.forEachIndexed { i, id -> put(id); put(if (i % 2 == 0) -1f else 1f); put(0f); put(0f) } }
        mesh.t!!.mapAndWrite_ints { indices.forEach { put(it) } }
        module.meshes[name] = mesh; target.modules[name] = pointer
        return mesh
    }
    private val referenceMesh: Mesh
    private val background: Module
    private val mappingMesh: Mesh
    private val paperFill: Module
    private var closed = false
    init {
        try {
            referenceMesh = geometry(referenceIds, IDS, "reference-ids")
            background = Module(parameters(renderer) {
                +mapping; vertex = FULLSCREEN; fragment = BACKGROUND
                depthTest = false; depthWrite = false; blend = false
                textureResolver = { t -> if (t.name == "referenceIds") fromViews(referenceIds, t.name, referenceIds.defaultSampler!!) else null }
            }).also { module ->
                modules += module; check(module.valid)
                val pointer = module.pointer()
                module.meshes["background"] = Mesh(renderer, pointer).resize(3, 0)
                mapping.modules["background"] = pointer
            }
            mappingMesh = geometry(mapping, MOTION, "curve-correspondence")
            paperFill = Module(parameters(renderer) {
                +filledPaper; vertex = FULLSCREEN; fragment = PAPER_FILL
                depthTest = false; depthWrite = false; blend = false
                textureResolver = { t ->
                    when (t.name) {
                        "sourceImage" -> fromViews(source, t.name, source.defaultSampler!!)
                        "referenceIds" -> fromViews(referenceIds, t.name, referenceIds.defaultSampler!!)
                        "motionImage" -> fromViews(mapping, t.name, mapping.defaultSampler!!)
                        "referenceImage" -> {
                            val provider = object : HasImageViewArray, PreparesSampledImages {
                                override val imageViewArray get() = styledReference.imageViewArray
                                override fun prepareSampledImagesFor(command: CommandBuffer) =
                                    styledReference.prepareSampledImagesFor(command)
                            }
                            fromViews(provider, t.name, source.defaultSampler!!)
                        }
                        else -> null
                    }
                }
            }).also { module ->
                modules += module; check(module.valid) { "Diffusion paper fill shader failed" }
                val pointer = module.pointer()
                module.meshes["fill-paper"] = Mesh(renderer, pointer).resize(3, 0)
                filledPaper.modules["fill-paper"] = pointer
            }
        } catch (e: Throwable) { close(); throw e }
    }
    override fun onFrame(frame: StreamDiffusionFrame) {
        angles[frame.id] = angle()
        while (angles.size > 512) angles.remove(angles.keys.first())
    }
    override fun record(command: CommandBuffer, request: StreamDiffusionMotionRequest): RenderToTexture? {
        val current = angles[request.current.id] ?: return null
        val previous = angles[request.reference.id] ?: return null
        referenceMesh.push["angle"] = previous; referenceMesh.push["referenceAngle"] = previous
        referenceIds.render(command, 0)
        mappingMesh.push["angle"] = current; mappingMesh.push["referenceAngle"] = previous
        mapping.render(command, 0)
        return mapping
    }
    override fun recordFallback(command: CommandBuffer, request: StreamDiffusionMotionRequest,
        source: RenderToTexture, referenceImage: RenderToTexture): RenderToTexture {
        styledReference = referenceImage
        filledPaper.render(command, 0)
        return filledPaper
    }
    override fun close() {
        if (closed) return
        closed = true
        renderer.cleanup += Runnable {
            modules.forEach { it.cleanup() }
            for (target in listOf(filledPaper, mapping, referenceIds)) {
                target.imageViewArray.forEach { it.imageView.cleanup() }
                target.colorImageRefs.forEach { it?.cleanup() }; target.defaultSampler?.cleanup()
            }
        }
    }
    companion object {
        private const val PROJECTION = """
            vec3 transformed(vec3 q,float angle) {
                float tilt=.32*sin(angle);
                q=vec3(cos(tilt)*q.x+sin(tilt)*q.z,q.y,-sin(tilt)*q.x+cos(tilt)*q.z);
                mat2 turn=mat2(cos(angle),sin(angle),-sin(angle),cos(angle));
                return vec3(turn*q.xy,q.z);
            }
            vec2 projected(vec3 q,float angle) {
                q=transformed(q,angle); return q.xy*(3.5/(q.z+3.5))*.76;
            }
        """
        private val VERTEX = """#version 460
            layout(location=0) in vec3 pos;
            layout(location=1) in vec2 tc;
            layout(location=2) in vec4 col;
            layout(location=0) out vec3 surfacePoint;
            layout(location=1) out float surfaceScale;
            layout(location=2) flat out float surfaceId;
            layout(location=3) out float ribbonSide;
            layout(push_constant) uniform P { float angle; float referenceAngle; } p;
            $PROJECTION
            void main() {
                // Extend correspondence around ribbons to carry generated stroke edges.
                vec3 q=pos+vec3(tc*6,0);
                surfaceScale=1./(transformed(q,p.angle).z+3.5);
                surfacePoint=q*surfaceScale; surfaceId=col.x; ribbonSide=col.y;
                gl_Position=vec4(projected(q,p.angle),0,1);
            }
        """
        private const val IDS = """#version 460
            layout(location=2) flat in float surfaceId;
            layout(location=0) out vec4 color;
            void main() { color=vec4(surfaceId,0,0,1); }
        """
        private val MOTION = """#version 460
            layout(location=3) in float ribbonSide;
            layout(location=0) in vec3 surfacePoint;
            layout(location=1) in float surfaceScale;
            layout(location=2) flat in float surfaceId;
            layout(set=0,binding=0) uniform sampler2D referenceIds;
            layout(push_constant) uniform P { float angle; float referenceAngle; } p;
            layout(location=0) out vec4 color;
            $PROJECTION
            void main() {
                vec2 previousUv=projected(surfacePoint/surfaceScale,p.referenceAngle)*.5+.5;
                previousUv.y=1-previousUv.y; // Field uses a negative-height viewport.
                bool inside=all(greaterThanEqual(previousUv,vec2(0))) && all(lessThanEqual(previousUv,vec2(1)));
                float visible=inside && abs(texture(referenceIds,previousUv).r-surfaceId)<.5?1:0;
                color=vec4(previousUv,visible*(1-smoothstep(.65,1,abs(ribbonSide))),0);
            }
        """
        private const val FULLSCREEN = """#version 460
            layout(location=0) out vec2 uv;
            void main() {
                vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                uv=vec2(p.x,1-p.y); gl_Position=vec4(p*2-1,0,1);
            }
        """
        // The source paper is flat; generated paper has a different tone/texture. Fill rejected
        // paper using nearby reference paper, preserving actual current source strokes. This
        // prevents the visibility mask leaving a bright impression of the old curve positions.
        private const val PAPER_FILL = """#version 460
            layout(location=0) in vec2 uv;
            layout(set=0,binding=0) uniform sampler2D sourceImage;
            layout(set=0,binding=1) uniform sampler2D referenceImage;
            layout(set=0,binding=2) uniform sampler2D referenceIds;
            layout(set=0,binding=3) uniform sampler2D motionImage;
            layout(location=0) out vec4 color;
            void main() {
                vec4 fresh=texture(sourceImage,uv);
                vec3 sourcePaper=texelFetch(sourceImage,ivec2(0),0).rgb;
                vec4 m=texelFetch(motionImage,ivec2(gl_FragCoord.xy),0);
                if (m.z*(1-m.w)>=.999 || distance(fresh.rgb,sourcePaper)>.005) {
                    color=fresh; return;
                }
                vec3 paper=texture(referenceImage,vec2(.02)).rgb;
                vec2 texel=1./vec2(textureSize(referenceImage,0));
                vec3 sum=vec3(0); float total=0;
                for(int y=-4;y<=4;y++) for(int x=-4;x<=4;x++) {
                    vec2 q=uv+vec2(x,y)*6*texel;
                    if (any(lessThan(q,vec2(0))) || any(greaterThan(q,vec2(1))) ||
                        texture(referenceIds,q).r>.5) continue;
                    vec3 c=texture(referenceImage,q).rgb;
                    // Do not copy another strong mark into newly revealed paper.
                    if (abs(dot(c-paper,vec3(.2126,.7152,.0722)))>.25) continue;
                    float w=1./(1+float(x*x+y*y)); sum+=c*w; total+=w;
                }
                color=vec4(total>0?sum/total:paper,fresh.a);
            }
        """
        private const val BACKGROUND = """#version 460
            layout(location=0) in vec2 uv;
            layout(set=0,binding=0) uniform sampler2D referenceIds;
            layout(location=0) out vec4 color;
            void main() {
                // Newly revealed paper, plus a conservative band for generated stroke edges.
                vec2 texel=1./vec2(textureSize(referenceIds,0));
                float occupied=0;
                for(int y=-2;y<=2;y++) for(int x=-2;x<=2;x++) {
                    float hit=texture(referenceIds,uv+vec2(x,y)*4*texel).r>.5?1:0;
                    occupied=max(occupied,hit*(1-length(vec2(x,y))/3.));
                }
                color=vec4(uv,1-occupied,0);
            }
        """
    }
}
