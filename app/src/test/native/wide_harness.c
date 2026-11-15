// Desktop tests for the wide-view painter (wide.c) over a fake machine (fake_bus.c).
// Run under ASan/UBSan by run.sh.

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "bus.h"
#include "fake_bus.h"
#include "wide.h"

static int failures = 0;
static int checks = 0;

#define CHECK(cond)                                                                 \
    do {                                                                            \
        checks++;                                                                   \
        if (!(cond)) {                                                              \
            failures++;                                                             \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);         \
        }                                                                           \
    } while (0)

enum {
    GRID = 0x02000000u,
    BORDER = 0x02001000u,
    METATILES = 0x02002000u,
    METATILES2 = 0x02004000u,
    TILES = 0x06000000u,
    PALETTE = 0x05000000u,
};

static void poke8(uint32_t a, uint8_t v) { gbaduo_bus_write8(a, v); }
static void poke16(uint32_t a, uint16_t v) { poke8(a, (uint8_t) v); poke8(a + 1, (uint8_t) (v >> 8)); }

static uint16_t marker_color(int n) { return (uint16_t) (((n + 1) & 0x1F) | (((n + 1) & 0x1F) << 5)); }

/* Tile [tile] filled with palette index [index]. */
static void solid_tile(int tile, int index) {
    for (int i = 0; i < 32; i++) poke8(TILES + (uint32_t) tile * 32 + (uint32_t) i, (uint8_t) (index | index << 4));
}

/* Metatile at [base]: all four bottom entries show tile 1 (solid, index 1) in bank [n]; top layer empty. */
static void marker_metatile(uint32_t base, int n) {
    for (int q = 0; q < 4; q++) poke16(base + (uint32_t) q * 2, (uint16_t) (1 | n << 12));
    for (int q = 4; q < 8; q++) poke16(base + (uint32_t) q * 2, 0);
}

static struct WideWorld fixture(void) {
    fake_reset(GBADUO_BUS_GBA);
    solid_tile(1, 1);
    for (int n = 0; n < 16; n++) poke16(PALETTE + (uint32_t) (n * 16 + 1) * 2, marker_color(n));
    struct WideWorld w = {
        .x = 0, .y = 0, .grid = GRID, .gridW = 3, .gridH = 2, .idMask = 0x3FF,
        .border = BORDER, .borderW = 2, .borderH = 2,
        .borderShift = 0, .undefinedId = -1,
        .metatiles = METATILES, .metatiles2 = METATILES2, .split = 640,
        .tiles = TILES, .palette = PALETTE,
    };
    return w;
}

static uint32_t marker(int n) { return wide_color(marker_color(n)); }

static void color_matches_mgba(void) {
    CHECK(wide_color(0x7FFF) == 0xFFFFFFu);
    CHECK(wide_color(0x001F) == 0x0000FFu);
    CHECK(wide_color(0x0000) == 0u);
}

static void pixel_reads_bottom_then_top_then_backdrop(void) {
    struct WideWorld w = fixture();
    poke16(PALETTE, 0x7C00);          /* backdrop: blue */
    poke16(PALETTE + 2, 0x001F);      /* index 1: red */
    poke16(PALETTE + 4, 0x03E0);      /* index 2: green */
    for (int i = 0; i < 32; i++) poke8(TILES + 2 * 32 + (uint32_t) i, 0);
    poke8(TILES + 2 * 32, 0x02);      /* tile 2: pixel (0,0) is index 2 */
    poke16(GRID, 1);                  /* cell (0,0) = metatile 1 */
    poke16(GRID + 2, 0);              /* cell (1,0) = metatile 0, all zero */
    poke16(METATILES + 1 * 16, 1);    /* bottom TL: tile 1 (solid index 1) */
    poke16(METATILES + 1 * 16 + 8, 2); /* top TL: tile 2 */
    CHECK(wide_pixel(&w, 0, 0) == wide_color(0x03E0));
    CHECK(wide_pixel(&w, 1, 0) == wide_color(0x001F));
    CHECK(wide_pixel(&w, 16, 0) == wide_color(0x7C00));
}

static void flips_and_banks(void) {
    struct WideWorld w = fixture();
    for (int i = 0; i < 16; i++) poke16(PALETTE + (uint32_t) i * 2, 0);
    poke16(PALETTE + 5 * 2, 0x0111);
    poke16(PALETTE + 6 * 2, 0x0222);
    poke16(PALETTE + (3 * 16 + 1) * 2, 0x0333);
    for (int t = 3; t <= 4; t++) for (int i = 0; i < 32; i++) poke8(TILES + (uint32_t) t * 32 + (uint32_t) i, 0);
    poke8(TILES + 3 * 32, 0x05);      /* tile 3: pixel (0,0) is index 5 */
    poke8(TILES + 4 * 32, 0x06);      /* tile 4: pixel (0,0) is index 6 */
    solid_tile(5, 1);
    poke16(GRID, 2);
    poke16(METATILES + 2 * 16 + 0, 3 | 0x400);       /* bottom TL: tile 3, h-flip */
    poke16(METATILES + 2 * 16 + 2, 4 | 0x800);       /* bottom TR: tile 4, v-flip */
    poke16(METATILES + 2 * 16 + 4, 5 | (3 << 12));   /* bottom BL: tile 5, bank 3 */
    CHECK(wide_pixel(&w, 7, 0) == wide_color(0x0111));
    CHECK(wide_pixel(&w, 8, 7) == wide_color(0x0222));
    CHECK(wide_pixel(&w, 0, 8) == wide_color(0x0333));
}

static void outside_grid_uses_border(void) {
    struct WideWorld w = fixture();
    for (int c = 0; c < 6; c++) poke16(GRID + (uint32_t) c * 2, 1);
    for (int n = 1; n <= 6; n++) marker_metatile(METATILES + (uint32_t) n * 16, n);
    poke16(BORDER + 0, 3); poke16(BORDER + 2, 4); poke16(BORDER + 4, 5); poke16(BORDER + 6, 6);
    CHECK(wide_pixel(&w, 0, 0) == marker(1));
    CHECK(wide_pixel(&w, -1, -1) == marker(6));    /* cell (-1,-1) -> border (1,1) */
    CHECK(wide_pixel(&w, 3 * 16, 0) == marker(4)); /* cell (3,0) -> border (1,0) */
}

static void border_shift_moves_the_block_origin(void) {
    struct WideWorld w = fixture();
    for (int c = 0; c < 6; c++) poke16(GRID + (uint32_t) c * 2, 1);
    for (int n = 1; n <= 6; n++) marker_metatile(METATILES + (uint32_t) n * 16, n);
    poke16(BORDER + 0, 3); poke16(BORDER + 2, 4); poke16(BORDER + 4, 5); poke16(BORDER + 6, 6);
    w.borderShift = 7;                              /* block index = (cell - 7) mod size */
    CHECK(wide_pixel(&w, -1 * 16, 0) == marker(5)); /* cell (-1,0): (-8 mod 2, -7 mod 2) = (0,1) -> block 2: id 5 */
}

static void undefined_cell_uses_border(void) {
    struct WideWorld w = fixture();
    w.undefinedId = 0x3FF;
    for (int c = 0; c < 6; c++) poke16(GRID + (uint32_t) c * 2, 1);
    poke16(GRID, 0x3FF);                            /* cell (0,0) is undefined */
    poke16(GRID + 2, 0x3FF | 0x0C00);               /* collision bits do not hide it */
    for (int n = 1; n <= 6; n++) marker_metatile(METATILES + (uint32_t) n * 16, n);
    poke16(BORDER + 0, 3); poke16(BORDER + 2, 4); poke16(BORDER + 4, 5); poke16(BORDER + 6, 6);
    CHECK(wide_pixel(&w, 0, 0) == marker(3));       /* border block (0,0) */
    CHECK(wide_pixel(&w, 16, 0) == marker(4));      /* border block (1,0) */
    CHECK(wide_pixel(&w, 32, 0) == marker(1));      /* a defined cell is untouched */
    w.undefinedId = -1;
    CHECK(wide_pixel(&w, 0, 0) != marker(3));       /* off: 0x3FF is just a metatile id */
}

static void split_selects_second_table(void) {
    struct WideWorld w = fixture();
    w.split = 4;
    poke16(GRID, 6);
    marker_metatile(METATILES + 6 * 16, 8);
    marker_metatile(METATILES2 + 2 * 16, 9);
    CHECK(wide_pixel(&w, 0, 0) == marker(9));
}

