#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <lua.h>
#include <lauxlib.h>
#include <lualib.h>

#include <mgba/core/core.h>

#define LOG_TAG "GBADuoScript"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SCRIPT_MEMORY_LIMIT (16u * 1024u * 1024u)
#define INSTRUCTION_BUDGET_PER_TICK 5000000L
#define HOOK_INSTRUCTION_INTERVAL 10000
#define JSON_MAX_DEPTH 32
#define IMAGE_CACHE_SIZE 16
/* Caps on pack-controlled sizes: these buffers live outside the Lua memory limit
 * and are filled by C loops the instruction budget doesn't count. */
#define LZ77_MAX_OUTPUT (1024u * 1024u)
#define IMAGE_MAX_DIMENSION 256

/** Implemented in core.c; NULL when no ROM is loaded. */
extern struct mCore* gbaduo_active_core(void);

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
    struct mCore* core = gbaduo_active_core();
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    lua_pushinteger(L, core ? core->rawRead8(core, addr, -1) : 0);
    return 1;
}

static int l_mem_s8(lua_State* L) {
    struct mCore* core = gbaduo_active_core();
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    int8_t v = core ? (int8_t) core->rawRead8(core, addr, -1) : 0;
    lua_pushinteger(L, v);
    return 1;
}

static int l_mem_u16(lua_State* L) {
    struct mCore* core = gbaduo_active_core();
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    lua_pushinteger(L, core ? core->rawRead16(core, addr, -1) : 0);
    return 1;
}

static int l_mem_s16(lua_State* L) {
    struct mCore* core = gbaduo_active_core();
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    int16_t v = core ? (int16_t) core->rawRead16(core, addr, -1) : 0;
    lua_pushinteger(L, v);
    return 1;
}

static int l_mem_u32(lua_State* L) {
    struct mCore* core = gbaduo_active_core();
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    lua_pushinteger(L, core ? (lua_Integer) (uint32_t) core->rawRead32(core, addr, -1) : 0);
    return 1;
}

static int l_mem_s32(lua_State* L) {
    struct mCore* core = gbaduo_active_core();
    uint32_t addr = (uint32_t) luaL_checkinteger(L, 1);
    int32_t v = core ? (int32_t) core->rawRead32(core, addr, -1) : 0;
    lua_pushinteger(L, v);
    return 1;
}

static int l_mem_read(lua_State* L) {
    struct mCore* core = gbaduo_active_core();
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
        buf[i] = core ? (char) core->rawRead8(core, addr + (uint32_t) i, -1) : 0;
    }
    lua_pushlstring(L, buf, (size_t) n);
    free(buf);
    return 1;
}

/* ------------------------------------------------------------------------ */
/* log()                                                                    */
/* ------------------------------------------------------------------------ */

static int l_log(lua_State* L) {
    const char* msg = luaL_tolstring(L, 1, NULL);
    __android_log_print(ANDROID_LOG_INFO, "GBADuoPack", "%s", msg);
    lua_pop(L, 1); /* pop the string luaL_tolstring pushed */
    return 0;
}

/* ------------------------------------------------------------------------ */
/* LZ77 (GBA BIOS type-0x10 compression)                                    */
/* ------------------------------------------------------------------------ */

