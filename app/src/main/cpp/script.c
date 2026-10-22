#include <math.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <lua.h>
#include <lauxlib.h>
#include <lualib.h>

#include "bus.h"
#include "script.h"

/* GBADUO_HOST_TEST builds this file on the desktop for the test harness
 * (app/src/test/native): no JNI, logs to stderr. */
#ifdef GBADUO_HOST_TEST
#define LOG_PRINT(level, tag, ...) (fprintf(stderr, "%s %s: ", level, tag), fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define LOG_INFO "I"
#define LOG_ERROR "E"
#else
#include <jni.h>
#include <android/log.h>
#define LOG_PRINT(level, tag, ...) __android_log_print(level, tag, __VA_ARGS__)
#define LOG_INFO ANDROID_LOG_INFO
#define LOG_ERROR ANDROID_LOG_ERROR
#endif

#define LOG_TAG "GBADuoScript"
#define LOGE(...) LOG_PRINT(LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SCRIPT_MEMORY_LIMIT (16u * 1024u * 1024u)
#define INSTRUCTION_BUDGET_PER_TICK 5000000L
#define HOOK_INSTRUCTION_INTERVAL 10000
#define JSON_MAX_DEPTH 32
/* Decoded images kept at once, and their total size: a pack showing a full item
 * grid (each icon its own image) plus a map stays cached instead of re-decoding
 * every tick, and the pixels stay bounded (4M px = 16 MB). */
#define IMAGE_CACHE_SIZE 96
#define IMAGE_CACHE_MAX_PIXELS (4u * 1024u * 1024u)
/* Caps on pack-controlled sizes: these buffers live outside the Lua memory limit
 * and are filled by C loops the instruction budget doesn't count. */
#define LZ77_MAX_OUTPUT (1024u * 1024u)
#define IMAGE_MAX_DIMENSION 256


struct CachedImage {
    bool used;
    uint32_t key;
    int width;
    int height;
    uint32_t* pixels; /* ARGB8888, width*height */
};

struct GbaDuoScript {
    lua_State* L;
    size_t memUsed;
    long instructionsLeft;
    char packDir[1024];
    char lastError[512];
    struct CachedImage images[IMAGE_CACHE_SIZE];
    int nextImageSlot;
    /* mem.write*: open only for the length of one action call made with may_write
     * (see gbaduo_script_action); tapBytesLeft is that call's remaining allowance. */
    bool writeWindow;
    uint32_t tapBytesLeft;
};

static struct GbaDuoScript g_script;

/* Guards g_script.images: gfx.decode/unloadScript run on the emu thread while
 * nativeImage is called from the UI thread. */
static pthread_mutex_t g_imageLock = PTHREAD_MUTEX_INITIALIZER;

/* ------------------------------------------------------------------------ */
/* Capped allocator + instruction-count hook                                */
/* ------------------------------------------------------------------------ */

static void* script_alloc(void* ud, void* ptr, size_t osize, size_t nsize) {
    struct GbaDuoScript* s = (struct GbaDuoScript*) ud;
    if (nsize == 0) {
        if (ptr) {
            s->memUsed -= osize;
            free(ptr);
        }
        return NULL;
    }
    size_t oldAccounted = ptr ? osize : 0;
    size_t newUsed = s->memUsed - oldAccounted + nsize;
    if (newUsed > SCRIPT_MEMORY_LIMIT) {
        return NULL; /* Lua treats this as LUA_ERRMEM */
    }
    void* newPtr = realloc(ptr, nsize);
    if (!newPtr) {
        return NULL;
    }
    s->memUsed = newUsed;
    return newPtr;
}

static void instruction_hook(lua_State* L, lua_Debug* ar) {
    (void) ar;
    g_script.instructionsLeft -= HOOK_INSTRUCTION_INTERVAL;
    if (g_script.instructionsLeft <= 0) {
        luaL_error(L, "script exceeded its per-tick instruction budget");
    }
}

/* ------------------------------------------------------------------------ */
/* mem.* API                                                                */
/* ------------------------------------------------------------------------ */

static int l_mem_u8(lua_State* L) {
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    lua_pushinteger(L, gbaduo_bus_read8(addr));
    return 1;
}

static int l_mem_s8(lua_State* L) {
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    int8_t v = (int8_t) gbaduo_bus_read8(addr);
    lua_pushinteger(L, v);
    return 1;
}

static int l_mem_u16(lua_State* L) {
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    lua_pushinteger(L, gbaduo_bus_read16(addr));
    return 1;
}

static int l_mem_s16(lua_State* L) {
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    int16_t v = (int16_t) gbaduo_bus_read16(addr);
    lua_pushinteger(L, v);
    return 1;
}

static int l_mem_u32(lua_State* L) {
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    lua_pushinteger(L, (lua_Integer) gbaduo_bus_read32(addr));
    return 1;
}

static int l_mem_s32(lua_State* L) {
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    int32_t v = (int32_t) gbaduo_bus_read32(addr);
    lua_pushinteger(L, v);
    return 1;
}

static int l_mem_read(lua_State* L) {
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    lua_Integer n = luaL_checkinteger(L, 2);
    if (n < 0) n = 0;
    if (n > 65536) n = 65536;
    if (n == 0) {
        lua_pushliteral(L, "");
        return 1;
    }
    char* buf = malloc((size_t) n);
    if (!buf) {
        return luaL_error(L, "mem.read: out of memory");
    }
    for (lua_Integer i = 0; i < n; i++) {
        buf[i] = (char) gbaduo_bus_read8(addr + (uint32_t) i);
    }
    lua_pushlstring(L, buf, (size_t) n);
    free(buf);
    return 1;
}

/* ------------------------------------------------------------------------ */
/* mem.write* — only from a tap, only RAM, capped (bus.h, memwrite.c)       */
/* ------------------------------------------------------------------------ */

/* Every refusal returns false plus why, so a pack can show it on the companion screen. */
static int write_refused(lua_State* L, const char* why) {
    lua_pushboolean(L, 0);
    lua_pushstring(L, why);
    return 2;
}

static int write_checked(lua_State* L, lua_Integer address, const uint8_t* bytes, size_t size) {
    if (!g_script.writeWindow) {
        return write_refused(L, "writing is only allowed from a tap, with the player's permission");
    }
    if (address < 0 || address > (lua_Integer) UINT32_MAX) {
        return write_refused(L, "address out of range");
    }
    if (size == 0 || size > SCRIPT_WRITE_MAX_PER_CALL) {
        return write_refused(L, "a write must be 1 to 64 bytes");
    }
    if (size > g_script.tapBytesLeft) {
        return write_refused(L, "at most 256 bytes per tap");
    }
    if (!gbaduo_write_bytes((uint32_t) address, bytes, size)) {
        return write_refused(L, "not writable RAM");
    }
    g_script.tapBytesLeft -= (uint32_t) size;
    LOG_PRINT(LOG_INFO, "Companion", "pack wrote %u byte(s) at 0x%08X", (unsigned) size, (unsigned) address);
    lua_pushboolean(L, 1);
    return 1;
}

static int l_mem_write8(lua_State* L) {
    lua_Integer address = luaL_checkinteger(L, 1);
    lua_Integer value = luaL_checkinteger(L, 2);
    uint8_t b[1] = {(uint8_t) value};
    return write_checked(L, address, b, sizeof(b));
}