static void paint_fills_only_margins(void) {
    struct WideWorld w = fixture();
    for (int c = 0; c < 6; c++) poke16(GRID + (uint32_t) c * 2, 1);
    marker_metatile(METATILES + 16, 2);
    enum { STRIDE = 280 };
    uint32_t* buf = malloc((size_t) STRIDE * 160 * sizeof(uint32_t));
    for (int i = 0; i < STRIDE * 160; i++) buf[i] = 0xDEADBEEFu;
    wide_paint(&w, buf, STRIDE, 20, 20, 0, 0);
    int untouched = 1, painted = 1;
    for (int row = 0; row < 160; row++) {
        for (int c = 20; c < 260; c++) if (buf[row * STRIDE + c] != 0xDEADBEEFu) untouched = 0;
        for (int c = 0; c < 20; c++) if (buf[row * STRIDE + c] == 0xDEADBEEFu) painted = 0;
        for (int c = 260; c < 280; c++) if (buf[row * STRIDE + c] == 0xDEADBEEFu) painted = 0;
    }
    CHECK(untouched);
    CHECK(painted);
    free(buf);
}

static uint32_t rnd(uint32_t* seed) {
    *seed = *seed * 1664525u + 1013904223u;
    return *seed >> 8;
}

/* A busy world: random tiles, flips, banks, layers and palette; paint must equal wide_pixel pixel by pixel. */
/* An 8bpp, vertically mirrored sprite marker: 64 bytes a tile, 256 colours, index 0 clear. */
static void sprite_markers_8bpp_and_vflip(void) {
    fake_reset(GBADUO_BUS_GBA);
    enum { SPR = 0x02008000u, SPRPAL = 0x02009000u, STR = 400, LEFT = 80 };
    poke16(PALETTE, 0x7C00);
    solid_tile(1, 0);
    for (int i = 0; i < 4; i++) { poke16(METATILES + 8 + (uint32_t) i * 2, 1); poke16(METATILES + (uint32_t) i * 2, 1); }
    for (int i = 0; i < 64; i++) poke16(GRID + (uint32_t) i * 2, 0);
    poke16(BORDER, 0);
    poke16(SPRPAL + 2 * 200, 0x001F);                      /* index 200 red */
    poke16(SPRPAL + 2 * 3, 0x03E0);                        /* index 3 green */
    for (int i = 0; i < 256; i++) poke8(SPR + (uint32_t) i, 0);
    /* 16x16: tiles TL, TR, BL, BR (64 bytes each). TL pixel (0,0) = 200, (1,0) = 3; BL pixel (0,7) (sprite row 15 of 0..15 -> y 8) = 3 */
    poke8(SPR, 200);
    poke8(SPR + 1, 3);
    poke8(SPR + 64 * 2 + 0, 3);                            /* BL tile, pixel (0,0): sprite (0, 8) */
    poke8(SPR + 64 + 7, 200);                              /* TR tile, pixel (7,0): sprite (15, 0) */
    struct WideWorld w = {
        .x = 0, .y = 0, .grid = GRID, .gridW = 8, .gridH = 8, .idMask = 0x3FF,
        .border = BORDER, .borderW = 1, .borderH = 1, .undefinedId = -1,
        .metatiles = METATILES, .metatiles2 = METATILES2, .split = 512, .tiles = TILES, .palette = PALETTE,
    };
    static uint32_t buf[STR * 160];
    w.markerCount = 1;
    w.markers[0] = (struct WideMarker){ .x = -60, .y = 100, .kind = 1, .tiles = SPR, .palette = SPRPAL, .w = 16, .h = 16, .bpp8 = 1 };
    wide_paint(&w, buf, STR, LEFT, 80, 0, 0);
    int ox = LEFT - 60, oy = 100;
    CHECK(buf[oy * STR + ox] == wide_color(0x001F));        /* (0,0) index 200 */
    CHECK(buf[oy * STR + ox + 1] == wide_color(0x03E0));    /* (1,0) index 3 */
    CHECK(buf[(oy + 8) * STR + ox] == wide_color(0x03E0));  /* the bottom-left tile, 2 tiles on in 1D order */
    CHECK(buf[oy * STR + ox + 15] == wide_color(0x001F));   /* the right tile's pixel (7,0) */
    w.markers[0].vflip = 1;
    wide_paint(&w, buf, STR, LEFT, 80, 0, 0);
    CHECK(buf[(oy + 15) * STR + ox] == wide_color(0x001F)); /* row 0 is now the bottom row */
    CHECK(buf[(oy + 7) * STR + ox] == wide_color(0x03E0));  /* sprite row 8 lands on row 7 */
    CHECK(buf[oy * STR + ox] != wide_color(0x001F));
}

static void paint_equals_pixelwise_reference(void) {
    struct WideWorld w = fixture();
    uint32_t seed = 12345u;
#define RND() rnd(&seed)
    for (int i = 0; i < 48 * 32; i++) poke8(TILES + (uint32_t) i, (uint8_t) RND());
    for (int i = 0; i < 256; i++) poke16(PALETTE + (uint32_t) i * 2, (uint16_t) (RND() & 0x7FFF));
    for (int m = 0; m < 8; m++) {
        for (int q = 0; q < 8; q++) {
            uint16_t entry = (uint16_t) ((RND() % 48) | ((RND() & 3) << 10) | ((RND() % 4) << 12));
            poke16(METATILES + (uint32_t) m * 16 + (uint32_t) q * 2, entry);
        }
    }
    for (int c = 0; c < 6; c++) poke16(GRID + (uint32_t) c * 2, (uint16_t) (RND() % 8));
    for (int c = 0; c < 4; c++) poke16(BORDER + (uint32_t) c * 2, (uint16_t) (RND() % 8));
    w.borderShift = 7;
    enum { STRIDE = 288, LEFT = 24, RIGHT = 24 };
    static const int32_t origins[][2] = {{0, 0}, {-5, 3}, {7, -9}, {45, 31}, {-100, -70}};
    uint32_t* buf = malloc((size_t) STRIDE * 160 * sizeof(uint32_t));
    int same = 1;
    for (unsigned o = 0; o < sizeof(origins) / sizeof(origins[0]); o++) {
        w.x = origins[o][0];
        w.y = origins[o][1];
        for (int i = 0; i < STRIDE * 160; i++) buf[i] = 0xDEADBEEFu;
        wide_paint(&w, buf, STRIDE, LEFT, RIGHT, 0, 0);
        for (int row = 0; row < 160; row++) {
            for (int c = 0; c < STRIDE; c++) {
                if (c >= LEFT && c < LEFT + 240) continue;
                if (buf[row * STRIDE + c] != wide_pixel(&w, w.x + c - LEFT, w.y + row)) same = 0;
            }
        }
    }
    CHECK(same);
    free(buf);
#undef RND
}

static void paint_survives_garbage_addresses(void) {
    struct WideWorld w = fixture();
    w.gridW = 5000;
    w.gridH = 0;
    w.split = -1;
    enum { STRIDE = 280 };
    uint32_t* buf = malloc((size_t) STRIDE * 160 * sizeof(uint32_t));
    for (int i = 0; i < STRIDE * 160; i++) buf[i] = 0xDEADBEEFu;
    wide_paint(&w, buf, STRIDE, 20, 20, 0, 0);
    CHECK(buf[0] == 0u);
    CHECK(buf[159 * STRIDE + 279] == 0u);
    CHECK(buf[100 * STRIDE + 100] == 0xDEADBEEFu);
    free(buf);
}

static void seam_match_is_one_on_its_own_picture(void) {
    struct WideWorld w = fixture();
    for (int c = 0; c < 6; c++) poke16(GRID + (uint32_t) c * 2, (uint16_t) (c % 3 + 1));
    for (int n = 1; n <= 3; n++) marker_metatile(METATILES + (uint32_t) n * 16, n);
    enum { STRIDE = 280, LEFT = 20 };
    uint32_t* buf = calloc((size_t) STRIDE * 160, sizeof(uint32_t));
    for (int row = 0; row < 160; row++)
        for (int x = 0; x < 240; x++) buf[row * STRIDE + LEFT + x] = wide_pixel(&w, x, row);
    CHECK(fabsf(wide_seam_match(&w, buf, STRIDE, LEFT, 0, false) - 1.0f) < 1e-6f);
    for (int row = 0; row < 160; row++) buf[row * STRIDE + LEFT] ^= 0x00FFFFFFu;
    CHECK(fabsf(wide_seam_match(&w, buf, STRIDE, LEFT, 0, false) - (1.0f - 160.0f / 2560.0f)) < 1e-6f);
    free(buf);
}

