package com.khumam.gbaduo.achievements

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.khumam.gbaduo.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RetroAchievements for GBADuo, built on rcheevos (achievements.c).
 *
 * - Login: username + password once; only the token the server returns is stored.
 *   Nothing touches the network unless you're logged in.
 * - Playing: [gameStarted] / [frame] / [paused] / [gameStopped] are called by EmuThread on
 *   the emu thread. While a game runs, server responses are queued and handed to rcheevos
 *   on that thread before the next frame, because processing them reads emulated memory.
 * - Softcore only for now (see achievements.c).
 *
 * The `@JvmStatic` callbacks below are called from native code; their names and
 * signatures must match achievements.c.
 */
object RetroAchievements {
    private const val TAG = "GBADuoRA"
    private const val PREFS = "retroachievements"
    private const val KEY_USER = "username"
    private const val KEY_TOKEN = "token"
    private const val TIMEOUT_MS = 30_000

    // Event types from achievements.c.
    const val EVENT_ACHIEVEMENT = 1
    const val EVENT_GAME_COMPLETED = 2
    const val EVENT_SERVER_ERROR = 3
    const val EVENT_DISCONNECTED = 4
    const val EVENT_RECONNECTED = 5

    sealed class State {
        object LoggedOut : State()
        object LoggingIn : State()
        data class LoggedIn(val username: String, val displayName: String, val score: Int) : State()
        data class Failed(val message: String) : State()
    }

    /** What happened in the running game, for on-screen popups. Delivered on the main thread. */
    interface GameListener {
        fun onGameLoaded(title: String?, total: Int, unlocked: Int, imageUrl: String?, error: String?)
        fun onEvent(type: Int, title: String?, description: String?, points: Int, badgeUrl: String?)
    }

    @Volatile var state: State = State.LoggedOut
        private set