static int l_mem_write16(lua_State* L) {
    lua_Integer address = luaL_checkinteger(L, 1);
    lua_Integer value = luaL_checkinteger(L, 2);
    uint8_t b[2] = {(uint8_t) value, (uint8_t) (value >> 8)};
    return write_checked(L, address, b, sizeof(b));
}

static int l_mem_write32(lua_State* L) {
    lua_Integer address = luaL_checkinteger(L, 1);
    lua_Integer value = luaL_checkinteger(L, 2);
    uint8_t b[4] = {(uint8_t) value, (uint8_t) (value >> 8), (uint8_t) (value >> 16), (uint8_t) (value >> 24)};
    return write_checked(L, address, b, sizeof(b));
}

/* mem.write(addr, bytes): a short Lua string of raw bytes. */
static int l_mem_write(lua_State* L) {
    lua_Integer address = luaL_checkinteger(L, 1);
    size_t size = 0;
    const char* bytes = luaL_checklstring(L, 2, &size);
    return write_checked(L, address, (const uint8_t*) bytes, size);
}

bool gbaduo_script_writes_open(void) {
    return g_script.writeWindow;
}

/* ------------------------------------------------------------------------ */
/* log()                                                                    */
/* ------------------------------------------------------------------------ */

static int l_log(lua_State* L) {
    const char* msg = luaL_tolstring(L, 1, NULL);
    LOG_PRINT(LOG_INFO, "GBADuoPack", "%s", msg);
    lua_pop(L, 1); /* pop the string luaL_tolstring pushed */
    return 0;
}

/* ------------------------------------------------------------------------ */
/* LZ77 (GBA BIOS type-0x10 compression)                                    */
/* ------------------------------------------------------------------------ */

/** Decompresses GBA LZ77 data starting at [addr]; returns a malloc'd buffer and sets *outSize. */
static uint8_t* lz77_decompress(uint32_t addr, size_t* outSize) {
    bool core = gbaduo_bus_platform() != GBADUO_BUS_NONE;
    if (!core) {
        *outSize = 0;
        return NULL;
    }
    uint32_t header = gbaduo_bus_read32(addr);
    if ((header & 0xFF) != 0x10) {
        *outSize = 0;
        return NULL;
    }
    size_t size = (header >> 8) & 0xFFFFFF;
    if (size > LZ77_MAX_OUTPUT) {
        *outSize = 0;
        return NULL;
    }
    uint8_t* out = malloc(size > 0 ? size : 1);
    if (!out) {
        *outSize = 0;
        return NULL;
    }
    size_t outPos = 0;
    uint32_t src = addr + 4;

    while (outPos < size) {
        uint8_t flags = (uint8_t) gbaduo_bus_read8(src++);
        for (int bit = 7; bit >= 0 && outPos < size; bit--) {
            if ((flags & (1 << bit)) == 0) {
                out[outPos++] = (uint8_t) gbaduo_bus_read8(src++);
            } else {
                uint8_t b0 = (uint8_t) gbaduo_bus_read8(src++);
                uint8_t b1 = (uint8_t) gbaduo_bus_read8(src++);
                int length = (b0 >> 4) + 3;
                size_t disp = (size_t) ((b0 & 0x0F) << 8 | b1) + 1;
                if (disp > outPos) {
                    /* Back-reference before the start of the output: not LZ77 data. */
                    free(out);
                    *outSize = 0;
                    return NULL;
                }
                for (int i = 0; i < length && outPos < size; i++) {
                    out[outPos] = out[outPos - disp];
                    outPos++;
                }
            }
        }
    }
    *outSize = size;
    return out;
}

static int l_lz77(lua_State* L) {
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    size_t size = 0;
    uint8_t* data = lz77_decompress(addr, &size);
    if (!data) {
        /* lua_pushfstring has no %x/%u, so format the message first. */
        char message[96];
        snprintf(message, sizeof(message), "lz77: no valid LZ77 data at 0x%08x (or larger than %u bytes)", addr, LZ77_MAX_OUTPUT);
        return luaL_error(L, "%s", message);
    }
    lua_pushlstring(L, (const char*) data, size);
    free(data);
    return 1;
}

/* ------------------------------------------------------------------------ */
/* gfx.decode{tiles=, palette=, width=, height=, bpp=4, lz77=}              */
/* ------------------------------------------------------------------------ */

static uint32_t fnv1a(const void* data, size_t len) {
    const uint8_t* p = (const uint8_t*) data;
    uint32_t hash = 0x811C9DC5u;
    for (size_t i = 0; i < len; i++) {
        hash ^= p[i];
        hash *= 0x01000193u;
    }
    return hash;
}

static struct CachedImage* image_cache_find(uint32_t key) {
    for (int i = 0; i < IMAGE_CACHE_SIZE; i++) {
        if (g_script.images[i].used && g_script.images[i].key == key) {
            return &g_script.images[i];
        }
    }
    return NULL;
}

static struct CachedImage* image_cache_store(uint32_t key, int width, int height, uint32_t* pixels) {
    /* Over the pixel budget: drop the oldest entries first (the slots after the next one). */
    size_t total = (size_t) width * height;
    for (int i = 0; i < IMAGE_CACHE_SIZE; i++) {
        if (g_script.images[i].used) total += (size_t) g_script.images[i].width * g_script.images[i].height;
    }
    for (int k = 0; k < IMAGE_CACHE_SIZE && total > IMAGE_CACHE_MAX_PIXELS; k++) {
        struct CachedImage* old = &g_script.images[(g_script.nextImageSlot + k) % IMAGE_CACHE_SIZE];
        if (!old->used) continue;
        total -= (size_t) old->width * old->height;
        free(old->pixels);
        old->used = false;
        old->pixels = NULL;
    }
    int slot = g_script.nextImageSlot;
    g_script.nextImageSlot = (slot + 1) % IMAGE_CACHE_SIZE;
    if (g_script.images[slot].used) {
        free(g_script.images[slot].pixels);
    }
    g_script.images[slot].used = true;
    g_script.images[slot].key = key;
    g_script.images[slot].width = width;
    g_script.images[slot].height = height;
    g_script.images[slot].pixels = pixels;
    return &g_script.images[slot];
}

/** A GBA BGR555 colour as ARGB8888; [transparent] gives alpha 0. */
static uint32_t gba_argb(uint16_t entry, bool transparent) {
    uint32_t r = (entry & 0x1F) << 3;
    uint32_t g = ((entry >> 5) & 0x1F) << 3;
    uint32_t b = ((entry >> 10) & 0x1F) << 3;
    uint32_t a = transparent ? 0 : 0xFFu;
    return (a << 24) | (r << 16) | (g << 8) | b;
}

static void push_image_key(lua_State* L, uint32_t key) {
    char keyStr[32];
    snprintf(keyStr, sizeof(keyStr), "gfx:%08x", key);
    lua_pushstring(L, keyStr);
}

