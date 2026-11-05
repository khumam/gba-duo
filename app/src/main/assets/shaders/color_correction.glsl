// Color correction, put after `precision` in every fragment shader (GameView.buildProgram).
// Shaders read the game through cc(texture2D(...)). ccMode: 0 off, 1 GBA, 2 GBC.
// The GBA branch is mGBA's gba-color shader (MPL-2.0, https://github.com/mgba-emu/mgba).
uniform float ccMode;
// Ambient (Quick menu > Ambient colour): the pack's colour grade (game.lua's ambient()) on the plain picture. 0 off, 1 on.
uniform float ccAmbient;
// The pack's grade: [0] saturation, contrast, brightness, sun slope; [1] tint r, g, b, vignette; [2] vignette colour r, g, b.
uniform vec4 ccLook[3];
uniform vec4 ccView; // x, y, width, height of the viewport being drawn (for the shading, in fragment coordinates)

vec4 ccDevice(vec4 c) {
    if (ccMode < 0.5) {
        return c;
    }
    if (ccMode < 1.5) {
        const float darken = 0.5;
        vec3 lin = pow(c.rgb, vec3(2.2 + darken)) * 0.99;
        mat3 m = mat3(0.84, 0.09, 0.15,
                      0.18, 0.67, 0.10,
                      0.00, 0.26, 0.73);
        return vec4(pow(clamp(m * lin, 0.0, 1.0), vec3(1.0 / 2.5 + darken * 0.125)), c.a);
    }
    // Game Boy Color: the LCD bled the channels into each other and was dim.
    mat3 m = mat3(26.0, 0.0, 6.0,
                   4.0, 24.0, 4.0,
                   2.0, 8.0, 22.0) / 32.0;
    return vec4(clamp(m * c.rgb, 0.0, 1.0), c.a);
}

vec4 cc(vec4 c) {
    c = ccDevice(c);
    if (ccAmbient < 0.5) {
        return c;
    }
    float lum = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    vec3 rgb = mix(vec3(lum), c.rgb, ccLook[0].x);
    rgb = (rgb - 0.5) * ccLook[0].y + 0.5;
    rgb *= ccLook[1].rgb * ccLook[0].z;
    // Shading: [sun slope] lights the top left that much more than the bottom right, and the edges sink toward the
    // vignette colour, so the picture has depth and a centre.
    vec2 uv = (gl_FragCoord.xy - ccView.xy) / max(ccView.zw, vec2(1.0));
    rgb *= 1.0 + ccLook[0].w * ((0.5 * (1.0 - uv.x) + 0.5 * uv.y) - 0.5);
    float edge = smoothstep(0.38, 1.0, length((uv - 0.5) * vec2(1.0, 1.1)) * 1.45);
    rgb = mix(rgb, rgb * ccLook[2].rgb, edge * ccLook[1].w);
    return vec4(clamp(rgb, 0.0, 1.0), c.a);
}
