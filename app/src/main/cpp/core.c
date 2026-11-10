#include <jni.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/serialize.h>
#include <mgba/gba/core.h>
#include <mgba-util/vfs.h>

#include "bus.h"
#include "layers.h"
#include "script.h"
#include "wide.h"

#define LOG_TAG "GBADuoCore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define AUDIO_SAMPLE_RATE 32768

/* Frames the margins stay as last painted once the world stops matching, before they go black. */
#define WIDE_FREEZE_FRAMES 30

struct GbaDuoState {
    struct mCore* core;
    color_t* videoBuffer;
    size_t videoBufferBytes;
    unsigned videoWidth;
    unsigned videoHeight;
    /* Wide view (GBA only): the frame sits [wideLeft] columns and [wideTop] rows into a [videoWidth] x [videoHeight] picture
     * (the middle, unless a map edge is near: see wide_clamp_frame). */
    unsigned wideLeft;
    unsigned wideTop;
    struct WideGate wideGate;
    float wideMatch;
    bool wideBlank;           /* the margins are already zeroed */
    unsigned wideHiddenFrames; /* frames in a row the gate kept the margins from being repainted */
    float wideCoverage;       /* share of the margin area inside the map (see wide_coverage) */
    unsigned wideProbeW, wideProbeH; /* at 240x160: the size Wide view would draw, for [probe_wide]; 0 = not wanted */
    int wideErrorLogCountdown;
    void* romBuffer;
    size_t romSize;
};

static struct GbaDuoState g_state;

/* While the picture is bigger than the game's frame, mGBA draws the frame here and paint_wide_margins() puts it into the picture. */
static color_t g_wideStage[WIDE_FRAME_W * WIDE_FRAME_H];

/** The loaded ROM's bytes (for achievements.c's RetroAchievements hash); NULL when none. */
const void* gbaduo_rom(size_t* size) {
    if (size) {
        *size = g_state.core ? g_state.romSize : 0;
    }
    return g_state.core ? g_state.romBuffer : NULL;
}

/** Used by script.c's mem.* Lua API; NULL when no ROM is loaded. */
struct mCore* gbaduo_active_core(void) {
    return g_state.core;
}

/* ---- bus.h backend: script.c's mem.* and gfx.* go through these ---------------- */

int gbaduo_bus_platform(void) {
    if (!g_state.core) {
        return GBADUO_BUS_NONE;
    }
    return g_state.core->platform(g_state.core) == mPLATFORM_GBA ? GBADUO_BUS_GBA : GBADUO_BUS_GB;
}

uint8_t gbaduo_bus_read8(uint32_t address) {
    return g_state.core ? (uint8_t) g_state.core->rawRead8(g_state.core, address, -1) : 0;
}

uint16_t gbaduo_bus_read16(uint32_t address) {
    return g_state.core ? (uint16_t) g_state.core->rawRead16(g_state.core, address, -1) : 0;
}

uint32_t gbaduo_bus_read32(uint32_t address) {
    return g_state.core ? (uint32_t) g_state.core->rawRead32(g_state.core, address, -1) : 0;
}

/* Unchecked: only memwrite.c calls this, after its region check. Runs on the emu
 * thread between frames (script.c's actions), so it never races mGBA. */
