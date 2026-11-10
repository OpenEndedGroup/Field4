layout(std430,set=0,binding=2) buffer VideoExposureStatistics { vec4 records[]; } meter;
const uint RECORDS=16u; // min/mean/max and trackers, counts, metadata, endpoints, quantiles/trackers/sketch metadata

vec2 track(float measurement,float previousMeasurement,float level,float velocity) {
    float dt=max(pc.times.x,.000001);
    float predicted=level+velocity*dt;
    float residual=measurement-predicted;
    float movement=measurement-previousMeasurement;
    // On a flat measurement, use the correction direction so velocity settles rather than drifting.
    bool rising=abs(movement)>.000000001 ? movement>0 : residual>=0;
    vec2 gains=rising ? pc.gains.xy : pc.gains.zw;
    level=predicted+gains.x*residual;
    velocity+=gains.y*residual/dt;
    if(pc.limits.x>0) velocity=clamp(velocity,-pc.limits.x,pc.limits.x);
    return vec2(level,velocity);
}

void writeStatistics(uint screen,vec4 lo,vec4 mean,vec4 hi,vec4 count,bool measurement) {
    uint at=screen*RECORDS;
    bool wasValid=meter.records[at+10u].x>.5;
    bool valid=count.w>0;
    bool seed=!wasValid||pc.times.w>.5;
    vec4 raw[3]=vec4[3](lo,mean,hi);
    vec4 forecast[3];
    for(uint which=0u;which<3u;which++) {
        vec4 x=meter.records[at+3u+which], v=meter.records[at+6u+which];
        for(int k=0;k<4;k++) {
            if(count[k]<=0) { x[k]=0; v[k]=0; }
            else if(seed) { x[k]=raw[which][k]; v[k]=0; }
            else if(measurement) {
                vec2 updated=track(raw[which][k],meter.records[at+which][k],x[k],v[k]);
                x[k]=updated.x; v[k]=updated.y;
            }
        }
        meter.records[at+which]=raw[which];
        meter.records[at+3u+which]=x;
        meter.records[at+6u+which]=v;
        forecast[which]=x+v*pc.times.y;
    }
    vec4 previousQuantiles=meter.records[at+12u];
    vec4 qLevel=meter.records[at+13u], qVelocity=meter.records[at+14u];
    vec4 qRaw=vec4(0), sketchMeta=vec4(0);
    bool qValid=false;
    if(pc.quantiles.x>.5 && pc.quantiles.w>.5 && valid) {
        FieldScalarStats sketchStats=fieldStatsRead(screen);
        qValid=sketchStats.finiteCount>0u && sketchStats.droppedPartials==0;
        qRaw=vec4(fieldStatsQuantile(screen,pc.quantiles.y),fieldStatsQuantile(screen,.5),
            fieldStatsQuantile(screen,pc.quantiles.z),qValid?1:0);
        sketchMeta=vec4(float(sketchStats.finiteCount),sketchStats.variance,sketchStats.standardDeviation,sketchStats.droppedPartials);
    }
    for(int k=0;k<3;k++) {
        if(!qValid) { qLevel[k]=0; qVelocity[k]=0; }
        else if(seed || previousQuantiles.w<.5) { qLevel[k]=qRaw[k]; qVelocity[k]=0; }
        else if(measurement) {
            vec2 updated=track(qRaw[k],previousQuantiles[k],qLevel[k],qVelocity[k]);
            qLevel[k]=updated.x; qVelocity[k]=updated.y;
        }
    }
    qLevel.w=qValid?1:0;
    meter.records[at+12u]=qRaw; meter.records[at+13u]=qLevel;
    meter.records[at+14u]=qVelocity; meter.records[at+15u]=sketchMeta;
    vec3 qForecast=qLevel.xyz+qVelocity.xyz*pc.times.y;
    // Prediction can overshoot or cross. Endpoint bounds and minimum span always remain authoritative.
    float low=min(forecast[0].w,forecast[2].w), high=max(forecast[0].w,forecast[2].w);
    if(qValid) { low=min(qForecast.x,qForecast.z); high=max(qForecast.x,qForecast.z); }
    float black=clamp(low-pc.margins.x,pc.bounds.x,min(pc.bounds.y,pc.bounds.w-pc.margins.z));
    float white=clamp(high+pc.margins.y,max(pc.bounds.z,black+pc.margins.z),pc.bounds.w);
    meter.records[at+9u]=count;
    meter.records[at+10u]=vec4(valid?1:0,pc.times.y,0,0);
    meter.records[at+11u]=vec4(black,white,forecast[1].w,valid?1:0);
}
