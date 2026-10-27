// Adapted from mGBA (third_party/mgba/res/shaders/scale2x.shader/scale2x.fs), by singron.
// mGBA is MPL-2.0: https://github.com/mgba-emu/mgba
precision mediump float;

varying vec2 texCoord;
uniform sampler2D tex;
uniform vec2 texSize;

void main() {
    // o = offset, the width of a pixel
    vec2 o = 1.0 / texSize;

    // texel arrangement
    // A B C
    // D E F
    // G H I
    vec4 B = cc(texture2D(tex, texCoord + vec2(0.0, o.y)));
    vec4 D = cc(texture2D(tex, texCoord + vec2(-o.x, 0.0)));
    vec4 E = cc(texture2D(tex, texCoord + vec2(0.0, 0.0)));
    vec4 F = cc(texture2D(tex, texCoord + vec2(o.x, 0.0)));
    vec4 H = cc(texture2D(tex, texCoord + vec2(0.0, -o.y)));
    vec2 p = texCoord * texSize;
    // p = the texCoord within a pixel [0...1]
    p = fract(p);
    if (p.x > 0.5) {
        if (p.y > 0.5) {
            // Top Right
            gl_FragColor = (B == F && B != D && F != H) ? F : E;
        } else {
            // Bottom Right
            gl_FragColor = (H == F && D != H && B != F) ? F : E;
        }
    } else {
        if (p.y > 0.5) {
            // Top Left
            gl_FragColor = (D == B && B != F && D != H) ? D : E;
        } else {
            // Bottom Left
            gl_FragColor = (D == H && D != B && H != F) ? D : E;
        }
    }
}
