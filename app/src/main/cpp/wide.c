#include "wide.h"

#include "bus.h"

#define MAX_CELLS 1024
#define GOOD_MATCH 0.85f
#define HIDE_AFTER_BAD 4
#define SHOW_AFTER_GOOD 4
#define SEAM_COLUMNS 8
/* The edge rows hold the game's HUD (hearts, items, counters), which is no part of the world: they count with this much slack. */
#define ROW_SLACK 0.2f
/* The game blends effects over its layers (cloud shadows, fades) that the world does not describe, which only darkens or tints:
 * a pixel counts as predicted when each colour channel is within this much of the world's. */
#define SEAM_TOLERANCE 40

static bool layers_valid(const struct WideWorld* w) {
    if (w->layerCount < 1 || w->layerCount > WIDE_MAX_LAYERS) return false;
    for (int i = 0; i < w->layerCount; i++) {
        const struct WideLayer* l = &w->layers[i];
        if (l->gridW < 1 || l->gridW > MAX_CELLS || l->gridH < 1 || l->gridH > MAX_CELLS ||
            l->stride < l->gridW || l->stride > MAX_CELLS || (l->cellBytes != 0 && l->cellBytes != 2 && l->cellBytes != 4)) {
            return false;
        }
    }
    return true;
}

bool wide_world_valid(const struct WideWorld* w) {
    if (w->layerCount != 0) return layers_valid(w);
    return w->gridW >= 1 && w->gridW <= MAX_CELLS && w->gridH >= 1 && w->gridH <= MAX_CELLS &&
           w->borderW >= 1 && w->borderW <= MAX_CELLS && w->borderH >= 1 && w->borderH <= MAX_CELLS &&
           w->split >= 0;
}

uint32_t wide_color(uint16_t v) {
    uint32_t c = (uint32_t) (v & 0x1F) << 3 | (uint32_t) ((v >> 5) & 0x1F) << 11 | (uint32_t) ((v >> 10) & 0x1F) << 19;
    return c | ((c >> 5) & 0x070707u);
}

