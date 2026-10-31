// A fake machine for the harness: the bus.h backend over plain arrays, so the real
// script.c and memwrite.c run on the desktop. Every byte reachable through
// gbaduo_bus_write8 is recorded, so a test can tell a refused write never got here.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "bus.h"
#include "fake_bus.h"

int fake_platform = GBADUO_BUS_GBA;
unsigned fake_writes = 0;

static uint8_t gba_ewram[0x40000];
static uint8_t gba_iwram[0x8000];
static uint8_t gb_space[0x10000];
// Palette RAM and VRAM, readable (and raw-writable, for building scenes) on the GBA.
static uint8_t gba_palette[0x400];
static uint8_t gba_vram[0x18000];
// A cartridge ROM at 0x08000000, when a test loads one (fake_load_rom).
static uint8_t* rom;
static size_t romSize;
// Anything else written lands here (it shouldn't: memwrite.c refuses it first).
static uint8_t elsewhere[16];

static uint8_t* cell(uint32_t a) {
    if (fake_platform == GBADUO_BUS_GBA) {
        if (a >= 0x02000000u && a <= 0x0203FFFFu) return &gba_ewram[a - 0x02000000u];
        if (a >= 0x03000000u && a <= 0x03007FFFu) return &gba_iwram[a - 0x03000000u];
        if (a >= 0x05000000u && a < 0x05000400u) return &gba_palette[a - 0x05000000u];
        if (a >= 0x06000000u && a < 0x06018000u) return &gba_vram[a - 0x06000000u];
        if (rom && a >= 0x08000000u && a - 0x08000000u < romSize) return &rom[a - 0x08000000u];
        return &elsewhere[a % sizeof(elsewhere)];
    }
    if (fake_platform == GBADUO_BUS_GB && a <= 0xFFFFu) return &gb_space[a];
    return &elsewhere[a % sizeof(elsewhere)];
}

void fake_reset(int platform) {
    fake_platform = platform;
    fake_writes = 0;
    memset(gba_ewram, 0, sizeof(gba_ewram));
    memset(gba_iwram, 0, sizeof(gba_iwram));
    memset(gb_space, 0, sizeof(gb_space));
    memset(gba_palette, 0, sizeof(gba_palette));
    memset(gba_vram, 0, sizeof(gba_vram));
    memset(elsewhere, 0, sizeof(elsewhere));
}

uint8_t fake_peek(uint32_t address) {
    return *cell(address);
}

int gbaduo_bus_platform(void) {
    return fake_platform;
}

uint8_t gbaduo_bus_read8(uint32_t address) {
    return fake_platform == GBADUO_BUS_NONE ? 0 : *cell(address);
}

uint16_t gbaduo_bus_read16(uint32_t address) {
    return (uint16_t) (gbaduo_bus_read8(address) | gbaduo_bus_read8(address + 1) << 8);
}

uint32_t gbaduo_bus_read32(uint32_t address) {
    return (uint32_t) gbaduo_bus_read16(address) | (uint32_t) gbaduo_bus_read16(address + 2) << 16;
}

void gbaduo_bus_write8(uint32_t address, uint8_t value) {
    fake_writes++;
    *cell(address) = value;
}

int fake_load_rom(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) return 0;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    free(rom);
    rom = malloc((size_t) n);
    romSize = rom ? fread(rom, 1, (size_t) n, f) : 0;
    fclose(f);
    return romSize > 0;
}
