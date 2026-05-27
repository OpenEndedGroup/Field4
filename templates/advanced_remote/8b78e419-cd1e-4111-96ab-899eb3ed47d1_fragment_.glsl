precision highp float;
precision highp int;
// the stuff above just needs to be there

// these are _inputs_ from the vertex shader
// you'll see declarations just like these
// there
varying vec3 vNormal;
varying vec3 pos;
varying vec2 vUv;


uniform sampler2D map;
// this is the declaration for a texture map called 'map
//uniform sampler2D map;

void main()
{
    // and this is how we look up a texture
    // given texture coordinates 'vUv'
    //vec4 m = texture2D(map, vUv);

    // in Javascript we'd call this 'vec'
    // but here we have to say vec4
    // this is our translucent red
    gl_FragColor = vec4(1,0,0,1);

    vec4 texcol = texture2D(map, vUv);
    gl_FragColor.x = texcol.x;
    gl_FragColor.y = texcol.y;
    gl_FragColor.z = texcol.z;

    // this counts as the world most simple 'blue' screen
    if (gl_FragColor.z>0.9) discard;
}