static int l_gfx_decode(lua_State* L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_getfield(L, 1, "tiles");
    uint32_t tilesAddr = (uint32_t) luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "palette");
    uint32_t paletteAddr = (uint32_t) luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "width");
    int width = (int) luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "height");
    int height = (int) luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "bpp");
    int bpp = lua_isnil(L, -1) ? 4 : (int) luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "lz77");
    bool useLz77 = lua_toboolean(L, -1);
    lua_pop(L, 6);

    if (bpp != 4) {
        return luaL_error(L, "gfx.decode: only bpp=4 is supported");
    }
    if (width <= 0 || height <= 0 || width % 8 != 0 || height % 8 != 0) {
        return luaL_error(L, "gfx.decode: width/height must be positive multiples of 8");
    }
    if (width > IMAGE_MAX_DIMENSION || height > IMAGE_MAX_DIMENSION) {
        return luaL_error(L, "gfx.decode: width/height must be at most %d", IMAGE_MAX_DIMENSION);
    }

    struct {
        uint32_t tilesAddr, paletteAddr;
        int width, height, bpp;
        bool lz77;
    } descriptor = {tilesAddr, paletteAddr, width, height, bpp, useLz77};
    uint32_t key = fnv1a(&descriptor, sizeof(descriptor));

    pthread_mutex_lock(&g_imageLock);
    bool cached = image_cache_find(key) != NULL;
    pthread_mutex_unlock(&g_imageLock);
    if (cached) {
        char keyStr[32];
        snprintf(keyStr, sizeof(keyStr), "gfx:%08x", key);
        lua_pushstring(L, keyStr);
        return 1;
    }

    bool core = gbaduo_bus_platform() != GBADUO_BUS_NONE;
    if (!core) {
        return luaL_error(L, "gfx.decode: no ROM loaded");
    }

    int tilesWide = width / 8;
    int tilesHigh = height / 8;
    size_t tileBytes = (size_t) tilesWide * tilesHigh * 32; /* 32 bytes per 8x8 4bpp tile */

    uint8_t* tileData;
    size_t decodedSize = 0;
    if (useLz77) {
        tileData = lz77_decompress(tilesAddr, &decodedSize);
        if (!tileData || decodedSize < tileBytes) {
            free(tileData);
            return luaL_error(L, "gfx.decode: LZ77 tile data missing or shorter than expected");
        }
    } else {
        tileData = malloc(tileBytes);
        if (!tileData) {
            return luaL_error(L, "gfx.decode: out of memory");
        }
        for (size_t i = 0; i < tileBytes; i++) {
            tileData[i] = (uint8_t) gbaduo_bus_read8(tilesAddr + (uint32_t) i);
        }
    }

    uint16_t palette[16];
    for (int i = 0; i < 16; i++) {
        palette[i] = (uint16_t) gbaduo_bus_read16(paletteAddr + (uint32_t) i * 2);
    }

    uint32_t* pixels = malloc((size_t) width * height * sizeof(uint32_t));
    if (!pixels) {
        free(tileData);
        return luaL_error(L, "gfx.decode: out of memory");
    }
    for (int ty = 0; ty < tilesHigh; ty++) {
        for (int tx = 0; tx < tilesWide; tx++) {
            int tileIndex = ty * tilesWide + tx;
            const uint8_t* tile = tileData + (size_t) tileIndex * 32;
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col += 2) {
                    uint8_t byte = tile[row * 4 + col / 2];
                    int lo = byte & 0x0F;
                    int hi = (byte >> 4) & 0x0F;
                    int px = tx * 8 + col;
                    int py = ty * 8 + row;
                    for (int k = 0; k < 2; k++) {
                        int paletteIndex = k == 0 ? lo : hi;
                        pixels[(size_t) py * width + (px + k)] = gba_argb(palette[paletteIndex], paletteIndex == 0);
                    }
                }
            }
        }
    }

    free(tileData);
    pthread_mutex_lock(&g_imageLock);
    image_cache_store(key, width, height, pixels);
    pthread_mutex_unlock(&g_imageLock);

    char keyStr[32];
    snprintf(keyStr, sizeof(keyStr), "gfx:%08x", key);
    lua_pushstring(L, keyStr);
    return 1;
}

/* Most tilemap entries per row (a GBA screen block is 32; wider maps exist in ROM data). */
#define TILEMAP_MAX_STRIDE 256

/*
 * gfx.tilemap{ tiles, palette, map, stride, x, y, width, height, opaque, lz77 } -> "gfx:..." key.
 *
 * Composes a picture the way the GBA draws a 4bpp text background: [map] holds u16
 * entries ([stride] per row; bits 0-9 tile number, 10 h-flip, 11 v-flip, 12-15 palette
 * bank), [tiles] the 8x8 tiles (32 bytes each), and [palette] palette bank 0 (bank n is
 * 32 bytes further each). The picture is [width] x [height] px starting at map cell
 * ([x], [y]). Colour 0 of every bank is transparent unless [opaque].
 * Everything is read from emulated memory (normally the ROM), raw, or with [lz77]
 * both [tiles] and [map] GBA LZ77-compressed (BIOS type 0x10), as many games store them.
 */
