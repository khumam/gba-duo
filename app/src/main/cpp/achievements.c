// RetroAchievements: the rcheevos rc_client, bridged to Kotlin
// (com.khumam.gbaduo.achievements.RaNative / RetroAchievements).
//
// One rc_client lives for the whole app so the login survives between games.
// rcheevos makes no network calls: server_call hands each request to Kotlin, which
// answers through RaNative.serverResponse(handle, ...). Memory reads go through the
// loaded mGBA core, so doFrame/loadGame must run on the emulator thread.
//
// Softcore only for now: the server demotes hardcore unlocks until RetroAchievements
// has validated GBADuo's User-Agent, and hardcore would also have to block loading states.

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <mgba/core/core.h>

#include <rc_client.h>
#include <rc_consoles.h>

#define LOG_TAG "GBADuoRA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern struct mCore* gbaduo_active_core(void);
extern const void* gbaduo_rom(size_t* size);

static JavaVM* g_vm;
static jclass g_bridge;               // RetroAchievements (callbacks)
static jmethodID g_serverCall;        // (JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
static jmethodID g_onLogin;           // (ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
static jmethodID g_onGameLoad;        // (ILjava/lang/String;Ljava/lang/String;IIILjava/lang/String;)V
static jmethodID g_onEvent;           // (ILjava/lang/String;Ljava/lang/String;ILjava/lang/String;)V

static rc_client_t* g_client;
static uint32_t g_console;            // console id of the loaded game, for memory mapping

// Event types passed to RetroAchievements.onEvent (keep in sync with RetroAchievements.kt).
enum {
    EVENT_ACHIEVEMENT = 1,
    EVENT_GAME_COMPLETED = 2,
    EVENT_SERVER_ERROR = 3,
    EVENT_DISCONNECTED = 4,
    EVENT_RECONNECTED = 5,
};

// ---- JNI plumbing ---------------------------------------------------------------

static JNIEnv* env_for_thread(void) {
    JNIEnv* env = NULL;
    if (!g_vm) {
        return NULL;
    }
    if ((*g_vm)->GetEnv(g_vm, (void**) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        // rc_client calls back on whichever thread delivered the server response; those are
        // Java threads, but be safe for native ones too.
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
            return NULL;
        }
    }
    return env;
}

static jstring jstr(JNIEnv* env, const char* s) {
    return s ? (*env)->NewStringUTF(env, s) : NULL;
}

static void clear_exception(JNIEnv* env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

// ---- rc_client callbacks ---------------------------------------------------------

/** RA addresses -> real GBA/GB bus addresses, using rcheevos' own memory map per console. */
static uint32_t read_memory(uint32_t address, uint8_t* buffer, uint32_t num_bytes, rc_client_t* client) {
    (void) client;
    struct mCore* core = gbaduo_active_core();
    const rc_memory_regions_t* regions = rc_console_memory_regions(g_console);
    if (!core || !regions) {
        return 0;
    }
    for (uint32_t i = 0; i < num_bytes; i++) {
        uint32_t ra = address + i;
        uint32_t j;
        for (j = 0; j < regions->num_regions; j++) {
            const rc_memory_region_t* r = &regions->region[j];
            if (ra >= r->start_address && ra <= r->end_address) {
                buffer[i] = (uint8_t) core->rawRead8(core, r->real_address + (ra - r->start_address), -1);
                break;
            }
        }
        if (j == regions->num_regions) {
            return i; // unmapped: stop, rcheevos treats the rest as unavailable
        }
    }
    return num_bytes;
}

struct PendingCall {
    rc_client_server_callback_t callback;
    void* callback_data;
};

static void server_call(const rc_api_request_t* request, rc_client_server_callback_t callback,
                        void* callback_data, rc_client_t* client) {
    (void) client;
    JNIEnv* env = env_for_thread();
    struct PendingCall* call = malloc(sizeof(*call));
    if (!env || !call) {
        free(call);
        rc_api_server_response_t none = {0};
        none.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
        callback(&none, callback_data);
        return;
    }
    call->callback = callback;
    call->callback_data = callback_data;
    jstring url = jstr(env, request->url);
    jstring post = jstr(env, request->post_data);
    jstring type = jstr(env, request->content_type);
    (*env)->CallStaticVoidMethod(env, g_bridge, g_serverCall, (jlong) (intptr_t) call, url, post, type);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, url);
    if (post) (*env)->DeleteLocalRef(env, post);
    if (type) (*env)->DeleteLocalRef(env, type);
}

static void log_message(const char* message, const rc_client_t* client) {
    (void) client;
    LOGI("%s", message);
}

