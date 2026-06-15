#version 450
layout(local_size_x = 16, local_size_y = 1, local_size_z = 1) in;

layout(set=0, binding = 0) buffer InputBuffer {
    vec4 dat[];
};

void main() {
    uint idx = gl_GlobalInvocationID.x;
    float timeStep = 1.1;
    dat[idx].xy += dat[idx].zw * timeStep;
    dat[idx].zw *= 0.99;
}