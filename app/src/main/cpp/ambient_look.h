// The look a pack gives a game while Ambient is on (game.lua's ambient()): the colour grade and the light. The app has
// the effects, the pack the numbers; every field is optional and its default changes nothing.

#ifndef GBADUO_AMBIENT_LOOK_H
#define GBADUO_AMBIENT_LOOK_H

/** Indices into the look, a flat array of floats (the order the app's shaders read it in). */
enum AmbientField {
    AMB_SATURATION,             /* 1 = the game's own */
    AMB_CONTRAST,               /* 1 = the game's own */
    AMB_BRIGHTNESS,             /* 1 = the game's own */
    AMB_TINT_R, AMB_TINT_G, AMB_TINT_B,                 /* a multiplier per channel, 1 = none */
    AMB_VIGNETTE,               /* 0..1: how far the edges sink toward vignetteColor */
    AMB_VIGNETTE_R, AMB_VIGNETTE_G, AMB_VIGNETTE_B,     /* what the edges are multiplied by at full strength */
    AMB_SUN_SLOPE,              /* -1..1: the top left this much brighter than the bottom right, 0 = even */
    AMB_SHADOW,                 /* 0..1: shadows of sprites (needs the layer probe) */
    AMB_SHADOW_LENGTH,          /* game pixels, 1..16 */
    AMB_SHADOW_R, AMB_SHADOW_G, AMB_SHADOW_B,           /* what a full shadow (and a cloud's) multiplies the picture by */
    AMB_RIM,                    /* 0..1: light on the edges that face the sun */
    AMB_RIM_R, AMB_RIM_G, AMB_RIM_B,                    /* the light added on those edges */
    AMB_CREASE,                 /* 0..1: darkening where things meet the ground */
    AMB_BLOOM,                  /* 0..1: bright pixels glow */
    AMB_HAZE,                   /* 0..1: the backdrop sinks into a cool haze */
    AMB_CLOUDS,                 /* 0..1: cloud shadows drifting over the ground */
    AMB_CLOUD_SIZE,             /* game pixels across a cloud, 16..1024 */
    AMB_CLOUD_SPEED,            /* 0..10, 1 = a slow drift */
    AMB_FIELDS
};

#endif
