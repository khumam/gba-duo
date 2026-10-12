// Shared vertex shader for all single-pass presets except xBR (which needs its own,
// since it precomputes neighbor texture coordinates). Matches mGBA's own default
// vertex shader orientation (src/platform/opengl/gles2.c _vertexShader).
precision mediump float;

attribute vec4 position;
varying vec2 texCoord;

void main() {
    gl_Position = position;
    texCoord = (position.st + vec2(1.0, -1.0)) * vec2(0.5, -0.5);
}