static int l_gfx_tilemap(lua_State* L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_getfield(L, 1, "tiles");
    uint32_t tilesAddr = (uint32_t) luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "palette");
    uint32_t paletteAddr = (uint32_t) luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "map");
    uint32_t mapAddr = (uint32_t) luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "stride");
    lua_Integer stride = lua_isnil(L, -1) ? 32 : luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "x");
    lua_Integer x0 = lua_isnil(L, -1) ? 0 : luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "y");
    lua_Integer y0 = lua_isnil(L, -1) ? 0 : luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "width");
    lua_Integer width = luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "height");
    lua_Integer height = luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "opaque");
    bool opaque = lua_toboolean(L, -1);
    lua_getfield(L, 1, "lz77");
    bool useLz77 = lua_toboolean(L, -1);
    lua_pop(L, 10);

    if (width <= 0 || height <= 0 || width % 8 != 0 || height % 8 != 0) {
        return luaL_error(L, "gfx.tilemap: width/height must be positive multiples of 8");
    }
    if (width > IMAGE_MAX_DIMENSION || height > IMAGE_MAX_DIMENSION) {
        return luaL_error(L, "gfx.tilemap: width/height must be at most %d", IMAGE_MAX_DIMENSION);
    }
    if (stride < 1 || stride > TILEMAP_MAX_STRIDE) {
        return luaL_error(L, "gfx.tilemap: stride must be 1 to %d", TILEMAP_MAX_STRIDE);
    }
    if (x0 < 0 || y0 < 0 || x0 + width / 8 > stride || y0 > 4096) {
        return luaL_error(L, "gfx.tilemap: x/y outside the map");
    }

    struct {
        uint32_t tilesAddr, paletteAddr, mapAddr;
        int32_t stride, x, y, width, height;
        int32_t opaque, lz77;
    } descriptor = {tilesAddr, paletteAddr, mapAddr, (int32_t) stride, (int32_t) x0, (int32_t) y0,
                    (int32_t) width, (int32_t) height, opaque ? 1 : 0, useLz77 ? 1 : 0};
    /* Distinct from gfx.decode keys: different descriptor size and a salt. */
    uint32_t key = fnv1a(&descriptor, sizeof(descriptor)) ^ 0x7417u;

    pthread_mutex_lock(&g_imageLock);
    bool cached = image_cache_find(key) != NULL;
    pthread_mutex_unlock(&g_imageLock);
    if (cached) {
        push_image_key(L, key);
        return 1;
    }
    if (gbaduo_bus_platform() == GBADUO_BUS_NONE) {
        return luaL_error(L, "gfx.tilemap: no ROM loaded");
    }

    /* Compressed: unpack both once; a tile or cell past the end reads as 0. */
    uint8_t* tileData = NULL;
    uint8_t* mapData = NULL;
    size_t tileSize = 0, mapSize = 0;
    if (useLz77) {
        tileData = lz77_decompress(tilesAddr, &tileSize);
        mapData = lz77_decompress(mapAddr, &mapSize);
        if (!tileData || !mapData) {
            free(tileData);
            free(mapData);
            return luaL_error(L, "gfx.tilemap: no valid LZ77 data at tiles or map");
        }
        if ((size_t) ((y0 + height / 8) * stride) * 2 > mapSize) {
            free(tileData);
            free(mapData);
            return luaL_error(L, "gfx.tilemap: x/y outside the map");
        }
    }

    uint16_t palettes[16][16];
    for (int bank = 0; bank < 16; bank++) {
        for (int i = 0; i < 16; i++) {
            palettes[bank][i] = gbaduo_bus_read16(paletteAddr + (uint32_t) (bank * 32 + i * 2));
        }
    }

    int w = (int) width, h = (int) height;
    uint32_t* pixels = malloc((size_t) w * h * sizeof(uint32_t));
    if (!pixels) {
        free(tileData);
        free(mapData);
        return luaL_error(L, "gfx.tilemap: out of memory");
    }
    for (int ty = 0; ty < h / 8; ty++) {
        for (int tx = 0; tx < w / 8; tx++) {
            uint32_t cell = (uint32_t) ((y0 + ty) * stride + x0 + tx);
            uint16_t entry = mapData ? (uint16_t) (mapData[cell * 2] | mapData[cell * 2 + 1] << 8)
                                     : (uint16_t) gbaduo_bus_read16(mapAddr + cell * 2);
            uint32_t tile = entry & 0x3FF;
            bool hflip = (entry >> 10) & 1;
            bool vflip = (entry >> 11) & 1;
            const uint16_t* palette = palettes[(entry >> 12) & 0xF];
            for (int row = 0; row < 8; row++) {
                int sy = vflip ? 7 - row : row;
                uint32_t at = tile * 32 + (uint32_t) sy * 4;
                uint32_t rowBits;
                if (tileData) {
                    rowBits = at + 4 <= tileSize ? (uint32_t) (tileData[at] | tileData[at + 1] << 8 | tileData[at + 2] << 16 |
                                                               (uint32_t) tileData[at + 3] << 24)
                                                 : 0;
                } else {
                    rowBits = gbaduo_bus_read32(tilesAddr + at);
                }
                for (int col = 0; col < 8; col++) {
                    int sx = hflip ? 7 - col : col;
                    int index = (int) ((rowBits >> (sx * 4)) & 0xF);
                    pixels[(size_t) (ty * 8 + row) * w + tx * 8 + col] = gba_argb(palette[index], index == 0 && !opaque);
                }
            }
        }
    }

    free(tileData);
    free(mapData);
    pthread_mutex_lock(&g_imageLock);
    image_cache_store(key, w, h, pixels);
    pthread_mutex_unlock(&g_imageLock);
    push_image_key(L, key);
    return 1;
}

#define SPRITE_MAX_PARTS 32

/*
 * gfx.sprite{ tiles, palette, width, height, parts = { {x, y, w, h, tile, bank, hflip, vflip}, ... } }
 *
 * Composes a picture the way the GBA draws a multi-part sprite (OAM objects, 1D tile
 * mapping): each part is a [w] x [h] px object (8, 16, 32 or 64) placed at ([x], [y])
 * in the picture, whose 8x8 tiles start at tile number [tile] counted from [tiles] and
 * run row by row, coloured with palette bank [bank] (32 bytes each from [palette]).
 * As on the GBA, an earlier part is drawn over a later one. Colour 0 is transparent.
 */
static int l_gfx_sprite(lua_State* L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_getfield(L, 1, "tiles");
    uint32_t tilesAddr = (uint32_t) luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "palette");
    uint32_t paletteAddr = (uint32_t) luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "width");
    lua_Integer width = luaL_checkinteger(L, -1);
    lua_getfield(L, 1, "height");
    lua_Integer height = luaL_checkinteger(L, -1);
    lua_pop(L, 4);
    if (width <= 0 || height <= 0 || width > IMAGE_MAX_DIMENSION || height > IMAGE_MAX_DIMENSION) {
        return luaL_error(L, "gfx.sprite: width/height must be 1 to %d", IMAGE_MAX_DIMENSION);
    }

    struct Part {
        int32_t x, y, w, h, tile, bank, hflip, vflip;
    } parts[SPRITE_MAX_PARTS];
    int count = 0;
    if (lua_getfield(L, 1, "parts") != LUA_TTABLE) {
        return luaL_error(L, "gfx.sprite: parts must be a list");
    }
    lua_Integer n = (lua_Integer) lua_rawlen(L, -1);
    if (n < 1 || n > SPRITE_MAX_PARTS) {
        return luaL_error(L, "gfx.sprite: 1 to %d parts", SPRITE_MAX_PARTS);
    }
    for (lua_Integer i = 1; i <= n; i++) {
        if (lua_rawgeti(L, -1, i) != LUA_TTABLE) {
            return luaL_error(L, "gfx.sprite: part %d must be a table", (int) i);
        }
        struct Part* p = &parts[count++];
        const char* intFields[] = {"x", "y", "w", "h", "tile", "bank"};
        int32_t* targets[] = {&p->x, &p->y, &p->w, &p->h, &p->tile, &p->bank};
        for (int f = 0; f < 6; f++) {
            lua_getfield(L, -1, intFields[f]);
            *targets[f] = (f < 2 && lua_isnil(L, -1)) ? 0 : (int32_t) luaL_checkinteger(L, -1);
            lua_pop(L, 1);
        }
        lua_getfield(L, -1, "hflip");
        p->hflip = lua_toboolean(L, -1);
        lua_getfield(L, -2, "vflip");
        p->vflip = lua_toboolean(L, -1);
        lua_pop(L, 3);
        bool sizeOk = (p->w == 8 || p->w == 16 || p->w == 32 || p->w == 64) && (p->h == 8 || p->h == 16 || p->h == 32 || p->h == 64);
        if (!sizeOk || p->tile < 0 || p->tile > 0xFFFF || p->bank < 0 || p->bank > 15 ||
            p->x < -64 || p->x > IMAGE_MAX_DIMENSION || p->y < -64 || p->y > IMAGE_MAX_DIMENSION) {
            return luaL_error(L, "gfx.sprite: part %d is invalid (w/h 8-64, tile 0-65535, bank 0-15)", (int) i);
        }
    }
    lua_pop(L, 1);

    struct {
        uint32_t tilesAddr, paletteAddr;
        int32_t width, height, count;
        struct Part parts[SPRITE_MAX_PARTS];
    } descriptor;
    memset(&descriptor, 0, sizeof(descriptor));
    descriptor.tilesAddr = tilesAddr;
    descriptor.paletteAddr = paletteAddr;
    descriptor.width = (int32_t) width;
    descriptor.height = (int32_t) height;
    descriptor.count = count;
    memcpy(descriptor.parts, parts, sizeof(struct Part) * (size_t) count);
    uint32_t key = fnv1a(&descriptor, sizeof(descriptor)) ^ 0x5b17u;

    pthread_mutex_lock(&g_imageLock);
    bool cached = image_cache_find(key) != NULL;
    pthread_mutex_unlock(&g_imageLock);
    if (cached) {
        push_image_key(L, key);
        return 1;
    }
    if (gbaduo_bus_platform() == GBADUO_BUS_NONE) {
        return luaL_error(L, "gfx.sprite: no ROM loaded");
    }

    int w = (int) width, h = (int) height;
    uint32_t* pixels = calloc((size_t) w * h, sizeof(uint32_t));
    if (!pixels) {
        return luaL_error(L, "gfx.sprite: out of memory");
    }
    for (int pi = 0; pi < count; pi++) {
        const struct Part* p = &parts[pi];
        uint16_t palette[16];
        for (int i = 0; i < 16; i++) palette[i] = gbaduo_bus_read16(paletteAddr + (uint32_t) (p->bank * 32 + i * 2));
        int tilesWide = p->w / 8;
        for (int py = 0; py < p->h; py++) {
            for (int px = 0; px < p->w; px++) {
                int dx = p->x + (p->hflip ? p->w - 1 - px : px);
                int dy = p->y + (p->vflip ? p->h - 1 - py : py);
                if (dx < 0 || dy < 0 || dx >= w || dy >= h) continue;
                uint32_t* out = &pixels[(size_t) dy * w + dx];
                if (*out >> 24) continue; /* an earlier part is already here */
                uint32_t tile = (uint32_t) p->tile + (uint32_t) ((py / 8) * tilesWide + px / 8);
                uint8_t byte = gbaduo_bus_read8(tilesAddr + tile * 32 + (uint32_t) ((py % 8) * 4 + (px % 8) / 2));
                int index = (px & 1) ? byte >> 4 : byte & 0xF;
                if (index) *out = gba_argb(palette[index], false);
            }
        }
    }

    pthread_mutex_lock(&g_imageLock);
    image_cache_store(key, w, h, pixels);
    pthread_mutex_unlock(&g_imageLock);
    push_image_key(L, key);
    return 1;
}

