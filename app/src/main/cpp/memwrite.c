// Checked writes into the emulated machine's RAM (see bus.h). No Android, no mGBA:
// compiled into the app and into the desktop test harness alike.

#include "bus.h"

struct Region {
    uint32_t start;
    uint32_t end; // inclusive
};

// GBA: on-board work RAM (256 KB) and in-chip work RAM (32 KB), their canonical
// addresses only (not the mirrors). BIOS, I/O, palette, VRAM, OAM, ROM and SRAM are out.
static const struct Region GBA_REGIONS[] = {
    {0x02000000u, 0x0203FFFFu}, // EWRAM
    {0x03000000u, 0x03007FFFu}, // IWRAM
};

// GB/GBC: work RAM (0xD000 bank is whichever the game has switched in) and high RAM,
// not the IE register at 0xFFFF. ROM, VRAM, cartridge RAM (the save), echo RAM,
// OAM and I/O are out.
static const struct Region GB_REGIONS[] = {
    {0xC000u, 0xDFFFu}, // WRAM
    {0xFF80u, 0xFFFEu}, // HRAM
};

bool gbaduo_region_writable(int platform, uint32_t address, uint32_t size) {
    if (size == 0) {
        return false;
    }
    const struct Region* regions;
    size_t count;
    if (platform == GBADUO_BUS_GBA) {
        regions = GBA_REGIONS;
        count = sizeof(GBA_REGIONS) / sizeof(GBA_REGIONS[0]);
    } else if (platform == GBADUO_BUS_GB) {
        regions = GB_REGIONS;
        count = sizeof(GB_REGIONS) / sizeof(GB_REGIONS[0]);
    } else {
        return false;
    }
    // last byte, without wrapping past 0xFFFFFFFF
    if (address > UINT32_MAX - (size - 1)) {
        return false;
    }
    uint32_t last = address + (size - 1);
    for (size_t i = 0; i < count; i++) {
        if (address >= regions[i].start && last <= regions[i].end) {
            return true;
        }
    }
    return false;
}

bool gbaduo_write_bytes(uint32_t address, const uint8_t* bytes, size_t size) {
    if (size > UINT32_MAX || !gbaduo_region_writable(gbaduo_bus_platform(), address, (uint32_t) size)) {
        return false;
    }
    for (size_t i = 0; i < size; i++) {
        gbaduo_bus_write8(address + (uint32_t) i, bytes[i]);
    }
    return true;
}

bool gbaduo_write8(uint32_t address, uint8_t value) {
    return gbaduo_write_bytes(address, &value, 1);
}

bool gbaduo_write16(uint32_t address, uint16_t value) {
    uint8_t b[2] = {(uint8_t) value, (uint8_t) (value >> 8)};
    return gbaduo_write_bytes(address, b, 2);
}

bool gbaduo_write32(uint32_t address, uint32_t value) {
    uint8_t b[4] = {(uint8_t) value, (uint8_t) (value >> 8), (uint8_t) (value >> 16), (uint8_t) (value >> 24)};
    return gbaduo_write_bytes(address, b, 4);
}
