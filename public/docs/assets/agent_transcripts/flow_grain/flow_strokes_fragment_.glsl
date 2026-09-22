#version 450
layout(location=0) in vec2 edge;
layout(location=1) in vec2 world;
layout(location=0) out vec4 color;
void main() {
    bool rock = (abs(world.y+2.)<.10 && abs(world.x)<4.) || length(world-vec2(2.4,1.2))<.8;
    if (rock) discard;
    float alpha = (1.-abs(edge.y)) * smoothstep(0.,.25,edge.x);
    float light = 1. / (1. + dot(world-vec2(-3,3), world-vec2(-3,3))*.035);
    color = vec4(vec3(.8), alpha * light * .35);
}
