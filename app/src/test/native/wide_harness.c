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
    wide_paint(&w, buf, STRIDE, 20, 20);
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
        wide_paint(&w, buf, STRIDE, LEFT, RIGHT);
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
    wide_paint(&w, buf, STRIDE, 20, 20);
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
    CHECK(fabsf(wide_seam_match(&w, buf, STRIDE, LEFT) - 1.0f) < 1e-6f);
    for (int row = 0; row < 160; row++) buf[row * STRIDE + LEFT] ^= 0x00FFFFFFu;
    CHECK(fabsf(wide_seam_match(&w, buf, STRIDE, LEFT) - (1.0f - 160.0f / 2560.0f)) < 1e-6f);
    free(buf);
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

static void hysteresis_shows_after_eight_good_frames(void) {
    struct WideGate g = {0};
    for (int i = 0; i < 7; i++) CHECK(!wide_gate_step(&g, true, 0.95f));
    CHECK(wide_gate_step(&g, true, 0.95f));
    struct WideGate h = {0};
    for (int i = 0; i < 7; i++) wide_gate_step(&h, true, 0.95f);
    wide_gate_step(&h, true, 0.5f);      /* a bad frame restarts the count */
    for (int i = 0; i < 7; i++) CHECK(!wide_gate_step(&h, true, 0.95f));
    CHECK(wide_gate_step(&h, true, 0.95f));
}

static void no_world_hides_at_once(void) {
    struct WideGate g = {0};
    for (int i = 0; i < 8; i++) wide_gate_step(&g, true, 0.95f);
    CHECK(g.shown);
    CHECK(!wide_gate_step(&g, false, 0.0f));
    CHECK(!g.shown);
}

int main(void) {
    color_matches_mgba();
    pixel_reads_bottom_then_top_then_backdrop();
    flips_and_banks();
    outside_grid_uses_border();
    border_shift_moves_the_block_origin();
    undefined_cell_uses_border();
    split_selects_second_table();
    paint_fills_only_margins();
    paint_equals_pixelwise_reference();
    paint_survives_garbage_addresses();
    seam_match_is_one_on_its_own_picture();
    hysteresis_hides_after_four_bad_frames();
    hysteresis_shows_after_eight_good_frames();
    no_world_hides_at_once();
    printf("wide: %d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
