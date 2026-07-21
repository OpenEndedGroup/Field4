#version 450
#extension GL_KHR_shader_subgroup_ballot: enable
#extension GL_KHR_shader_subgroup_arithmetic: enable

layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;

layout(set=0, binding = 0) buffer InputBuffer {
    vec4 dat[];
};

layout(set=0, binding = 1) buffer DrawingControl {
    uint at;
};

layout(set=0, binding = 2) buffer VertexData {
    vec4 vertexData[];
};

void main() {
    uint idx = gl_GlobalInvocationID.x;
    float timeStep = 0.01;
    
    dat[idx].xy = dat[idx].zw * timeStep;
    float l = length(dat[idx].zw);
    float targetLength = sin(dat[idx].w*8+dat[idx].x*40-dat[idx].y*21);
    float alpha = 0.9999;
    if (l>0) {
        l = l*alpha + (1-alpha)*targetLength;
         dat[idx].zw = normalize(dat[idx].zw)*l;
    }


    uvec4 bits = subgroupBallot(true);
    uint num = subgroupBallotBitCount(bits);
    bool leader = subgroupElect();
    uint now_at = 0;
    if (leader) {
        now_at = atomicAdd(at, num);
    }
    uint base = subgroupMax(now_at) + gl_SubgroupInvocationID;
    if (base<2560000 && base>=0)
        vertexData[base] = dat[idx];
}