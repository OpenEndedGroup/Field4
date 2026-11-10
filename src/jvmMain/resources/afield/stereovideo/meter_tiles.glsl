#version 450
layout(set=0,binding=0) uniform sampler2D accumulationImage;
layout(std430,set=0,binding=1) buffer VideoMeterTiles { vec4 records[]; } tiles;
layout(push_constant) uniform MeterTilePush {
    vec4 geometry; // width, height, tiles X, first output record for this screen
} pc;

layout(local_size_x=16,local_size_y=16) in;
shared vec4 minima[256], sums[256], maxima[256], counts[256];

void main() {
    uint lane=gl_LocalInvocationIndex;
    ivec2 size=ivec2(pc.geometry.xy+.5);
    ivec2 base=ivec2(gl_WorkGroupID.xy)*32+ivec2(gl_LocalInvocationID.xy);
    vec4 lo=vec4(3.402823e38), hi=vec4(-3.402823e38), sum=vec4(0), count=vec4(0);
    for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
        ivec2 p=base+ivec2(x,y)*16;
        if(all(lessThan(p,size))) {
            vec3 rgb=texelFetch(accumulationImage,p,0).rgb;
            vec4 value=vec4(rgb,dot(rgb,vec3(.2126,.7152,.0722))); // RGB plus linear luminance
            for(int k=0;k<4;k++) if(!isnan(value[k])&&!isinf(value[k])) {
                lo[k]=min(lo[k],value[k]); hi[k]=max(hi[k],value[k]); sum[k]+=value[k]; count[k]++;
            }
        }
    }
    minima[lane]=lo; sums[lane]=sum; maxima[lane]=hi; counts[lane]=count;
    barrier();
    for(uint step=128u;step>0u;step>>=1u) {
        if(lane<step) {
            minima[lane]=min(minima[lane],minima[lane+step]); sums[lane]+=sums[lane+step];
            maxima[lane]=max(maxima[lane],maxima[lane+step]); counts[lane]+=counts[lane+step];
        }
        barrier();
    }
    if(lane==0u) {
        uint at=uint(pc.geometry.w+.5)+(gl_WorkGroupID.y*uint(pc.geometry.z+.5)+gl_WorkGroupID.x)*4u;
        tiles.records[at]=minima[0]; tiles.records[at+1u]=sums[0];
        tiles.records[at+2u]=maxima[0]; tiles.records[at+3u]=counts[0];
    }
}