/* For tests: a copy of the decoded image for [key] ("gfx:..."), or NULL. Caller frees. */
uint32_t* gbaduo_script_image(const char* key, int* width, int* height) {
    if (strncmp(key, "gfx:", 4) != 0) return NULL;
    uint32_t wanted = (uint32_t) strtoul(key + 4, NULL, 16);
    pthread_mutex_lock(&g_imageLock);
    struct CachedImage* image = image_cache_find(wanted);
    uint32_t* copy = NULL;
    if (image) {
        size_t n = (size_t) image->width * image->height;
        copy = malloc(n * sizeof(uint32_t));
        if (copy) {
            memcpy(copy, image->pixels, n * sizeof(uint32_t));
            *width = image->width;
            *height = image->height;
        }
    }
    pthread_mutex_unlock(&g_imageLock);
    return copy;
}

/* ------------------------------------------------------------------------ */
/* pack.require(name) — loads <packDir>/<name>.lua once, caches the result  */
/* ------------------------------------------------------------------------ */

#define PACK_CACHE_REGISTRY_KEY "gbaduo.pack.cache"

static int l_pack_require(lua_State* L) {
    const char* name = luaL_checkstring(L, 1);
    /* Module names are plain names (letters, digits, '_', '-', '.'): no path separators,
     * no "..", so a pack can only load .lua files from its own folder. */
    if (name[0] == '\0' || strstr(name, "..") != NULL) {
        return luaL_error(L, "pack.require: invalid module name '%s'", name);
    }
    for (const char* c = name; *c; c++) {
        bool ok = (*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || (*c >= '0' && *c <= '9') ||
                  *c == '_' || *c == '-' || *c == '.';
        if (!ok) {
            return luaL_error(L, "pack.require: invalid module name '%s'", name);
        }
    }

    lua_getfield(L, LUA_REGISTRYINDEX, PACK_CACHE_REGISTRY_KEY);
    lua_getfield(L, -1, name);
    if (!lua_isnil(L, -1)) {
        return 1; /* cached result already on top of the stack */
    }
    lua_pop(L, 1); /* pop nil */

    char path[1280];
    snprintf(path, sizeof(path), "%s/%s.lua", g_script.packDir, name);
    FILE* f = fopen(path, "rb");
    if (!f) {
        return luaL_error(L, "pack.require: no such module '%s'", name);
    }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    char* source = size >= 0 ? malloc((size_t) size + 1) : NULL;
    if (!source) {
        fclose(f);
        return luaL_error(L, "pack.require: could not read module '%s'", name);
    }
    size_t read = fread(source, 1, (size_t) size, f);
    fclose(f);

    if (luaL_loadbuffer(L, source, read, name) != LUA_OK) {
        free(source);
        return lua_error(L);
    }
    free(source);

    if (lua_pcall(L, 0, 1, 0) != LUA_OK) {
        return lua_error(L);
    }

    lua_pushvalue(L, -1);
    lua_setfield(L, -3, name); /* cache[name] = result */
    return 1;
}

/* ------------------------------------------------------------------------ */
/* Lua value -> JSON serialization (for update()'s return value)            */
/* ------------------------------------------------------------------------ */

struct JsonBuf {
    char* data;
    size_t len;
    size_t cap;
    bool failed;
};

static bool json_reserve(struct JsonBuf* b, size_t extra) {
    if (b->failed) return false;
    if (b->len + extra + 1 <= b->cap) return true;
    size_t newCap = b->cap == 0 ? 256 : b->cap;
    while (b->len + extra + 1 > newCap) newCap *= 2;
    char* data = realloc(b->data, newCap);
    if (!data) {
        b->failed = true;
        return false;
    }
    b->data = data;
    b->cap = newCap;
    return true;
}

static void json_append(struct JsonBuf* b, const char* s, size_t n) {
    if (!json_reserve(b, n)) return;
    memcpy(b->data + b->len, s, n);
    b->len += n;
    b->data[b->len] = '\0';
}

static void json_append_str(struct JsonBuf* b, const char* s) {
    json_append(b, s, strlen(s));
}

/**
 * Length of the valid UTF-8 sequence starting at s[i] (1-4), or 0 if invalid
 * (bad lead/continuation byte, overlong form, surrogate, or above U+10FFFF).
 * *codepoint receives the decoded value.
 */
static size_t utf8_sequence(const unsigned char* s, size_t n, size_t i, uint32_t* codepoint) {
    unsigned char c = s[i];
    size_t len;
    uint32_t cp;
    if (c < 0x80) {
        *codepoint = c;
        return 1;
    } else if ((c & 0xE0) == 0xC0) {
        len = 2;
        cp = c & 0x1F;
    } else if ((c & 0xF0) == 0xE0) {
        len = 3;
        cp = c & 0x0F;
    } else if ((c & 0xF8) == 0xF0) {
        len = 4;
        cp = c & 0x07;
    } else {
        return 0;
    }
    if (i + len > n) return 0;
    for (size_t k = 1; k < len; k++) {
        if ((s[i + k] & 0xC0) != 0x80) return 0;
        cp = (cp << 6) | (s[i + k] & 0x3F);
    }
    static const uint32_t minimum[5] = {0, 0, 0x80, 0x800, 0x10000};
    if (cp < minimum[len] || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return 0;
    *codepoint = cp;
    return len;
}

/**
 * Appends [s] as a JSON string. The result is handed to JNI's NewStringUTF, which
 * aborts on anything but (modified) UTF-8: invalid bytes (e.g. raw game memory a
 * pack returned as a string) become U+FFFD, and 4-byte sequences are written as
 * escaped surrogate pairs.
 */
static void json_append_escaped(struct JsonBuf* b, const char* str, size_t n) {
    const unsigned char* s = (const unsigned char*) str;
    json_append(b, "\"", 1);
    for (size_t i = 0; i < n;) {
        unsigned char c = s[i];
        if (c == '"' || c == '\\') {
            char esc[2] = {'\\', (char) c};
            json_append(b, esc, 2);
            i++;
        } else if (c == '\n') {
            json_append_str(b, "\\n");
            i++;
        } else if (c < 0x20) {
            char buf[8];
            snprintf(buf, sizeof(buf), "\\u%04x", c);
            json_append_str(b, buf);
            i++;
        } else if (c < 0x80) {
            json_append(b, (const char*) &s[i], 1);
            i++;
        } else {
            uint32_t cp = 0;
            size_t len = utf8_sequence(s, n, i, &cp);
            if (len == 0) {
                json_append_str(b, "\\ufffd");
                i++;
            } else if (len == 4) {
                char buf[16];
                uint32_t v = cp - 0x10000;
                snprintf(buf, sizeof(buf), "\\u%04x\\u%04x", 0xD800 + (v >> 10), 0xDC00 + (v & 0x3FF));
                json_append_str(b, buf);
                i += 4;
            } else {
                json_append(b, (const char*) &s[i], len);
                i += len;
            }
        }
    }
    json_append(b, "\"", 1);
}

static bool table_is_array(lua_State* L, int idx) {
    lua_Integer n = (lua_Integer) lua_rawlen(L, idx);
    if (n == 0) return false;
    lua_Integer count = 0;
    lua_pushnil(L);
    while (lua_next(L, idx) != 0) {
        count++;
        lua_pop(L, 1);
    }
    return count == n;
}

static void json_serialize(lua_State* L, int idx, struct JsonBuf* b, int depth) {
    idx = lua_absindex(L, idx);
    if (depth > JSON_MAX_DEPTH) {
        json_append_str(b, "null");
        return;
    }
    switch (lua_type(L, idx)) {
        case LUA_TNIL:
            json_append_str(b, "null");
            break;
        case LUA_TBOOLEAN:
            json_append_str(b, lua_toboolean(L, idx) ? "true" : "false");
            break;
        case LUA_TNUMBER: {
            char buf[64];
            if (lua_isinteger(L, idx)) {
                snprintf(buf, sizeof(buf), "%lld", (long long) lua_tointeger(L, idx));
            } else {
                double d = lua_tonumber(L, idx);
                if (isnan(d) || isinf(d)) {
                    snprintf(buf, sizeof(buf), "null");
                } else {
                    snprintf(buf, sizeof(buf), "%.17g", d);
                }
            }
            json_append_str(b, buf);
            break;
        }
        case LUA_TSTRING: {
            size_t len;
            const char* s = lua_tolstring(L, idx, &len);
            json_append_escaped(b, s, len);
            break;
        }
        case LUA_TTABLE: {
            if (table_is_array(L, idx)) {
                json_append(b, "[", 1);
                lua_Integer n = (lua_Integer) lua_rawlen(L, idx);
                for (lua_Integer i = 1; i <= n; i++) {
                    if (i > 1) json_append(b, ",", 1);
                    lua_geti(L, idx, i);
                    json_serialize(L, -1, b, depth + 1);
                    lua_pop(L, 1);
                }
                json_append(b, "]", 1);
            } else {
                json_append(b, "{", 1);
                bool first = true;
                lua_pushnil(L);
                while (lua_next(L, idx) != 0) {
                    if (lua_type(L, -2) == LUA_TSTRING) {
                        if (!first) json_append(b, ",", 1);
                        first = false;
                        size_t keyLen;
                        const char* key = lua_tolstring(L, -2, &keyLen);
                        json_append_escaped(b, key, keyLen);
                        json_append(b, ":", 1);
                        json_serialize(L, -1, b, depth + 1);
                    }
                    lua_pop(L, 1);
                }
                json_append(b, "}", 1);
            }
            break;
        }
        default:
            json_append_str(b, "null");
            break;
    }
}

/* ------------------------------------------------------------------------ */
/* Sandbox setup + JNI entry points                                        */
/* ------------------------------------------------------------------------ */

static void remove_global(lua_State* L, const char* name) {
    lua_pushnil(L);
    lua_setglobal(L, name);
}

static void register_api(lua_State* L) {
    static const luaL_Reg memFuncs[] = {
        {"u8", l_mem_u8}, {"s8", l_mem_s8},
        {"u16", l_mem_u16}, {"s16", l_mem_s16},
        {"u32", l_mem_u32}, {"s32", l_mem_s32},
        {"read", l_mem_read},
        {"write8", l_mem_write8}, {"write16", l_mem_write16},
        {"write32", l_mem_write32}, {"write", l_mem_write},
        {NULL, NULL},
    };
    luaL_newlib(L, memFuncs);
    lua_setglobal(L, "mem");

    static const luaL_Reg gfxFuncs[] = {
        {"decode", l_gfx_decode},
        {"tilemap", l_gfx_tilemap},
        {"sprite", l_gfx_sprite},
        {NULL, NULL},
    };
    luaL_newlib(L, gfxFuncs);
    lua_setglobal(L, "gfx");

    static const luaL_Reg packFuncs[] = {
        {"require", l_pack_require},
        {NULL, NULL},
    };
    luaL_newlib(L, packFuncs);
    lua_setglobal(L, "pack");

    lua_pushcfunction(L, l_lz77);
    lua_setglobal(L, "lz77");

    lua_pushcfunction(L, l_log);
    lua_setglobal(L, "log");
}

static bool script_setup(lua_State** outL) {
    lua_State* L = lua_newstate(script_alloc, &g_script);
    if (!L) return false;

    luaL_requiref(L, LUA_GNAME, luaopen_base, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_TABLIBNAME, luaopen_table, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_STRLIBNAME, luaopen_string, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_MATHLIBNAME, luaopen_math, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_UTF8LIBNAME, luaopen_utf8, 1);
    lua_pop(L, 1);

    remove_global(L, "dofile");
    remove_global(L, "loadfile");
    remove_global(L, "load");
    remove_global(L, "collectgarbage");
    remove_global(L, "require");
    remove_global(L, "print");

    lua_getglobal(L, "string");
    lua_pushnil(L);
    lua_setfield(L, -2, "dump");
    lua_pop(L, 1);

    register_api(L);

    lua_newtable(L);
    lua_setfield(L, LUA_REGISTRYINDEX, PACK_CACHE_REGISTRY_KEY);

    lua_sethook(L, instruction_hook, LUA_MASKCOUNT, HOOK_INSTRUCTION_INTERVAL);

    *outL = L;
    return true;
}

static void free_images(void) {
    pthread_mutex_lock(&g_imageLock);
    for (int i = 0; i < IMAGE_CACHE_SIZE; i++) {
        if (g_script.images[i].used) {
            free(g_script.images[i].pixels);
        }
    }
    memset(g_script.images, 0, sizeof(g_script.images));
    g_script.nextImageSlot = 0;
    pthread_mutex_unlock(&g_imageLock);
}

/* ---- the sandbox without JNI (script.h) ---------------------------------- */

static void set_error(const char* message) {
    strncpy(g_script.lastError, message ? message : "unknown error", sizeof(g_script.lastError) - 1);
    g_script.lastError[sizeof(g_script.lastError) - 1] = '\0';
}

const char* gbaduo_script_last_error(void) {
    return g_script.lastError;
}

bool gbaduo_script_load(const char* source, const char* packDir) {
    if (g_script.L) {
        lua_close(g_script.L);
        g_script.L = NULL;
    }
    free_images();
    g_script.memUsed = 0;
    g_script.lastError[0] = '\0';
    g_script.writeWindow = false;
    g_script.tapBytesLeft = 0;

    strncpy(g_script.packDir, packDir ? packDir : "", sizeof(g_script.packDir) - 1);

    lua_State* L;
    if (!script_setup(&L)) {
        set_error("could not create Lua state (out of memory)");
        return false;
    }

    g_script.instructionsLeft = INSTRUCTION_BUDGET_PER_TICK;
    if (luaL_loadstring(L, source) != LUA_OK || lua_pcall(L, 0, 0, 0) != LUA_OK) {
        set_error(lua_tostring(L, -1));
        lua_close(L);
        return false;
    }

    g_script.L = L;

    /* Optional init(), run once after load. */
    lua_getglobal(L, "init");
    if (lua_isfunction(L, -1)) {
        g_script.instructionsLeft = INSTRUCTION_BUDGET_PER_TICK;
        if (lua_pcall(L, 0, 0, 0) != LUA_OK) {
            const char* err = lua_tostring(L, -1);
            set_error(err ? err : "init() failed");
            lua_close(L);
            g_script.L = NULL;
            return false;
        }
    } else {
        lua_pop(L, 1);
    }
    return true;
}

/* The table on top of the stack as malloc'd JSON, popped; NULL (with [what] in the error) if too large. */
static char* pop_json(lua_State* L, const char* what) {
    struct JsonBuf buf = {0};
    json_serialize(L, -1, &buf, 0);
    lua_pop(L, 1);
    if (buf.failed) {
        free(buf.data);
        char message[128];
        snprintf(message, sizeof(message), "%s result too large (out of memory)", what);
        set_error(message);
        return NULL;
    }
    if (!buf.data) {
        char* empty = malloc(3);
        if (empty) memcpy(empty, "{}", 3);
        return empty;
    }
    return buf.data;
}

char* gbaduo_script_tick(void) {
    if (!g_script.L) {
        return NULL;
    }
    lua_State* L = g_script.L;
    g_script.lastError[0] = '\0';

    lua_getglobal(L, "update");
    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 1);
        set_error("pack has no update() function");
        return NULL;
    }

    lua_getglobal(L, "ui"); /* update(ui): the companion screen's UI state */
    g_script.instructionsLeft = INSTRUCTION_BUDGET_PER_TICK;
    if (lua_pcall(L, 1, 1, 0) != LUA_OK) {
        const char* err = lua_tostring(L, -1);
        set_error(err ? err : "update() failed");
        lua_pop(L, 1);
        return NULL;
    }
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        set_error("update() must return a table");
        return NULL;
    }
    return pop_json(L, "update()");
}

