// Desktop tests for mem.write*: the real Lua sandbox (script.c, built with
// GBADUO_HOST_TEST) and the real checked writes (memwrite.c) over a fake machine
// (fake_bus.c). Run under ASan/UBSan by run.sh.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "bus.h"
#include "fake_bus.h"
#include "script.h"

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

static void load(const char* source) {
    if (!gbaduo_script_load(source, ".")) {
        fprintf(stderr, "load failed: %s\n", gbaduo_script_last_error());
        exit(2);
    }
}

/* Runs actions[name]() and returns its JSON result (caller frees), or NULL. */
static char* action(const char* name, int may_write) {
    return gbaduo_script_action(name, NULL, NULL, 0, may_write != 0);
}

static int json_has(const char* json, const char* needle) {
    return json && strstr(json, needle) != NULL;
}

/* ---- the script all tests share ------------------------------------------------ */

static const char* SCRIPT =
    "TOP = { mem.write8(0x02000000, 1) }\n"          /* top level: refused */
    "function init() INIT = { mem.write8(0x02000001, 1) } end\n"
    "actions = {}\n"
    "function update() local ok, why = mem.write8(0x02000002, 1); return { ok = ok, why = why } end\n"
    "function actions.top() return { top = TOP[1], init = INIT[1] } end\n"
    "function actions.w8() local ok, why = mem.write8(ADDR, VALUE); return { ok = ok, why = why or '' } end\n"
    "function actions.w16() return { ok = mem.write16(ADDR, VALUE) } end\n"
    "function actions.w32() return { ok = mem.write32(ADDR, VALUE) } end\n"
    "function actions.wbytes() return { ok = mem.write(ADDR, string.rep('\\x5A', N)) } end\n"
    "function actions.readback() return { b0 = mem.u8(ADDR), h = mem.u16(ADDR), w = mem.u32(ADDR) } end\n"
    "function actions.tap()\n"
    "  local n = 0\n"
    "  for i = 0, 5 do if mem.write(0x02000100 + i * 64, string.rep('\\1', 64)) then n = n + 1 end end\n"
    "  return { n = n }\n"
    "end\n"
    "function actions.boom() mem.write8(0x02000200, 7); error('boom') end\n"
    "function actions.spin() mem.write8(0x02000201, 7); while true do end end\n"
    "function actions.keep() KEPT = function() return mem.write8(0x02000202, 9) end; return { ok = KEPT() } end\n"
    "function actions.later() return { ok = KEPT() } end\n";

/* The sandbox has no load(): reload the script with the target baked in. */
static void load_with(unsigned long address, long value, int n) {
    size_t size = strlen(SCRIPT) + 160;
    char* src = malloc(size);
    snprintf(src, size, "ADDR = %lu; VALUE = %ld; N = %d\n%s", address, value, n, SCRIPT);
    load(src);
    free(src);
}

/* ---- tests ------------------------------------------------------------------------- */

static void refused_outside_an_action(void) {
    fake_reset(GBADUO_BUS_GBA);
    load_with(0x02000010, 1, 1);

    char* tick = gbaduo_script_tick();
    CHECK(json_has(tick, "\"ok\":false"));
    CHECK(json_has(tick, "only allowed from a tap"));
    free(tick);

    char* r = action("top", 1);
    CHECK(json_has(r, "\"top\":false"));
    CHECK(json_has(r, "\"init\":false"));
    free(r);
    CHECK(fake_writes == 0);
    CHECK(!gbaduo_script_writes_open());
}

static void refused_without_permission(void) {
    fake_reset(GBADUO_BUS_GBA);
    load_with(0x02000010, 0x42, 1);
    char* r = action("w8", 0);
    CHECK(json_has(r, "\"ok\":false"));
    free(r);
    CHECK(fake_writes == 0);
    CHECK(fake_peek(0x02000010) == 0);
}

static void ram_writes_succeed_and_read_back(void) {
    fake_reset(GBADUO_BUS_GBA);
    load_with(0x02000010, 0x42, 1);
    char* r = action("w8", 1);
    CHECK(json_has(r, "\"ok\":true"));
    free(r);
    CHECK(fake_peek(0x02000010) == 0x42);

    load_with(0x03000020, 0xBEEF, 1);
    r = action("w16", 1);
    CHECK(json_has(r, "\"ok\":true"));
    free(r);
    CHECK(fake_peek(0x03000020) == 0xEF && fake_peek(0x03000021) == 0xBE);

    load_with(0x02000030, 0x12345678, 1);
    r = action("w32", 1);
    CHECK(json_has(r, "\"ok\":true"));
    free(r);
    r = action("readback", 0); /* reads need no permission */
    CHECK(json_has(r, "\"w\":305419896"));
    CHECK(json_has(r, "\"b0\":120"));
    free(r);

    load_with(0x02000040, 0, 64);
    r = action("wbytes", 1);
    CHECK(json_has(r, "\"ok\":true"));
    free(r);
    CHECK(fake_peek(0x02000040) == 0x5A && fake_peek(0x0200007F) == 0x5A);
    CHECK(!gbaduo_script_writes_open());
}

