#version 450
layout(std430,set=0,binding=1) readonly buffer VideoMeterTiles { vec4 records[]; } tiles;
layout(push_constant) uniform MeterFinishPush {
    vec4 dimensions; // tile count per screen, screen count, active mask, changed-image mask
    vec4 times; // measurement dt, prediction horizon, measurement tick, reset
    vec4 gains; // rising alpha/beta, falling alpha/beta
    vec4 margins; // black footroom, white headroom, minimum endpoint span, reserved
    vec4 bounds; // black minimum/maximum, white minimum/maximum
    vec4 quantiles; // endpoint source (0 extrema / 1 sketch), low/high quantiles, sketch available
    vec4 limits; // optional velocity magnitude cap (zero unlimited), reserved
} pc;
// FIELD_STATS
// METER_COMMON
layout(local_size_x=256) in;
shared vec4 minima[256], sums[256], maxima[256], counts[256];
void main() {
    uint lane=gl_LocalInvocationIndex, screen=gl_WorkGroupID.x;
    uint enabledMask=uint(pc.dimensions.z+.5), changed=uint(pc.dimensions.w+.5);
    uint at=screen*RECORDS;
    if((enabledMask&(1u<<screen))==0u) {
        if(lane==0u) meter.records[at+10u]=vec4(0);
        return;
    }
    vec4 lo=meter.records[at], mean=meter.records[at+1u], hi=meter.records[at+2u], count=meter.records[at+9u];
    if((changed&(1u<<screen))!=0u) {
        uint tileCount=uint(pc.dimensions.x+.5);
        vec4 localMin=vec4(3.402823e38), localMax=vec4(-3.402823e38), sum=vec4(0), n=vec4(0);
        for(uint tile=lane;tile<tileCount;tile+=256u) {
            uint offset=(screen*tileCount+tile)*4u;
            localMin=min(localMin,tiles.records[offset]); sum+=tiles.records[offset+1u];
            localMax=max(localMax,tiles.records[offset+2u]); n+=tiles.records[offset+3u];
        }
        minima[lane]=localMin; sums[lane]=sum; maxima[lane]=localMax; counts[lane]=n;
        barrier();
        for(uint step=128u;step>0u;step>>=1u) {
            if(lane<step) {
                minima[lane]=min(minima[lane],minima[lane+step]); sums[lane]+=sums[lane+step];
                maxima[lane]=max(maxima[lane],maxima[lane+step]); counts[lane]+=counts[lane+step];
            }
            barrier();
        }
        lo=minima[0]; hi=maxima[0]; count=counts[0]; mean=sums[0]/max(count,vec4(1));
        for(int k=0;k<4;k++) if(count[k]<=0) { lo[k]=0; hi[k]=0; }
    }
    if(lane==0u) writeStatistics(screen,lo,mean,hi,count,pc.times.z>.5);
}
