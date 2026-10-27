// Adapted from mGBA (third_party/mgba/res/shaders/scanlines.shader/scanlines.fs), by Dominus Iniquitatis.
// mGBA is MPL-2.0: https://github.com/mgba-emu/mgba
precision mediump float;

uniform sampler2D tex;
uniform vec2 texSize;
varying vec2 texCoord;

uniform float lineBrightness;

void main() {
    vec4 color = cc(texture2D(tex, texCoord));

    if (mod(texCoord.t * texSize.y * 2.0, 2.0) < 1.0) {
        color.rgb *= vec3(1.0, 1.0, 1.0) * lineBrightness;
    }

    gl_FragColor = color;
}