/* address, size: each must be refused, and nothing may reach the bus */
static void expect_refused(int platform, unsigned long address, int size) {
    fake_reset(platform);
    load_with(address, 0, size);
    char* r = action("wbytes", 1);
    CHECK(json_has(r, "\"ok\":false"));
    if (!json_has(r, "\"ok\":false")) fprintf(stderr, "  -> 0x%08lX +%d was allowed\n", address, size);
    free(r);
    CHECK(fake_writes == 0);
}

static void rom_io_and_other_regions_refused(void) {
    /* GBA */
    expect_refused(GBADUO_BUS_GBA, 0x00000000, 1); /* BIOS */
    expect_refused(GBADUO_BUS_GBA, 0x04000000, 2); /* I/O registers */
    expect_refused(GBADUO_BUS_GBA, 0x05000000, 2); /* palette */
    expect_refused(GBADUO_BUS_GBA, 0x06000000, 2); /* VRAM */
    expect_refused(GBADUO_BUS_GBA, 0x07000000, 2); /* OAM */
    expect_refused(GBADUO_BUS_GBA, 0x08000000, 1); /* ROM */
    expect_refused(GBADUO_BUS_GBA, 0x0E000000, 1); /* SRAM / save */
    expect_refused(GBADUO_BUS_GBA, 0x02040000, 1); /* EWRAM mirror */
    expect_refused(GBADUO_BUS_GBA, 0x0203FFFE, 4); /* runs off the end of EWRAM */
    expect_refused(GBADUO_BUS_GBA, 0x03007FFF, 2); /* runs off the end of IWRAM */
    expect_refused(GBADUO_BUS_GBA, 0xFFFFFFFF, 2); /* wraps around */
    /* GB/GBC */
    expect_refused(GBADUO_BUS_GB, 0x0100, 1); /* ROM */
    expect_refused(GBADUO_BUS_GB, 0x8000, 1); /* VRAM */
    expect_refused(GBADUO_BUS_GB, 0xA000, 1); /* cartridge RAM (the save) */
    expect_refused(GBADUO_BUS_GB, 0xE000, 1); /* echo RAM */
    expect_refused(GBADUO_BUS_GB, 0xFF00, 1); /* I/O */
    expect_refused(GBADUO_BUS_GB, 0xFFFF, 1); /* IE register */
    expect_refused(GBADUO_BUS_GB, 0x1C000, 1); /* past the 16-bit bus */
    /* nothing loaded */
    expect_refused(GBADUO_BUS_NONE, 0x02000000, 1);
}

static void gb_ram_allowed(void) {
    fake_reset(GBADUO_BUS_GB);
    load_with(0xC000, 0, 4);
    char* r = action("wbytes", 1);
    CHECK(json_has(r, "\"ok\":true"));
    free(r);
    load_with(0xFF80, 0, 1);
    r = action("wbytes", 1);
    CHECK(json_has(r, "\"ok\":true"));
    free(r);
    CHECK(fake_peek(0xC000) == 0x5A && fake_peek(0xFF80) == 0x5A);
}

static void oversized_and_negative_refused(void) {
    expect_refused(GBADUO_BUS_GBA, 0x02000000, 65); /* more than 64 bytes in one call */
    expect_refused(GBADUO_BUS_GBA, 0x02000000, 0);  /* empty */

    fake_reset(GBADUO_BUS_GBA);
    load("actions = {} function actions.neg() return { ok = mem.write8(-1, 1) } end");
    char* r = action("neg", 1);
    CHECK(json_has(r, "\"ok\":false"));
    free(r);
    CHECK(fake_writes == 0);
}

static void at_most_256_bytes_per_tap(void) {
    fake_reset(GBADUO_BUS_GBA);
    load_with(0, 0, 1);
    char* r = action("tap", 1);
    CHECK(json_has(r, "\"n\":4")); /* 4 x 64 = 256 written, the 5th and 6th refused */
    free(r);
    CHECK(fake_writes == 256);
    /* The allowance is per tap: the next one starts fresh. */
    r = action("tap", 1);
    CHECK(json_has(r, "\"n\":4"));
    free(r);
}

