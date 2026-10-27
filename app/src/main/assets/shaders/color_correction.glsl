// Color correction, put after `precision` in every fragment shader (GameView.buildProgram).
// Shaders read the game through cc(texture2D(...)). ccMode: 0 off, 1 GBA, 2 GBC.
// The GBA branch is mGBA's gba-color shader (MPL-2.0, https://github.com/mgba-emu/mgba).
uniform float ccMode;

vec4 cc(vec4 c) {
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