static bool close_enough(uint32_t drawn, uint32_t wanted) {
    for (int shift = 0; shift < 24; shift += 8) {
        int diff = (int) ((drawn >> shift) & 0xFF) - (int) ((wanted >> shift) & 0xFF);
        if (diff > SEAM_TOLERANCE || diff < -SEAM_TOLERANCE) return false;
    }
    return true;
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

/* The tile entry of layer [l] at world pixel ([wx], [wy]) and the pixel inside it; false outside the grid. */
static bool layer_entry(const struct WideLayer* l, int32_t wx, int32_t wy, uint16_t* entry, int* tx, int* ty) {
    int32_t cx = floor_div(wx, 16);
    int32_t cy = floor_div(wy, 16);
    if (cx < 0 || cx >= l->gridW || cy < 0 || cy >= l->gridH) return false;
    int px = (int) (wx - cx * 16);
    int py = (int) (wy - cy * 16);
    uint32_t cell = l->grid + ((uint32_t) cy * (uint32_t) l->stride + (uint32_t) cx) * (l->cellBytes == 4 ? 4u : 2u);
    uint32_t id;
    if (l->cellBytes == 4) {
        id = gbaduo_bus_read32(cell);
    } else {
        id = gbaduo_bus_read16(cell);
        if (id >= 0x4000u && l->special != 0) id = gbaduo_bus_read16(l->special + (id - 0x4000u) * 2u);
    }
    id &= l->idMask;
    *entry = gbaduo_bus_read16(l->metatiles + id * 8u + (uint32_t) ((py >> 3) * 2 + (px >> 3)) * 2u);
    *tx = px & 7;
    *ty = py & 7;
    return true;
}

static uint32_t layered_pixel(const struct WideWorld* w, const uint32_t pal[256], int32_t wx, int32_t wy) {
    bool inside = false;
    for (int i = w->layerCount - 1; i >= 0; i--) {
        const struct WideLayer* l = &w->layers[i];
        uint16_t entry;
        int tx, ty;
        if (!layer_entry(l, wx, wy, &entry, &tx, &ty)) continue;
        inside = true;
        int x = (entry & 0x400) ? 7 - tx : tx;
        int y = (entry & 0x800) ? 7 - ty : ty;
        uint8_t b = gbaduo_bus_read8(l->tiles + (uint32_t) (entry & 0x3FF) * 32u + (uint32_t) y * 4u + (uint32_t) (x >> 1));
        int index = (x & 1) ? b >> 4 : b & 0x0F;
        if (index != 0) return pal[(entry >> 12) * 16 + index];
    }
    return inside ? pal[0] : 0;
}

uint32_t wide_pixel(const struct WideWorld* w, int32_t wx, int32_t wy) {
    if (w->layerCount != 0) {
        uint32_t pal[256];
        for (int i = 0; i < 256; i++) pal[i] = wide_color(gbaduo_bus_read16(w->palette + (uint32_t) i * 2u));
        return layered_pixel(w, pal, wx, wy);
    }
    int32_t cx = floor_div(wx, 16);
    int32_t cy = floor_div(wy, 16);
    int px = (int) (wx - cx * 16);
    int py = (int) (wy - cy * 16);

    int id = -1;
    if (cx >= 0 && cx < w->gridW && cy >= 0 && cy < w->gridH) {
        uint16_t cell = gbaduo_bus_read16(w->grid + ((uint32_t) cy * (uint32_t) w->gridW + (uint32_t) cx) * 2u);
        id = cell & w->idMask;
    }
    if (w->finite && (id < 0 || id == w->undefinedId)) return 0; /* outside a finite world there is nothing */
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
/* With [bounded], a finite world is black outside its defined cells; without, the border repeats there (what the game itself draws). */
static void span(const struct WideWorld* w, const uint32_t* pal, int32_t wy, int32_t wx, int n, uint32_t* out, bool bounded) {
    int32_t cx = floor_div(wx, 16);
    int32_t cy = floor_div(wy, 16);
    int px = (int) (wx - cx * 16);
    int py = (int) (wy - cy * 16);

    int id = -1;
    if (cx >= 0 && cx < w->gridW && cy >= 0 && cy < w->gridH) {
        id = gbaduo_bus_read16(w->grid + ((uint32_t) cy * (uint32_t) w->gridW + (uint32_t) cx) * 2u) & w->idMask;
    }
    if (bounded && w->finite && (id < 0 || id == w->undefinedId)) {
        for (int i = 0; i < n; i++) out[i] = 0;
        return;
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

/* [n] (1..8) pixels of a layered world, inside one 8-pixel tile column: per layer, one set of bus reads. */
static void layered_span(const struct WideWorld* w, const uint32_t* pal, int32_t wy, int32_t wx, int n, uint32_t* out) {
    uint32_t rows[WIDE_MAX_LAYERS] = {0};
    uint16_t entries[WIDE_MAX_LAYERS] = {0};
    bool has[WIDE_MAX_LAYERS] = {false};
    bool inside = false;
    int tx0 = 0;
    for (int i = 0; i < w->layerCount; i++) {
        const struct WideLayer* l = &w->layers[i];
        int tx, ty;
        if (!layer_entry(l, wx, wy, &entries[i], &tx, &ty)) continue;
        has[i] = true;
        inside = true;
        tx0 = tx;
        int y = (entries[i] & 0x800) ? 7 - ty : ty;
        rows[i] = gbaduo_bus_read32(l->tiles + (uint32_t) (entries[i] & 0x3FF) * 32u + (uint32_t) y * 4u);
    }
    for (int k = 0; k < n; k++) {
        uint32_t color = inside ? pal[0] : 0;
        for (int i = w->layerCount - 1; i >= 0; i--) {
            if (!has[i]) continue;
            int index = row_index(rows[i], entries[i], tx0 + k);
            if (index != 0) {
                color = pal[(entries[i] >> 12) * 16 + index];
                break;
            }
        }
        out[k] = color;
    }
}

/* [n] pixels of world row [wy] from [wx], any count. */
static void wide_row(const struct WideWorld* w, const uint32_t* pal, int32_t wy, int32_t wx, int n, uint32_t* out, bool bounded) {
    if (w->layerCount != 0) {
        while (n > 0) {
            int inTile = 8 - (int) ((uint32_t) wx & 7u);
            int m = n < inTile ? n : inTile;
            layered_span(w, pal, wy, wx, m, out);
            wx += m;
            out += m;
            n -= m;
        }
        return;
    }
    while (n > 0) {
        int inTile = 8 - (int) ((uint32_t) wx & 7u);
        int m = n < inTile ? n : inTile;
        span(w, pal, wy, wx, m, out, bounded);
        wx += m;
        out += m;
        n -= m;
    }
}

void wide_paint(const struct WideWorld* w, uint32_t* buf, int stride, int left, int right, int top, int bottom) {
    bool valid = wide_world_valid(w);
    uint32_t pal[256];
    if (valid) load_palette(w, pal);
    int total = WIDE_FRAME_W + left + right;
    for (int row = 0; row < top + WIDE_FRAME_H + bottom; row++) {
        uint32_t* line = buf + (size_t) row * (size_t) stride;
        bool inFrame = row >= top && row < top + WIDE_FRAME_H;
        if (!valid) {
            if (inFrame) {
                for (int c = 0; c < left; c++) line[c] = 0;
                for (int c = left + WIDE_FRAME_W; c < total; c++) line[c] = 0;
            } else {
                for (int c = 0; c < total; c++) line[c] = 0;
            }
            continue;
        }
        int32_t wy = w->y + (row - top);
        if (inFrame) {
            wide_row(w, pal, wy, w->x - left, left, line, true);
            wide_row(w, pal, wy, w->x + WIDE_FRAME_W, right, line + left + WIDE_FRAME_W, true);
        } else {
            wide_row(w, pal, wy, w->x - left, total, line, true);
        }
    }
}

void wide_render(const struct WideWorld* w, uint32_t* out, int width, int height, int scale) {
    if (!wide_world_valid(w) || scale < 1) {
        for (size_t i = 0; i < (size_t) width * (size_t) height; i++) out[i] = 0;
        return;
    }
    uint32_t pal[256];
    load_palette(w, pal);
    for (int row = 0; row < height; row++) {
        int32_t wy = w->y + row * scale + scale / 2;
        for (int col = 0; col < width; col++) {
            uint32_t c;
            wide_row(w, pal, wy, w->x + col * scale + scale / 2, 1, &c, true);
            /* wide_color is 0x00BBGGRR; black is 0 too, so a black pixel reads as outside: fine for a map */
            out[(size_t) row * (size_t) width + (size_t) col] =
                c == 0 ? 0u : 0xFF000000u | (c & 0xFF) << 16 | (c & 0xFF00) | (c >> 16 & 0xFF);
        }
    }
}

float wide_seam_match(const struct WideWorld* w, const uint32_t* buf, int stride, int left, int top, bool rows) {
    if (!wide_world_valid(w)) return 0.0f;
    uint32_t pal[256];
    load_palette(w, pal);
    int same = 0;
    int checked = 0;
    uint32_t want[WIDE_FRAME_W];
    for (int row = 0; row < WIDE_FRAME_H; row++) {
        const uint32_t* line = buf + (size_t) (top + row) * (size_t) stride + left;
        int32_t wy = w->y + row;
        wide_row(w, pal, wy, w->x, SEAM_COLUMNS, want, false);
        for (int k = 0; k < SEAM_COLUMNS; k++) {
            if (close_enough(line[k], want[k])) same++;
        }
        wide_row(w, pal, wy, w->x + WIDE_FRAME_W - SEAM_COLUMNS, SEAM_COLUMNS, want, false);
        for (int k = 0; k < SEAM_COLUMNS; k++) {
            if (close_enough(line[WIDE_FRAME_W - SEAM_COLUMNS + k], want[k])) same++;
        }
        checked += 2 * SEAM_COLUMNS;
    }
    float share = (float) same / (float) checked;
    if (rows) {
        same = 0;
        checked = 0;
        /* The edge rows between the edge columns (those corners are counted above). */
        int span = WIDE_FRAME_W - 2 * SEAM_COLUMNS;
        for (int k = 0; k < 2 * SEAM_COLUMNS; k++) {
            int row = k < SEAM_COLUMNS ? k : WIDE_FRAME_H - 2 * SEAM_COLUMNS + k;
            const uint32_t* line = buf + (size_t) (top + row) * (size_t) stride + left + SEAM_COLUMNS;
            wide_row(w, pal, w->y + row, w->x + SEAM_COLUMNS, span, want, false);
            for (int c = 0; c < span; c++) {
                if (close_enough(line[c], want[c])) same++;
            }
            checked += span;
        }
        float rowShare = (float) same / (float) checked + ROW_SLACK;
        if (rowShare < share) share = rowShare;
    }
    return share;
}

/* The area in pixels of the world's map when the view is to stay inside it: a layered world's biggest grid, or the defined
 * cells of a finite single grid (the rest is the game's padding of border tiles, which a finite world does not draw);
 * false for a world whose border repeats forever. */
struct WideBounds {
    int64_t x0, y0, x1, y1;
};

#define MAX_SCANNED_CELLS 65536

static bool world_bounds(const struct WideWorld* w, struct WideBounds* b) {
    if (w->layerCount == 0) {
        if (!w->finite) return false;
        b->x0 = 0;
        b->y0 = 0;
        b->x1 = (int64_t) w->gridW * 16;
        b->y1 = (int64_t) w->gridH * 16;
        if (w->undefinedId < 0 || (int64_t) w->gridW * w->gridH > MAX_SCANNED_CELLS) return true;
        int64_t minX = w->gridW, minY = w->gridH, maxX = -1, maxY = -1;
        for (int cy = 0; cy < w->gridH; cy++) {
            for (int cx = 0; cx < w->gridW; cx++) {
                int id = gbaduo_bus_read16(w->grid + ((uint32_t) cy * (uint32_t) w->gridW + (uint32_t) cx) * 2u) & w->idMask;
                if (id == w->undefinedId) continue;
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
            }
        }
        if (maxX < 0) return true; /* nothing defined: keep the whole grid */
        b->x0 = minX * 16;
        b->y0 = minY * 16;
        b->x1 = (maxX + 1) * 16;
        b->y1 = (maxY + 1) * 16;
        return true;
    }
    b->x0 = 0;
    b->y0 = 0;
    b->x1 = 0;
    b->y1 = 0;
    for (int i = 0; i < w->layerCount; i++) {
        if ((int64_t) w->layers[i].gridW * 16 > b->x1) b->x1 = (int64_t) w->layers[i].gridW * 16;
        if ((int64_t) w->layers[i].gridH * 16 > b->y1) b->y1 = (int64_t) w->layers[i].gridH * 16;
    }
    return true;
}

static int64_t overlap(int64_t ax, int64_t ay, int64_t aw, int64_t ah, int64_t bx, int64_t by, int64_t bw, int64_t bh) {
    int64_t w = (ax + aw < bx + bw ? ax + aw : bx + bw) - (ax > bx ? ax : bx);
    int64_t h = (ay + ah < by + bh ? ay + ah : by + bh) - (ay > by ? ay : by);
    return w > 0 && h > 0 ? w * h : 0;
}

float wide_coverage(const struct WideWorld* w, int left, int top, int width, int height) {
    if (!wide_world_valid(w)) return 0.0f;
    int64_t margin = (int64_t) width * height - (int64_t) WIDE_FRAME_W * WIDE_FRAME_H;
    struct WideBounds m;
    if (margin <= 0 || !world_bounds(w, &m)) return 1.0f;
    int64_t inPicture = overlap(m.x0, m.y0, m.x1 - m.x0, m.y1 - m.y0, (int64_t) w->x - left, (int64_t) w->y - top, width, height);
    int64_t inFrame = overlap(m.x0, m.y0, m.x1 - m.x0, m.y1 - m.y0, w->x, w->y, WIDE_FRAME_W, WIDE_FRAME_H);
    float share = (float) (inPicture - inFrame) / (float) margin;
    return share < 0.0f ? 0.0f : share > 1.0f ? 1.0f : share;
}

static int clamp_int(int64_t v, int64_t lo, int64_t hi) {
    return (int) (v < lo ? lo : v > hi ? hi : v);
}

/* One axis of wide_clamp_frame: the frame's [pos] in the picture, its world position [at], the picture's [size] and the map's [lo]..[hi]. */
static int clamp_axis(int pos, int64_t at, int size, int frame, int64_t lo, int64_t hi) {
    int64_t origin;
    if (hi - lo < size) {
        origin = lo + (hi - lo - size) / 2; /* the map cannot fill the picture: it stays put, the black splits evenly */
    } else {
        origin = at - pos;
        if (origin < lo) origin = lo;
        if (origin > hi - size) origin = hi - size;
    }
    return clamp_int(at - origin, 0, size - frame);
}

void wide_clamp_frame(const struct WideWorld* w, int width, int height, int* left, int* top) {
    struct WideBounds m;
    if (!wide_world_valid(w) || !world_bounds(w, &m)) return;
    *left = clamp_axis(*left, w->x, width, WIDE_FRAME_W, m.x0, m.x1);
    *top = clamp_axis(*top, w->y, height, WIDE_FRAME_H, m.y0, m.y1);
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