void gbaduo_bus_write8(uint32_t address, uint8_t value) {
    if (g_state.core) {
        g_state.core->rawWrite8(g_state.core, address, -1, value);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_load(JNIEnv* env, jobject thiz, jbyteArray rom, jstring savePath) {
    if (g_state.core) {
        return JNI_FALSE;
    }

    jsize romSize = (*env)->GetArrayLength(env, rom);
    void* romBuffer = malloc((size_t) romSize);
    if (!romBuffer) {
        return JNI_FALSE;
    }
    (*env)->GetByteArrayRegion(env, rom, 0, romSize, (jbyte*) romBuffer);

    // mCoreFindVF picks the GBA or GB core (the GB core also runs GBC) from the ROM itself.
    struct VFile* romFile = VFileFromMemory(romBuffer, (size_t) romSize);
    struct mCore* core = romFile ? mCoreFindVF(romFile) : NULL;
    if (!core) {
        LOGE("not a GBA/GB/GBC ROM");
        if (romFile) {
            romFile->close(romFile);
        }
        free(romBuffer);
        return JNI_FALSE;
    }

    mCoreInitConfig(core, NULL);
    core->init(core);

    // Super Game Boy borders default to on, which would turn SGB-enhanced games' output
    // into 256x224 with a frame around it. Keep every GB/GBC game at its native 160x144.
    // reloadConfigOption touches only this option (a full mCoreLoadConfig would also
    // apply the config's unset volume of 0 and mute the GB core). No-op on GBA.
    mCoreConfigSetIntValue(&core->config, "sgb.borders", 0);
    core->reloadConfigOption(core, "sgb.borders", NULL);

    unsigned videoWidth = 0;
    unsigned videoHeight = 0;
    core->desiredVideoDimensions(core, &videoWidth, &videoHeight);
    /* A GBA game's buffer has room for the biggest wide view; the frame itself stays 240x160 until setWide. */
    bool gba = core->platform(core) == mPLATFORM_GBA;
    size_t bufferBytes = (size_t) (gba ? WIDE_MAX_W : videoWidth) * (gba ? WIDE_MAX_H : videoHeight) * sizeof(color_t);
    color_t* videoBuffer = calloc(1, bufferBytes);
    if (!videoBuffer) {
        mCoreConfigDeinit(&core->config);
        core->deinit(core);
        romFile->close(romFile);
        free(romBuffer);
        return JNI_FALSE;
    }
    core->setVideoBuffer(core, videoBuffer, videoWidth);

    if (!core->loadROM(core, romFile)) {
        LOGE("loadROM failed");
        mCoreConfigDeinit(&core->config);
        core->deinit(core); // owns (and closes) romFile once loadROM has seen it
        free(videoBuffer);
        free(romBuffer);
        return JNI_FALSE;
    }

    const char* savePathChars = (*env)->GetStringUTFChars(env, savePath, NULL);
    struct VFile* saveFile = VFileOpen(savePathChars, O_RDWR | O_CREAT);
    (*env)->ReleaseStringUTFChars(env, savePath, savePathChars);
    if (saveFile) {
        core->loadSave(core, saveFile);
    }

    core->reset(core);

    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), AUDIO_SAMPLE_RATE);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), AUDIO_SAMPLE_RATE);
    core->setAudioBufferSize(core, 2048);

    g_state.core = core;
    g_state.videoBuffer = videoBuffer;
    g_state.videoBufferBytes = bufferBytes;
    g_state.videoWidth = videoWidth;
    g_state.videoHeight = videoHeight;
    g_state.romBuffer = romBuffer;
    g_state.romSize = (size_t) romSize;
    return JNI_TRUE;
}

/* ---- wide view: the margins beside the frame, from game.lua's wide() ---------- */

static void blank_wide_margins(void) {
    unsigned width = g_state.videoWidth;
    unsigned left = g_state.wideLeft;
    unsigned top = g_state.wideTop;
    unsigned right = width - WIDE_FRAME_W - left;
    for (unsigned row = 0; row < g_state.videoHeight; row++) {
        color_t* line = g_state.videoBuffer + (size_t) row * width;
        if (row < top || row >= top + WIDE_FRAME_H) {
            memset(line, 0, width * sizeof(color_t));
        } else {
            memset(line, 0, left * sizeof(color_t));
            memset(line + left + WIDE_FRAME_W, 0, right * sizeof(color_t));
        }
    }
    g_state.wideBlank = true;
}

/* The frame mGBA drew (in the staging buffer) into the picture at ([left], [top]). */
static void place_frame(int left, int top) {
    int width = (int) g_state.videoWidth;
    for (int row = 0; row < WIDE_FRAME_H; row++) {
        memcpy(g_state.videoBuffer + (size_t) (top + row) * (size_t) width + (size_t) left,
               g_wideStage + (size_t) row * WIDE_FRAME_W, WIDE_FRAME_W * sizeof(color_t));
    }
}

/* After each emulated frame: ask game.lua for the world, check its prediction against the
 * edge pixels mGBA just drew (in the staging buffer), then put the frame into the picture and paint
 * the margins around it, only while the world holds up. The frame sits in the middle unless a map edge
 * is near: wide_clamp_frame() then moves it toward the edge, so the picture stays inside the map. */
