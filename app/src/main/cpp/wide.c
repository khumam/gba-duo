#include "wide.h"

#include "bus.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#define MAX_CELLS 1024
/* Showing needs a good match, hiding a clearly bad one (hysteresis): a crowded edge (NPCs and the player standing on the
 * seam columns are no part of the world) sits around 0.85 without the world being wrong. */
#define GOOD_MATCH 0.80f
#define BAD_MATCH 0.55f
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
        if (l->tileMode == 1) {
            if (l->chunkW < 1 || l->chunkW > 64 || l->chunkH < 1 || l->chunkH > 64 || (l->chunkCodec != 0 && l->chunkCodec != 1)) return false;
            continue;
        }
        if (l->gridW < 1 || l->gridW > MAX_CELLS || l->gridH < 1 || l->gridH > MAX_CELLS ||
            l->stride < l->gridW || l->stride > MAX_CELLS || (l->cellBytes != 0 && l->cellBytes != 2 && l->cellBytes != 4) ||
            l->idShift < 0 || l->idShift > 4) {
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

/* ---- chunked 8bpp tilemap layers ---------------------------------------------------------------------------------- */

#define CHUNK_BYTES 2048 /* 32 x 32 u16 tile entries */
#define CHUNK_SLOTS 128
#define CHUNK_PROBE 8

struct ChunkCache {
    uint32_t key[CHUNK_SLOTS];   /* the chunk's bus address, 0: free */
    uint8_t ok[CHUNK_SLOTS];
    uint8_t data[CHUNK_SLOTS][CHUNK_BYTES];
    unsigned evict;
};

static _Thread_local struct ChunkCache* t_chunks;
static atomic_uint g_chunkGeneration;       /* bumped by wide_forget; a thread's cache older than that is emptied before use */
static _Thread_local unsigned t_chunkGeneration;

void wide_forget(void) {
    atomic_fetch_add(&g_chunkGeneration, 1u);
}

/* An MSB-first bit reader over u32 words read from the bus (what the game's own unpacker does with r6/sl). */
struct PackBits {
    uint32_t src;
    uint32_t word;
    uint32_t buf;
    int left;
    bool bad;
};

static uint32_t pack_bit(struct PackBits* b) {
    if (b->left == 0) {
        if (b->word >= 4096) { /* a chunk never packs to more than 16 KB: a runaway stream */
            b->bad = true;
            return 0;
        }
        b->buf = gbaduo_bus_read32(b->src + b->word * 4u);
        b->word++;
        b->left = 32;
    }
    uint32_t bit = b->buf >> 31;
    b->buf <<= 1;
    b->left--;
    return bit;
}

static uint32_t pack_get(struct PackBits* b, int n) {
    uint32_t v = 0;
    for (int i = 0; i < n && !b->bad; i++) v = (v << 1) | pack_bit(b);
    return v;
}

/* An Elias-gamma style number: 1, then (value bit, continue bit) pairs. */
static uint32_t pack_gamma(struct PackBits* b) {
    uint32_t v = 1;
    for (int i = 0; i < 24 && !b->bad; i++) {
        v = v * 2 + pack_bit(b);
        if (pack_bit(b) == 0) return v;
    }
    b->bad = true;
    return v;
}

/* Webfoot's packer (The Legacy of Goku II's routine at IWRAM 0x03000000, copied there from ROM 0x087FD6BC): literals of r3
 * bits plus a base, matches by gamma-coded length and distance (with a repeat-last-distance code), short matches, a
 * 256-byte raw block, and the end code. Verified bit for bit against the chunks the game itself keeps. */
static bool pack_unpack(uint32_t src, uint8_t* out) {
    struct PackBits b = { src, 0, 0, 0, false };
    int n = 0, litBits = 8, shift = 8;
    uint32_t litBase = 0, dist = 1;
#define COPY(count) do { uint32_t cnt_ = (count); if (dist < 1 || dist > (uint32_t) n) return false; \
        while (cnt_-- && n < CHUNK_BYTES) { out[n] = out[n - (int) dist]; n++; } } while (0)
    while (n < CHUNK_BYTES && !b.bad) {
        if (pack_bit(&b)) {
            out[n++] = (uint8_t) (pack_get(&b, litBits) + litBase);
            continue;
        }
        if (pack_bit(&b)) {
            uint32_t len = pack_gamma(&b);
            if (len == 2) {
                len = pack_gamma(&b);
                COPY(len);
                continue;
            }
            len -= 3;
            dist = pack_get(&b, shift) + (shift < 32 ? len << shift : 0u);
            len = pack_gamma(&b);
            if (dist >= 0x10000u) len += 3;
            else if (dist >= 0x37FFu) len += 2;
            else if (dist >= 0x27Fu) len += 1;
            else if (dist <= 0x7Fu) len += 4;
            COPY(len);
            continue;
        }
        if (pack_bit(&b) == 0) {
            uint32_t v = pack_get(&b, 7);
            if (v == 0) {
                v = pack_get(&b, 2);
                if (v == 0) break;
                shift = (int) pack_get(&b, (int) v + 3);
                if (shift > 32) return false;
                continue;
            }
            dist = v;
            COPY(pack_get(&b, 2) + 2);
            continue;
        }
        int v = (int) pack_get(&b, 4) - 1;
        if (v == 0) {
            out[n++] = 0;
            continue;
        }
        if (v > 0) {
            if (v > n) return false;
            out[n] = out[n - v];
            n++;
            continue;
        }
        if (pack_bit(&b) == 0) {
            litBase = 0;
            if (pack_bit(&b)) {
                litBits = 8;
            } else {
                litBits = 7;
                litBase = pack_get(&b, 8);
            }
            continue;
        }
        do {
            for (int i = 0; i < 256 && n < CHUNK_BYTES; i++) out[n++] = (uint8_t) pack_get(&b, 8);
        } while (pack_bit(&b) && n < CHUNK_BYTES && !b.bad);
    }
#undef COPY
    return !b.bad && n == CHUNK_BYTES;
}

