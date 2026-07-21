#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 3) in vec4 vColor;
layout(location = 2) in vec2 vUV;

layout(set = 0, binding = 1) uniform sampler2D uTextures[6];

void main()
{
   uFragColor = texture(uTextures[0], vUV);
//    uFragColor = vec4(1,1,1,1);
    //uFragColor.x = 0.0;
   // uFragColor.w += 0.2;
    //uFragColor.xy = vUV;
    
    uFragColor = vec4(vUV.xxx, 1);
    
}