static void paint_wide_margins(void) {
    int width = (int) g_state.videoWidth;
    int height = (int) g_state.videoHeight;
    int left = (int) g_state.wideLeft;
    int top = (int) g_state.wideTop;
    uint32_t* buf = (uint32_t*) g_state.videoBuffer;
    const uint32_t* frame = (const uint32_t*) g_wideStage;

    struct WideWorld world;
    bool have = gbaduo_game_enabled() && gbaduo_game_wide(width, height, left, top, g_state.wideMatch, &world);
    int newLeft = (width - WIDE_FRAME_W) / 2;
    int newTop = (height - WIDE_FRAME_H) / 2;
    if (!have) {
        const char* error = gbaduo_game_last_error();
        if (error[0] && g_state.wideErrorLogCountdown-- <= 0) {
            g_state.wideErrorLogCountdown = 300;
            LOGE("game.lua: %s", error);
        }
        g_state.wideMatch = 0.0f;
        g_state.wideCoverage = 0.0f;
    } else {
        g_state.wideMatch = wide_seam_match(&world, frame, WIDE_FRAME_W, 0, 0, height > WIDE_FRAME_H);
        wide_clamp_frame(&world, width, height, &newLeft, &newTop);
        g_state.wideCoverage = wide_coverage(&world, newLeft, newTop, width, height);
    }

    if (!wide_gate_step(&g_state.wideGate, have, g_state.wideMatch)) {
        /* Keep the last painted margins for a moment instead of blanking them: the app is about to zoom back to
         * the game's own picture (a menu, a small room) and the old margins zoom out of view with it. The frame
         * stays where those margins have it. */
        place_frame(left, top);
        if (g_state.wideHiddenFrames++ >= WIDE_FREEZE_FRAMES && !g_state.wideBlank) {
            blank_wide_margins();
        }
        return;
    }
    g_state.wideHiddenFrames = 0;
    left = newLeft;
    top = newTop;
    g_state.wideLeft = (unsigned) left;
    g_state.wideTop = (unsigned) top;
    place_frame(left, top);
    wide_paint(&world, buf, width, left, width - WIDE_FRAME_W - left, top, height - WIDE_FRAME_H - top);
    uint32_t alpha = frame[0] & 0xFF000000u;
    for (int row = 0; row < height; row++) {
        uint32_t* line = buf + (size_t) row * (size_t) width;
        if (row < top || row >= top + WIDE_FRAME_H) {
            for (int c = 0; c < width; c++) line[c] |= alpha;
        } else {
            for (int c = 0; c < left; c++) line[c] |= alpha;
            for (int c = left + WIDE_FRAME_W; c < width; c++) line[c] |= alpha;
        }
    }
    g_state.wideBlank = false;
}

/* While the picture is the game's own 240x160 but Wide view is wanted: the same question as
 * paint_wide_margins() asks, answered for the picture Wide view would draw, so the app can go back to it
 * when a world is there again (a menu or a small room has none). Paints nothing. */
static void probe_wide(void) {
    int width = (int) g_state.wideProbeW;
    int height = (int) g_state.wideProbeH;
    int left = (width - WIDE_FRAME_W) / 2;
    int top = (height - WIDE_FRAME_H) / 2;
    struct WideWorld world;
    bool have = gbaduo_game_enabled() && gbaduo_game_wide(width, height, left, top, g_state.wideMatch, &world);
    if (have) {
        g_state.wideMatch = wide_seam_match(&world, (const uint32_t*) g_state.videoBuffer, WIDE_FRAME_W, 0, 0, false);
        /* The frame sits where paint_wide_margins() would put it (moved toward a map edge), so a room that
         * covers enough when painted also covers enough here and the app can leave the fallback. */
        wide_clamp_frame(&world, width, height, &left, &top);
        g_state.wideCoverage = wide_coverage(&world, left, top, width, height);
    } else {
        g_state.wideMatch = 0.0f;
        g_state.wideCoverage = 0.0f;
    }
    wide_gate_step(&g_state.wideGate, have, g_state.wideMatch);
}

JNIEXPORT void JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_runFrame(JNIEnv* env, jobject thiz, jint keys) {
    if (!g_state.core) {
        return;
    }
    g_state.core->setKeys(g_state.core, (uint32_t) keys);
    g_state.core->runFrame(g_state.core);
    if (g_state.videoWidth > WIDE_FRAME_W || g_state.videoHeight > WIDE_FRAME_H) {
        paint_wide_margins();
    } else if (g_state.wideProbeW > 0) {
        probe_wide();
    }
}

/* Between frames: the layer probe on or off (GBA only). While on, each frame's alpha is its depth. Returns whether it
 * runs. */
JNIEXPORT jboolean JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_setLayerDepth(JNIEnv* env, jobject thiz, jboolean on) {
    if (!g_state.core) return JNI_FALSE;
    gbaduo_layers_set(g_state.core, on);
    return gbaduo_layers_on() ? JNI_TRUE : JNI_FALSE;
}

