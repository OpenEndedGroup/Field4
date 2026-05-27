#version 450

layout(local_size_x = 64) in;

layout(binding = 0) buffer InputBuffer {
    uint inData[];
};

layout(binding = 1) buffer OutputBuffer {
    uint outData[];
};

void main() {
    uint idx = gl_GlobalInvocationID.x;
    outData[idx] = inData[idx] + 1;
}