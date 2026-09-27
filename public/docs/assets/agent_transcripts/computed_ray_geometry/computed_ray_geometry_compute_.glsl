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