static void window_closes_after_an_error(void) {
    fake_reset(GBADUO_BUS_GBA);
    load_with(0, 0, 1);
    char* r = action("boom", 1);
    CHECK(r == NULL);
    CHECK(strstr(gbaduo_script_last_error(), "boom") != NULL);
    CHECK(!gbaduo_script_writes_open());
    free(r);
    unsigned before = fake_writes;
    char* tick = gbaduo_script_tick();
    CHECK(json_has(tick, "\"ok\":false"));
    free(tick);
    CHECK(fake_writes == before);
}

static void window_closes_after_the_instruction_budget(void) {
    fake_reset(GBADUO_BUS_GBA);
    load_with(0, 0, 1);
    char* r = action("spin", 1);
    CHECK(r == NULL);
    CHECK(strstr(gbaduo_script_last_error(), "instruction budget") != NULL);
    CHECK(!gbaduo_script_writes_open());
    free(r);
    char* tick = gbaduo_script_tick();
    CHECK(json_has(tick, "\"ok\":false"));
    free(tick);
}

static void a_kept_function_cant_write_later(void) {
    fake_reset(GBADUO_BUS_GBA);
    load_with(0, 0, 1);
    char* r = action("keep", 1);
    CHECK(json_has(r, "\"ok\":true"));
    free(r);
    /* Same function, called from an action without permission: refused. */
    r = action("later", 0);
    CHECK(json_has(r, "\"ok\":false"));
    free(r);
    CHECK(fake_writes == 1);
}

/* ---- gfx.tilemap ------------------------------------------------------------------ */

static void poke16(uint32_t a, uint16_t v) { gbaduo_bus_write8(a, (uint8_t) v); gbaduo_bus_write8(a + 1, (uint8_t) (v >> 8)); }

static uint32_t argb(uint16_t c) {
    return 0xFF000000u | ((uint32_t) (c & 31) << 3 << 16) | ((uint32_t) (c >> 5 & 31) << 3 << 8) | ((uint32_t) (c >> 10 & 31) << 3);
}

static void tilemap_composes_tiles_flips_and_banks(void) {
    fake_reset(GBADUO_BUS_GBA);
    const uint32_t TILES = 0x02010000, PAL = 0x02011000, MAP = 0x02012000;
    /* Tile 1: column c has colour index c (0..7) on every row: a left-to-right ramp. */
    for (int row = 0; row < 8; row++) {
        for (int c = 0; c < 8; c += 2) gbaduo_bus_write8(TILES + 32 + row * 4 + c / 2, (uint8_t) (c | (c + 1) << 4));
    }
    /* Tile 2: row r has colour index r: a top-to-bottom ramp. */
    for (int row = 0; row < 8; row++) {
        for (int c = 0; c < 4; c++) gbaduo_bus_write8(TILES + 64 + row * 4 + c, (uint8_t) (row | row << 4));
    }
    /* Banks 0 and 3: colour i = i (bank 0) or 0x7C00 | i (bank 3, blue). */
    for (int i = 0; i < 16; i++) { poke16(PAL + i * 2, (uint16_t) i); poke16(PAL + 3 * 32 + i * 2, (uint16_t) (0x7C00 | i)); }
    /* A 4-wide map; we draw the 2x2 cells starting at (1, 0). */
    poke16(MAP + 1 * 2, 1);                               /* (1,0): tile 1 */
    poke16(MAP + 2 * 2, 1 | 1 << 10);                     /* (2,0): tile 1, h-flip */
    poke16(MAP + (4 + 1) * 2, 2 | 1 << 11 | 3 << 12);     /* (1,1): tile 2, v-flip, bank 3 */
    poke16(MAP + (4 + 2) * 2, 0);                         /* (2,1): tile 0, all colour 0 */

    load("function update() return { key = gfx.tilemap{ tiles = 0x02010000, palette = 0x02011000, map = 0x02012000,"
         " stride = 4, x = 1, y = 0, width = 16, height = 16 } } end");
    char* r = gbaduo_script_tick();
    CHECK(json_has(r, "gfx:"));
    char key[32] = {0};
    if (r) sscanf(strstr(r, "gfx:"), "%12[^\"]", key);
    free(r);
    int w = 0, h = 0;
    uint32_t* px = gbaduo_script_image(key, &w, &h);
    CHECK(px != NULL && w == 16 && h == 16);
    if (px) {
        CHECK((px[0] >> 24) == 0);                 /* colour 0 is transparent */
        CHECK(px[5] == argb(5));                   /* tile 1 ramp */
        CHECK(px[8 + 0] == argb(7));               /* h-flipped: column 0 shows index 7 */
        CHECK((px[8 + 7] >> 24) == 0);             /* ... and column 7 index 0 */
        CHECK(px[8 * 16 + 0] == argb(0x7C00 | 7)); /* v-flipped bank 3: row 0 shows index 7 */
        CHECK((px[15 * 16 + 3] >> 24) == 0);       /* row 7 of it is index 0 */
        CHECK((px[8 * 16 + 8] >> 24) == 0);        /* tile 0: blank */
        free(px);
    }

    /* opaque: colour 0 is drawn too. */
    load("function update() return { key = gfx.tilemap{ tiles = 0x02010000, palette = 0x02011000, map = 0x02012000,"
         " stride = 4, x = 1, width = 8, height = 8, opaque = true } } end");
    r = gbaduo_script_tick();
    char key2[32] = {0};
    if (r && strstr(r, "gfx:")) sscanf(strstr(r, "gfx:"), "%12[^\"]", key2);
    free(r);
    px = gbaduo_script_image(key2, &w, &h);
    CHECK(px != NULL && px[0] == argb(0));
    free(px);
}