/* The 2048 bytes of the Webfoot chunk at [ptr] (u32 kind, u32 size, data: kind 0 raw, otherwise packed), or NULL. The packed
 * chunk lives in ROM and never changes, so the decoded copy is kept (per thread, 128 chunks). */
static const uint8_t* chunk_unpacked(uint32_t ptr) {
    if (!t_chunks) {
        t_chunks = (struct ChunkCache*) calloc(1, sizeof(*t_chunks));
        if (!t_chunks) return NULL;
    }
    struct ChunkCache* c = t_chunks;
    unsigned generation = atomic_load(&g_chunkGeneration);
    if (t_chunkGeneration != generation) {
        memset(c->key, 0, sizeof(c->key));
        t_chunkGeneration = generation;
    }
    unsigned h = (unsigned) (((ptr >> 2) * 2654435761u) >> 25) % CHUNK_SLOTS;
    for (int p = 0; p < CHUNK_PROBE; p++) {
        unsigned s = (h + (unsigned) p) % CHUNK_SLOTS;
        if (c->key[s] == ptr) return c->ok[s] ? c->data[s] : NULL;
        if (c->key[s] == 0) {
            h = s;
            goto fill;
        }
    }
    h = (h + (c->evict++ % CHUNK_PROBE)) % CHUNK_SLOTS;
fill:
    c->key[h] = ptr;
    uint32_t kind = gbaduo_bus_read32(ptr), size = gbaduo_bus_read32(ptr + 4);
    bool ok = false;
    if (size == CHUNK_BYTES) {
        if (kind == 0) {
            for (uint32_t i = 0; i < CHUNK_BYTES; i += 4) {
                uint32_t v = gbaduo_bus_read32(ptr + 8 + i);
                memcpy(c->data[h] + i, &v, 4);
            }
            ok = true;
        } else if (kind == 1 || kind == 2) {
            ok = pack_unpack(ptr + 8, c->data[h]);
        }
    }
    c->ok[h] = ok;
    return ok ? c->data[h] : NULL;
}

