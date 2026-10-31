// Wide view: the margins beside the GBA's 240x160 frame, painted from a description of the
// game's world that game.lua's wide() returns (see script.h), and the check that keeps
// them honest. No Android, no mGBA: compiled into the app and into the desktop harness.

#ifndef GBADUO_WIDE_H
#define GBADUO_WIDE_H

#include <stdbool.h>
#include <stdint.h>

#define WIDE_MAX_W 400
#define WIDE_FRAME_W 240
#define WIDE_FRAME_H 160

/**
 * A tile-and-metatile world (the Gen 3 Pokémon overworld). Every address is on the GBA bus.
 * A grid cell is one 16x16 px metatile; a metatile is 8 u16 tile entries: bottom layer
 * TL, TR, BL, BR, then top layer TL, TR, BL, BR (tile entry bits: 0-9 tile, 10 h-flip,
 * 11 v-flip, 12-15 palette bank).
 */
struct WideWorld {
    int32_t x, y;               /* world pixel of the GBA screen's top-left */
    uint32_t grid;              /* u16 cells, row-major, gridW x gridH */
    int gridW, gridH;
    uint16_t idMask;            /* metatile id = cell & idMask */
    uint32_t border;            /* u16 cells repeated outside the grid */
    int borderW, borderH;
    int borderShift;            /* the block index of cell c is (c - borderShift) mod size (the game's own origin) */
    int undefinedId;            /* a grid cell with this metatile id is outside the map: use the border (-1: none) */
    uint32_t metatiles;         /* ids below split: metatiles + id * 16 */
    uint32_t metatiles2;        /* the rest: metatiles2 + (id - split) * 16 */
    int split;
    uint32_t tiles;             /* 4bpp tile data, 32 bytes a tile */
    uint32_t palette;           /* 16 banks x 16 BGR555 */
};

/** True when the world's sizes are in range (1..1024 cells each way, split >= 0). */
bool wide_world_valid(const struct WideWorld* w);

/** BGR555 to the core's 32-bit colour, as mGBA's mColorFrom555 (alpha byte 0). */
uint32_t wide_color(uint16_t bgr555);

/** The colour at world pixel ([wx], [wy]): top layer, else bottom layer, else the backdrop. */
uint32_t wide_pixel(const struct WideWorld* w, int32_t wx, int32_t wy);

/**
 * Fills the [left] columns before the frame and the [right] after it, rows 0..159, of a
 * buffer whose GBA frame starts at column [left]. An invalid world paints black.
 */
void wide_paint(const struct WideWorld* w, uint32_t* buf, int stride, int left, int right);

/**
 * The share (0..1) of the 16 edge columns of the frame (GBA x 0..7 and 232..239) that the
 * world predicts exactly. An invalid world matches nothing.
 */
float wide_seam_match(const struct WideWorld* w, const uint32_t* buf, int stride, int left);

/** Whether the margins show: hides after 4 bad frames in a row, shows after 8 good ones. */
struct WideGate {
    int bad, good;
    bool shown;
};

/** One frame's step; returns whether the margins show this frame. Starts hidden. */
bool wide_gate_step(struct WideGate* g, bool haveWorld, float match);

#endif
