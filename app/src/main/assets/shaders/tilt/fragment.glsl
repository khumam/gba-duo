// Tilt-shift: blurs along one axis (run it horizontally, then vertically) with a radius that is zero in the
// middle of the picture and grows toward its top and bottom edges.
precision mediump float;

varying vec2 texCoord;
uniform sampler2D tex;
uniform vec2 axis;        // one texel along the blur direction, in texture coordinates
uniform float maxRadius;  // blur reach at the top and bottom edge, in texels
uniform float focus;      // part of the half-height around the middle that stays sharp (0 to 1)

void main() {
    float away = abs(texCoord.y - 0.5) * 2.0;           // 0 in the middle, 1 at the top and bottom
    float radius = maxRadius * smoothstep(focus, 1.0, away);
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    for (int i = -4; i <= 4; i++) {
        float u = float(i) / 4.0;
        float weight = exp(-2.5 * u * u);
        sum += texture2D(tex, texCoord + axis * (radius * u)).rgb * weight;
        weights += weight;
    }
    gl_FragColor = vec4(sum / weights, 1.0);
}
