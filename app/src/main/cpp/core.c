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
#include "script.h"
#include "wide.h"

#define LOG_TAG "GBADuoCore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define AUDIO_SAMPLE_RATE 32768

struct GbaDuoState {
    struct mCore* core;
    color_t* videoBuffer;
    size_t videoBufferBytes;
    unsigned videoWidth;
    unsigned videoHeight;
    /* Wide view (GBA only): the frame is drawn [wideLeft] columns into rows [videoWidth] wide. */
    unsigned wideLeft;
    struct WideGate wideGate;
    float wideMatch;
    bool wideBlank;           /* the margins are already zeroed */
    int wideErrorLogCountdown;
    void* romBuffer;
    size_t romSize;
};

static struct GbaDuoState g_state;

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
    /* A GBA game's buffer has room for the widest wide view; the frame itself stays 240 wide until setWide. */
    size_t bufferBytes = (size_t) (core->platform(core) == mPLATFORM_GBA ? WIDE_MAX_W : videoWidth) * videoHeight * sizeof(color_t);
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
    unsigned right = g_state.videoWidth - WIDE_FRAME_W - g_state.wideLeft;
    for (unsigned row = 0; row < g_state.videoHeight; row++) {
        color_t* line = g_state.videoBuffer + (size_t) row * g_state.videoWidth;
        memset(line, 0, g_state.wideLeft * sizeof(color_t));
        memset(line + g_state.wideLeft + WIDE_FRAME_W, 0, right * sizeof(color_t));
    }
    g_state.wideBlank = true;
}

/* After each emulated frame: ask game.lua for the world, check its prediction against the
 * edge columns mGBA just drew, and paint the margins only while it holds up. */
static void paint_wide_margins(void) {
    int width = (int) g_state.videoWidth;
    int left = (int) g_state.wideLeft;
    int right = width - WIDE_FRAME_W - left;
    uint32_t* buf = (uint32_t*) g_state.videoBuffer;

    struct WideWorld world;
    bool have = gbaduo_game_enabled() && gbaduo_game_wide(width, left, g_state.wideMatch, &world);
    if (!have) {
        const char* error = gbaduo_game_last_error();
        if (error[0] && g_state.wideErrorLogCountdown-- <= 0) {
            g_state.wideErrorLogCountdown = 300;
            LOGE("game.lua: %s", error);
        }
        g_state.wideMatch = 0.0f;
    } else {
        g_state.wideMatch = wide_seam_match(&world, buf, width, left);
    }

    if (!wide_gate_step(&g_state.wideGate, have, g_state.wideMatch)) {
        if (!g_state.wideBlank) {
            blank_wide_margins();
        }
        return;
    }
    wide_paint(&world, buf, width, left, right);
    for (unsigned row = 0; row < g_state.videoHeight; row++) {
        uint32_t* line = buf + (size_t) row * (size_t) width;
        uint32_t alpha = line[left] & 0xFF000000u;
        for (int c = 0; c < left; c++) line[c] |= alpha;
        for (int c = left + WIDE_FRAME_W; c < width; c++) line[c] |= alpha;
    }
    g_state.wideBlank = false;
}

JNIEXPORT void JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_runFrame(JNIEnv* env, jobject thiz, jint keys) {
    if (!g_state.core) {
        return;
    }
    g_state.core->setKeys(g_state.core, (uint32_t) keys);
    g_state.core->runFrame(g_state.core);
    if (g_state.videoWidth > WIDE_FRAME_W) {
        paint_wide_margins();
    }
}

JNIEXPORT jobject JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_videoBuffer(JNIEnv* env, jobject thiz) {
    if (!g_state.videoBuffer) {
        return NULL;
    }
    return (*env)->NewDirectByteBuffer(env, g_state.videoBuffer, (jlong) g_state.videoBufferBytes);
}

/* Between frames: the width of the picture the core draws into (GBA only; 240 = off). Returns
 * the width now in effect. The game's frame sits in the middle; the margins are painted
 * by paint_wide_margins(). */
JNIEXPORT jint JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_setWide(JNIEnv* env, jobject thiz, jint width) {
    if (!g_state.core || g_state.core->platform(g_state.core) != mPLATFORM_GBA) {
        return (jint) g_state.videoWidth;
    }
    unsigned wanted = (unsigned) (width < WIDE_FRAME_W ? WIDE_FRAME_W : width > WIDE_MAX_W ? WIDE_MAX_W : width);
    wanted &= ~1u;
    if (wanted == g_state.videoWidth) {
        return (jint) wanted;
    }
    unsigned left = (wanted - WIDE_FRAME_W) / 2;
    g_state.core->setVideoBuffer(g_state.core, g_state.videoBuffer + left, wanted);
    memset(g_state.videoBuffer, 0, g_state.videoBufferBytes);
    g_state.videoWidth = wanted;
    g_state.wideLeft = left;
    memset(&g_state.wideGate, 0, sizeof(g_state.wideGate));
    g_state.wideMatch = 0.0f;
    g_state.wideBlank = true;
    return (jint) wanted;
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
