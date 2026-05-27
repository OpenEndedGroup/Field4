precision highp float;
precision highp int;
// those two lines just need to be there at the start

// this is an 'attribute' - it is associated
// with each _vertex_
attribute vec3 position;

// similarily this is a texture coordinate
// that's on a vertex
attribute vec2 uv;
// this is an _output_. It's computed here and sent
// onwards to the fragment shader
varying vec2 vUv;

// same as uv above
attribute vec3 normal;
varying vec3 vNormal;

// these are _uniforms_: they don't vary from vertex
// to vertex. These are associated whith the whole
// mesh. These are matrices that define where the camera
// is and what it can see
uniform mat3 normalMatrix;
uniform mat4 modelViewMatrix;
uniform mat4 modelMatrix;
uniform mat4 viewMatrix;
uniform mat4 projectionMatrix;

void main()
{
    // we just copy the input texture coordinate
    // to the outuput
    vUv = uv;

    // we transform the _position_ by the camera
    gl_Position = modelViewMatrix * vec4( position, 1.0);

    // and project it onto the screen
    gl_Position = projectionMatrix  * gl_Position;

    // and figure out what this does to the normal
    vNormal = normalMatrix * normal;
}