struct ActionCall {
    const char* name;
    gbaduo_push_args push;
    void* userdata;
    int count;
};

/* actions[name](args...), returning its first result. */
static int call_action(lua_State* L) {
    struct ActionCall* c = (struct ActionCall*) lua_touserdata(L, 1);
    lua_settop(L, 0);
    if (lua_getglobal(L, "actions") != LUA_TTABLE) {
        return luaL_error(L, "pack has no 'actions' table (for '%s')", c->name);
    }
    lua_getfield(L, -1, c->name);
    if (!lua_isfunction(L, -1)) {
        return luaL_error(L, "pack has no actions.%s() function", c->name);
    }
    lua_remove(L, 1); /* actions */
    luaL_checkstack(L, c->count, "too many action arguments");
    if (c->push && c->count > 0) {
        c->push(L, c->userdata);
    }
    lua_call(L, c->count, 1);
    return 1;
}

char* gbaduo_script_action(const char* name, gbaduo_push_args push, void* userdata, int nargs, bool may_write) {
    if (!g_script.L) {
        return NULL;
    }
    lua_State* L = g_script.L;
    g_script.lastError[0] = '\0';

    struct ActionCall c = {name, push, userdata, push ? nargs : 0};
    lua_pushcfunction(L, call_action);
    lua_pushlightuserdata(L, &c);
    g_script.instructionsLeft = INSTRUCTION_BUDGET_PER_TICK;
    /* The write window is open for exactly this protected call: lua_pcall returns on
     * success and on every error (a Lua error, out of memory, the instruction budget),
     * so it's always closed again below. */
    g_script.writeWindow = may_write;
    g_script.tapBytesLeft = SCRIPT_WRITE_MAX_PER_TAP;
    int status = lua_pcall(L, 1, 1, 0);
    g_script.writeWindow = false;
    g_script.tapBytesLeft = 0;

    if (status != LUA_OK) {
        const char* err = lua_tostring(L, -1);
        set_error(err ? err : "action failed");
        lua_pop(L, 1);
        return NULL;
    }
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1); /* no UI changes requested */
        return NULL;
    }
    return pop_json(L, "action");
}