    private val main = Handler(Looper.getMainLooper())
    private val http: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "gbaduo-ra-http") }
    private val stateListeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val gameListeners = CopyOnWriteArrayList<GameListener>()

    private var prefs: SharedPreferences? = null
    private var userAgent = "GBADuo/${BuildConfig.VERSION_NAME}"
    private var initialized = false

    /** True while a game session owns rc_client: responses then wait for the emu thread. */
    @Volatile private var gameActive = false
    private val pendingResponses = ConcurrentLinkedQueue<Triple<Long, ByteArray?, Int>>()
    private var lastIdleAt = 0L

    /** Safe to call often (every screen's onCreate). Logs back in with a stored token. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Never take a screen down with it: without the native library (e.g. JVM tests),
        // achievements just stay off.
        val ok = runCatching { RaNative.init() }.onFailure { Log.e(TAG, "rc_client unavailable", it) }.getOrDefault(false)
        if (!ok) {
            Log.e(TAG, "rc_client init failed")
            return
        }
        initialized = true
        // <product>/<version> (<system>) <extensions>, as RetroAchievements asks.
        userAgent = "GBADuo/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
            RaNative.userAgentClause()
        val user = prefs?.getString(KEY_USER, null)
        val token = prefs?.getString(KEY_TOKEN, null)
        if (user != null && token != null) {
            setState(State.LoggingIn)
            RaNative.loginWithToken(user, token)
        }
    }

    val storedUsername: String? get() = prefs?.getString(KEY_USER, null)
    val isLoggedIn: Boolean get() = state is State.LoggedIn

    fun login(username: String, password: String) {
        if (!initialized) return
        setState(State.LoggingIn)
        RaNative.loginWithPassword(username.trim(), password)
    }

    fun logout() {
        if (!initialized) return
        RaNative.logout()
        prefs?.edit()?.remove(KEY_TOKEN)?.apply()
        setState(State.LoggedOut)
    }

    fun addStateListener(listener: (State) -> Unit) { stateListeners += listener; listener(state) }
    fun removeStateListener(listener: (State) -> Unit) { stateListeners -= listener }
    fun addGameListener(listener: GameListener) { gameListeners += listener }
    fun removeGameListener(listener: GameListener) { gameListeners -= listener }

    // ---- emu thread -----------------------------------------------------------------

    /** After the core has loaded the ROM. Starts a session only if you're logged in. */
    fun gameStarted() {
        if (!initialized) return
        val s = state
        if (s !is State.LoggedIn && s !is State.LoggingIn) return
        gameActive = true
        RaNative.loadGame()
    }

    /** After every emulated frame. */
    fun frame() {
        if (!gameActive) return
        deliverPending()
        RaNative.doFrame()
    }

    /** While the game is paused; idles about once a second. */
    fun paused() {
        if (!gameActive) return
        deliverPending()
        val now = System.currentTimeMillis()
        if (now - lastIdleAt >= 1000) {
            lastIdleAt = now
            RaNative.idle()
        }
    }

    /** Before the core unloads. */
    fun gameStopped() {
        if (!gameActive) return
        gameActive = false
        RaNative.unloadGame()
        deliverPending()
    }

    private fun deliverPending() {
        while (true) {
            val (handle, body, status) = pendingResponses.poll() ?: break
            RaNative.serverResponse(handle, body, status)
        }
    }

    // ---- called from achievements.c ---------------------------------------------

    @JvmStatic
    fun serverCall(handle: Long, url: String, postData: String?, contentType: String?) {
        http.execute {
            var body: ByteArray? = null
            var status = -1 // RC_API_SERVER_RESPONSE_CLIENT_ERROR
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", userAgent)
                if (postData != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", contentType ?: "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write(postData.toByteArray()) }
                }
                status = connection.responseCode
                body = (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() }
                connection.disconnect()
            }.onFailure { Log.w(TAG, "request failed: ${it.message}") }
            if (gameActive) pendingResponses += Triple(handle, body, status)
            else RaNative.serverResponse(handle, body, status)
        }
    }

    @JvmStatic
    fun onLogin(result: Int, error: String?, username: String?, displayName: String?, token: String?, score: Int) {
        if (result == 0 && username != null && token != null) {
            prefs?.edit()?.putString(KEY_USER, username)?.putString(KEY_TOKEN, token)?.apply()
            setState(State.LoggedIn(username, displayName ?: username, score))
        } else {
            // A rejected token means it expired or was revoked: ask for the password again.
            prefs?.edit()?.remove(KEY_TOKEN)?.apply()
            setState(State.Failed(error ?: "Login failed"))
        }
    }

    /** rcheevos' result when the ROM's hash isn't in the RetroAchievements database. */
    private const val RC_NO_GAME_LOADED = -29
    const val ERROR_UNKNOWN_GAME = "unknown_game"

    @JvmStatic
    fun onGameLoad(result: Int, error: String?, title: String?, gameId: Int, total: Int, unlocked: Int, imageUrl: String?) {
        main.post {
            gameListeners.forEach { it.onGameLoaded(title, total, unlocked, imageUrl, when (result) {
                0 -> null
                RC_NO_GAME_LOADED -> ERROR_UNKNOWN_GAME
                else -> error ?: "Unknown error"
            }) }
        }
    }

    @JvmStatic
    fun onEvent(type: Int, title: String?, description: String?, points: Int, badgeUrl: String?) {
        main.post { gameListeners.forEach { it.onEvent(type, title, description, points, badgeUrl) } }
    }

    private fun setState(newState: State) {
        state = newState
        main.post { stateListeners.forEach { it(newState) } }
    }

    /** For badges in popups: fetched off the main thread, only while logged in. */
    fun fetchImage(url: String, onLoaded: (android.graphics.Bitmap) -> Unit) {
        if (!isLoggedIn) return
        http.execute {
            val bitmap = runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("User-Agent", userAgent)
                    inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                }
            }.getOrNull() ?: return@execute
            main.post { onLoaded(bitmap) }
        }
    }
}
