// Desktop tests for mem.write*: the real Lua sandbox (script.c, built with
// GBADUO_HOST_TEST) and the real checked writes (memwrite.c) over a fake machine
// (fake_bus.c). Run under ASan/UBSan by run.sh.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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
    gbaduo_script_unload();

    printf("%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
