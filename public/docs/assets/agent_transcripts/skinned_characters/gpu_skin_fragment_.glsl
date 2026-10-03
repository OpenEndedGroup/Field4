#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 3) in vec4 vColor;
layout(location = 1) in vec3 vNormalVS;

void main()
{
    float d = abs(dot(vNormalVS.xyz, normalize(vec3(1, 0.4, -0.2))));
    uFragColor = vec4(vNormalVS.rgb*d,1);
}