/* Margins on all four sides: every pixel outside the frame is the world's, the frame itself is not touched. */
static void paint_fills_all_four_sides(void) {
    struct WideWorld w = fixture();
    uint32_t seed = 777u;
#define RND() rnd(&seed)
    for (int i = 0; i < 48 * 32; i++) poke8(TILES + (uint32_t) i, (uint8_t) RND());
    for (int i = 0; i < 256; i++) poke16(PALETTE + (uint32_t) i * 2, (uint16_t) (RND() & 0x7FFF));
    for (int m = 0; m < 8; m++)
        for (int q = 0; q < 8; q++) poke16(METATILES + (uint32_t) m * 16 + (uint32_t) q * 2, (uint16_t) ((RND() % 48) | ((RND() % 4) << 12)));
    for (int c = 0; c < 6; c++) poke16(GRID + (uint32_t) c * 2, (uint16_t) (RND() % 8));
    for (int c = 0; c < 4; c++) poke16(BORDER + (uint32_t) c * 2, (uint16_t) (RND() % 8));
    enum { STRIDE = 300, HEIGHT = 230, LEFT = 30, RIGHT = 30, TOP = 35, BOTTOM = 35 };
    uint32_t* buf = malloc((size_t) STRIDE * HEIGHT * sizeof(uint32_t));
    static const int32_t origins[][2] = {{0, 0}, {-5, 3}, {45, -31}};
    int same = 1, untouched = 1;
    for (unsigned o = 0; o < sizeof(origins) / sizeof(origins[0]); o++) {
        w.x = origins[o][0];
        w.y = origins[o][1];
        for (int i = 0; i < STRIDE * HEIGHT; i++) buf[i] = 0xDEADBEEFu;
        wide_paint(&w, buf, STRIDE, LEFT, RIGHT, TOP, BOTTOM);
        for (int row = 0; row < HEIGHT; row++) {
            for (int c = 0; c < STRIDE; c++) {
                bool inFrame = row >= TOP && row < TOP + 160 && c >= LEFT && c < LEFT + 240;
                uint32_t got = buf[row * STRIDE + c];
                if (inFrame) {
                    if (got != 0xDEADBEEFu) untouched = 0;
                } else if (got != wide_pixel(&w, w.x + c - LEFT, w.y + row - TOP)) {
                    same = 0;
                }
            }
        }
    }
    CHECK(same);
    CHECK(untouched);
    free(buf);
#undef RND
}

/* With the edge rows on, the seam check reads the frame at (left, top) and counts the top and bottom strips too, with slack. */
static void seam_match_counts_the_edge_rows(void) {
    struct WideWorld w = fixture();
    for (int c = 0; c < 6; c++) poke16(GRID + (uint32_t) c * 2, (uint16_t) (c % 3 + 1));
    for (int n = 1; n <= 3; n++) marker_metatile(METATILES + (uint32_t) n * 16, n);
    enum { STRIDE = 300, HEIGHT = 230, LEFT = 30, TOP = 35 };
    uint32_t* buf = calloc((size_t) STRIDE * HEIGHT, sizeof(uint32_t));
    for (int row = 0; row < 160; row++)
        for (int x = 0; x < 240; x++) buf[(TOP + row) * STRIDE + LEFT + x] = wide_pixel(&w, x, row);
    CHECK(fabsf(wide_seam_match(&w, buf, STRIDE, LEFT, TOP, true) - 1.0f) < 1e-6f);
    /* A little HUD on the top edge is forgiven (the rows have slack)... */
    for (int x = 100; x < 108; x++) buf[TOP * STRIDE + LEFT + x] ^= 0x00FFFFFFu;
    CHECK(fabsf(wide_seam_match(&w, buf, STRIDE, LEFT, TOP, true) - 1.0f) < 1e-6f);
    /* ...but a wrong top and bottom (all 16 edge rows) is not: row share 0, so 0.2 with the slack. */
    for (int row = 0; row < 160; row++) {
        if (row >= 8 && row < 152) continue;
        for (int x = 8; x < 232; x++) buf[(TOP + row) * STRIDE + LEFT + x] ^= 0x00FFFFFFu;
    }
    CHECK(fabsf(wide_seam_match(&w, buf, STRIDE, LEFT, TOP, true) - 0.2f) < 5e-3f);
    /* Without the rows flag the same spoil is invisible. */
    CHECK(fabsf(wide_seam_match(&w, buf, STRIDE, LEFT, TOP, false) - 1.0f) < 1e-6f);
    free(buf);
}

/* A blend the world doesn't describe (a cloud shadow darkens the picture a little) still counts as predicted; a different colour does not. */
static void seam_match_tolerates_a_tint(void) {
    struct WideWorld w = fixture();
    for (int c = 0; c < 6; c++) poke16(GRID + (uint32_t) c * 2, (uint16_t) (c % 3 + 1));
    for (int n = 1; n <= 3; n++) marker_metatile(METATILES + (uint32_t) n * 16, n);
    enum { STRIDE = 280, LEFT = 20 };
    uint32_t* buf = calloc((size_t) STRIDE * 160, sizeof(uint32_t));
    for (int row = 0; row < 160; row++)
        for (int x = 0; x < 240; x++) buf[row * STRIDE + LEFT + x] = wide_pixel(&w, x, row);
    for (int row = 0; row < 160; row++) {
        for (int x = 0; x < 240; x++) {
            uint32_t c = buf[row * STRIDE + LEFT + x];
            uint32_t r = (c & 0xFF) > 30 ? (c & 0xFF) - 30 : 0;
            uint32_t g = ((c >> 8) & 0xFF) > 30 ? ((c >> 8) & 0xFF) - 30 : 0;
            uint32_t b = ((c >> 16) & 0xFF) > 30 ? ((c >> 16) & 0xFF) - 30 : 0;
            buf[row * STRIDE + LEFT + x] = (c & 0xFF000000u) | b << 16 | g << 8 | r;
        }
    }
    CHECK(fabsf(wide_seam_match(&w, buf, STRIDE, LEFT, 0, false) - 1.0f) < 1e-6f);
    for (int row = 0; row < 160; row++) buf[row * STRIDE + LEFT] ^= 0x00FFFFFFu;
    CHECK(wide_seam_match(&w, buf, STRIDE, LEFT, 0, false) < 1.0f);
    free(buf);
}

/* The share of the margins that is map: a layered world is as big as its grids, a single-grid world's border repeats forever. */
static void coverage_is_the_share_of_margin_inside_the_map(void) {
    struct WideWorld w = fixture();
    CHECK(wide_coverage(&w, 80, 40, 400, 240) == 1.0f);
    struct WideWorld l;
    memset(&l, 0, sizeof(l));
    l.layerCount = 1;
    l.layers[0].gridW = 15;       /* 240 px */
    l.layers[0].gridH = 10;       /* 160 px: the frame is the whole room */
    l.layers[0].stride = 15;
    l.layers[0].cellBytes = 2;
    CHECK(wide_coverage(&l, 80, 40, 400, 240) == 0.0f);
    l.layers[0].gridW = 25;       /* 400 x 160 px, the frame at the left of it */
    l.layers[0].stride = 25;
    float c = wide_coverage(&l, 80, 40, 400, 240);
    /* the map (400x160, the frame at its left edge) fills part of the margin: the strip to the right of the frame and nothing above or below */
    CHECK(c > 0.0f && c < 1.0f);
    l.layers[0].gridW = 64;
    l.layers[0].gridH = 64;
    l.layers[0].stride = 64;
    l.x = 400;
    l.y = 400;                    /* the frame deep inside a big room: the picture is all map */
    CHECK(wide_coverage(&l, 80, 40, 400, 240) == 1.0f);
}

static void clamp_frame_keeps_the_picture_inside_the_map(void) {
    struct WideWorld l;
    memset(&l, 0, sizeof(l));
    l.layerCount = 1;
    l.layers[0].gridW = 64;       /* 1024 x 640 px */
    l.layers[0].gridH = 40;
    l.layers[0].stride = 64;
    l.layers[0].cellBytes = 2;
    int left, top;
    /* deep inside: the frame stays centred in a 400x240 picture */
    l.x = 400; l.y = 200;
    left = 80; top = 40;
    wide_clamp_frame(&l, 400, 240, &left, &top);
    CHECK(left == 80 && top == 40);
    /* the game's camera at the map's top-left corner: the frame sits in the picture's corner */
    l.x = 0; l.y = 0;
    left = 80; top = 40;
    wide_clamp_frame(&l, 400, 240, &left, &top);
    CHECK(left == 0 && top == 0);
    /* 30 px from the left edge: the picture is stuck at the map's 0, the frame 30 px in */
    l.x = 30; l.y = 200;
    left = 80; top = 40;
    wide_clamp_frame(&l, 400, 240, &left, &top);
    CHECK(left == 30 && top == 40);
    /* the bottom-right corner: the frame at the picture's bottom-right */
    l.x = 1024 - 240; l.y = 640 - 160;
    left = 80; top = 40;
    wide_clamp_frame(&l, 400, 240, &left, &top);
    CHECK(left == 160 && top == 80);
    /* a map narrower than the picture stays put: the frame moves with the game's camera inside it, the black splits evenly */
    l.layers[0].gridW = 20;       /* 320 px */
    l.layers[0].stride = 20;
    l.x = 0; l.y = 0;
    left = 80; top = 40;
    wide_clamp_frame(&l, 400, 240, &left, &top);
    CHECK(left == 40 && top == 0);
    /* a border-repeating world is never clamped */
    struct WideWorld w = fixture();
    left = 80; top = 40;
    wide_clamp_frame(&w, 400, 240, &left, &top);
    CHECK(left == 80 && top == 40);
}