void gbaduo_script_unload(void) {
    if (g_script.L) {
        lua_close(g_script.L);
        g_script.L = NULL;
    }
    free_images();
    g_script.memUsed = 0;
    g_script.writeWindow = false;
    g_script.tapBytesLeft = 0;
}

/* ---- JNI entry points (NativeCore) ------------------------------------------ */

#ifndef GBADUO_HOST_TEST

JNIEXPORT jboolean JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_loadScript(JNIEnv* env, jobject thiz, jstring source, jstring packDir) {
    const char* packDirChars = (*env)->GetStringUTFChars(env, packDir, NULL);
    const char* sourceChars = (*env)->GetStringUTFChars(env, source, NULL);
    bool ok = packDirChars && sourceChars && gbaduo_script_load(sourceChars, packDirChars);
    if (sourceChars) (*env)->ReleaseStringUTFChars(env, source, sourceChars);
    if (packDirChars) (*env)->ReleaseStringUTFChars(env, packDir, packDirChars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_tickScript(JNIEnv* env, jobject thiz) {
    char* json = gbaduo_script_tick();
    if (!json) {
        return NULL;
    }
    jstring result = (*env)->NewStringUTF(env, json);
    free(json);
    return result;
}

/* ------------------------------------------------------------------------ */
/* Companion screen UI state and actions                                    */
/* ------------------------------------------------------------------------ */

#define ACTION_MAX_ARGS 16

/** Pushes a java.lang String/Boolean/Number as a Lua value; null and anything else become nil. */
static void push_java_value(JNIEnv* env, lua_State* L, jobject value) {
    if (value == NULL) {
        lua_pushnil(L);
        return;
    }
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jclass booleanClass = (*env)->FindClass(env, "java/lang/Boolean");
    jclass longClass = (*env)->FindClass(env, "java/lang/Long");
    jclass numberClass = (*env)->FindClass(env, "java/lang/Number");
    if ((*env)->IsInstanceOf(env, value, stringClass)) {
        const char* chars = (*env)->GetStringUTFChars(env, (jstring) value, NULL);
        lua_pushstring(L, chars ? chars : "");
        if (chars) (*env)->ReleaseStringUTFChars(env, (jstring) value, chars);
    } else if ((*env)->IsInstanceOf(env, value, booleanClass)) {
        jmethodID booleanValue = (*env)->GetMethodID(env, booleanClass, "booleanValue", "()Z");
        lua_pushboolean(L, (*env)->CallBooleanMethod(env, value, booleanValue));
    } else if ((*env)->IsInstanceOf(env, value, longClass)) {
        jmethodID longValue = (*env)->GetMethodID(env, numberClass, "longValue", "()J");
        lua_pushinteger(L, (lua_Integer) (*env)->CallLongMethod(env, value, longValue));
    } else if ((*env)->IsInstanceOf(env, value, numberClass)) {
        jmethodID doubleValue = (*env)->GetMethodID(env, numberClass, "doubleValue", "()D");
        lua_pushnumber(L, (lua_Number) (*env)->CallDoubleMethod(env, value, doubleValue));
    } else {
        lua_pushnil(L);
    }
    (*env)->DeleteLocalRef(env, stringClass);
    (*env)->DeleteLocalRef(env, booleanClass);
    (*env)->DeleteLocalRef(env, longClass);
    (*env)->DeleteLocalRef(env, numberClass);
}

/* What the protected helpers below read; Lua errors in them (e.g. out of memory)
 * are caught by lua_pcall instead of aborting the app. */
struct JavaCall {
    JNIEnv* env;
    jobjectArray keys;   /* setScriptUi */
    jobjectArray values; /* setScriptUi: values; callScriptAction: arguments */
    jsize count;
    const char* name;    /* callScriptAction */
};

/* ui = { [keys[i]] = values[i], ... } */
static int build_ui(lua_State* L) {
    struct JavaCall* c = (struct JavaCall*) lua_touserdata(L, 1);
    JNIEnv* env = c->env;
    lua_createtable(L, 0, c->count);
    for (jsize i = 0; i < c->count; i++) {
        jstring key = (jstring) (*env)->GetObjectArrayElement(env, c->keys, i);
        if (!key) continue;
        const char* keyChars = (*env)->GetStringUTFChars(env, key, NULL);
        if (keyChars) {
            lua_pushstring(L, keyChars);
            (*env)->ReleaseStringUTFChars(env, key, keyChars);
            jobject value = (*env)->GetObjectArrayElement(env, c->values, i);
            push_java_value(env, L, value);
            if (value) (*env)->DeleteLocalRef(env, value);
            lua_rawset(L, -3);
        }
        (*env)->DeleteLocalRef(env, key);
    }
    lua_setglobal(L, "ui");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_setScriptUi(JNIEnv* env, jobject thiz, jobjectArray keys, jobjectArray values) {
    if (!g_script.L) {
        return;
    }
    lua_State* L = g_script.L;
    jsize n = (*env)->GetArrayLength(env, keys);
    jsize nValues = (*env)->GetArrayLength(env, values);
    struct JavaCall c = {env, keys, values, n < nValues ? n : nValues, NULL};
    lua_pushcfunction(L, build_ui);
    lua_pushlightuserdata(L, &c);
    g_script.instructionsLeft = INSTRUCTION_BUDGET_PER_TICK;
    if (lua_pcall(L, 1, 0, 0) != LUA_OK) {
        const char* err = lua_tostring(L, -1);
        LOGE("could not set ui: %s", err ? err : "?");
        lua_pop(L, 1);
    }
}

struct JniArgs {
    JNIEnv* env;
    jobjectArray values;
    jsize count;
};

static void push_jni_args(lua_State* L, void* userdata) {
    struct JniArgs* a = (struct JniArgs*) userdata;
    for (jsize i = 0; i < a->count; i++) {
        jobject arg = (*a->env)->GetObjectArrayElement(a->env, a->values, i);
        push_java_value(a->env, L, arg);
        if (arg) (*a->env)->DeleteLocalRef(a->env, arg);
    }
}

JNIEXPORT jstring JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_callScriptAction(JNIEnv* env, jobject thiz, jstring name, jobjectArray args,
                                                      jboolean mayWrite) {
    const char* nameChars = (*env)->GetStringUTFChars(env, name, NULL);
    if (!nameChars) {
        return NULL;
    }
    jsize nargs = args ? (*env)->GetArrayLength(env, args) : 0;
    struct JniArgs a = {env, args, nargs < ACTION_MAX_ARGS ? nargs : ACTION_MAX_ARGS};
    char* json = gbaduo_script_action(nameChars, push_jni_args, &a, (int) a.count, mayWrite == JNI_TRUE);
    (*env)->ReleaseStringUTFChars(env, name, nameChars);
    if (!json) {
        return NULL;
    }
    jstring result = (*env)->NewStringUTF(env, json);
    free(json);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_lastScriptError(JNIEnv* env, jobject thiz) {
    /* Lua error messages can quote script bytes; keep only valid UTF-8 for NewStringUTF. */
    char safe[sizeof(g_script.lastError)];
    const unsigned char* s = (const unsigned char*) g_script.lastError;
    size_t n = strlen(g_script.lastError);
    size_t out = 0;
    for (size_t i = 0; i < n && out < sizeof(safe) - 1;) {
        uint32_t cp;
        size_t len = utf8_sequence(s, n, i, &cp);
        if (len == 0 || len == 4 || out + len > sizeof(safe) - 1) {
            safe[out++] = '?';
            i += len == 0 ? 1 : len;
        } else {
            memcpy(&safe[out], &s[i], len);
            out += len;
            i += len;
        }
    }
    safe[out] = '\0';
    return (*env)->NewStringUTF(env, safe);
}

JNIEXPORT void JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_unloadScript(JNIEnv* env, jobject thiz) {
    gbaduo_script_unload();
}

JNIEXPORT jintArray JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_nativeImage(JNIEnv* env, jobject thiz, jstring key) {
    const char* keyChars = (*env)->GetStringUTFChars(env, key, NULL);
    uint32_t wanted = 0;
    if (strncmp(keyChars, "gfx:", 4) == 0) {
        wanted = (uint32_t) strtoul(keyChars + 4, NULL, 16);
    }
    (*env)->ReleaseStringUTFChars(env, key, keyChars);

    /* Copy under the lock: the emu thread may evict or free this entry at any time. */
    pthread_mutex_lock(&g_imageLock);
    struct CachedImage* image = image_cache_find(wanted);
    jint* buffer = NULL;
    jsize total = 0;
    if (image) {
        total = 2 + image->width * image->height;
        buffer = malloc((size_t) total * sizeof(jint));
        if (buffer) {
            buffer[0] = image->width;
            buffer[1] = image->height;
            for (int i = 0; i < image->width * image->height; i++) {
                buffer[2 + i] = (jint) image->pixels[i];
            }
        }
    }
    pthread_mutex_unlock(&g_imageLock);
    if (!buffer) {
        return NULL;
    }

    jintArray result = (*env)->NewIntArray(env, total);
    if (result) {
        (*env)->SetIntArrayRegion(env, result, 0, total, buffer);
    }
    free(buffer);
    return result;
}

#endif /* GBADUO_HOST_TEST */
