// Ambient, second part: light, as the pack's ambient() says (every strength and colour comes from [look]). With the layer probe on (GBA), the game texture's alpha says which of the game's own
// layers drew each pixel (0 the backdrop, the backgrounds close together around 0.4-0.6, the sprites from 0.88), so the
// light knows what stands in front of what: sprites throw soft shadows onto the ground (the sun is at the top left), the
// edges that face the sun catch a warm rim, creases at their feet darken, bright pixels bloom and the backdrop sinks into
// a cool haze. Small steps between backgrounds (one floor split over two of them) cast nothing. Clouds drift over it all,
// pinned to the floor's scroll ([world]) so they stay on the ground while the camera moves. Without depth
// (GB games, the probe off) a dark outline throws a shadow onto the lighter picture next to it.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;     // the clouds' world coordinates outgrow mediump
#else
precision mediump float;
#endif

varying vec2 texCoord;    // the picture's (+y up)
uniform sampler2D tex;    // the picture, drawn at screen size
uniform sampler2D depthTex; // the game's own frame: its alpha is the depth
uniform vec2 pixel;       // one game pixel, in texture coordinates
uniform float useDepth;   // 1 when depthTex's alpha is a depth map
uniform vec2 world;       // how far the floor has scrolled, game pixels
uniform float time;       // seconds
// The pack's light: [0] shadow, shadow length (game px), rim, crease; [1] shadow colour r, g, b, bloom;
// [2] rim colour r, g, b, haze; [3] clouds, cloud size (game px), cloud speed.
uniform vec4 look[4];

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// The depth at [uv] (picture coordinates): the game texture's rows run top to bottom.
float depthAt(vec2 uv) {
    return texture2D(depthTex, vec2(uv.x, 1.0 - uv.y)).a;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// How much cloud is over game pixel [p] of the world: soft-edged patches [look[3].y] game pixels across, drifting.
float cloud(vec2 p) {
    vec2 q = p / look[3].y + vec2(time * 0.035, time * 0.015) * look[3].z;
    float n = noise(q) * 0.55 + noise(q * 2.1 + 3.7) * 0.30 + noise(q * 4.3 - 1.3) * 0.15;
    return smoothstep(0.50, 0.72, n);
}

vec3 lumaShadows(vec3 c) {
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
    return c * mix(vec3(1.0), look[1].rgb, look[0].x * shade * lit); // in the pack's shadow colour
}

void main() {
    vec3 c = texture2D(tex, texCoord).rgb;
    if (useDepth < 0.5) {
        gl_FragColor = vec4(lumaShadows(c), 1.0);
        return;
    }
    vec2 sun = vec2(-1.0, 1.0);            // toward the sun: up and to the left
    vec2 side = vec2(1.0, 1.0) * 0.5;      // across the shadow, to soften its edge
    float d = depthAt(texCoord);

    // Shadows: something nearer between this pixel and the sun, up to [shadow length] game pixels away, fading with it.
    float shade = 0.0;
    float reachMax = look[0].y;
    for (int i = 1; i <= 16; i++) {
        float reach = float(i);
        if (reach > reachMax) break;
        vec2 at = texCoord + sun * pixel * reach;
        float lift = max(depthAt(at) - d, max(depthAt(at + side * pixel) - d, depthAt(at - side * pixel) - d));
        float occluder = smoothstep(0.12, 0.30, lift);
        shade = max(shade, occluder * (1.0 - reach / (reachMax + 1.0)));
    }

    // Creases: nearer layers all around darken the pixel a little (contact shadow).
    float crease = 0.0;
    crease += smoothstep(0.12, 0.30, depthAt(texCoord + vec2(pixel.x, 0.0)) - d);
    crease += smoothstep(0.12, 0.30, depthAt(texCoord - vec2(pixel.x, 0.0)) - d);
    crease += smoothstep(0.12, 0.30, depthAt(texCoord + vec2(0.0, pixel.y)) - d);
    crease += smoothstep(0.12, 0.30, depthAt(texCoord - vec2(0.0, pixel.y)) - d);
    crease *= 0.25;

    // Rim: this pixel is nearer than the one toward the sun, so its edge faces the light.
    float rim = smoothstep(0.10, 0.25, d - depthAt(texCoord + sun * pixel))
              * (1.0 - smoothstep(0.10, 0.25, d - depthAt(texCoord + sun * pixel * 2.0)) * 0.5);

    // Bloom: bright pixels around spill a little light.
    vec3 glow = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        vec3 s = texture2D(tex, texCoord + vec2(cos(a), sin(a)) * pixel * 2.5).rgb;
        glow += s * smoothstep(0.70, 0.95, luma(s));
    }
    glow /= 8.0;

    float lum = luma(c);
    c *= mix(vec3(1.0), look[1].rgb, look[0].x * shade);      // shadows, in the pack's shadow colour
    c *= 1.0 - 0.3 * look[0].w * crease;
    c += look[2].rgb * rim * look[0].z * (0.3 + 0.5 * lum);   // sunlit edges
    c += glow * look[1].w;
    float far = 1.0 - smoothstep(0.0, 0.2, d);                 // the backdrop
    c = mix(c, vec3(0.62, 0.70, 0.82) * (0.6 + 0.6 * lum), look[2].w * far);

    // Clouds: their shadow dims everything under it, in the shadow colour.
    if (look[3].x > 0.0) {
        vec2 game = vec2(texCoord.x, 1.0 - texCoord.y) / pixel;   // this pixel, in game pixels of the picture
        c *= mix(vec3(1.0), look[1].rgb, look[3].x * cloud(game + world));
    }
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