static uint32_t* image_of(const char* json, int* w, int* h);

/* Writes [n] bytes from [from] to [to] as GBA LZ77 made only of literal blocks. */
static uint32_t put_lz77(uint32_t to, uint32_t from, uint32_t n) {
    gbaduo_bus_write8(to, 0x10);
    gbaduo_bus_write8(to + 1, (uint8_t) n);
    gbaduo_bus_write8(to + 2, (uint8_t) (n >> 8));
    gbaduo_bus_write8(to + 3, (uint8_t) (n >> 16));
    uint32_t at = to + 4;
    for (uint32_t i = 0; i < n; i++) {
        if (i % 8 == 0) gbaduo_bus_write8(at++, 0); /* flags: 8 literals */
        gbaduo_bus_write8(at++, fake_peek(from + i));
    }
    return at;
}

static void tilemap_reads_lz77_tiles_and_map(void) {
    /* The same picture as above, from compressed copies of the tiles and map. */
    tilemap_composes_tiles_flips_and_banks();
    const char* raw = "gfx.tilemap{ tiles = 0x02010000, palette = 0x02011000, map = 0x02012000,"
                      " stride = 4, x = 1, width = 16, height = 16 }";
    char src[512];
    snprintf(src, sizeof(src), "function update() return { key = %s } end", raw);
    load(src);
    char* r = gbaduo_script_tick();
    int w = 0, h = 0, w2 = 0, h2 = 0;
    uint32_t* want = image_of(r, &w, &h);
    free(r);

    put_lz77(0x02020000, 0x02010000, 3 * 32);
    put_lz77(0x02021000, 0x02012000, 4 * 2 * 2);
    load("function update() return { key = gfx.tilemap{ tiles = 0x02020000, palette = 0x02011000, map = 0x02021000,"
         " stride = 4, x = 1, width = 16, height = 16, lz77 = true } } end");
    r = gbaduo_script_tick();
    uint32_t* got = image_of(r, &w2, &h2);
    free(r);
    CHECK(want != NULL && got != NULL && w2 == 16 && h2 == 16);
    if (want && got) CHECK(memcmp(want, got, 16 * 16 * 4) == 0);
    free(want);
    free(got);

    /* A map shorter than the picture needs, or data that isn't LZ77: errors. */
    const char* bad[] = {
        "gfx.tilemap{ tiles = 0x02020000, palette = 0, map = 0x02021000, stride = 4, x = 1, width = 16, height = 24, lz77 = true }",
        "gfx.tilemap{ tiles = 0x02010000, palette = 0, map = 0x02021000, stride = 4, width = 8, height = 8, lz77 = true }",
    };
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        snprintf(src, sizeof(src), "actions = {} function actions.go() %s end", bad[i]);
        load(src);
        r = action("go", 0);
        CHECK(r == NULL && strstr(gbaduo_script_last_error(), "gfx.tilemap") != NULL);
        free(r);
    }
}

/* C work counts against the instruction budget: a short loop can't hold the emu thread. */
static void c_work_is_charged_to_the_budget(void) {
    fake_reset(GBADUO_BUS_GBA);
    load("function update() for i = 1, 1000000 do mem.read(0x02000000, 65536) end return {} end");
    char* r = gbaduo_script_tick();
    CHECK(r == NULL && strstr(gbaduo_script_last_error(), "instruction budget") != NULL);
    free(r);
    /* Normal use stays well inside it, and the budget is fresh each tick. */
    load("function update() local s = mem.read(0x02000000, 65536) return { n = #s } end");
    for (int i = 0; i < 3; i++) {
        r = gbaduo_script_tick();
        CHECK(json_has(r, "\"n\":65536"));
        free(r);
    }
}

