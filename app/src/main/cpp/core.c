#include <jni.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <mgba/core/core.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/serialize.h>
#include <mgba/gba/core.h>
#include <mgba-util/vfs.h>

#define LOG_TAG "GBADuoCore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define GBA_VIDEO_WIDTH 240
#define GBA_VIDEO_HEIGHT 160
#define AUDIO_SAMPLE_RATE 32768

struct GbaDuoState {
    struct mCore* core;
    color_t* videoBuffer;
    void* romBuffer;
};

static struct GbaDuoState g_state;

/** Used by script.c's mem.* Lua API; NULL when no ROM is loaded. */
struct mCore* gbaduo_active_core(void) {
    return g_state.core;
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

    struct mCore* core = mCoreCreate(mPLATFORM_GBA);
    if (!core) {
        free(romBuffer);
        return JNI_FALSE;
    }

    mCoreInitConfig(core, NULL);
    core->init(core);

    color_t* videoBuffer = malloc(GBA_VIDEO_WIDTH * GBA_VIDEO_HEIGHT * sizeof(color_t));
    if (!videoBuffer) {
        core->deinit(core);
        free(romBuffer);
        return JNI_FALSE;
    }
    core->setVideoBuffer(core, videoBuffer, GBA_VIDEO_WIDTH);

    struct VFile* romFile = VFileFromMemory(romBuffer, (size_t) romSize);
    if (!core->loadROM(core, romFile)) {
        LOGE("loadROM failed");
        core->deinit(core);
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
    g_state.romBuffer = romBuffer;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_runFrame(JNIEnv* env, jobject thiz, jint keys) {
    if (!g_state.core) {
        return;
    }
    g_state.core->setKeys(g_state.core, (uint32_t) keys);
    g_state.core->runFrame(g_state.core);
}

JNIEXPORT jobject JNICALL
Java_com_khumam_gbaduo_emu_NativeCore_videoBuffer(JNIEnv* env, jobject thiz) {
    if (!g_state.videoBuffer) {
        return NULL;
    }
    return (*env)->NewDirectByteBuffer(env, g_state.videoBuffer, GBA_VIDEO_WIDTH * GBA_VIDEO_HEIGHT * sizeof(color_t));
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
