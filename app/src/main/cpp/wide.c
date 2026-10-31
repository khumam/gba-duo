#include "wide.h"

#include "bus.h"

#define MAX_CELLS 1024
#define GOOD_MATCH 0.85f
#define HIDE_AFTER_BAD 4
#define SHOW_AFTER_GOOD 8
#define SEAM_COLUMNS 8

bool wide_world_valid(const struct WideWorld* w) {
    return w->gridW >= 1 && w->gridW <= MAX_CELLS && w->gridH >= 1 && w->gridH <= MAX_CELLS &&
           w->borderW >= 1 && w->borderW <= MAX_CELLS && w->borderH >= 1 && w->borderH <= MAX_CELLS &&
           w->split >= 0;
}

uint32_t wide_color(uint16_t v) {
    uint32_t c = (uint32_t) (v & 0x1F) << 3 | (uint32_t) ((v >> 5) & 0x1F) << 11 | (uint32_t) ((v >> 10) & 0x1F) << 19;
    return c | ((c >> 5) & 0x070707u);
}

static int32_t floor_div(int32_t a, int32_t b) {
    int32_t q = a / b;
    return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q;
}

static int32_t floor_mod(int32_t a, int32_t b) {
    return a - floor_div(a, b) * b;
}

/* The palette index of pixel ([tx], [ty]) of the tile [entry] names, or 0 for transparent;
 * [bank] gets that entry's palette bank. */
static int tile_index(const struct WideWorld* w, uint16_t entry, int tx, int ty, int* bank) {
    int x = (entry & 0x400) ? 7 - tx : tx;
    int y = (entry & 0x800) ? 7 - ty : ty;
    *bank = entry >> 12;
    uint32_t addr = w->tiles + (uint32_t) (entry & 0x3FF) * 32u + (uint32_t) y * 4u + (uint32_t) (x >> 1);
    uint8_t b = gbaduo_bus_read8(addr);
    return (x & 1) ? b >> 4 : b & 0x0F;
}

uint32_t wide_pixel(const struct WideWorld* w, int32_t wx, int32_t wy) {
    int32_t cx = floor_div(wx, 16);
    int32_t cy = floor_div(wy, 16);
    int px = (int) (wx - cx * 16);
    int py = (int) (wy - cy * 16);

    int id = -1;
    if (cx >= 0 && cx < w->gridW && cy >= 0 && cy < w->gridH) {
        uint16_t cell = gbaduo_bus_read16(w->grid + ((uint32_t) cy * (uint32_t) w->gridW + (uint32_t) cx) * 2u);
        id = cell & w->idMask;
    }
    if (id < 0 || id == w->undefinedId) {
        int32_t bx = floor_mod(cx - w->borderShift, w->borderW);
        int32_t by = floor_mod(cy - w->borderShift, w->borderH);
        uint16_t cell = gbaduo_bus_read16(w->border + ((uint32_t) by * (uint32_t) w->borderW + (uint32_t) bx) * 2u);
        id = cell & w->idMask;
    }
    uint32_t base = id < w->split ? w->metatiles + (uint32_t) id * 16u
                                  : w->metatiles2 + (uint32_t) (id - w->split) * 16u;

    int quadrant = (py >> 3) * 2 + (px >> 3);
    int tx = px & 7;
    int ty = py & 7;
    uint16_t top = gbaduo_bus_read16(base + (uint32_t) (4 + quadrant) * 2u);
    uint16_t bottom = gbaduo_bus_read16(base + (uint32_t) quadrant * 2u);

    int bank;
    int index = tile_index(w, top, tx, ty, &bank);
    if (index == 0) index = tile_index(w, bottom, tx, ty, &bank);
    if (index == 0) bank = 0;
    return wide_color(gbaduo_bus_read16(w->palette + ((uint32_t) bank * 16u + (uint32_t) index) * 2u));
}

/* ---- the fast path: one set of bus reads per 8-pixel span, one palette table per frame ------ */

static void load_palette(const struct WideWorld* w, uint32_t pal[256]) {
    for (int i = 0; i < 256; i++) {
        pal[i] = wide_color(gbaduo_bus_read16(w->palette + (uint32_t) i * 2u));
    }
}

/* One row of the 8x8 tile [entry] names (4 bytes, 8 pixels of 4 bits), flipped as the entry says. */
static uint32_t tile_row(const struct WideWorld* w, uint16_t entry, int ty) {
    int y = (entry & 0x800) ? 7 - ty : ty;
    return gbaduo_bus_read32(w->tiles + (uint32_t) (entry & 0x3FF) * 32u + (uint32_t) y * 4u);
}

static int row_index(uint32_t row, uint16_t entry, int tx) {
    int x = (entry & 0x400) ? 7 - tx : tx;
    return (int) ((row >> (x * 4)) & 0x0F);
}