/* Wall-clock cap: a few slow instructions (big string.rep) can't hold the thread either. */
static void slow_ticks_hit_the_time_limit(void) {
    fake_reset(GBADUO_BUS_GBA);
    load("function update() for i = 1, 100000 do local s = string.rep('x', 4000000) end return {} end");
    struct timespec a, b;
    clock_gettime(CLOCK_MONOTONIC, &a);
    char* r = gbaduo_script_tick();
    clock_gettime(CLOCK_MONOTONIC, &b);
    double ms = (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6;
    CHECK(r == NULL && strstr(gbaduo_script_last_error(), "time limit") != NULL);
    if (ms >= 500) printf("string.rep loop took %.0f ms\n", ms);
    CHECK(ms < 500); /* 25 ms plus the instructions in flight; generous for sanitizer builds */
    free(r);

    /* One pathological pattern is a single C call: the matcher checks the clock too. */
    load("function update() local s = string.rep('a', 3000) return { r = tostring(string.find(s, '.-.-.-.-b')) } end");
    clock_gettime(CLOCK_MONOTONIC, &a);
    r = gbaduo_script_tick();
    clock_gettime(CLOCK_MONOTONIC, &b);
    ms = (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6;
    CHECK(r == NULL && strstr(gbaduo_script_last_error(), "time limit") != NULL);
    CHECK(ms < 500);
    free(r);
    /* Ordinary patterns are unaffected. */
    load("function update() return { r = (string.gsub('Lv2-5', '%d', '#')) } end");
    r = gbaduo_script_tick();
    CHECK(json_has(r, "\"r\":\"Lv#-#\""));
    free(r);
}

static void tilemap_rejects_bad_sizes(void) {
    fake_reset(GBADUO_BUS_GBA);
    const char* bad[] = {
        "gfx.tilemap{ tiles = 0, palette = 0, map = 0, width = 12, height = 8 }",   /* not a multiple of 8 */
        "gfx.tilemap{ tiles = 0, palette = 0, map = 0, width = 512, height = 8 }",  /* too big */
        "gfx.tilemap{ tiles = 0, palette = 0, map = 0, stride = 0, width = 8, height = 8 }",
        "gfx.tilemap{ tiles = 0, palette = 0, map = 0, stride = 4, x = 1, width = 32, height = 8 }", /* past the row */
        "gfx.tilemap{ tiles = 0, palette = 0, map = 0, x = -1, width = 8, height = 8 }",
    };
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        char src[256];
        snprintf(src, sizeof(src), "actions = {} function actions.go() %s end", bad[i]);
        load(src);
        char* r = action("go", 0);
        CHECK(r == NULL && strstr(gbaduo_script_last_error(), "gfx.tilemap") != NULL);
        free(r);
    }
}

/* ---- gfx.sprite ------------------------------------------------------------------- */

static uint32_t* image_of(const char* json, int* w, int* h) {
    char key[32] = {0};
    if (!json || !strstr(json, "gfx:")) return NULL;
    sscanf(strstr(json, "gfx:"), "%12[^\"]", key);
    return gbaduo_script_image(key, w, h);
}

static void sprite_composes_parts(void) {
    fake_reset(GBADUO_BUS_GBA);
    const uint32_t TILES = 0x02010000, PAL = 0x02011000;
    /* Tile 0: all index 1. Tiles 1-4 (a 16x16 object): tile k filled with index k+1. */
    for (int t = 0; t < 5; t++)
        for (int b = 0; b < 32; b++) gbaduo_bus_write8(TILES + t * 32 + b, (uint8_t) ((t + 1) | (t + 1) << 4));
    for (int bank = 0; bank < 2; bank++)
        for (int i = 0; i < 16; i++) poke16(PAL + bank * 32 + i * 2, (uint16_t) (bank * 0x400 + i));
    load("function update() return { k = gfx.sprite{ tiles = 0x02010000, palette = 0x02011000, width = 24, height = 16,"
         " parts = { { x = 0, y = 0, w = 8, h = 8, tile = 0, bank = 1 },"            /* on top */
         "           { x = 0, y = 0, w = 16, h = 16, tile = 1, bank = 0, hflip = true } } } } end");
    char* r = gbaduo_script_tick();
    int w = 0, h = 0;
    uint32_t* px = image_of(r, &w, &h);
    free(r);
    CHECK(px != NULL && w == 24 && h == 16);
    if (px) {
        CHECK(px[0] == argb(0x400 + 1));        /* the first part wins where they overlap */
        CHECK(px[8] == argb(2));                /* h-flipped: right tile (index 3) now... */
        CHECK(px[8 * 24 + 0] == argb(5));       /* bottom-left shows the bottom-right tile, index 5 */
        CHECK(px[8 * 24 + 8] == argb(4));       /* bottom-right shows bottom-left, index 4 */
        CHECK((px[20] >> 24) == 0);             /* outside every part: transparent */
        free(px);
    }
}

static void sprite_rejects_bad_parts(void) {
    fake_reset(GBADUO_BUS_GBA);
    const char* bad[] = {
        "gfx.sprite{ tiles = 0, palette = 0, width = 16, height = 16, parts = {} }",
        "gfx.sprite{ tiles = 0, palette = 0, width = 16, height = 16, parts = { { w = 12, h = 8, tile = 0, bank = 0 } } }",
        "gfx.sprite{ tiles = 0, palette = 0, width = 16, height = 16, parts = { { w = 8, h = 8, tile = 0, bank = 16 } } }",
        "gfx.sprite{ tiles = 0, palette = 0, width = 300, height = 16, parts = { { w = 8, h = 8, tile = 0, bank = 0 } } }",
        "gfx.sprite{ tiles = 0, palette = 0, width = 16, height = 16, parts = 5 }",
    };
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        char src[300];
        snprintf(src, sizeof(src), "actions = {} function actions.go() %s end", bad[i]);
        load(src);
        char* r = action("go", 0);
        CHECK(r == NULL && strstr(gbaduo_script_last_error(), "gfx.sprite") != NULL);
        free(r);
    }
}