static void finite_grid_clamps_like_a_layered_world(void) {
    struct WideWorld w;
    memset(&w, 0, sizeof(w));
    w.gridW = 64;                 /* 1024 x 640 px */
    w.gridH = 40;
    w.borderW = 1;
    w.borderH = 1;
    w.finite = 1;
    int left, top;
    w.x = 0; w.y = 0;
    left = 80; top = 40;
    wide_clamp_frame(&w, 400, 240, &left, &top);
    CHECK(left == 0 && top == 0);
    w.x = -50; w.y = 200;         /* the game's own frame already shows border past the grid: it still sits in the picture */
    left = 80; top = 40;
    wide_clamp_frame(&w, 400, 240, &left, &top);
    CHECK(left == 0 && top == 40);
    w.finite = 0;
    w.x = 0; w.y = 0;
    left = 80; top = 40;
    wide_clamp_frame(&w, 400, 240, &left, &top);
    CHECK(left == 80 && top == 40);
    CHECK(wide_coverage(&w, 80, 40, 400, 240) == 1.0f);
    w.finite = 1;
    CHECK(wide_coverage(&w, 80, 40, 400, 240) < 1.0f);
}

static void finite_grid_is_black_outside_but_the_seam_still_sees_the_border(void) {
    struct WideWorld w = fixture();
    for (int c = 0; c < 6; c++) poke16(GRID + (uint32_t) c * 2, 1);
    for (int n = 1; n <= 6; n++) marker_metatile(METATILES + (uint32_t) n * 16, n);
    poke16(BORDER + 0, 3); poke16(BORDER + 2, 4); poke16(BORDER + 4, 5); poke16(BORDER + 6, 6);
    CHECK(wide_pixel(&w, -1, -1) == marker(6));            /* the border repeats */
    w.finite = 1;
    CHECK(wide_pixel(&w, -1, -1) == 0u);                   /* nothing outside a finite grid */
    CHECK(wide_pixel(&w, 0, 0) == marker(1));              /* inside is untouched */
    static uint32_t buf[400 * 240];
    for (int i = 0; i < 400 * 240; i++) buf[i] = 0xDEADBEEFu;
    w.x = -4; w.y = 0;
    wide_paint(&w, buf, 400, 80, 80, 40, 40);              /* the frame at (80, 40): the margins hold world x -84..-5 and 236..315 */
    CHECK(buf[48 * 400 + 0] == 0u);                        /* left of the grid: black */
    CHECK(buf[48 * 400 + 79] == 0u);
    CHECK(buf[48 * 400 + 399] == 0u);                      /* right of it too */
    CHECK(buf[0] == 0u);                                   /* above it too */
    w.finite = 0;
    wide_paint(&w, buf, 400, 80, 80, 40, 40);
    CHECK(buf[48 * 400 + 0] != 0u);                        /* infinite: the border */
}

static void overscan_limits_the_repeating_border(void) {
    struct WideWorld w = fixture();
    marker_metatile(METATILES + 3 * 16, 3);
    poke16(BORDER, 3);
    w.gridW = 64;                 /* 1024 x 640 px; the cells read as metatile 0, not undefined */
    w.gridH = 40;
    w.borderW = 1;
    w.borderH = 1;
    w.overscan = 32;
    int left, top;
    w.x = 0; w.y = 0;
    left = 80; top = 40;
    wide_clamp_frame(&w, 400, 240, &left, &top);          /* picture may reach 32 px past the map: frame moves toward the edge */
    CHECK(left == 32 && top == 32);
    CHECK(wide_coverage(&w, 80, 40, 400, 240) < 1.0f);
    w.overscan = 0;
    left = 80; top = 40;
    wide_clamp_frame(&w, 400, 240, &left, &top);          /* unlimited: not clamped */
    CHECK(left == 80 && top == 40);
    w.overscan = 32;
    static uint32_t buf[400 * 240];
    for (int i = 0; i < 400 * 240; i++) buf[i] = 0xDEADBEEFu;
    wide_paint(&w, buf, 400, 80, 80, 40, 40);             /* world x -80..-1 on the left: past 32 px it is black */
    CHECK(buf[48 * 400 + 0] == 0u);                       /* x -80 */
    CHECK(buf[48 * 400 + 47] == 0u);                      /* x -33 */
    CHECK(buf[48 * 400 + 48] == marker(3));               /* x -32: border */
    CHECK(buf[0] == 0u);
}

static void finite_world_is_black_where_cells_are_undefined(void) {
    struct WideWorld w = fixture();
    for (int c = 0; c < 6; c++) poke16(GRID + (uint32_t) c * 2, 1);
    poke16(GRID + 0 * 2, 0x3FF);                           /* cell (0,0) undefined: padding */
    for (int n = 1; n <= 6; n++) marker_metatile(METATILES + (uint32_t) n * 16, n);
    poke16(BORDER + 0, 3); poke16(BORDER + 2, 4); poke16(BORDER + 4, 5); poke16(BORDER + 6, 6);
    w.undefinedId = 0x3FF;
    CHECK(wide_pixel(&w, 0, 0) == marker(3));              /* the game draws the border there */
    w.finite = 1;
    CHECK(wide_pixel(&w, 0, 0) == 0u);
    CHECK(wide_pixel(&w, 16, 0) == marker(1));
    /* the bounds are the defined cells (here the grid's 3x2: a map of 48x32 px, narrower than the picture, so it stays put) */
    w.x = 16; w.y = 0;
    int left = 80, top = 40;
    wide_clamp_frame(&w, 400, 240, &left, &top);
    CHECK(left == 160);
    CHECK(wide_coverage(&w, 80, 40, 400, 240) < 0.1f);
    /* a column of padding on the left is not part of the map: with 4 columns the view starts at the second one */
    struct WideWorld p = fixture();
    p.gridW = 64; p.gridH = 40;
    p.undefinedId = 0x3FF;
    p.finite = 1;
    for (int cy = 0; cy < 40; cy++)
        for (int cx = 0; cx < 64; cx++) poke16(GRID + ((uint32_t) cy * 64 + (uint32_t) cx) * 2, cx < 7 ? 0x3FF : 1);
    p.x = 7 * 16 - 100;                                    /* the frame hangs 100 px into the padding */
    p.y = 200;
    left = 80; top = 40;
    wide_clamp_frame(&p, 400, 240, &left, &top);
    CHECK(left == 0);                                      /* flush against the picture's left edge: no margin into the padding */
    p.x = 7 * 16 + 300;
    left = 80; top = 40;
    wide_clamp_frame(&p, 400, 240, &left, &top);
    CHECK(left == 80);
}

static void hysteresis_hides_after_four_bad_frames(void) {
    struct WideGate g = {0};
    for (int i = 0; i < 8; i++) wide_gate_step(&g, true, 0.95f);
    CHECK(g.shown);
    CHECK(wide_gate_step(&g, true, 0.5f));
    CHECK(wide_gate_step(&g, true, 0.5f));
    CHECK(wide_gate_step(&g, true, 0.5f));
    CHECK(!wide_gate_step(&g, true, 0.5f));
}

static void hysteresis_shows_after_four_good_frames(void) {
    struct WideGate g = {0};
    for (int i = 0; i < 3; i++) CHECK(!wide_gate_step(&g, true, 0.95f));
    CHECK(wide_gate_step(&g, true, 0.95f));
    struct WideGate h = {0};
    for (int i = 0; i < 3; i++) wide_gate_step(&h, true, 0.95f);
    wide_gate_step(&h, true, 0.5f);      /* a bad frame restarts the count */
    for (int i = 0; i < 3; i++) CHECK(!wide_gate_step(&h, true, 0.95f));
    CHECK(wide_gate_step(&h, true, 0.95f));
}

static void no_world_hides_at_once(void) {
    struct WideGate g = {0};
    for (int i = 0; i < 8; i++) wide_gate_step(&g, true, 0.95f);
    CHECK(g.shown);
    CHECK(!wide_gate_step(&g, false, 0.0f));
    CHECK(!g.shown);
}

