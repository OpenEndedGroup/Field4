#version 450
layout(local_size_x = 16, local_size_y = 1, local_size_z = 1) in;

layout(set=0, binding = 0) writeonly buffer OutputBuffer {
    vec4 outData[];
};

layout(set=0, binding = 1) readonly buffer InputBuffer {
    vec4 inData[];
};

void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx<256)
        outData[idx] = vec4(0.0)+inData[idx]*vec4(1,1,1,1)+vec4(3+idx*1, -(idx*21.0), idx*3, idx*4);
}