static void cache_keeps_many_small_images(void) {
    fake_reset(GBADUO_BUS_GBA);
    /* 40 different 16x16 sprites in one tick: all must still be there afterwards. */
    load("function update() local keys = {} for i = 0, 39 do keys[#keys + 1] = gfx.sprite{ tiles = 0x02010000, palette = 0x02011000,"
         " width = 16, height = 16, parts = { { w = 16, h = 16, tile = i, bank = 0 } } } end return { keys = keys } end");
    char* r = gbaduo_script_tick();
    int found = 0;
    for (const char* p = r; p && (p = strstr(p, "gfx:")); p += 4) {
        char key[32] = {0};
        int w, h;
        sscanf(p, "%12[^\"]", key);
        uint32_t* px = gbaduo_script_image(key, &w, &h);
        if (px) found++;
        free(px);
    }
    free(r);
    CHECK(found == 40);
}

/* ---- two instances: main.lua's and game.lua's -------------------------------------- */

static void two_instances_dont_share_state(void) {
    load("A = 1\nfunction update() return { a = A or 0, b = B or 0 } end\n");
    /* init() errors if the companion's global leaked into the game instance. */
    CHECK(gbaduo_game_load("B = 2\nfunction init() if A ~= nil then error('leak') end end\n", "."));
    CHECK(gbaduo_game_loaded());
    char* tick = gbaduo_script_tick();
    CHECK(json_has(tick, "\"a\":1"));
    CHECK(json_has(tick, "\"b\":0"));
    free(tick);
    gbaduo_game_unload();
}

static void unloading_one_leaves_the_other(void) {
    load("function update() return { ok = true } end\n");
    CHECK(gbaduo_game_load("function init() end\n", "."));
    gbaduo_game_unload();
    CHECK(!gbaduo_game_loaded());
    char* tick = gbaduo_script_tick();
    CHECK(json_has(tick, "\"ok\":true"));
    free(tick);

    CHECK(gbaduo_game_load("function init() end\n", "."));
    gbaduo_script_unload();
    CHECK(gbaduo_game_loaded());
    CHECK(gbaduo_script_tick() == NULL);
    gbaduo_game_unload();
}

static void game_load_failure_leaves_companion_alone(void) {
    load("function update() return { ok = true } end\n");
    CHECK(!gbaduo_game_load("this is not lua", "."));
    CHECK(!gbaduo_game_loaded());
    CHECK(strlen(gbaduo_game_last_error()) > 0);
    CHECK(strlen(gbaduo_script_last_error()) == 0);
    char* tick = gbaduo_script_tick();
    CHECK(json_has(tick, "\"ok\":true"));
    free(tick);
}

/* ---- game.lua hooks: frame() and wide() -------------------------------------------- */

static void gload(const char* source) {
    if (!gbaduo_game_load(source, ".")) {
        fprintf(stderr, "game load failed: %s\n", gbaduo_game_last_error());
        exit(2);
    }
}