/* A two-layer world: own grids with a 64-cell stride, 4-entry metatiles, special cells. */
static void layered_world_top_over_bottom_and_black_outside(void) {
    fake_reset(GBADUO_BUS_GBA);
    enum { TOPG = 0x02010000u, BOTG = 0x02020000u, TOPM = 0x02030000u, BOTM = 0x02031000u, SPECIAL = 0x02032000u };
    poke16(PALETTE, 0x7C00);                 /* backdrop: blue */
    poke16(PALETTE + 2, 0x001F);             /* index 1: red */
    poke16(PALETTE + 4, 0x03E0);             /* index 2: green */
    for (int i = 0; i < 32; i++) poke8(TILES + 1 * 32 + (uint32_t) i, 0x11);   /* tile 1: all index 1 */
    for (int i = 0; i < 32; i++) poke8(TILES + 2 * 32 + (uint32_t) i, 0);
    poke8(TILES + 2 * 32, 0x02);             /* tile 2: pixel (0,0) is index 2 */
    poke16(BOTG, 5);                         /* bottom (0,0): metatile 5 */
    poke16(BOTG + 64 * 2, 6);                /* bottom (0,1) sits one row down: stride 64 */
    poke16(TOPG, 0x4001);                    /* top (0,0): special cell 1 */
    poke16(SPECIAL + 2, 7);                  /* special 1 -> metatile 7 */
    poke16(BOTM + 5 * 8, 1);                 /* metatile 5 bottom TL: tile 1 */
    poke16(BOTM + 6 * 8 + 6, 1 | (2 << 12)); /* metatile 6 BR: tile 1, bank 2 */
    poke16(TOPM + 7 * 8, 2);                 /* top metatile 7 TL: tile 2 */
    poke16(PALETTE + (2 * 16 + 1) * 2, 0x7FFF);
    struct WideWorld w = {
        .palette = PALETTE, .layerCount = 2,
        .layers = {
            { .grid = BOTG, .stride = 64, .gridW = 2, .gridH = 2, .idMask = 0x7FF, .metatiles = BOTM, .tiles = TILES },
            { .grid = TOPG, .stride = 64, .gridW = 2, .gridH = 2, .idMask = 0x7FF, .special = SPECIAL, .metatiles = TOPM, .tiles = TILES },
        },
    };
    CHECK(wide_world_valid(&w));
    CHECK(wide_pixel(&w, 0, 0) == wide_color(0x03E0));   /* the top layer's index 2 */
    CHECK(wide_pixel(&w, 1, 0) == wide_color(0x001F));   /* top is colour 0 there: the bottom layer shows */
    CHECK(wide_pixel(&w, 8, 0) == wide_color(0x7C00));   /* both empty: backdrop */
    CHECK(wide_pixel(&w, 8 + 0, 16 + 8) == wide_color(0x7FFF)); /* second row of the bottom grid, BR, bank 2 */
    CHECK(wide_pixel(&w, -1, 0) == 0u);                  /* outside the grids: black */
    CHECK(wide_pixel(&w, 0, 32) == 0u);
    uint32_t buf[400 * 160];
    wide_paint(&w, buf, 400, 80, 80, 0, 0);
    CHECK(buf[0 * 400 + 80 + 240 + 0] == 0u);            /* right margin of row 0: black, nothing there */
    for (int row = 0; row < 3; row++) CHECK(buf[(size_t) row * 400 + 79] == 0u);
    struct WideWorld shifted = w;
    shifted.x = 80;                                       /* world x 0 is the left margin's last column + 1 */
    wide_paint(&shifted, buf, 400, 80, 0, 0, 0);
    CHECK(buf[0] == wide_pixel(&shifted, 0, 0));
    w.layerCount = WIDE_MAX_LAYERS + 1;
    CHECK(!wide_world_valid(&w));
    w.layerCount = 2;
    w.layers[1].cellBytes = 3;
    CHECK(!wide_world_valid(&w));                        /* a cell is 2 or 4 bytes */
}

/* Three layers of u32 cells: the id is the cell's low bits (idMask), its other bits are ignored. */
static void three_layers_of_u32_cells(void) {
    fake_reset(GBADUO_BUS_GBA);
    enum { G0 = 0x02010000u, G1 = 0x02011000u, G2 = 0x02012000u, MT = 0x02020000u, TILES = 0x06000000u };
    poke16(PALETTE, 0x7C00);
    poke16(PALETTE + 2, 0x001F);
    poke16(PALETTE + 4, 0x03E0);
    poke16(PALETTE + 6, 0x7FFF);
    for (int i = 0; i < 32; i++) poke8(TILES + 1 * 32 + (uint32_t) i, 0x11);
    for (int i = 0; i < 32; i++) poke8(TILES + 2 * 32 + (uint32_t) i, 0x22);
    for (int i = 0; i < 32; i++) poke8(TILES + 3 * 32 + (uint32_t) i, 0x33);
    for (int i = 0; i < 4; i++) { poke16(MT + 1 * 8 + (uint32_t) i * 2, 1); poke16(MT + 2 * 8 + (uint32_t) i * 2, 2); poke16(MT + 3 * 8 + (uint32_t) i * 2, 3); }
    poke16(G0, 1); poke16(G0 + 2, 0x0100);               /* cell (0,0): u32 0x01000001: id 1, junk above */
    poke16(G1, 2); poke16(G1 + 2, 0x0300);               /* the middle layer shows id 2 over it */
    poke16(G2 + 4, 3);                                   /* the top layer covers only cell (1,0) */
    struct WideWorld w = {
        .palette = PALETTE, .layerCount = 3,
        .layers = {
            { .grid = G0, .cellBytes = 4, .stride = 4, .gridW = 2, .gridH = 1, .idMask = 0xFFF, .metatiles = MT, .tiles = TILES },
            { .grid = G1, .cellBytes = 4, .stride = 4, .gridW = 2, .gridH = 1, .idMask = 0xFFF, .metatiles = MT, .tiles = TILES },
            { .grid = G2, .cellBytes = 4, .stride = 4, .gridW = 2, .gridH = 1, .idMask = 0xFFF, .metatiles = MT, .tiles = TILES },
        },
    };
    CHECK(wide_world_valid(&w));
    CHECK(wide_pixel(&w, 0, 0) == wide_color(0x03E0));   /* middle layer (index 2) over the bottom one (index 1) */
    CHECK(wide_pixel(&w, 16, 0) == wide_color(0x7FFF));  /* top layer, index 3, over empty cells */
    CHECK(wide_pixel(&w, 32, 0) == 0u);                  /* outside the grids: black */
    uint32_t buf[400 * 160];
    w.x = 80;                                            /* world x 0 is the left margin's first column */
    wide_paint(&w, buf, 400, 80, 0, 0, 0);
    CHECK(buf[0] == wide_pixel(&w, 0, 0) && buf[16] == wide_pixel(&w, 16, 0)); /* the painted row reads the same u32 cells */
}

/* idShift: cells that hold a scaled id (Fire Emblem stores metatile * 4) read the right metatile. */
static void layer_id_shift_divides_scaled_cells(void) {
    fake_reset(GBADUO_BUS_GBA);
    enum { G = 0x02010000u, MT = 0x02020000u, TILES = 0x06000000u };
    poke16(PALETTE + 2, 0x001F);
    poke16(PALETTE + 4, 0x03E0);
    for (int i = 0; i < 32; i++) { poke8(TILES + 1 * 32 + (uint32_t) i, 0x11); poke8(TILES + 2 * 32 + (uint32_t) i, 0x22); }
    for (int i = 0; i < 4; i++) { poke16(MT + 1 * 8 + (uint32_t) i * 2, 1); poke16(MT + 2 * 8 + (uint32_t) i * 2, 2); }
    poke16(G, 1 * 4);                                    /* cell (0,0): metatile 1, stored as 4 */
    poke16(G + 2, 2 * 4);                                /* cell (1,0): metatile 2, stored as 8 */
    struct WideWorld w = {
        .palette = PALETTE, .layerCount = 1,
        .layers = { { .grid = G, .stride = 2, .gridW = 2, .gridH = 1, .idMask = 0xFFFF, .idShift = 2, .metatiles = MT, .tiles = TILES } },
    };
    CHECK(wide_world_valid(&w));
    CHECK(wide_pixel(&w, 0, 0) == wide_color(0x001F));   /* 4 >> 2 = metatile 1: index 1 */
    CHECK(wide_pixel(&w, 16, 0) == wide_color(0x03E0));  /* 8 >> 2 = metatile 2: index 2 */
    w.layers[0].idShift = 0;
    CHECK(wide_pixel(&w, 0, 0) != wide_color(0x001F));   /* unshifted, 4 reads metatile 4 instead */
    w.layers[0].idShift = 5;
    CHECK(!wide_world_valid(&w));                        /* 0 to 4 only */
    w.layers[0].idShift = -1;
    CHECK(!wide_world_valid(&w));
}

