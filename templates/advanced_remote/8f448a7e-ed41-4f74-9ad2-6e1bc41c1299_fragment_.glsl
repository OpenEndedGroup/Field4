precision highp float;
precision highp int;
// the stuff above just needs to be there

// these are _inputs_ from the vertex shader
// you'll see declarations just like these
// there
varying vec3 vNormal;
varying vec3 pos;
varying vec2 vUv;

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
    gl_FragColor = vec4(1,0,0,0.5);
}