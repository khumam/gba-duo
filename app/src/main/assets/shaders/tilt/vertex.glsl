// Draws the game offscreen texture again, blurred along one axis by more the nearer it is to the top or bottom.
precision mediump float;

attribute vec4 position;
varying vec2 texCoord;

void main() {
    gl_Position = position;
    texCoord = position.st * 0.5 + 0.5;
}
