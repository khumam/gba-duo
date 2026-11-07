// JNI for com.khumam.gbaduo.emu.SlangChain: prepare (CPU, any thread) -> instantiate (GL thread) -> render -> destroy.
#include "slang_chain.h"

#include <jni.h>

#include <string>

namespace {

thread_local std::string g_lastError;

std::string toString(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_khumam_gbaduo_emu_SlangChain_lastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_lastError.c_str());
}

// [id0, path0, id1, path1, ...] of the preset's lookup textures; null on error.
JNIEXPORT jobjectArray JNICALL Java_com_khumam_gbaduo_emu_SlangChain_textureList(JNIEnv* env, jobject, jstring root, jstring preset) {
    std::vector<std::pair<std::string, std::string>> list;
    if (!slang_chain_texture_list(toString(env, root), toString(env, preset), &list, &g_lastError)) return nullptr;
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(list.size() * 2), stringClass, nullptr);
    for (size_t i = 0; i < list.size(); i++) {
        env->SetObjectArrayElement(out, static_cast<jsize>(i * 2), env->NewStringUTF(list[i].first.c_str()));
        env->SetObjectArrayElement(out, static_cast<jsize>(i * 2 + 1), env->NewStringUTF(list[i].second.c_str()));
    }
    return out;
}

// ids[i], sizes[2i] x sizes[2i+1], pixels[i] (a direct RGBA ByteBuffer) are the decoded lookup textures. 0 on error.
JNIEXPORT jlong JNICALL Java_com_khumam_gbaduo_emu_SlangChain_prepare(JNIEnv* env, jobject, jstring root, jstring preset,
                                                                      jobjectArray ids, jintArray sizes, jobjectArray pixels) {
    std::vector<SlangLut> luts;
    jsize n = ids ? env->GetArrayLength(ids) : 0;
    jint* dims = n ? env->GetIntArrayElements(sizes, nullptr) : nullptr;
    for (jsize i = 0; i < n; i++) {
        SlangLut lut;
        jstring id = static_cast<jstring>(env->GetObjectArrayElement(ids, i));
        lut.id = toString(env, id);
        lut.width = dims[i * 2];
        lut.height = dims[i * 2 + 1];
        jobject buffer = env->GetObjectArrayElement(pixels, i);
        const uint8_t* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
        size_t bytes = static_cast<size_t>(lut.width) * lut.height * 4;
        if (data && env->GetDirectBufferCapacity(buffer) >= static_cast<jlong>(bytes)) lut.rgba.assign(data, data + bytes);
        else { lut.width = lut.height = 0; }
        luts.push_back(std::move(lut));
    }
    if (dims) env->ReleaseIntArrayElements(sizes, dims, JNI_ABORT);

    SlangChainPlan* plan = new SlangChainPlan();
    if (!slang_chain_prepare(toString(env, root), toString(env, preset), std::move(luts), plan, &g_lastError)) {
        delete plan;
        return 0;
    }
    return reinterpret_cast<jlong>(plan);
}

JNIEXPORT void JNICALL Java_com_khumam_gbaduo_emu_SlangChain_discard(JNIEnv*, jobject, jlong plan) {
    delete reinterpret_cast<SlangChainPlan*>(plan);
}

// Consumes the plan whether or not it succeeds. 0 on error.
JNIEXPORT jlong JNICALL Java_com_khumam_gbaduo_emu_SlangChain_instantiate(JNIEnv*, jobject, jlong planHandle) {
    SlangChainPlan* plan = reinterpret_cast<SlangChainPlan*>(planHandle);
    SlangChain* chain = slang_chain_instantiate(std::move(*plan), &g_lastError);
    delete plan;
    return reinterpret_cast<jlong>(chain);
}

JNIEXPORT void JNICALL Java_com_khumam_gbaduo_emu_SlangChain_render(JNIEnv*, jobject, jlong chain, jint inputTexture, jint inputWidth,
                                                                    jint inputHeight, jint outputTexture, jint outputWidth,
                                                                    jint outputHeight, jint frame) {
    slang_chain_render(reinterpret_cast<SlangChain*>(chain), static_cast<unsigned>(inputTexture), inputWidth, inputHeight,
                       static_cast<unsigned>(outputTexture), outputWidth, outputHeight, static_cast<uint32_t>(frame));
}

JNIEXPORT void JNICALL Java_com_khumam_gbaduo_emu_SlangChain_destroy(JNIEnv*, jobject, jlong chain) {
    slang_chain_destroy(reinterpret_cast<SlangChain*>(chain));
}

}  // extern "C"
