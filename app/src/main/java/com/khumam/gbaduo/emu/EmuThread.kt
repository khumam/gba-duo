package com.khumam.gbaduo.emu

import com.khumam.gbaduo.companion.CompanionSnapshot
import com.khumam.gbaduo.companion.LuaScript
import com.khumam.gbaduo.companion.NativeCoreMemoryReader
import com.khumam.gbaduo.companion.PackManifest
import java.io.File

/**
 * Owns the native core for one play session. Runs frames paced to the GBA's
 * native rate (or faster under fast-forward) on its own thread.
 */
class EmuThread(
    private val romBytes: ByteArray,
    private val savePath: String,
    private val gameView: GameView,
    private val audioOut: AudioOut,
    private val input: InputMapper,
) : Thread("gbaduo-emu") {

    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile var ffMultiplier = 2

    private val frameNanos = (1_000_000_000.0 / 59.7275).toLong()

    private val pendingSaveState = java.util.concurrent.atomic.AtomicReference<Pair<String, Boolean>?>(null)
    @Volatile var lastStateResult: Boolean? = null

    @Volatile var loaded = false
        private set

    /** Set by PlayerActivity once a pack is assigned; null means no companion data to compute. */
    @Volatile var activePack: PackManifest? = null

    /** Must be set alongside [activePack] — where its `manifest.scriptFile` (if any) lives. */
    @Volatile var activePackDir: File? = null

    /** Called on the emu thread (not main thread) with each new snapshot. */
    @Volatile var onSnapshot: ((Map<String, Any?>) -> Unit)? = null

    private var framesSinceSnapshot = 0
    private var scriptLoadedForPack: PackManifest? = null
    private var scriptActive = false

    override fun run() {
        loaded = NativeCore.load(romBytes, savePath)
        if (!loaded) return

        gameView.frameBuffer = NativeCore.videoBuffer()
        audioOut.start()
        running = true

        var nextFrameAt = System.nanoTime()
        while (running) {
            if (paused) {
                Thread.sleep(16)
                nextFrameAt = System.nanoTime()
                continue
            }
            servicePendingStateRequest()

            val fastForward = input.fastForward()
            val speed = if (fastForward) ffMultiplier.coerceAtLeast(2) else 1

            repeat(speed) {
                NativeCore.runFrame(input.keys())
            }
            gameView.postFrame()
            audioOut.pump(mute = speed != 1)

            val pack = activePack
            syncScriptForPack(pack)
            if (pack != null) {
                framesSinceSnapshot++
                val interval = (60 / pack.tickHz).coerceAtLeast(1)
                if (framesSinceSnapshot >= interval) {
                    framesSinceSnapshot = 0
                    val snapshot = CompanionSnapshot.build(pack, NativeCoreMemoryReader, NativeCore.frameCounter(), scriptActive)
                    onSnapshot?.invoke(snapshot)
                }
            }

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
        NativeCore.unload()
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
    }

    /** Thread-safe; services the request on the emu thread between frames. */
    fun requestState(path: String, save: Boolean) {
        pendingSaveState.set(path to save)
    }

    private fun servicePendingStateRequest() {
        val request = pendingSaveState.getAndSet(null) ?: return
        val (path, save) = request
        lastStateResult = if (save) NativeCore.saveState(path) else NativeCore.loadState(path)
    }

    fun shutdown() {
        running = false
    }
}