/* The tile entry of chunk layer [l] at world pixel ([wx], [wy]) and the pixel inside it; false where there is no chunk. */
static bool chunk_entry(const struct WideLayer* l, int32_t wx, int32_t wy, uint16_t* entry, int* tx, int* ty) {
    int32_t lx = wx + l->dx, ly = wy + l->dy;
    int32_t cx = floor_div(lx, 256), cy = floor_div(ly, 256);
    if (cx < 0 || cx >= l->chunkW || cy < 0 || cy >= l->chunkH) return false;
    uint32_t ptr = gbaduo_bus_read32(l->chunks + ((uint32_t) cy * (uint32_t) l->chunkW + (uint32_t) cx) * 4u);
    if (ptr == 0) return false;
    int px = (int) (lx - cx * 256), py = (int) (ly - cy * 256);
    uint32_t off = (uint32_t) (((py >> 3) * 32 + (px >> 3)) * 2);
    if (l->chunkCodec == 1) {
        const uint8_t* d = chunk_unpacked(ptr);
        if (!d) return false;
        *entry = (uint16_t) (d[off] | d[off + 1] << 8);
    } else {
        *entry = gbaduo_bus_read16(ptr + off);
    }
    *tx = px & 7;
    *ty = py & 7;
    return true;
}

/* The palette index of one pixel of an 8bpp tile entry. */
static int tile8_index(const struct WideLayer* l, uint16_t entry, int tx, int ty) {
    int x = (entry & 0x400) ? 7 - tx : tx;
    int y = (entry & 0x800) ? 7 - ty : ty;
    return gbaduo_bus_read8(l->tiles + (uint32_t) (entry & 0x3FF) * 64u + (uint32_t) y * 8u + (uint32_t) x);
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
    id = (id & l->idMask) >> l->idShift;
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
        if (l->tileMode == 1) {
            if (!chunk_entry(l, wx, wy, &entry, &tx, &ty)) continue;
            inside = true;
            int index8 = tile8_index(l, entry, tx, ty);
            if (index8 != 0) return pal[index8];
            continue;
        }
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
    int nl = w->metatileLayers == 3 ? 3 : 2;
    uint32_t stride = (uint32_t) nl * 8u;
    uint32_t base = id < w->split ? w->metatiles + (uint32_t) id * stride
                                  : w->metatiles2 + (uint32_t) (id - w->split) * stride;

    int quadrant = (py >> 3) * 2 + (px >> 3);
    int tx = px & 7;
    int ty = py & 7;

    int bank = 0;
    int index = 0;
    for (int layer = nl - 1; layer >= 0 && index == 0; layer--) {
        uint16_t entry = gbaduo_bus_read16(base + (uint32_t) (layer * 4 + quadrant) * 2u);
        index = tile_index(w, entry, tx, ty, &bank);
    }
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
    int nl = w->metatileLayers == 3 ? 3 : 2;
    uint32_t stride = (uint32_t) nl * 8u;
    uint32_t base = id < w->split ? w->metatiles + (uint32_t) id * stride
                                  : w->metatiles2 + (uint32_t) (id - w->split) * stride;

    int quadrant = (py >> 3) * 2 + (px >> 3);
    int ty = py & 7;
    int tx0 = px & 7;
    uint16_t entries[3];
    uint32_t rows[3];
    for (int layer = 0; layer < nl; layer++) {
        entries[layer] = gbaduo_bus_read16(base + (uint32_t) (layer * 4 + quadrant) * 2u);
        rows[layer] = tile_row(w, entries[layer], ty);
    }

    for (int i = 0; i < n; i++) {
        int index = 0;
        int bank = 0;
        for (int layer = nl - 1; layer >= 0 && index == 0; layer--) {
            index = row_index(rows[layer], entries[layer], tx0 + i);
            bank = entries[layer] >> 12;
        }
        if (index == 0) bank = 0;
        out[i] = pal[bank * 16 + index];
    }
}