/** Decompresses GBA LZ77 data starting at [addr]; returns a malloc'd buffer and sets *outSize. */
static uint8_t* lz77_decompress(uint32_t addr, size_t* outSize) {
    struct mCore* core = gbaduo_active_core();
    if (!core) {
        *outSize = 0;
        return NULL;
    }
    uint32_t header = core->rawRead32(core, addr, -1);
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
        uint8_t flags = (uint8_t) core->rawRead8(core, src++, -1);
        for (int bit = 7; bit >= 0 && outPos < size; bit--) {
            if ((flags & (1 << bit)) == 0) {
                out[outPos++] = (uint8_t) core->rawRead8(core, src++, -1);
            } else {
                uint8_t b0 = (uint8_t) core->rawRead8(core, src++, -1);
                uint8_t b1 = (uint8_t) core->rawRead8(core, src++, -1);
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

    struct mCore* core = gbaduo_active_core();
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
            tileData[i] = (uint8_t) core->rawRead8(core, tilesAddr + (uint32_t) i, -1);
        }
    }

    uint16_t palette[16];
    for (int i = 0; i < 16; i++) {
        palette[i] = (uint16_t) core->rawRead16(core, paletteAddr + (uint32_t) i * 2, -1);
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
                        uint16_t entry = palette[paletteIndex];
                        uint32_t r = (entry & 0x1F) << 3;
                        uint32_t g = ((entry >> 5) & 0x1F) << 3;
                        uint32_t b = ((entry >> 10) & 0x1F) << 3;
                        uint32_t a = paletteIndex == 0 ? 0 : 0xFFu;
                        uint32_t argb = (a << 24) | (r << 16) | (g << 8) | b;
                        pixels[(size_t) py * width + (px + k)] = argb;
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
        {NULL, NULL},
    };
    luaL_newlib(L, memFuncs);
    lua_setglobal(L, "mem");

    static const luaL_Reg gfxFuncs[] = {
        {"decode", l_gfx_decode},
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

JNIEXPORT jboolean JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_loadScript(JNIEnv* env, jobject thiz, jstring source, jstring packDir) {
    if (g_script.L) {
        lua_close(g_script.L);
        g_script.L = NULL;
    }
    free_images();
    g_script.memUsed = 0;
    g_script.lastError[0] = '\0';

    const char* packDirChars = (*env)->GetStringUTFChars(env, packDir, NULL);
    strncpy(g_script.packDir, packDirChars, sizeof(g_script.packDir) - 1);
    (*env)->ReleaseStringUTFChars(env, packDir, packDirChars);

    lua_State* L;
    if (!script_setup(&L)) {
        strncpy(g_script.lastError, "could not create Lua state (out of memory)", sizeof(g_script.lastError) - 1);
        return JNI_FALSE;
    }

    const char* sourceChars = (*env)->GetStringUTFChars(env, source, NULL);
    g_script.instructionsLeft = INSTRUCTION_BUDGET_PER_TICK;
    int loadResult = luaL_loadstring(L, sourceChars);
    (*env)->ReleaseStringUTFChars(env, source, sourceChars);

    if (loadResult != LUA_OK || lua_pcall(L, 0, 0, 0) != LUA_OK) {
        const char* err = lua_tostring(L, -1);
        strncpy(g_script.lastError, err ? err : "unknown script error", sizeof(g_script.lastError) - 1);
        lua_close(L);
        return JNI_FALSE;
    }

    g_script.L = L;

    /* Optional init(), run once after load. */
    lua_getglobal(L, "init");
    if (lua_isfunction(L, -1)) {
        g_script.instructionsLeft = INSTRUCTION_BUDGET_PER_TICK;
        if (lua_pcall(L, 0, 0, 0) != LUA_OK) {
            const char* err = lua_tostring(L, -1);
            strncpy(g_script.lastError, err ? err : "init() failed", sizeof(g_script.lastError) - 1);
            lua_close(L);
            g_script.L = NULL;
            return JNI_FALSE;
        }
    } else {
        lua_pop(L, 1);
    }

    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_tickScript(JNIEnv* env, jobject thiz) {
    if (!g_script.L) {
        return NULL;
    }
    lua_State* L = g_script.L;

    lua_getglobal(L, "update");
    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 1);
        strncpy(g_script.lastError, "pack has no update() function", sizeof(g_script.lastError) - 1);
        return NULL;
    }

    g_script.instructionsLeft = INSTRUCTION_BUDGET_PER_TICK;
    if (lua_pcall(L, 0, 1, 0) != LUA_OK) {
        const char* err = lua_tostring(L, -1);
        strncpy(g_script.lastError, err ? err : "update() failed", sizeof(g_script.lastError) - 1);
        lua_pop(L, 1);
        return NULL;
    }

    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        strncpy(g_script.lastError, "update() must return a table", sizeof(g_script.lastError) - 1);
        return NULL;
    }

    struct JsonBuf buf = {0};
    json_serialize(L, -1, &buf, 0);
    lua_pop(L, 1);
    if (buf.failed) {
        free(buf.data);
        strncpy(g_script.lastError, "update() result too large (out of memory)", sizeof(g_script.lastError) - 1);
        return NULL;
    }

    jstring result = (*env)->NewStringUTF(env, buf.data ? buf.data : "{}");
    free(buf.data);
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
    if (g_script.L) {
        lua_close(g_script.L);
        g_script.L = NULL;
    }
    free_images();
    g_script.memUsed = 0;
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