/* Markers: drawn in the margins, never over the game's frame, clipped to the picture. */
static void markers_paint_only_outside_the_frame(void) {
    fake_reset(GBADUO_BUS_GBA);
    poke16(PALETTE, 0x7C00);
    solid_tile(1, 0);
    for (int i = 0; i < 4; i++) { poke16(METATILES + 8 + (uint32_t) i * 2, 1); poke16(METATILES + (uint32_t) i * 2, 1); }
    for (int i = 0; i < 64; i++) poke16(GRID + (uint32_t) i * 2, 0);
    struct WideWorld w = {
        .x = 0, .y = 0, .grid = GRID, .gridW = 8, .gridH = 8, .idMask = 0x3FF,
        .border = BORDER, .borderW = 1, .borderH = 1, .undefinedId = -1,
        .metatiles = METATILES, .metatiles2 = METATILES2, .split = 512, .tiles = TILES, .palette = PALETTE,
    };
    poke16(BORDER, 0);
    enum { STR = 400, LEFT = 80, TOP = 0 };
    static uint32_t buf[STR * 160];
    wide_paint(&w, buf, STR, LEFT, 80, TOP, 0);
    uint32_t plain = buf[10 * STR + 20];
    w.markerCount = 3;
    w.markers[0] = (struct WideMarker){ .x = -40, .y = 50, .kind = WIDE_MARKER_TRAINER }; /* in the left margin */
    w.markers[1] = (struct WideMarker){ .x = 100, .y = 80, .kind = WIDE_MARKER_NPC };     /* inside the frame: hidden */
    w.markers[2] = (struct WideMarker){ .x = -2, .y = 50, .kind = WIDE_MARKER_WARP };      /* straddles the frame's left edge */
    for (int i = 0; i < STR * 160; i++) buf[i] = 0xDEADBEEFu;
    wide_paint(&w, buf, STR, LEFT, 80, TOP, 0);
    CHECK(buf[50 * STR + (LEFT - 40)] == wide_color(0x001F));                         /* the trainer's centre is red */
    CHECK(buf[50 * STR + (LEFT - 40) + 4] == wide_color(0x0842));                      /* its outline */
    CHECK(buf[50 * STR + (LEFT - 40) + 6] == plain);                                  /* beyond the icon: the world */
    CHECK(buf[80 * STR + LEFT + 100] == 0xDEADBEEFu);                                 /* the frame is the core's: untouched */
    CHECK(buf[50 * STR + (LEFT - 2)] == wide_color(0x7FE0));                           /* the warp's left half is in the margin */
    CHECK(buf[50 * STR + (LEFT - 2) + 2] == 0xDEADBEEFu);                              /* its right half is over the frame: not drawn */
    w.markers[0] = (struct WideMarker){ .x = -100000, .y = 50, .kind = WIDE_MARKER_ITEM }; /* far off the picture */
    w.markers[1].kind = 99;                                                            /* unknown kind: skipped */
    wide_paint(&w, buf, STR, LEFT, 80, TOP, 0);
    CHECK(buf[50 * STR + 0] == plain);
}

/* A sprite marker: tiles in 1D order, index 0 clear, mirrored by hflip, never over the frame. */
static void sprite_markers_draw_their_tiles(void) {
    fake_reset(GBADUO_BUS_GBA);
    enum { SPR = 0x02008000u, SPRPAL = 0x02009000u, STR = 400, LEFT = 80 };
    poke16(PALETTE, 0x7C00);
    solid_tile(1, 0);
    for (int i = 0; i < 4; i++) { poke16(METATILES + 8 + (uint32_t) i * 2, 1); poke16(METATILES + (uint32_t) i * 2, 1); }
    for (int i = 0; i < 64; i++) poke16(GRID + (uint32_t) i * 2, 0);
    poke16(BORDER, 0);
    poke16(SPRPAL + 2, 0x001F);                            /* index 1 red */
    poke16(SPRPAL + 4, 0x03E0);                            /* index 2 green */
    /* A 16x16 sprite = 4 tiles (TL, TR, BL, BR). TL: pixel (0,0) index 1, (1,0) index 2; the rest clear. */
    poke8(SPR, 0x21);
    for (int i = 1; i < 128; i++) poke8(SPR + (uint32_t) i, 0);
    poke8(SPR + 32, 0x01);                                 /* TR: pixel (0,0) of the right half (sprite x 8) index 1 */
    struct WideWorld w = {
        .x = 0, .y = 0, .grid = GRID, .gridW = 8, .gridH = 8, .idMask = 0x3FF,
        .border = BORDER, .borderW = 1, .borderH = 1, .undefinedId = -1,
        .metatiles = METATILES, .metatiles2 = METATILES2, .split = 512, .tiles = TILES, .palette = PALETTE,
    };
    static uint32_t buf[STR * 160];
    wide_paint(&w, buf, STR, LEFT, 80, 0, 0);
    uint32_t plain = buf[100 * STR + 20];
    w.markerCount = 1;
    w.markers[0] = (struct WideMarker){ .x = -60, .y = 100, .kind = 1, .tiles = SPR, .palette = SPRPAL, .w = 16, .h = 16 };
    wide_paint(&w, buf, STR, LEFT, 80, 0, 0);
    int ox = LEFT - 60, oy = 100;
    CHECK(buf[oy * STR + ox] == wide_color(0x001F));        /* index 1 */
    CHECK(buf[oy * STR + ox + 1] == wide_color(0x03E0));    /* index 2 */
    CHECK(buf[oy * STR + ox + 2] == plain);                 /* index 0: the world shows through */
    CHECK(buf[oy * STR + ox + 8] == wide_color(0x001F));    /* the second tile of the row */
    w.markers[0].hflip = 1;
    wide_paint(&w, buf, STR, LEFT, 80, 0, 0);
    CHECK(buf[oy * STR + ox + 15] == wide_color(0x001F));   /* mirrored: sprite x 0 is now the right edge */
    CHECK(buf[oy * STR + ox + 14] == wide_color(0x03E0));
    CHECK(buf[oy * STR + ox + 7] == wide_color(0x001F));    /* sprite x 8 lands at 7 */
    w.markers[0].hflip = 0;
    w.markers[0].x = -8;                                    /* half over the frame (x 0 is the frame's left edge) */
    for (int i = 0; i < STR * 160; i++) buf[i] = 0xDEADBEEFu;
    wide_paint(&w, buf, STR, LEFT, 80, 0, 0);
    CHECK(buf[oy * STR + (LEFT - 8) + 0] == wide_color(0x001F));
    CHECK(buf[oy * STR + (LEFT - 8) + 8] == 0xDEADBEEFu);   /* the part over the frame is left alone */
    w.markers[0].w = 12;                                    /* not a multiple of 8: skipped */
    wide_paint(&w, buf, STR, LEFT, 80, 0, 0);
}

/* ---- chunked 8bpp tilemap layers (Webfoot's DBZ games) ---- */

enum { CHUNK_RAW = 0x02010000u, CHUNK_PACK = 0x02020000u, CHUNK_TABLE = 0x02030000u };

static void poke32(uint32_t a, uint32_t v) { poke16(a, (uint16_t) v); poke16(a + 2, (uint16_t) (v >> 16)); }

/* A bit writer for packed chunks: bits MSB first into u32 words stored at [addr]. */
struct BitWriter { uint32_t addr, cur; int n, words; };
static void bw_bit(struct BitWriter* b, int bit) {
    b->cur = (b->cur << 1) | (uint32_t) (bit & 1);
    if (++b->n == 32) {
        poke32(b->addr + (uint32_t) b->words * 4u, b->cur);
        b->words++;
        b->n = 0;
        b->cur = 0;
    }
}
static void bw_bits(struct BitWriter* b, uint32_t v, int n) { for (int i = n - 1; i >= 0; i--) bw_bit(b, (int) (v >> i) & 1); }
static void bw_flush(struct BitWriter* b) { while (b->n != 0) bw_bit(b, 0); }

/* 8bpp tile [t] filled with palette index [t]; palette index t = marker_color(t). */
static void solid_tile8(int t) {
    for (int i = 0; i < 64; i++) poke8(TILES + (uint32_t) t * 64 + (uint32_t) i, (uint8_t) t);
    poke16(PALETTE + (uint32_t) t * 2, marker_color(t + 20));
}

/* A chunk header + packed data at [at]: kind 1, 2048 bytes. */
static void packed_header(uint32_t at) { poke32(at, 1); poke32(at + 4, 2048); }

static struct WideWorld chunk_world(uint32_t table, int w, int h, int codec, int dx, int dy) {
    struct WideWorld world;
    memset(&world, 0, sizeof(world));
    world.palette = PALETTE;
    world.layerCount = 1;
    world.layers[0] = (struct WideLayer){ .tileMode = 1, .chunks = table, .chunkW = w, .chunkH = h, .chunkCodec = codec,
                                          .tiles = TILES, .dx = dx, .dy = dy };
    return world;
}

