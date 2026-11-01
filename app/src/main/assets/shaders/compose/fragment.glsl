// The finished picture, sampled smoothly: it is being zoomed while Wide view switches.
precision mediump float;

varying vec2 texCoord;
uniform sampler2D tex;

void main() {
    gl_FragColor = vec4(texture2D(tex, texCoord).rgb, 1.0);
}
