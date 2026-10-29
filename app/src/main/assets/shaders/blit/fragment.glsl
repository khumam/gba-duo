// Shrinks the prescaled game onto the screen by averaging exactly the area each screen pixel covers.
// Linear filtering would take two neighbours instead, so a game-pixel edge would be sharp at some
// scroll positions and soft at others, and the picture would wiggle as it moves.
precision mediump float;
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#endif

varying vec2 texCoord;
uniform sampler2D tex;    // the prescaled game, sampled without filtering
uniform vec2 srcSize;     // its size in texels
uniform vec2 dstSize;     // the viewport size in screen pixels

void main() {
    vec2 ratio = srcSize / dstSize;                 // texels under one screen pixel (1 to 2)
    vec2 lo = texCoord * srcSize - ratio * 0.5;     // the screen pixel's footprint, in texels
    vec2 hi = lo + ratio;
    vec2 first = floor(lo);
    vec3 sum = vec3(0.0);
    for (int j = 0; j < 3; j++) {
        float y = first.y + float(j);
        float weightY = max(0.0, min(hi.y, y + 1.0) - max(lo.y, y));
        for (int i = 0; i < 3; i++) {
            float x = first.x + float(i);
            float weightX = max(0.0, min(hi.x, x + 1.0) - max(lo.x, x));
            sum += texture2D(tex, (vec2(x, y) + 0.5) / srcSize).rgb * (weightX * weightY);
        }
    }
    gl_FragColor = vec4(sum / (ratio.x * ratio.y), 1.0);
}