static void raw_chunk_layer_reads_entries_through_the_table(void) {
    fake_reset(GBADUO_BUS_GBA);
    for (int t = 1; t <= 8; t++) solid_tile8(t);
    /* chunk (0,0): entry k = tile 1 + k % 3, chunk (1,0): every entry tile 5 h-flipped; chunk (0,1) has no pointer */
    for (int k = 0; k < 1024; k++) poke16(CHUNK_RAW + (uint32_t) k * 2, (uint16_t) (1 + k % 3));
    for (int k = 0; k < 1024; k++) poke16(CHUNK_RAW + 0x800 + (uint32_t) k * 2, (uint16_t) (5 | 0x400));
    poke32(CHUNK_TABLE, CHUNK_RAW);
    poke32(CHUNK_TABLE + 4, CHUNK_RAW + 0x800);
    poke32(CHUNK_TABLE + 8, 0);
    poke32(CHUNK_TABLE + 12, CHUNK_RAW);
    struct WideWorld w = chunk_world(CHUNK_TABLE, 2, 2, 0, 0, 0);
    CHECK(wide_world_valid(&w));
    CHECK(wide_pixel(&w, 0, 0) == wide_color(marker_color(21)));      /* tile 1 */
    CHECK(wide_pixel(&w, 8, 0) == wide_color(marker_color(22)));      /* tile 2 */
    CHECK(wide_pixel(&w, 16, 0) == wide_color(marker_color(23)));
    CHECK(wide_pixel(&w, 24, 0) == wide_color(marker_color(21)));
    CHECK(wide_pixel(&w, 8, 8) == wide_color(marker_color(20 + 1 + 33 % 3)));
    CHECK(wide_pixel(&w, 256, 0) == wide_color(marker_color(25)));    /* chunk (1,0) */
    CHECK(wide_pixel(&w, 100, 300) == 0);                             /* chunk (0,1): no pointer, outside */
    CHECK(wide_pixel(&w, 300, 300) == wide_color(marker_color(21)));  /* chunk (1,1) shares chunk (0,0)'s data */
    CHECK(wide_pixel(&w, -1, 0) == 0 && wide_pixel(&w, 512, 0) == 0 && wide_pixel(&w, 0, 512) == 0); /* beyond the map */
}

static void packed_chunk_with_raw_blocks_and_a_repeat_match_decodes(void) {
    fake_reset(GBADUO_BUS_GBA);
    wide_forget();
    for (int t = 1; t <= 8; t++) solid_tile8(t);
    /* chunk A: bit pattern 0,0,1 + get(4) = 0 + bit 1 = eight 256-byte raw blocks; entry k = tile 1 + k % 4 */
    packed_header(CHUNK_PACK);
    struct BitWriter b = { CHUNK_PACK + 8, 0, 0, 0 };
    bw_bits(&b, 0, 1); bw_bits(&b, 0, 1); bw_bits(&b, 1, 1); bw_bits(&b, 0, 4); bw_bits(&b, 1, 1);
    for (int blk = 0; blk < 8; blk++) {
        for (int i = 0; i < 256; i++) {
            int byte = blk * 256 + i;
            bw_bits(&b, (byte & 1) ? 0 : (uint32_t) (1 + (byte / 2) % 4), 8);
        }
        bw_bit(&b, blk < 7);
    }
    bw_flush(&b);
    /* chunk B: four literal bytes (1,0,2,0), then short matches (distance 4, length 5) repeat them: tiles 1,2,1,2... */
    packed_header(CHUNK_PACK + 0x1000);
    struct BitWriter c = { CHUNK_PACK + 0x1000 + 8, 0, 0, 0 };
    const int lits[4] = { 1, 0, 2, 0 };
    for (int i = 0; i < 4; i++) { bw_bit(&c, 1); bw_bits(&c, (uint32_t) lits[i], 8); }
    for (int i = 0; i < 2048 / 5 + 1; i++) { bw_bit(&c, 0); bw_bit(&c, 0); bw_bit(&c, 0); bw_bits(&c, 4, 7); bw_bits(&c, 3, 2); }
    bw_flush(&c);
    poke32(CHUNK_TABLE, CHUNK_PACK);
    poke32(CHUNK_TABLE + 4, CHUNK_PACK + 0x1000);
    struct WideWorld w = chunk_world(CHUNK_TABLE, 2, 1, 1, 0, 0);
    CHECK(wide_pixel(&w, 0, 0) == wide_color(marker_color(21)));      /* A: k 0 -> tile 1 */
    CHECK(wide_pixel(&w, 8, 0) == wide_color(marker_color(22)));      /* k 1 -> tile 2 */
    CHECK(wide_pixel(&w, 24, 0) == wide_color(marker_color(24)));     /* k 3 -> tile 4 */
    CHECK(wide_pixel(&w, 32, 0) == wide_color(marker_color(21)));     /* k 4 -> tile 1 again */
    CHECK(wide_pixel(&w, 256, 0) == wide_color(marker_color(21)));    /* B: tile 1 */
    CHECK(wide_pixel(&w, 264, 0) == wide_color(marker_color(22)));    /* tile 2 */
    CHECK(wide_pixel(&w, 256 + 8 * 31, 8 * 31) == wide_color(marker_color(21 + ((31 * 32 + 31) % 2))));  /* a late cell, made by matches */
    /* a stream that cannot be a chunk draws nothing instead of garbage */
    poke32(CHUNK_PACK + 0x2000, 1);
    poke32(CHUNK_PACK + 0x2004, 2048);
    for (int i = 0; i < 64; i++) poke32(CHUNK_PACK + 0x2008 + (uint32_t) i * 4, 0xFFFFFFFFu);
    poke32(CHUNK_TABLE, CHUNK_PACK + 0x2000);
    wide_forget();
    CHECK(wide_pixel(&w, 0, 0) == 0);
    poke32(CHUNK_PACK + 0x3000, 7);   /* an unknown kind */
    poke32(CHUNK_PACK + 0x3004, 2048);
    poke32(CHUNK_TABLE, CHUNK_PACK + 0x3000);
    wide_forget();
    CHECK(wide_pixel(&w, 0, 0) == 0);
    poke32(CHUNK_PACK + 0x4000, 0);   /* kind 0: raw data after the header */
    poke32(CHUNK_PACK + 0x4004, 2048);
    for (int k = 0; k < 1024; k++) poke16(CHUNK_PACK + 0x4008 + (uint32_t) k * 2, 6);
    poke32(CHUNK_TABLE, CHUNK_PACK + 0x4000);
    wide_forget();
    CHECK(wide_pixel(&w, 0, 0) == wide_color(marker_color(26)));
}

static void chunk_layer_is_transparent_at_index_zero_over_a_lower_layer(void) {
    fake_reset(GBADUO_BUS_GBA);
    for (int t = 1; t <= 8; t++) solid_tile8(t);
    for (int k = 0; k < 1024; k++) poke16(CHUNK_RAW + (uint32_t) k * 2, 3);              /* ground: tile 3 */
    for (int k = 0; k < 1024; k++) poke16(CHUNK_RAW + 0x800 + (uint32_t) k * 2, (uint16_t) (k % 2 ? 0 : 4)); /* top: tile 4 or clear (tile 0 = index 0) */
    poke32(CHUNK_TABLE, CHUNK_RAW);
    poke32(CHUNK_TABLE + 0x100, CHUNK_RAW + 0x800);
    struct WideWorld w = chunk_world(CHUNK_TABLE, 1, 1, 0, 0, 0);
    w.layerCount = 2;
    w.layers[1] = w.layers[0];
    w.layers[1].chunks = CHUNK_TABLE + 0x100;
    CHECK(wide_pixel(&w, 0, 0) == wide_color(marker_color(24)));   /* top, tile 4 */
    CHECK(wide_pixel(&w, 8, 0) == wide_color(marker_color(23)));   /* top is index 0: the ground, tile 3 */
}

static void chunk_layer_offsets_move_the_layer_and_spans_follow_them(void) {
    fake_reset(GBADUO_BUS_GBA);
    for (int t = 1; t <= 8; t++) solid_tile8(t);
    for (int k = 0; k < 1024; k++) poke16(CHUNK_RAW + (uint32_t) k * 2, (uint16_t) (1 + k % 7));
    poke32(CHUNK_TABLE, CHUNK_RAW);
    poke32(CHUNK_TABLE + 4, CHUNK_RAW);
    poke32(CHUNK_TABLE + 8, CHUNK_RAW);
    poke32(CHUNK_TABLE + 12, CHUNK_RAW);
    struct WideWorld w = chunk_world(CHUNK_TABLE, 2, 2, 0, 3, 5);   /* layer pixel = world pixel + (3, 5) */
    w.layerCount = 2;
    w.layers[1] = w.layers[0];
    w.layers[1].dx = 0;
    w.layers[1].dy = 0;
    w.x = -17;
    w.y = 7;
    /* a wide_paint over a picture must equal the one-pixel reference, whatever each layer's alignment */
    enum { PW = 400, PH = 160 };
    static uint32_t buf[PW * PH];
    int left = 80, right = 80;
    wide_paint(&w, buf, PW, left, right, 0, 0);
    int bad = 0;
    for (int y = 0; y < PH; y++) {
        for (int x = 0; x < PW; x++) {
            if (x >= left && x < left + 240) continue;
            if (buf[y * PW + x] != wide_pixel(&w, w.x - left + x, w.y + y)) bad++;
        }
    }
    CHECK(bad == 0);
    /* the layer pixel is world + dx: at world x -3 the layer's own x is 0, so its first tile is tile 1 */
    struct WideWorld one = chunk_world(CHUNK_TABLE, 2, 2, 0, 3, 5);
    CHECK(wide_pixel(&one, -3, -5) == wide_color(marker_color(21)));
    CHECK(wide_pixel(&one, -4, -5) == 0);
    CHECK(wide_pixel(&one, 4, -5) == wide_color(marker_color(21)));  /* layer x 7 is still the first tile */
    CHECK(wide_pixel(&one, 5, -5) == wide_color(marker_color(22)));  /* layer x 8 starts the second */
}