/* out[0..1] = how far the game's floor has scrolled (see gbaduo_layers_world). */
JNIEXPORT void JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_layerWorld(JNIEnv* env, jobject thiz, jintArray out) {
    int32_t xy[2];
    gbaduo_layers_world(&xy[0], &xy[1]);
    (*env)->SetIntArrayRegion(env, out, 0, 2, (const jint*) xy);
}

JNIEXPORT jobject JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_videoBuffer(JNIEnv* env, jobject thiz) {
    if (!g_state.videoBuffer) {
        return NULL;
    }
    return (*env)->NewDirectByteBuffer(env, g_state.videoBuffer, (jlong) g_state.videoBufferBytes);
}

/* Between frames: the size of the picture the core draws into (GBA only; 240x160 = off). Returns
 * the width now in effect (read the height with videoHeight). The game's frame sits in the middle; the
 * margins are painted by paint_wide_margins(). */
JNIEXPORT jint JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_setWide(JNIEnv* env, jobject thiz, jint width, jint height) {
    if (!g_state.core || g_state.core->platform(g_state.core) != mPLATFORM_GBA) {
        return (jint) g_state.videoWidth;
    }
    unsigned wantedW = (unsigned) (width < WIDE_FRAME_W ? WIDE_FRAME_W : width > WIDE_MAX_W ? WIDE_MAX_W : width);
    unsigned wantedH = (unsigned) (height < WIDE_FRAME_H ? WIDE_FRAME_H : height > WIDE_MAX_H ? WIDE_MAX_H : height);
    wantedW &= ~1u;
    wantedH &= ~1u;
    if (wantedW == g_state.videoWidth && wantedH == g_state.videoHeight) {
        return (jint) wantedW;
    }
    unsigned left = (wantedW - WIDE_FRAME_W) / 2;
    unsigned top = (wantedH - WIDE_FRAME_H) / 2;
    if (wantedW > WIDE_FRAME_W || wantedH > WIDE_FRAME_H) {
        g_state.core->setVideoBuffer(g_state.core, g_wideStage, WIDE_FRAME_W);
    } else {
        g_state.core->setVideoBuffer(g_state.core, g_state.videoBuffer, wantedW);
    }
    memset(g_state.videoBuffer, 0, g_state.videoBufferBytes);
    g_state.videoWidth = wantedW;
    g_state.videoHeight = wantedH;
    g_state.wideLeft = left;
    g_state.wideTop = top;
    memset(&g_state.wideGate, 0, sizeof(g_state.wideGate));
    g_state.wideMatch = 0.0f;
    g_state.wideCoverage = 0.0f;
    g_state.wideBlank = true;
    g_state.wideHiddenFrames = 0;
    return (jint) wantedW;
}

/* Between frames: the size Wide view would draw ([width] x [height]; 0 x 0 = Wide view not wanted), so that
 * while the picture is the game's own 240x160 the core keeps asking game.lua whether there is a world to show. */
JNIEXPORT void JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_setWideProbe(JNIEnv* env, jobject thiz, jint width, jint height) {
    /* Wanted when the picture would be bigger than the game's own in either direction: a square screen's extra wide
     * view is 240 wide and only taller. */
    bool wanted = width > WIDE_FRAME_W || height > WIDE_FRAME_H;
    unsigned w = wanted ? (width > WIDE_FRAME_W ? (unsigned) width : WIDE_FRAME_W) : 0u;
    unsigned h = height >= WIDE_FRAME_H ? (unsigned) height : WIDE_FRAME_H;
    if (w == g_state.wideProbeW && h == g_state.wideProbeH) return;
    g_state.wideProbeW = w;
    g_state.wideProbeH = w ? h : 0u;
    memset(&g_state.wideGate, 0, sizeof(g_state.wideGate));
    g_state.wideMatch = 0.0f;
    g_state.wideCoverage = 0.0f;
}

JNIEXPORT jfloat JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_wideCoverage(JNIEnv* env, jobject thiz) {
    return (jfloat) g_state.wideCoverage;
}