static void login_callback(int result, const char* error_message, rc_client_t* client, void* userdata) {
    (void) userdata;
    JNIEnv* env = env_for_thread();
    if (!env) {
        return;
    }
    const rc_client_user_t* user = result == RC_OK ? rc_client_get_user_info(client) : NULL;
    jstring err = jstr(env, error_message);
    jstring username = jstr(env, user ? user->username : NULL);
    jstring display = jstr(env, user ? user->display_name : NULL);
    jstring token = jstr(env, user ? user->token : NULL);
    (*env)->CallStaticVoidMethod(env, g_bridge, g_onLogin, (jint) result, err, username, display, token,
                                 (jint) (user ? user->score : 0));
    clear_exception(env);
}

static void load_game_callback(int result, const char* error_message, rc_client_t* client, void* userdata) {
    (void) userdata;
    JNIEnv* env = env_for_thread();
    if (!env) {
        return;
    }
    const rc_client_game_t* game = result == RC_OK ? rc_client_get_game_info(client) : NULL;
    rc_client_user_game_summary_t summary;
    memset(&summary, 0, sizeof(summary));
    char image[256] = {0};
    if (game) {
        rc_client_get_user_game_summary(client, &summary);
        rc_client_game_get_image_url(game, image, sizeof(image));
    }
    jstring err = jstr(env, error_message);
    jstring title = jstr(env, game ? game->title : NULL);
    jstring imageUrl = jstr(env, image[0] ? image : NULL);
    (*env)->CallStaticVoidMethod(env, g_bridge, g_onGameLoad, (jint) result, err, title,
                                 (jint) (game ? game->id : 0),
                                 (jint) summary.num_core_achievements,
                                 (jint) summary.num_unlocked_achievements, imageUrl);
    clear_exception(env);
}

static void send_event(int type, const char* title, const char* description, uint32_t points, const char* badgeUrl) {
    JNIEnv* env = env_for_thread();
    if (!env) {
        return;
    }
    jstring t = jstr(env, title);
    jstring d = jstr(env, description);
    jstring b = jstr(env, badgeUrl);
    (*env)->CallStaticVoidMethod(env, g_bridge, g_onEvent, (jint) type, t, d, (jint) points, b);
    clear_exception(env);
    if (t) (*env)->DeleteLocalRef(env, t);
    if (d) (*env)->DeleteLocalRef(env, d);
    if (b) (*env)->DeleteLocalRef(env, b);
}

static void event_handler(const rc_client_event_t* event, rc_client_t* client) {
    switch (event->type) {
    case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED: {
        char badge[256] = {0};
        rc_client_achievement_get_image_url(event->achievement, RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED, badge, sizeof(badge));
        send_event(EVENT_ACHIEVEMENT, event->achievement->title, event->achievement->description,
                   event->achievement->points, badge[0] ? badge : NULL);
        break;
    }
    case RC_CLIENT_EVENT_GAME_COMPLETED: {
        const rc_client_game_t* game = rc_client_get_game_info(client);
        send_event(EVENT_GAME_COMPLETED, game ? game->title : NULL, NULL, 0, NULL);
        break;
    }
    case RC_CLIENT_EVENT_SERVER_ERROR:
        send_event(EVENT_SERVER_ERROR, event->server_error ? event->server_error->api : NULL,
                   event->server_error ? event->server_error->error_message : NULL, 0, NULL);
        break;
    case RC_CLIENT_EVENT_DISCONNECTED:
        send_event(EVENT_DISCONNECTED, NULL, NULL, 0, NULL);
        break;
    case RC_CLIENT_EVENT_RECONNECTED:
        send_event(EVENT_RECONNECTED, NULL, NULL, 0, NULL);
        break;
    default:
        // Leaderboards, challenge indicators and progress trackers aren't shown yet.
        break;
    }
}

/** RA console id for the loaded core: GBA, or GB/GBC by the cartridge header's CGB flag. */
static uint32_t console_for_core(struct mCore* core, const uint8_t* rom, size_t size) {
    if (core->platform(core) == mPLATFORM_GBA) {
        return RC_CONSOLE_GAMEBOY_ADVANCE;
    }
    if (size > 0x143 && (rom[0x143] & 0x80)) {
        return RC_CONSOLE_GAMEBOY_COLOR;
    }
    return RC_CONSOLE_GAMEBOY;
}

// ---- RaNative ------------------------------------------------------------------------

#define RA_NATIVE(name) Java_com_khumam_gbaduo_achievements_RaNative_##name

