# Field Document: computed_ray_geometry

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["computed_ray_geometry"] "properties" {"children" #{#afield/box "computed_ray_geometry"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 17 "characters" 321 "sha256" "79dab431bdab018f5a13ebcb55d1eaf22c276161949fb54c70dbe0d69aa149fa"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableRayTracing = true
Options.enableVR = false
Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Field")
    Renderer(window)
}

println("agent graphics renderer ready")
start(renderer::run)
```

## 2. computed ray geometry

<!-- field-box id="computed_ray_geometry" index="1" -->

```edn [field-box id="computed_ray_geometry" role="properties"]
{"id" "computed_ray_geometry" "index" 1 "name" "computed ray geometry" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "computed ray geometry" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "computed_ray_geometry_code_.kt" "lines" 26 "characters" 1127 "sha256" "6e67a74c4e4d00e74356887fad4fec9b7f22411e402fc711bba68f80104938f3"} {"name" "compute" "language" "glsl" "path" "computed_ray_geometry_compute_.glsl" "lines" 30 "characters" 1768 "sha256" "25ae45ac65babc0545a50d58b73605a97867b88d479eae27c899c25880a9e579"}]}
```

```kotlin [field-property box="computed_ray_geometry" name="code" path="computed_ray_geometry_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.modules.*

prop<ClusterGeometryDemo>(lab)
fromContextWithNameOrNull<ClusterGeometryDemo>("lab")?.close()
check(renderer.device.supportsClusterAccelerationStructure) {
    "This Starting Point needs enabled VK_NV_cluster_acceleration_structure and a ray-tracing pipeline."
}
lab = ClusterGeometryDemo(renderer, producerFactory={ computeModuleHere(it) }).install()
var animate=true
var topology=0
var view=0
controlGroup("computed ray geometry") {
    checkbox("Animate computed vertices",initialValue=true) { animate=it }
    slider("Topology: alternate diagonals",min=0.0,max=1.0,step=1.0,initialValue=0.0) { topology=it.toInt() }
    slider("0: Surface · 1: Clusters · 2: Triangles",min=0.0,max=2.0,step=1.0,initialValue=0.0) { view=it.toInt() }
}
displayOpen("computed ray geometry")
println("Edit the compute shader: it writes the positions, local indices and cluster descriptions. 768 clusters / 98,304 triangles.")
frames {
    while(true) {
        lab.animate=animate;lab.topology=topology;lab.view=view
        frame()
    }
}.end { lab.close() }
```

```glsl [field-property box="computed_ray_geometry" name="compute" path="computed_ray_geometry_compute_.glsl"]
#version 460
#extension GL_EXT_buffer_reference : require
layout(local_size_x=64) in;
layout(buffer_reference,std430,buffer_reference_align=16) buffer Positions {vec4 data[];};
layout(buffer_reference,std430,buffer_reference_align=4) buffer Indices {uint data[];};
struct Cluster {uvec4 range;uvec4 identity;};
layout(buffer_reference,std430,buffer_reference_align=16) buffer Clusters {Cluster data[];};
layout(push_constant) uniform Generate {Positions positions;Indices indices;Clusters descriptions;
    uint columns;uint rows;float time;uint topology;int invalidCluster;int invalidMode;} pc;
void main(){uint id=gl_GlobalInvocationID.x;if(id>=pc.columns*pc.rows)return;
    uint cx=id%pc.columns,cy=id/pc.columns;
    for(uint y=0;y<9;y++)for(uint x=0;x<9;x++){
        vec2 p=(vec2(cx,cy)+vec2(x,y)/8)/vec2(pc.columns,pc.rows)*8-4;
        float z=.55*sin(p.x*1.7+pc.time)*cos(p.y*1.3-pc.time*.7)+.2*sin(p.x*3+p.y*2+pc.time*1.3);
        pc.positions.data[id*81+y*9+x]=vec4(p.x,z,p.y,1);
    }
    for(uint y=0;y<8;y++)for(uint x=0;x<8;x++){
        uint a=y*9+x,b=a+1,c=a+10,d=a+9,o=id*384+(y*8+x)*6;
        bool flip=((x+y+pc.topology)&1)!=0;
        pc.indices.data[o]=a;pc.indices.data[o+1]=flip?d:c;pc.indices.data[o+2]=b;
        pc.indices.data[o+3]=flip?b:a;pc.indices.data[o+4]=d;pc.indices.data[o+5]=c;
    }
    pc.descriptions.data[id]=Cluster(uvec4(id*81,id*384,81,128),uvec4(id,id,0,0));
    // Deliberate invalid address-range injection exercises the public builder's GPU guard.
    if(int(id)==pc.invalidCluster) {
        if(pc.invalidMode==1)pc.descriptions.data[id].range.x=0xffffffffu;
        if(pc.invalidMode==2)pc.indices.data[id*384]=100000u;
        if(pc.invalidMode==4)pc.positions.data[id*81].x=uintBitsToFloat(0x7fc00000u);
    }
}
```

