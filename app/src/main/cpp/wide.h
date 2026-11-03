// Wide view: the margins beside the GBA's 240x160 frame, painted from a description of the
// game's world that game.lua's wide() returns (see script.h), and the check that keeps
// them honest. No Android, no mGBA: compiled into the app and into the desktop harness.

#ifndef GBADUO_WIDE_H
#define GBADUO_WIDE_H

#include <stdbool.h>
#include <stdint.h>

#define WIDE_MAX_W 480
#define WIDE_MAX_H 320
#define WIDE_FRAME_W 240
#define WIDE_FRAME_H 160
#define WIDE_MAX_LAYERS 3

/**
 * One background layer of a layered world (e.g. The Minish Cap, Golden Sun): its own grid, 2x2-tile metatiles
 * (4 u16 entries: TL, TR, BL, BR, same bits as WideWorld) and tile data.
 */
struct WideLayer {
    uint32_t grid;              /* cells, row-major, [stride] cells a row, gridW x gridH of them in use */
    int stride, gridW, gridH;
    int cellBytes;              /* 2 (a u16 cell, the default) or 4 (a u32 cell: id = cell & idMask) */
    uint16_t idMask;            /* metatile id = cell & idMask */
    uint32_t special;           /* u16 table for cells >= 0x4000 (id = table[cell - 0x4000]); 0: none; u16 cells only */
    uint32_t metatiles;         /* metatiles + id * 8 */
    uint32_t tiles;             /* 4bpp tile data, 32 bytes a tile */
};

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
    int finite;                 /* 1: the defined cells (not undefinedId) are the whole world, as a layered world's grids are: the view stops at their edge (see wide_clamp_frame) and the margins are black outside them, not the repeating border */
    int overscan;               /* with finite 0: > 0 limits the repeating border to this many px beyond the defined cells; the wide picture stops scrolling there (see wide_clamp_frame) and what is farther out is black. 0: unlimited */
    uint32_t metatiles;         /* ids below split: metatiles + id * 16 */
    uint32_t metatiles2;        /* the rest: metatiles2 + (id - split) * 16 */
    int split;
    uint32_t tiles;             /* 4bpp tile data, 32 bytes a tile */
    uint32_t palette;           /* 16 banks x 16 BGR555 */
    int layerCount;             /* 0: the single grid above; 1..3: [layers] instead, bottom first (the rest above is ignored) */
    struct WideLayer layers[WIDE_MAX_LAYERS]; /* the top layer wins where its pixel is not colour 0; outside the grids it is black */
};

/** True when the world's sizes are in range (1..1024 cells each way, split >= 0). */
bool wide_world_valid(const struct WideWorld* w);

/** BGR555 to the core's 32-bit colour, as mGBA's mColorFrom555 (alpha byte 0). */
uint32_t wide_color(uint16_t bgr555);

/** The colour at world pixel ([wx], [wy]): top layer, else bottom layer, else the backdrop. */
uint32_t wide_pixel(const struct WideWorld* w, int32_t wx, int32_t wy);

/**
 * Fills everything around the frame in a buffer whose GBA frame starts at ([left], [top]): the [left]
 * columns before it, the [right] after it, the [top] rows above and the [bottom] below. An invalid world
 * paints black.
 */
void wide_paint(const struct WideWorld* w, uint32_t* buf, int stride, int left, int right, int top, int bottom);

/**
 * A picture of the world: [width] x [height] ARGB8888 pixels (Android's order), each the world pixel at the
 * centre of a [scale] x [scale] block, the first block's top-left at the world's (x, y). Outside the grids
 * the pixel is 0 (transparent). An invalid world gives all 0.
 */
void wide_render(const struct WideWorld* w, uint32_t* out, int width, int height, int scale);

/**
 * The share (0..1) of the frame's edge pixels that the world predicts exactly: the 16 edge columns (GBA x
 * 0..7 and 232..239) and, with [rows], the 16 edge rows between them (y 0..7 and 152..159); the result is the
 * lower of the two shares, the rows' with 0.2 of slack because the HUD sits there. The frame starts at
 * ([left], [top]) in [buf]. An invalid world matches nothing.
 */
float wide_seam_match(const struct WideWorld* w, const uint32_t* buf, int stride, int left, int top, bool rows);

/**
 * The share (0..1) of the margin area of a [width] x [height] picture (everything but the 240x160 frame in its
 * middle at ([left], [top])) that lies inside the world's map; 1 for a world whose border repeats forever, 0
 * for an invalid one. Low means the margins would be mostly black (a small room).
 */
float wide_coverage(const struct WideWorld* w, int left, int top, int width, int height);

/**
 * Slides the frame inside a [width] x [height] picture so the picture never reaches past the map. [left] and [top] come
 * in as where the frame sits when centred and go out as where it sits instead: near a map edge the picture stops scrolling
 * and the frame moves toward that edge (the game's own camera stops there too), so the margins there show the map's
 * inside, not black. An axis the map is too small to fill keeps the picture still on the map, the black split evenly; a world whose border repeats forever (not `finite`) is left as it is.
 */
void wide_clamp_frame(const struct WideWorld* w, int width, int height, int* left, int* top);

/** Whether the margins show: hides after 4 bad frames in a row, shows after 4 good ones. */
struct WideGate {
    int bad, good;
    bool shown;
};

/** One frame's step; returns whether the margins show this frame. Starts hidden. */
bool wide_gate_step(struct WideGate* g, bool haveWorld, float match);

#endif
