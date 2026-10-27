// No shader: straight passthrough of the emulator's framebuffer.
precision mediump float;

varying vec2 texCoord;
uniform sampler2D tex;

void main() {
    gl_FragColor = cc(texture2D(tex, texCoord));
}