static uint32_t peek32(uint32_t a) {
    return (uint32_t) fake_peek(a) | (uint32_t) fake_peek(a + 1) << 8 | (uint32_t) fake_peek(a + 2) << 16 |
           (uint32_t) fake_peek(a + 3) << 24;
}

static void has_hooks_only_when_defined(void) {
    gload("x = 1\n");
    CHECK(!gbaduo_game_has("frame"));
    CHECK(!gbaduo_game_has("wide"));
    gload("function frame() end\n");
    CHECK(gbaduo_game_has("frame"));
    CHECK(!gbaduo_game_has("wide"));
    gload("function wide() end\n");
    CHECK(!gbaduo_game_has("frame"));
    CHECK(gbaduo_game_has("wide"));
    gbaduo_game_unload();
    CHECK(!gbaduo_game_has("frame"));
}

static void frame_receives_view(void) {
    fake_reset(GBADUO_BUS_GBA);
    gload("function frame(v) mem.write32(0x02000000, v.frame); mem.write16(0x02000004, v.width); mem.write16(0x02000006, v.left) end\n");
    CHECK(gbaduo_game_frame(123456, 320, 40, true));
    CHECK(peek32(0x02000000) == 123456u);
    CHECK(peek32(0x02000004) == (320u | 40u << 16));
    gbaduo_game_unload();
}

static void writes_refused_outside_frame(void) {
    fake_reset(GBADUO_BUS_GBA);
    gload("TOP = mem.write8(0x02000010, 1)\n"
          "function init() INIT = mem.write8(0x02000011, 1) end\n"
          "function wide() mem.write8(0x02000012, 1) end\n");
    CHECK(fake_writes == 0);
    struct WideWorld w;
    CHECK(!gbaduo_game_wide(320, 40, 1.0f, &w));
    CHECK(fake_writes == 0);
    gbaduo_game_unload();
}

static void frame_writes_need_the_window(void) {
    fake_reset(GBADUO_BUS_GBA);
    gload("function frame() mem.write8(0x02000020, 7) end\n");
    CHECK(gbaduo_game_frame(1, 240, 0, false));
    CHECK(fake_peek(0x02000020) == 0);
    CHECK(fake_writes == 0);
    CHECK(gbaduo_game_frame(2, 240, 0, true));
    CHECK(fake_peek(0x02000020) == 7);
    gbaduo_game_unload();
}

static void frame_write_cap(void) {
    fake_reset(GBADUO_BUS_GBA);
    gload("function frame() for i = 0, 4 do mem.write(0x02000100 + i * 64, string.rep('\\1', 64)) end end\n");
    CHECK(gbaduo_game_frame(1, 240, 0, true));
    CHECK(fake_writes == SCRIPT_WRITE_MAX_PER_FRAME);
    CHECK(fake_peek(0x02000100 + 3 * 64) == 1);
    CHECK(fake_peek(0x02000100 + 4 * 64) == 0);
    /* The allowance is per frame: the next frame writes again. */
    CHECK(gbaduo_game_frame(2, 240, 0, true));
    CHECK(fake_writes == 2 * SCRIPT_WRITE_MAX_PER_FRAME);
    gbaduo_game_unload();
}

static void wide_returns_world(void) {
    gload("function wide(v) return { x = -5, y = 7, grid = 0x02000000, gridW = 3, gridH = 2, idMask = 0x3FF,\n"
          "  border = 0x02001000, borderW = 2, borderH = 2, metatiles = 0x02002000, metatiles2 = 0x02004000,\n"
          "  borderShift = 7, undefinedId = 0x3FF,\n"
          "  split = 640, tiles = 0x06000000, palette = 0x05000000 } end\n");
    struct WideWorld w;
    memset(&w, 0, sizeof(w));
    CHECK(gbaduo_game_wide(320, 40, 1.0f, &w));
    CHECK(w.x == -5 && w.y == 7);
    CHECK(w.grid == 0x02000000u && w.gridW == 3 && w.gridH == 2 && w.idMask == 0x3FF);
    CHECK(w.border == 0x02001000u && w.borderW == 2 && w.borderH == 2);
    CHECK(w.borderShift == 7 && w.undefinedId == 0x3FF);
    CHECK(w.metatiles == 0x02002000u && w.metatiles2 == 0x02004000u && w.split == 640);
    CHECK(w.tiles == 0x06000000u && w.palette == 0x05000000u);
    CHECK(strlen(gbaduo_game_last_error()) == 0);

    /* The view reaches Lua. */
    gload("function wide(v) return { x = v.width, y = v.left, split = math.floor(v.match * 100), grid = 0, gridW = 1, gridH = 1,\n"
          "  idMask = 0, border = 0, borderW = 1, borderH = 1, metatiles = 0, metatiles2 = 0, tiles = 0, palette = 0 } end\n");
    CHECK(gbaduo_game_wide(320, 40, 0.5f, &w));
    CHECK(w.x == 320 && w.y == 40 && w.split == 50);
    CHECK(w.borderShift == 0 && w.undefinedId == -1); /* both optional */
    gbaduo_game_unload();
}

