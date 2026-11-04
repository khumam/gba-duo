// Ambient colour, second part: soft drop shadows. The sun is at the top left, so a dark outline (a tree's edge, a wall's
// base, a sprite's feet) throws a shadow a few game pixels toward the bottom right, onto whatever lighter picture is there.
precision mediump float;

varying vec2 texCoord;
uniform sampler2D tex;
uniform vec2 pixel;       // one game pixel, in texture coordinates

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec3 c = texture2D(tex, texCoord).rgb;
    float lum = luma(c);
    float shade = 0.0;
    for (int i = 1; i <= 4; i++) {
        float reach = float(i);
        float s = luma(texture2D(tex, texCoord + vec2(-1.0, 1.0) * pixel * reach).rgb);
        // an occluder is a dark pixel that is clearly darker than the one in its shadow
        float occluder = (1.0 - smoothstep(0.10, 0.30, s)) * smoothstep(0.55, 0.30, s / max(lum, 0.06));
        shade = max(shade, occluder * (1.0 - 0.2 * (reach - 1.0)));
    }
    float lit = smoothstep(0.14, 0.34, lum); // the outline itself stays as it is
    c *= 1.0 - 0.40 * shade * lit;
    c = mix(c, c * vec3(0.78, 0.80, 1.0), 0.5 * shade * lit); // shadows lean cool
    gl_FragColor = vec4(c, 1.0);
}
