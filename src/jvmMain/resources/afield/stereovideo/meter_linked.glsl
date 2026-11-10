#version 450
layout(push_constant) uniform MeterLinkedPush {
    vec4 dimensions; // tile count, screen count, active mask, changed-image mask
    vec4 times; // measurement dt, prediction horizon, measurement tick, reset
    vec4 gains; // rising alpha/beta, falling alpha/beta
    vec4 margins; // black footroom, white headroom, minimum endpoint span, reserved
    vec4 bounds; // black minimum/maximum, white minimum/maximum
    vec4 limits; // optional velocity cap, reserved
} pc;
// METER_COMMON
layout(local_size_x=1) in;
void main() {
    uint screenCount=uint(pc.dimensions.y+.5);
    vec4 lo=vec4(3.402823e38), sum=vec4(0), hi=vec4(-3.402823e38), count=vec4(0);
    for(uint screen=0u;screen<screenCount;screen++) {
        uint at=screen*RECORDS;
        if(meter.records[at+10u].x>.5) {
            vec4 n=meter.records[at+9u];
            lo=min(lo,meter.records[at]); hi=max(hi,meter.records[at+2u]);
            sum+=meter.records[at+1u]*n; count+=n;
        }
    }
    vec4 mean=sum/max(count,vec4(1));
    for(int k=0;k<4;k++) if(count[k]<=0) { lo[k]=0; hi[k]=0; }
    writeStatistics(screenCount,lo,mean,hi,count,pc.times.z>.5);
}
