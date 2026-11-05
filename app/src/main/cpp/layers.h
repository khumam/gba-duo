// The layer probe (GBA only): which of the game's own layers drew each pixel of the frame, as a depth map, for
// lighting effects that work in every game. mGBA's software renderer knows the layers while it composes a scanline
// but keeps only colours in the frame, so the probe runs five one-layer copies of it (the sprites, BG0..BG3) next to
// the real one, fed every register, palette, VRAM and OAM write as it happens (mid-frame effects line up), and at
// the end of a frame names the layer whose pixel the real frame shows.

#ifndef GBADUO_LAYERS_H
#define GBADUO_LAYERS_H

#include <stdbool.h>
#include <stdint.h>

struct mCore;

#define LAYERS_W 240
#define LAYERS_H 160

/**
 * Depth of a pixel, 0..255 (bigger is nearer): 0 the backdrop, the backgrounds close together from 97 to 160 by priority
 * (3 farthest) and index (BG3 behind BG0 at the same priority), the sprites from 225 to 255, well in front of them:
 * a game's floor is often split over two backgrounds, and only what stands on it should throw a shadow.
 */
uint8_t layers_depth_of(bool sprite, int priority, int bg);

/** Turns the probe on or off for [core] (a GBA core; others are ignored). Between frames, on the emulation thread. */
void gbaduo_layers_set(struct mCore* core, bool on);

/** The probe is running. */
bool gbaduo_layers_on(void);

/** The last frame's depth map, LAYERS_W x LAYERS_H bytes row by row, or NULL while off. The frame itself carries the
 * same depth in each pixel's top byte (its alpha) while the probe runs. */
const uint8_t* gbaduo_layers_depth(void);

/**
 * How far the game's floor (the background that shows most) has scrolled since the probe came on, in game pixels,
 * followed across the 512 px wrap: a world position for effects that should stay put on the ground.
 */
void gbaduo_layers_world(int32_t* x, int32_t* y);

/** Counts finished frames since the probe came on (0 = nothing yet). */
uint32_t gbaduo_layers_frames(void);

#endif