static void wide_nil_is_hidden_not_error(void) {
    struct WideWorld w;
    gload("function wide() return nil end\n");
    CHECK(!gbaduo_game_wide(320, 40, 1.0f, &w));
    CHECK(strlen(gbaduo_game_last_error()) == 0);
    gload("function wide() return false end\n");
    CHECK(!gbaduo_game_wide(320, 40, 1.0f, &w));
    CHECK(strlen(gbaduo_game_last_error()) == 0);
    CHECK(gbaduo_game_enabled());
    gbaduo_game_unload();
}

static void wide_missing_field_is_error(void) {
    struct WideWorld w;
    gload("function wide() return { x = 0, y = 0, grid = 0, gridH = 1, idMask = 0, border = 0, borderW = 1, borderH = 1,\n"
          "  metatiles = 0, metatiles2 = 0, split = 0, tiles = 0, palette = 0 } end\n");
    CHECK(!gbaduo_game_wide(320, 40, 1.0f, &w));
    CHECK(strstr(gbaduo_game_last_error(), "gridW") != NULL);
    gbaduo_game_unload();
}

static void frame_error_skips_and_keeps_running(void) {
    fake_reset(GBADUO_BUS_GBA);
    load("function update() return { ok = true } end\n");
    gload("function frame(v) if v.frame == 1 then error('boom') end mem.write8(0x02000040, 9) end\n");
    CHECK(!gbaduo_game_frame(1, 240, 0, true));
    CHECK(strstr(gbaduo_game_last_error(), "boom") != NULL);
    CHECK(gbaduo_game_frame(2, 240, 0, true));
    CHECK(fake_peek(0x02000040) == 9);
    CHECK(strlen(gbaduo_game_last_error()) == 0);
    char* tick = gbaduo_script_tick();
    CHECK(json_has(tick, "\"ok\":true"));
    free(tick);
    gbaduo_game_unload();
}

static void budget_is_enforced(void) {
    gload("function frame() while true do end end\n");
    CHECK(!gbaduo_game_frame(1, 240, 0, false));
    CHECK(strstr(gbaduo_game_last_error(), "exceeded") != NULL);
    gbaduo_game_unload();
}

static void five_errors_turn_it_off(void) {
    fake_reset(GBADUO_BUS_GBA);
    const char* src = "function frame() mem.write8(0x02000030, 1); error('x') end\n";
    gload(src);
    for (int i = 0; i < 5; i++) {
        CHECK(gbaduo_game_enabled());
        CHECK(!gbaduo_game_frame(i, 240, 0, true));
    }
    CHECK(!gbaduo_game_enabled());
    fake_reset(GBADUO_BUS_GBA);
    CHECK(!gbaduo_game_frame(6, 240, 0, true));
    CHECK(fake_peek(0x02000030) == 0); /* Lua did not run */
    gload(src);
    CHECK(gbaduo_game_enabled());
    gbaduo_game_unload();
}

int main(void) {
    refused_outside_an_action();
    refused_without_permission();
    ram_writes_succeed_and_read_back();
    rom_io_and_other_regions_refused();
    gb_ram_allowed();
    oversized_and_negative_refused();
    at_most_256_bytes_per_tap();
    window_closes_after_an_error();
    window_closes_after_the_instruction_budget();
    a_kept_function_cant_write_later();
    tilemap_composes_tiles_flips_and_banks();
    tilemap_rejects_bad_sizes();
    tilemap_reads_lz77_tiles_and_map();
    c_work_is_charged_to_the_budget();
    slow_ticks_hit_the_time_limit();
    sprite_composes_parts();
    sprite_rejects_bad_parts();
    cache_keeps_many_small_images();
    two_instances_dont_share_state();
    unloading_one_leaves_the_other();
    game_load_failure_leaves_companion_alone();
    has_hooks_only_when_defined();
    frame_receives_view();
    writes_refused_outside_frame();
    frame_writes_need_the_window();
    frame_write_cap();
    wide_returns_world();
    wide_nil_is_hidden_not_error();
    wide_missing_field_is_error();
    frame_error_skips_and_keeps_running();
    budget_is_enforced();
    five_errors_turn_it_off();
    gbaduo_script_unload();

    printf("%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