static void chunk_layer_validity_bounds_and_clamp(void) {
    fake_reset(GBADUO_BUS_GBA);
    struct WideWorld w = chunk_world(CHUNK_TABLE, 2, 2, 0, 64, 32);
    CHECK(wide_world_valid(&w));
    w.layers[0].chunkW = 0;
    CHECK(!wide_world_valid(&w));
    w.layers[0].chunkW = 65;
    CHECK(!wide_world_valid(&w));
    w.layers[0].chunkW = 2;
    w.layers[0].chunkCodec = 2;
    CHECK(!wide_world_valid(&w));
    w.layers[0].chunkCodec = 1;
    CHECK(wide_world_valid(&w));
    /* the map is the ground layer's chunks, from world (-64, -32) to (448, 480): near an edge the frame slides */
    w.x = -50;
    w.y = 100;
    int left = 80, top = 0;
    wide_clamp_frame(&w, 400, 160, &left, &top);
    CHECK(left == 14);                    /* the picture's left edge stops at -64 */
    CHECK(top == 0);
    w.x = 200;
    left = 80;
    wide_clamp_frame(&w, 400, 160, &left, &top);
    CHECK(left == 200 - (448 - 400));     /* its right edge stops at 448 */
    w.x = 100;
    CHECK(wide_coverage(&w, 80, 0, 400, 160) == 1.0f);
}

static void explicit_bounds_override_the_grids_for_clamp_and_coverage(void) {
    fake_reset(GBADUO_BUS_GBA);
    struct WideWorld w = chunk_world(CHUNK_TABLE, 5, 5, 1, 192, 192);   /* the chunk grid spans -192..1088 */
    w.hasBounds = 1;
    w.bx0 = 0; w.by0 = 0; w.bx1 = 1024; w.by1 = 1024;
    w.x = 20; w.y = 500;
    int left = 120, top = 80;
    wide_clamp_frame(&w, 480, 320, &left, &top);
    CHECK(left == 20);                    /* the picture's left edge stops at world 0 */
    CHECK(top == 80);
    w.x = 700; w.y = 800;
    left = 120; top = 80;
    wide_clamp_frame(&w, 480, 320, &left, &top);
    CHECK(left == 700 - (1024 - 480));    /* right edge stops at 1024 */
    CHECK(top == 800 - (1024 - 320));
    w.x = 20; w.y = 500;
    CHECK(wide_coverage(&w, 120, 80, 480, 320) < 1.0f);   /* margin to the left of 0 is outside */
    w.x = 400; w.y = 400;
    CHECK(wide_coverage(&w, 120, 80, 480, 320) == 1.0f);
    w.hasBounds = 0;
    w.x = 20; w.y = 500;
    left = 120; top = 80;
    wide_clamp_frame(&w, 480, 320, &left, &top);
    CHECK(left == 120);                   /* without bounds the chunk grid (from -192) allows it */
}

static void trimmed_bounds_follow_the_ground_tiles(void) {
    fake_reset(GBADUO_BUS_GBA);
    wide_forget();
    for (int t = 1; t <= 8; t++) solid_tile8(t);
    /* a 2x2 chunk map whose tiles fill only layer pixels x 40..119, y 24..87 of chunk (0,0): 10 x 8 tiles of tile 1 */
    for (int k = 0; k < 1024; k++) poke16(CHUNK_RAW + (uint32_t) k * 2, 0);
    for (int ty = 3; ty < 11; ty++) for (int tx = 5; tx < 15; tx++) poke16(CHUNK_RAW + (uint32_t) (ty * 32 + tx) * 2, 1);
    poke32(CHUNK_TABLE, CHUNK_RAW);
    struct WideWorld w = chunk_world(CHUNK_TABLE, 2, 2, 0, 16, 8);
    w.layers[0].trimBounds = 1;
    /* world bounds = layer box minus (dx, dy): x 24..104, y 16..80; the picture is bigger than that, so the map stays centred */
    w.x = 30; w.y = 20;
    int left = 120, top = 80;
    wide_clamp_frame(&w, 480, 320, &left, &top);
    CHECK(left == 30 - ((24 + 104 - 480) / 2));   /* origin = lo + (hi - lo - size) / 2 */
    CHECK(top == 20 - ((16 + 80 - 320) / 2));
    /* with the camera far away the frame is still clamped into the picture, not pinned to a corner */
    w.x = -50;
    left = 120;
    wide_clamp_frame(&w, 480, 320, &left, &top);
    CHECK(left == -50 - ((24 + 104 - 480) / 2) || left == 0);
    /* margins are black outside the map */
    enum { PW = 480, PH = 320 };
    static uint32_t buf[PW * PH];
    for (int i = 0; i < PW * PH; i++) buf[i] = 0xDEADBEEFu;
    w.x = 30; w.y = 20;
    wide_paint(&w, buf, PW, 120, 120, 80, 80);
    CHECK(buf[10 * PW + 10] == 0);        /* far corner: outside the map */
    CHECK(buf[(80 + 5) * PW + 5] == 0);   /* left margin, left of the map */
    /* layer pixel (40, 24) = world (24, 16) is the map's first tile; with the frame centred it lands at picture x 120 + (24 - 30) */
    CHECK(buf[(80 + 16 - 20 + 4) * PW + (120 + 24 - 30 + 4)] != 0xDEADBEEFu);
}

static void chunk_cache_is_cleared_by_wide_forget(void) {
    fake_reset(GBADUO_BUS_GBA);
    wide_forget();
    for (int t = 1; t <= 8; t++) solid_tile8(t);
    poke32(CHUNK_PACK, 0);
    poke32(CHUNK_PACK + 4, 2048);
    for (int k = 0; k < 1024; k++) poke16(CHUNK_PACK + 8 + (uint32_t) k * 2, 2);
    poke32(CHUNK_TABLE, CHUNK_PACK);
    struct WideWorld w = chunk_world(CHUNK_TABLE, 1, 1, 1, 0, 0);
    CHECK(wide_pixel(&w, 0, 0) == wide_color(marker_color(22)));
    for (int k = 0; k < 1024; k++) poke16(CHUNK_PACK + 8 + (uint32_t) k * 2, 3);
    CHECK(wide_pixel(&w, 0, 0) == wide_color(marker_color(22)));    /* a packed chunk is ROM: the kept copy is used */
    wide_forget();
    CHECK(wide_pixel(&w, 0, 0) == wide_color(marker_color(23)));    /* a new game: read again */
}

int main(void) {
    color_matches_mgba();
    pixel_reads_bottom_then_top_then_backdrop();
    flips_and_banks();
    outside_grid_uses_border();
    border_shift_moves_the_block_origin();
    undefined_cell_uses_border();
    split_selects_second_table();
    layered_world_top_over_bottom_and_black_outside();
    three_layers_of_u32_cells();
    layer_id_shift_divides_scaled_cells();
    paint_fills_only_margins();
    markers_paint_only_outside_the_frame();
    sprite_markers_draw_their_tiles();
    sprite_markers_8bpp_and_vflip();
    paint_equals_pixelwise_reference();
    paint_survives_garbage_addresses();
    seam_match_is_one_on_its_own_picture();
    paint_fills_all_four_sides();
    seam_match_counts_the_edge_rows();
    seam_match_tolerates_a_tint();
    coverage_is_the_share_of_margin_inside_the_map();
    clamp_frame_keeps_the_picture_inside_the_map();
    finite_grid_clamps_like_a_layered_world();
    finite_grid_is_black_outside_but_the_seam_still_sees_the_border();
    overscan_limits_the_repeating_border();
    finite_world_is_black_where_cells_are_undefined();
    hysteresis_hides_after_four_bad_frames();
    hysteresis_shows_after_four_good_frames();
    no_world_hides_at_once();
    raw_chunk_layer_reads_entries_through_the_table();
    packed_chunk_with_raw_blocks_and_a_repeat_match_decodes();
    chunk_layer_is_transparent_at_index_zero_over_a_lower_layer();
    chunk_layer_offsets_move_the_layer_and_spans_follow_them();
    chunk_layer_validity_bounds_and_clamp();
    explicit_bounds_override_the_grids_for_clamp_and_coverage();
    trimmed_bounds_follow_the_ground_tiles();
    chunk_cache_is_cleared_by_wide_forget();
    printf("wide: %d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
