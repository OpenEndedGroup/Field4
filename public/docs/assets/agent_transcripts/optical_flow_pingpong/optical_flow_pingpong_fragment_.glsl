#version 460
layout(location=0) out vec4 color;
layout(push_constant) uniform Frame { float time; } frame;
void main() {
    vec2 p=gl_FragCoord.xy-vec2(sin(frame.time)*180,cos(frame.time*1.3)*120);
    vec2 cell=floor(p/34),local=fract(p/34)-.5;
    float seed=fract(sin(dot(cell,vec2(12.9898,78.233)))*43758.5453);
    vec3 pigment=.35+.3*cos(seed*6.283+vec3(0,2.094,4.189));
    float disk=1-smoothstep(.20+.12*seed,.24+.12*seed,length(local));
    float checker=mod(cell.x+cell.y,2);
    vec3 background=vec3(.05+.15*checker)+.04*sin(p.x*.27)*sin(p.y*.23);
    color=vec4(mix(background,pigment,disk),1);
}