JNIEXPORT jboolean JNICALL
RA_NATIVE(init)(JNIEnv* env, jobject thiz) {
    if (g_client) {
        return JNI_TRUE;
    }
    (*env)->GetJavaVM(env, &g_vm);
    jclass bridge = (*env)->FindClass(env, "com/khumam/gbaduo/achievements/RetroAchievements");
    if (!bridge) {
        clear_exception(env);
        return JNI_FALSE;
    }
    g_bridge = (*env)->NewGlobalRef(env, bridge);
    g_serverCall = (*env)->GetStaticMethodID(env, g_bridge, "serverCall",
        "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    g_onLogin = (*env)->GetStaticMethodID(env, g_bridge, "onLogin",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    g_onGameLoad = (*env)->GetStaticMethodID(env, g_bridge, "onGameLoad",
        "(ILjava/lang/String;Ljava/lang/String;IIILjava/lang/String;)V");
    g_onEvent = (*env)->GetStaticMethodID(env, g_bridge, "onEvent",
        "(ILjava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
    if (!g_serverCall || !g_onLogin || !g_onGameLoad || !g_onEvent) {
        clear_exception(env);
        LOGE("RetroAchievements callbacks missing");
        return JNI_FALSE;
    }

    g_client = rc_client_create(read_memory, server_call);
    if (!g_client) {
        return JNI_FALSE;
    }
    rc_client_enable_logging(g_client, RC_CLIENT_LOG_LEVEL_INFO, log_message);
    rc_client_set_event_handler(g_client, event_handler);
    rc_client_set_hardcore_enabled(g_client, 0);
    return JNI_TRUE;
}

/** "rcheevos/12.5" for the User-Agent header. */
JNIEXPORT jstring JNICALL
RA_NATIVE(userAgentClause)(JNIEnv* env, jobject thiz) {
    char clause[64] = {0};
    if (g_client) {
        rc_client_get_user_agent_clause(g_client, clause, sizeof(clause));
    }
    return (*env)->NewStringUTF(env, clause);
}

JNIEXPORT void JNICALL
RA_NATIVE(loginWithPassword)(JNIEnv* env, jobject thiz, jstring username, jstring password) {
    if (!g_client) return;
    const char* u = (*env)->GetStringUTFChars(env, username, NULL);
    const char* p = (*env)->GetStringUTFChars(env, password, NULL);
    rc_client_begin_login_with_password(g_client, u, p, login_callback, NULL);
    (*env)->ReleaseStringUTFChars(env, username, u);
    (*env)->ReleaseStringUTFChars(env, password, p);
}

JNIEXPORT void JNICALL
RA_NATIVE(loginWithToken)(JNIEnv* env, jobject thiz, jstring username, jstring token) {
    if (!g_client) return;
    const char* u = (*env)->GetStringUTFChars(env, username, NULL);
    const char* t = (*env)->GetStringUTFChars(env, token, NULL);
    rc_client_begin_login_with_token(g_client, u, t, login_callback, NULL);
    (*env)->ReleaseStringUTFChars(env, username, u);
    (*env)->ReleaseStringUTFChars(env, token, t);
}

JNIEXPORT void JNICALL
RA_NATIVE(logout)(JNIEnv* env, jobject thiz) {
    if (g_client) rc_client_logout(g_client);
}

/** Identifies the loaded ROM by its RetroAchievements hash and starts a session. Emu thread. */
JNIEXPORT jboolean JNICALL
RA_NATIVE(loadGame)(JNIEnv* env, jobject thiz) {
    struct mCore* core = gbaduo_active_core();
    size_t size = 0;
    const uint8_t* rom = gbaduo_rom(&size);
    if (!g_client || !core || !rom || !size) {
        return JNI_FALSE;
    }
    g_console = console_for_core(core, rom, size);
    rc_client_begin_identify_and_load_game(g_client, g_console, NULL, rom, size, load_game_callback, NULL);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
RA_NATIVE(unloadGame)(JNIEnv* env, jobject thiz) {
    if (g_client) rc_client_unload_game(g_client);
    g_console = 0;
}

/** After each emulated frame, on the emu thread. */
JNIEXPORT void JNICALL
RA_NATIVE(doFrame)(JNIEnv* env, jobject thiz) {
    if (g_client) rc_client_do_frame(g_client);
}

/** While paused (keeps the session alive, retries queued unlocks). */
JNIEXPORT void JNICALL
RA_NATIVE(idle)(JNIEnv* env, jobject thiz) {
    if (g_client) rc_client_idle(g_client);
}

/** Kotlin's answer to serverCall: [body] null means the request failed or timed out. */
JNIEXPORT void JNICALL
RA_NATIVE(serverResponse)(JNIEnv* env, jobject thiz, jlong handle, jbyteArray body, jint status) {
    struct PendingCall* call = (struct PendingCall*) (intptr_t) handle;
    if (!call) {
        return;
    }
    rc_api_server_response_t response;
    memset(&response, 0, sizeof(response));
    jbyte* bytes = NULL;
    if (body) {
        bytes = (*env)->GetByteArrayElements(env, body, NULL);
        response.body = (const char*) bytes;
        response.body_length = (size_t) (*env)->GetArrayLength(env, body);
    }
    response.http_status_code = status;
    call->callback(&response, call->callback_data);
    if (bytes) {
        (*env)->ReleaseByteArrayElements(env, body, bytes, JNI_ABORT);
    }
    free(call);
}
