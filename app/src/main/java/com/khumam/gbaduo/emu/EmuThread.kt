package com.khumam.gbaduo.emu

import android.util.Log
import com.khumam.gbaduo.achievements.RetroAchievements
import com.khumam.gbaduo.companion.CompanionSnapshot
import com.khumam.gbaduo.companion.LuaScript
import com.khumam.gbaduo.companion.NativeCoreMemoryReader
import com.khumam.gbaduo.companion.PackManifest
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Owns the native core for one play session. Runs frames paced to the GBA's
 * native rate (or faster under fast-forward) on its own thread.
 */
class EmuThread(
    romBytes: ByteArray,
    private val savePath: String,
    private val gameView: GameView,
    private val audioOut: AudioOut,
    private val input: InputMapper,
) : Thread("gbaduo-emu") {

    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile var ffMultiplier = 2

    private val frameNanos = (1_000_000_000.0 / 59.7275).toLong()

    /** Only until the core has its own copy: a whole ROM (up to 32 MB) shouldn't be held twice. */
    @Volatile private var romBytes: ByteArray? = romBytes

    private val pendingSaveState = java.util.concurrent.atomic.AtomicReference<StateRequest?>(null)
    @Volatile var lastStateResult: Boolean? = null

    @Volatile var loaded = false
        private set

    /** Set by PlayerActivity once a pack is assigned; null means no companion data to compute. */
    @Volatile var activePack: PackManifest? = null

    /** Must be set alongside [activePack] — where its `manifest.scriptFile` (if any) lives. */
    @Volatile var activePackDir: File? = null

    /** Called on the emu thread (not main thread) with each new snapshot. */
    @Volatile var onSnapshot: ((Map<String, Any?>) -> Unit)? = null

    /** Called on the emu thread with what a script action returned (UI actions for the companion screen). */
    @Volatile var onActionResult: ((Any?) -> Unit)? = null

    /** Whether the active pack's actions may change the game's memory (`mem.write*`). */
    enum class WriteAccess {
        /** No: the pack doesn't ask, the setting is off, or the player said no. Writes are refused. */
        NONE,

        /** Yes: the player allowed this pack for this game. */
        ALLOWED,

        /** Not decided yet: the next action waits while [onWriteConsentNeeded] asks the player. */
        ASK,
    }

    /** Set by PlayerActivity with the pack; see [answerWriteConsent]. */
    @Volatile var writeAccess = WriteAccess.NONE

    /**
     * Called on this thread, once, when an action is waiting for the player to allow
     * (or refuse) writes. Answer with [answerWriteConsent]; until then actions stay queued.
     */
    @Volatile var onWriteConsentNeeded: (() -> Unit)? = null

    @Volatile private var consentAsked = false

    /** A save state to write right before the first allowed action runs (see [answerWriteConsent]). */
    @Volatile private var safetyStatePath: String? = null

    /**
     * Called on this thread when the game's save data may have changed on disk: the
     * save file's size or time changed (checked every [SAVE_CHECK_FRAMES]), or a save
     * state was written. Used to copy saves out to the save folder.
     */
    @Volatile var onSaveDataChanged: (() -> Unit)? = null

    /** Called on this thread as it ends, after the core is unloaded (its save written): every time, even after a failure. */
    @Volatile var onStopped: (() -> Unit)? = null

    private val saveFile = File(savePath)
    private var saveSeen = 0L to 0L
    private var framesSinceSaveCheck = 0

    private var framesSinceSnapshot = 0
    private var scriptLoadedForPack: PackManifest? = null
    private var scriptActive = false

    @Volatile private var uiState: Map<String, Any?> = emptyMap()
    @Volatile private var uiStateChanged = true
    @Volatile private var snapshotRequested = false
    private val pendingCalls = ConcurrentLinkedQueue<Pair<String, List<Any?>>>()

    override fun run() {
        try {
            play()
        } finally {
            onStopped?.invoke()
        }
    }

    private fun play() {
        loaded = loadCore()
        if (!loaded) return
        saveSeen = saveFile.length() to saveFile.lastModified()
        RetroAchievements.gameStarted()

        gameView.setVideoSize(NativeCore.videoWidth(), NativeCore.videoHeight())
        gameView.frameBuffer = NativeCore.videoBuffer()
        audioOut.start()
        running = true

        var nextFrameAt = System.nanoTime()
        while (running) {
            if (paused) {
                // Taps on the companion screen still get their data while the game is paused.
                if (snapshotRequested) serviceCompanion(activePack)
                RetroAchievements.paused()
                Thread.sleep(16)
                nextFrameAt = System.nanoTime()
                continue
            }
            servicePendingStateRequest()

            val fastForward = input.fastForward()
            val speed = if (fastForward) ffMultiplier.coerceAtLeast(2) else 1

            repeat(speed) {
                NativeCore.runFrame(input.keys())
                RetroAchievements.frame()
            }
            gameView.postFrame()
            audioOut.pump(mute = speed != 1)

            serviceCompanion(activePack)
            checkSaveFile()

            nextFrameAt += frameNanos / speed
            val sleepNanos = nextFrameAt - System.nanoTime()
            if (sleepNanos > 0) {
                Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
            } else {
                nextFrameAt = System.nanoTime()
            }
        }

        LuaScript.unload()
        audioOut.stop()
        RetroAchievements.gameStopped()
        NativeCore.unload()
    }

    /** mGBA writes the save file itself shortly after the game saves; notice when it has. */
    private fun checkSaveFile() {
        if (++framesSinceSaveCheck < SAVE_CHECK_FRAMES) return
        framesSinceSaveCheck = 0
        val now = saveFile.length() to saveFile.lastModified()
        if (now != saveSeen) {
            saveSeen = now
            onSaveDataChanged?.invoke()
        }
    }

    /** In its own frame so nothing in [run] keeps the ROM reachable once the core has copied it. */
    private fun loadCore(): Boolean {
        val rom = romBytes ?: return false
        romBytes = null
        return NativeCore.load(rom, savePath)
    }

    /**
     * Hands UI state and queued taps to the pack's script, then builds a snapshot
     * every `tickHz`, or right away after a tap so the screen reacts quickly.
     */
    private fun serviceCompanion(pack: PackManifest?) {
        syncScriptForPack(pack)
        pack ?: return
        var due = false
        if (snapshotRequested) {
            snapshotRequested = false
            if (scriptActive) {
                if (uiStateChanged) {
                    uiStateChanged = false
                    LuaScript.setUi(uiState)
                }
                while (true) {
                    if (writeAccess == WriteAccess.ASK) {
                        // The pack may write: nothing runs until the player has answered.
                        if (pendingCalls.isNotEmpty() && !consentAsked) {
                            consentAsked = true
                            onWriteConsentNeeded?.invoke()
                        }
                        break
                    }
                    val (name, args) = pendingCalls.poll() ?: break
                    val mayWrite = writeAccess == WriteAccess.ALLOWED
                    if (mayWrite) saveSafetyState()
                    val result = LuaScript.call(name, args, mayWrite)
                    if (result != null) {
                        onActionResult?.invoke(result)
                    } else {
                        LuaScript.lastError()?.let { Log.w(TAG, "actions.$name(): $it") }
                    }
                }
            } else {
                pendingCalls.clear()
            }
            due = true
        }
        framesSinceSnapshot++
        val interval = (60 / pack.tickHz).coerceAtLeast(1)
        if (due || framesSinceSnapshot >= interval) {
            framesSinceSnapshot = 0
            val snapshot = CompanionSnapshot.build(pack, NativeCoreMemoryReader, NativeCore.frameCounter(), scriptActive)
            onSnapshot?.invoke(snapshot)
        }
    }

    /** Thread-safe: the companion screen's UI state changed (the script sees it as `ui`). */
    fun setUiState(state: Map<String, Any?>) {
        uiState = state
        uiStateChanged = true
        snapshotRequested = true
    }

    /**
     * Thread-safe: the player's answer to [onWriteConsentNeeded]; runs the actions that
     * were waiting. With [safetyState], a save state is written there first, on this
     * thread, before the pack's first write can happen (even while the game is paused).
     */
    fun answerWriteConsent(access: WriteAccess, safetyState: String? = null) {
        safetyStatePath = safetyState
        writeAccess = access
        consentAsked = false
        snapshotRequested = true
    }

    /** Thread-safe: queues a call to the script's `actions.<name>(args...)`, run before the next snapshot. */
    fun callAction(name: String, args: List<Any?>) {
        pendingCalls += name to args
        snapshotRequested = true
    }

    /** Loads/unloads the pack's Lua script (if any) exactly once per pack change, on this thread. */
    private fun syncScriptForPack(pack: PackManifest?) {
        if (pack === scriptLoadedForPack) return
        scriptLoadedForPack = pack
        scriptActive = false
        LuaScript.unload()

        val scriptFile = pack?.scriptFile ?: return
        val packDir = activePackDir ?: return
        val source = runCatching { File(packDir, scriptFile).readText() }.getOrNull() ?: return
        scriptActive = LuaScript.load(source, packDir.absolutePath)
        if (scriptActive) {
            // A fresh Lua state has no `ui` yet.
            uiStateChanged = false
            LuaScript.setUi(uiState)
        }
    }

    private fun saveSafetyState() {
        val path = safetyStatePath ?: return
        safetyStatePath = null
        File(path).parentFile?.mkdirs()
        if (NativeCore.saveState(path)) onSaveDataChanged?.invoke()
        else Log.w(TAG, "could not save the state before the pack's first write")
    }

    private class StateRequest(val path: String, val save: Boolean, val onDone: ((Boolean) -> Unit)?)

    /** Thread-safe; services the request on the emu thread between frames. [onDone] runs there with the core's result. */
    fun requestState(path: String, save: Boolean, onDone: ((Boolean) -> Unit)? = null) {
        pendingSaveState.set(StateRequest(path, save, onDone))
    }

    private fun servicePendingStateRequest() {
        val request = pendingSaveState.getAndSet(null) ?: return
        val (path, save) = request.path to request.save
        val ok = if (save) NativeCore.saveState(path) else NativeCore.loadState(path)
        lastStateResult = ok
        if (save && ok) onSaveDataChanged?.invoke()
        request.onDone?.invoke(ok)
    }

    fun shutdown() {
        running = false
    }

    private companion object {
        const val TAG = "EmuThread"

        /** About every 3 seconds. */
        const val SAVE_CHECK_FRAMES = 180
    }
}