JNIEXPORT jboolean JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_wideShown(JNIEnv* env, jobject thiz) {
    return g_state.wideGate.shown ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_wideMatch(JNIEnv* env, jobject thiz) {
    return g_state.wideMatch;
}

JNIEXPORT jint JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_videoWidth(JNIEnv* env, jobject thiz) {
    return (jint) g_state.videoWidth;
}

JNIEXPORT jint JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_videoHeight(JNIEnv* env, jobject thiz) {
    return (jint) g_state.videoHeight;
}

JNIEXPORT jint JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_audio(JNIEnv* env, jobject thiz, jshortArray out) {
    if (!g_state.core) {
        return 0;
    }
    jsize capacity = (*env)->GetArrayLength(env, out);
    int maxFrames = capacity / 2;

    blip_t* left = g_state.core->getAudioChannel(g_state.core, 0);
    blip_t* right = g_state.core->getAudioChannel(g_state.core, 1);
    int available = blip_samples_avail(left);
    if (available > maxFrames) {
        available = maxFrames;
    }
    if (available <= 0) {
        return 0;
    }

    short* buffer = malloc((size_t) available * 2 * sizeof(short));
    if (!buffer) {
        return 0;
    }
    blip_read_samples(left, buffer, available, 1);
    blip_read_samples(right, buffer + 1, available, 1);

    (*env)->SetShortArrayRegion(env, out, 0, available * 2, buffer);
    free(buffer);
    return available;
}

JNIEXPORT jboolean JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_saveState(JNIEnv* env, jobject thiz, jstring path) {
    if (!g_state.core) {
        return JNI_FALSE;
    }
    const char* pathChars = (*env)->GetStringUTFChars(env, path, NULL);
    // mCoreSaveStateNamed mmaps the file PROT_WRITE|MAP_SHARED, which requires
    // the fd to be opened O_RDWR (O_WRONLY is not sufficient for mmap writes).
    struct VFile* vf = VFileOpen(pathChars, O_RDWR | O_CREAT | O_TRUNC);
    (*env)->ReleaseStringUTFChars(env, path, pathChars);
    if (!vf) {
        return JNI_FALSE;
    }
    bool ok = mCoreSaveStateNamed(g_state.core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
    vf->close(vf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_loadState(JNIEnv* env, jobject thiz, jstring path) {
    if (!g_state.core) {
        return JNI_FALSE;
    }
    const char* pathChars = (*env)->GetStringUTFChars(env, path, NULL);
    struct VFile* vf = VFileOpen(pathChars, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, pathChars);
    if (!vf) {
        return JNI_FALSE;
    }
    bool ok = mCoreLoadStateNamed(g_state.core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
    vf->close(vf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_gameCode(JNIEnv* env, jobject thiz) {
    if (!g_state.core) {
        return (*env)->NewStringUTF(env, "");
    }
    char code[9];
    memset(code, 0, sizeof(code));
    g_state.core->getGameCode(g_state.core, code);
    return (*env)->NewStringUTF(env, code);
}

JNIEXPORT jlong JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_crc32(JNIEnv* env, jobject thiz) {
    if (!g_state.core) {
        return 0;
    }
    uint32_t crc = 0;
    g_state.core->checksum(g_state.core, &crc, mCHECKSUM_CRC32);
    return (jlong) crc;
}

JNIEXPORT jint JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_rawRead8(JNIEnv* env, jobject thiz, jlong address) {
    if (!g_state.core) {
        return 0;
    }
    return (jint) g_state.core->rawRead8(g_state.core, (uint32_t) address, -1);
}

JNIEXPORT jint JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_rawRead16(JNIEnv* env, jobject thiz, jlong address) {
    if (!g_state.core) {
        return 0;
    }
    return (jint) g_state.core->rawRead16(g_state.core, (uint32_t) address, -1);
}

JNIEXPORT jlong JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_rawRead32(JNIEnv* env, jobject thiz, jlong address) {
    if (!g_state.core) {
        return 0;
    }
    return (jlong) (uint32_t) g_state.core->rawRead32(g_state.core, (uint32_t) address, -1);
}

JNIEXPORT jlong JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_frameCounter(JNIEnv* env, jobject thiz) {
    if (!g_state.core) {
        return 0;
    }
    return (jlong) g_state.core->frameCounter(g_state.core);
}

JNIEXPORT void JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_unload(JNIEnv* env, jobject thiz) {
    if (!g_state.core) {
        return;
    }
    gbaduo_layers_set(g_state.core, false);
    mCoreConfigDeinit(&g_state.core->config);
    g_state.core->deinit(g_state.core);
    free(g_state.videoBuffer);
    free(g_state.romBuffer);
    memset(&g_state, 0, sizeof(g_state));
}

JNIEXPORT jboolean JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_selfTest(JNIEnv* env, jobject thiz) {
    struct mCore* core = mCoreCreate(mPLATFORM_GBA);
    if (!core) {
        return JNI_FALSE;
    }
    core->deinit(core);
    return JNI_TRUE;
}
