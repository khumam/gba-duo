// The emulated machine's memory as the Lua sandbox (script.c) sees it.
//
// The gbaduo_bus_* backend is core.c over mGBA in the app, and a fake RAM in the
// desktop test harness (app/src/test/native). The checked writes on top of it live in
// memwrite.c, shared by both, so the harness tests the same rules the app enforces.

#ifndef GBADUO_BUS_H
#define GBADUO_BUS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

enum {
    GBADUO_BUS_NONE = 0, // no game loaded
    GBADUO_BUS_GBA = 1,
    GBADUO_BUS_GB = 2,   // GB and GBC
};

// ---- backend -----------------------------------------------------------------

/** Which machine is loaded, or GBADUO_BUS_NONE. */
int gbaduo_bus_platform(void);

/** Raw bus reads at any address; 0 when no game is loaded. */
uint8_t gbaduo_bus_read8(uint32_t address);
uint16_t gbaduo_bus_read16(uint32_t address);
uint32_t gbaduo_bus_read32(uint32_t address);

/** Raw, unchecked byte write. Only memwrite.c calls this. */
void gbaduo_bus_write8(uint32_t address, uint8_t value);

// ---- checked writes (memwrite.c) ------------------------------------------------

/**
 * True when [size] bytes from [address] all lie inside one writable RAM region of
 * [platform]: GBA EWRAM and IWRAM; GB/GBC work RAM and high RAM. ROM, I/O registers,
 * video memory, save memory and everything else are refused.
 */
bool gbaduo_region_writable(int platform, uint32_t address, uint32_t size);

/** Little-endian writes into writable RAM only; false (and nothing written) otherwise. */
bool gbaduo_write8(uint32_t address, uint8_t value);
bool gbaduo_write16(uint32_t address, uint16_t value);
bool gbaduo_write32(uint32_t address, uint32_t value);
bool gbaduo_write_bytes(uint32_t address, const uint8_t* bytes, size_t size);

#endif
