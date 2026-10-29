// Draws the prescaled game (an offscreen texture, already the right way up) onto the screen.
precision mediump float;

attribute vec4 position;
varying vec2 texCoord;

void main() {
    gl_Position = position;
    texCoord = position.st * 0.5 + 0.5;
}