/* The colours of [n] (1..8) pixels of world row [wy] from [wx], all inside one 8-pixel tile column. */
static void span(const struct WideWorld* w, const uint32_t* pal, int32_t wy, int32_t wx, int n, uint32_t* out) {
    int32_t cx = floor_div(wx, 16);
    int32_t cy = floor_div(wy, 16);
    int px = (int) (wx - cx * 16);
    int py = (int) (wy - cy * 16);

    int id = -1;
    if (cx >= 0 && cx < w->gridW && cy >= 0 && cy < w->gridH) {
        id = gbaduo_bus_read16(w->grid + ((uint32_t) cy * (uint32_t) w->gridW + (uint32_t) cx) * 2u) & w->idMask;
    }
    if (id < 0 || id == w->undefinedId) {
        int32_t bx = floor_mod(cx - w->borderShift, w->borderW);
        int32_t by = floor_mod(cy - w->borderShift, w->borderH);
        id = gbaduo_bus_read16(w->border + ((uint32_t) by * (uint32_t) w->borderW + (uint32_t) bx) * 2u) & w->idMask;
    }
    uint32_t base = id < w->split ? w->metatiles + (uint32_t) id * 16u
                                  : w->metatiles2 + (uint32_t) (id - w->split) * 16u;

    int quadrant = (py >> 3) * 2 + (px >> 3);
    int ty = py & 7;
    int tx0 = px & 7;
    uint16_t top = gbaduo_bus_read16(base + (uint32_t) (4 + quadrant) * 2u);
    uint16_t bottom = gbaduo_bus_read16(base + (uint32_t) quadrant * 2u);
    uint32_t topRow = tile_row(w, top, ty);
    uint32_t bottomRow = tile_row(w, bottom, ty);

    for (int i = 0; i < n; i++) {
        int index = row_index(topRow, top, tx0 + i);
        int bank = top >> 12;
        if (index == 0) {
            index = row_index(bottomRow, bottom, tx0 + i);
            bank = bottom >> 12;
        }
        if (index == 0) bank = 0;
        out[i] = pal[bank * 16 + index];
    }
}

/* [n] pixels of world row [wy] from [wx], any count. */
static void wide_row(const struct WideWorld* w, const uint32_t* pal, int32_t wy, int32_t wx, int n, uint32_t* out) {
    while (n > 0) {
        int inTile = 8 - (int) ((uint32_t) wx & 7u);
        int m = n < inTile ? n : inTile;
        span(w, pal, wy, wx, m, out);
        wx += m;
        out += m;
        n -= m;
    }
}

void wide_paint(const struct WideWorld* w, uint32_t* buf, int stride, int left, int right) {
    bool valid = wide_world_valid(w);
    uint32_t pal[256];
    if (valid) load_palette(w, pal);
    for (int row = 0; row < WIDE_FRAME_H; row++) {
        uint32_t* line = buf + (size_t) row * (size_t) stride;
        if (!valid) {
            for (int c = 0; c < left; c++) line[c] = 0;
            for (int c = left + WIDE_FRAME_W; c < left + WIDE_FRAME_W + right; c++) line[c] = 0;
            continue;
        }
        int32_t wy = w->y + row;
        wide_row(w, pal, wy, w->x - left, left, line);
        wide_row(w, pal, wy, w->x + WIDE_FRAME_W, right, line + left + WIDE_FRAME_W);
    }
}

float wide_seam_match(const struct WideWorld* w, const uint32_t* buf, int stride, int left) {
    if (!wide_world_valid(w)) return 0.0f;
    uint32_t pal[256];
    load_palette(w, pal);
    int same = 0;
    uint32_t want[SEAM_COLUMNS];
    for (int row = 0; row < WIDE_FRAME_H; row++) {
        const uint32_t* line = buf + (size_t) row * (size_t) stride + left;
        int32_t wy = w->y + row;
        wide_row(w, pal, wy, w->x, SEAM_COLUMNS, want);
        for (int k = 0; k < SEAM_COLUMNS; k++) {
            if ((line[k] & 0x00FFFFFFu) == (want[k] & 0x00FFFFFFu)) same++;
        }
        wide_row(w, pal, wy, w->x + WIDE_FRAME_W - SEAM_COLUMNS, SEAM_COLUMNS, want);
        for (int k = 0; k < SEAM_COLUMNS; k++) {
            if ((line[WIDE_FRAME_W - SEAM_COLUMNS + k] & 0x00FFFFFFu) == (want[k] & 0x00FFFFFFu)) same++;
        }
    }
    return (float) same / (float) (WIDE_FRAME_H * 2 * SEAM_COLUMNS);
}

bool wide_gate_step(struct WideGate* g, bool haveWorld, float match) {
    if (!haveWorld) {
        g->bad = 0;
        g->good = 0;
        g->shown = false;
        return false;
    }
    if (match >= GOOD_MATCH) {
        g->bad = 0;
        if (!g->shown && ++g->good >= SHOW_AFTER_GOOD) {
            g->shown = true;
            g->good = 0;
        }
    } else {
        g->good = 0;
        if (g->shown && ++g->bad >= HIDE_AFTER_BAD) {
            g->shown = false;
            g->bad = 0;
        }
    }
    return g->shown;
}