/* [n] (1..8) pixels of a layered world, inside one 8-pixel tile column of every layer (wide_row splits the spans there):
 * per layer, one set of bus reads. */
static void layered_span(const struct WideWorld* w, const uint32_t* pal, int32_t wy, int32_t wx, int n, uint32_t* out) {
    uint32_t rows[WIDE_MAX_LAYERS] = {0};
    uint8_t rows8[WIDE_MAX_LAYERS][8];
    uint16_t entries[WIDE_MAX_LAYERS] = {0};
    int tx0[WIDE_MAX_LAYERS] = {0};
    bool has[WIDE_MAX_LAYERS] = {false};
    bool inside = false;
    for (int i = 0; i < w->layerCount; i++) {
        const struct WideLayer* l = &w->layers[i];
        int tx, ty;
        if (l->tileMode == 1) {
            if (!chunk_entry(l, wx, wy, &entries[i], &tx, &ty)) continue;
            has[i] = true;
            inside = true;
            tx0[i] = tx;
            int y = (entries[i] & 0x800) ? 7 - ty : ty;
            uint32_t base = l->tiles + (uint32_t) (entries[i] & 0x3FF) * 64u + (uint32_t) y * 8u;
            uint32_t lo = gbaduo_bus_read32(base), hi = gbaduo_bus_read32(base + 4);
            for (int k = 0; k < 4; k++) {
                rows8[i][k] = (uint8_t) (lo >> (8 * k));
                rows8[i][4 + k] = (uint8_t) (hi >> (8 * k));
            }
            continue;
        }
        if (!layer_entry(l, wx, wy, &entries[i], &tx, &ty)) continue;
        has[i] = true;
        inside = true;
        tx0[i] = tx;
        int y = (entries[i] & 0x800) ? 7 - ty : ty;
        rows[i] = gbaduo_bus_read32(l->tiles + (uint32_t) (entries[i] & 0x3FF) * 32u + (uint32_t) y * 4u);
    }
    for (int k = 0; k < n; k++) {
        uint32_t color = inside ? pal[0] : 0;
        for (int i = w->layerCount - 1; i >= 0; i--) {
            if (!has[i]) continue;
            if (w->layers[i].tileMode == 1) {
                int x = (entries[i] & 0x400) ? 7 - (tx0[i] + k) : tx0[i] + k;
                int index8 = rows8[i][x & 7];
                if (index8 != 0) {
                    color = pal[index8];
                    break;
                }
                continue;
            }
            int index = row_index(rows[i], entries[i], tx0[i] + k);
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
            for (int i = 0; i < w->layerCount; i++) { /* a chunk layer's tiles line up with its own origin, not the world's */
                if (w->layers[i].tileMode != 1) continue;
                int t = 8 - (int) ((uint32_t) (wx + w->layers[i].dx) & 7u);
                if (t < inTile) inTile = t;
            }
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

static void blank_beyond_overscan(const struct WideWorld* w, uint32_t* buf, int stride, int left, int right, int top, int bottom);

/* Marker colours (BGR555), by kind: white, red, yellow, cyan. */
static const uint16_t MARKER_COLOR[WIDE_MARKER_KIND_COUNT] = { 0, 0x7FFF, 0x001F, 0x03FF, 0x7FE0 };
#define MARKER_OUTLINE 0x0842 /* near black */
#define MARKER_RADIUS 4       /* a diamond 7 px across inside a 1 px outline */

static bool marker_in_frame(int px, int py, int left, int top) {
    return px >= left && px < left + WIDE_FRAME_W && py >= top && py < top + WIDE_FRAME_H;
}

/* A sprite marker: its tiles read from the bus, drawn only outside the frame and inside the picture. */
static void paint_sprite_marker(const struct WideMarker* m, const struct WideWorld* w, uint32_t* buf, int stride,
                                int left, int top, int pictureW, int pictureH) {
    if (m->w < 8 || m->h < 8 || m->w > 64 || m->h > 64 || (m->w & 7) || (m->h & 7)) return;
    int64_t ox = (int64_t) left + ((int64_t) m->x - w->x);
    int64_t oy = (int64_t) top + ((int64_t) m->y - w->y);
    if (ox + m->w <= 0 || oy + m->h <= 0 || ox >= pictureW || oy >= pictureH) return;
    uint32_t pal[256];
    int colors = m->bpp8 ? 256 : 16;
    for (int i = 1; i < colors; i++) pal[i] = wide_color(gbaduo_bus_read16(m->palette + (uint32_t) i * 2u));
    int tilesPerRow = m->w / 8;
    for (int sy = 0; sy < m->h; sy++) {
        int py = (int) oy + sy;
        if (py < 0 || py >= pictureH) continue;
        int ry = m->vflip ? m->h - 1 - sy : sy;
        for (int sx = 0; sx < m->w; sx++) {
            int px = (int) ox + sx;
            if (px < 0 || px >= pictureW || marker_in_frame(px, py, left, top)) continue;
            int tx = m->hflip ? m->w - 1 - sx : sx;
            uint32_t tile = (uint32_t) ((ry >> 3) * tilesPerRow + (tx >> 3));
            int index;
            if (m->bpp8) {
                index = gbaduo_bus_read8(m->tiles + tile * 64u + (uint32_t) (ry & 7) * 8u + (uint32_t) (tx & 7));
            } else {
                uint8_t b = gbaduo_bus_read8(m->tiles + tile * 32u + (uint32_t) (ry & 7) * 4u + (uint32_t) ((tx & 7) >> 1));
                index = (tx & 1) ? b >> 4 : b & 0x0F;
            }
            if (index == 0) continue;
            buf[(size_t) py * (size_t) stride + (size_t) px] = pal[index];
        }
    }
}

static void paint_markers(const struct WideWorld* w, uint32_t* buf, int stride, int left, int right, int top, int bottom) {
    int pictureW = left + WIDE_FRAME_W + right, pictureH = top + WIDE_FRAME_H + bottom;
    int n = w->markerCount < 0 ? 0 : w->markerCount > WIDE_MAX_MARKERS ? WIDE_MAX_MARKERS : w->markerCount;
    for (int i = 0; i < n; i++) {
        if (w->markers[i].tiles != 0) {
            paint_sprite_marker(&w->markers[i], w, buf, stride, left, top, pictureW, pictureH);
            continue;
        }
        int kind = w->markers[i].kind;
        if (kind < 1 || kind >= WIDE_MARKER_KIND_COUNT) continue;
        int64_t cx = (int64_t) left + ((int64_t) w->markers[i].x - w->x);
        int64_t cy = (int64_t) top + ((int64_t) w->markers[i].y - w->y);
        if (cx < -MARKER_RADIUS - 1 || cy < -MARKER_RADIUS - 1 || cx > pictureW + MARKER_RADIUS || cy > pictureH + MARKER_RADIUS) continue;
        uint32_t fill = wide_color(MARKER_COLOR[kind]), edge = wide_color(MARKER_OUTLINE);
        for (int dy = -MARKER_RADIUS; dy <= MARKER_RADIUS; dy++) {
            for (int dx = -MARKER_RADIUS; dx <= MARKER_RADIUS; dx++) {
                int dist = (dx < 0 ? -dx : dx) + (dy < 0 ? -dy : dy); /* a diamond */
                if (dist > MARKER_RADIUS) continue;
                int px = (int) cx + dx, py = (int) cy + dy;
                if (px < 0 || py < 0 || px >= pictureW || py >= pictureH || marker_in_frame(px, py, left, top)) continue;
                buf[(size_t) py * (size_t) stride + (size_t) px] = dist == MARKER_RADIUS ? edge : fill;
            }
        }
    }
}

/* The overlay over [n] pixels of [line], whose first pixel is at screen x [sx] on screen row [sy]. */
static void overlay_row(const struct WideWorld* w, const uint32_t* pal, int sx, int sy, int n, uint32_t* line) {
    const struct WideOverlay* o = &w->overlay;
    int my = (sy + o->scrollY) & 255;
    for (int k = 0; k < n; k++) {
        int mx = (sx + k + o->scrollX) & 255;
        uint16_t entry = gbaduo_bus_read16(o->map + (uint32_t) ((my >> 3) * 32 + (mx >> 3)) * 2u);
        int tx = mx & 7, ty = my & 7;
        if (entry & 0x400) tx = 7 - tx;
        if (entry & 0x800) ty = 7 - ty;
        uint8_t b = gbaduo_bus_read8(o->tiles + (uint32_t) (entry & 0x3FF) * 32u + (uint32_t) ty * 4u + (uint32_t) (tx >> 1));
        int idx = (tx & 1) ? b >> 4 : b & 0x0F;
        if (idx == 0) continue;
        uint32_t over = pal[(entry >> 12) * 16 + idx], ground = line[k], res = 0;
        for (int shift = 0; shift < 24; shift += 8) {
            uint32_t v = (((over >> shift) & 0xFF) * (uint32_t) o->eva + ((ground >> shift) & 0xFF) * (uint32_t) o->evb) / 16u;
            res |= (v > 255u ? 255u : v) << shift;
        }
        line[k] = res;
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
            if (w->overlay.present) {
                overlay_row(w, pal, -left, row - top, left, line);
                overlay_row(w, pal, WIDE_FRAME_W, row - top, right, line + left + WIDE_FRAME_W);
            }
        } else {
            wide_row(w, pal, wy, w->x - left, total, line, true);
            if (w->overlay.present) overlay_row(w, pal, -left, row - top, total, line);
        }
    }
    blank_beyond_overscan(w, buf, stride, left, right, top, bottom);
    if (valid) paint_markers(w, buf, stride, left, right, top, bottom);
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

static bool defined_bounds(const struct WideWorld* w, struct WideBounds* b) {
    if (w->layerCount == 0) {
        if (!w->finite && w->overscan <= 0) return false;
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
    return false; /* not reached for a single grid */
}

/* The bounding box (layer pixels) of the non-empty tiles of chunk layer [l], kept per thread while the same layer is asked for. */
struct TrimCache {
    uint32_t chunks;
    int chunkW, chunkH, codec;
    unsigned generation;
    bool valid, empty;
    int64_t x0, y0, x1, y1;
};
static _Thread_local struct TrimCache t_trim;

static bool trimmed_bounds(const struct WideLayer* l, struct WideBounds* b) {
    unsigned generation = atomic_load(&g_chunkGeneration);
    struct TrimCache* t = &t_trim;
    if (!t->valid || t->chunks != l->chunks || t->chunkW != l->chunkW || t->chunkH != l->chunkH || t->codec != l->chunkCodec ||
        t->generation != generation) {
        t->valid = true;
        t->chunks = l->chunks;
        t->chunkW = l->chunkW;
        t->chunkH = l->chunkH;
        t->codec = l->chunkCodec;
        t->generation = generation;
        t->empty = true;
        t->x0 = t->y0 = INT64_MAX;
        t->x1 = t->y1 = INT64_MIN;
        for (int cy = 0; cy < l->chunkH; cy++) {
            for (int cx = 0; cx < l->chunkW; cx++) {
                uint32_t ptr = gbaduo_bus_read32(l->chunks + ((uint32_t) cy * (uint32_t) l->chunkW + (uint32_t) cx) * 4u);
                if (ptr == 0) continue;
                const uint8_t* d = l->chunkCodec == 1 ? chunk_unpacked(ptr) : NULL;
                for (int k = 0; k < 1024; k++) {
                    uint16_t e = d ? (uint16_t) (d[k * 2] | d[k * 2 + 1] << 8) : gbaduo_bus_read16(ptr + (uint32_t) k * 2u);
                    if ((e & 0x3FF) == 0) continue;
                    int64_t px = (int64_t) cx * 256 + (k & 31) * 8, py = (int64_t) cy * 256 + (k >> 5) * 8;
                    t->empty = false;
                    if (px < t->x0) t->x0 = px;
                    if (py < t->y0) t->y0 = py;
                    if (px + 8 > t->x1) t->x1 = px + 8;
                    if (py + 8 > t->y1) t->y1 = py + 8;
                }
            }
        }
    }
    if (t->empty) return false;
    b->x0 = t->x0 - l->dx;
    b->y0 = t->y0 - l->dy;
    b->x1 = t->x1 - l->dx;
    b->y1 = t->y1 - l->dy;
    return true;
}

static bool world_bounds(const struct WideWorld* w, struct WideBounds* b) {
    if (w->hasBounds) {
        b->x0 = w->bx0;
        b->y0 = w->by0;
        b->x1 = w->bx1;
        b->y1 = w->by1;
        return true;
    }
    if (w->layerCount == 0) {
        if (!defined_bounds(w, b)) return false;
        if (!w->finite) { /* the border shows only [overscan] px past the defined cells */
            b->x0 -= w->overscan;
            b->y0 -= w->overscan;
            b->x1 += w->overscan;
            b->y1 += w->overscan;
        }
        return true;
    }
    if (w->layers[0].tileMode == 1 && w->layers[0].trimBounds && trimmed_bounds(&w->layers[0], b)) return true;
    if (w->layers[0].tileMode == 1) { /* the bottom chunk layer is the ground: the map ends where it does */
        const struct WideLayer* g = &w->layers[0];
        b->x0 = -(int64_t) g->dx;
        b->y0 = -(int64_t) g->dy;
        b->x1 = (int64_t) g->chunkW * 256 - g->dx;
        b->y1 = (int64_t) g->chunkH * 256 - g->dy;
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

/* A single grid with an [overscan]: the margin is black past the defined cells' rectangle grown by it. */
static void blank_beyond_overscan(const struct WideWorld* w, uint32_t* buf, int stride, int left, int right, int top, int bottom) {
    struct WideBounds m;
    bool layeredBounds = w->layerCount != 0 && (w->hasBounds || (w->layers[0].tileMode == 1 && w->layers[0].trimBounds));
    if (!layeredBounds && (w->layerCount != 0 || w->finite || w->overscan <= 0)) return;
    if (!world_bounds(w, &m)) return;
    int total = left + WIDE_FRAME_W + right;
    for (int row = 0; row < top + WIDE_FRAME_H + bottom; row++) {
        int64_t wy = (int64_t) w->y + (row - top);
        bool rowOut = wy < m.y0 || wy >= m.y1;
        bool inFrameRows = row >= top && row < top + WIDE_FRAME_H;
        uint32_t* line = buf + (size_t) row * (size_t) stride;
        for (int col = 0; col < total; col++) {
            if (inFrameRows && col >= left && col < left + WIDE_FRAME_W) continue;
            int64_t wx = (int64_t) w->x + (col - left);
            if (rowOut || wx < m.x0 || wx >= m.x1) line[col] = 0;
        }
    }
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
    } else if (match >= BAD_MATCH) {
        g->good = 0;
        if (g->shown) g->bad = 0;
    } else {
        g->good = 0;
        if (g->shown && ++g->bad >= HIDE_AFTER_BAD) {
            g->shown = false;
            g->bad = 0;
        }
    }
    return g->shown;
}
