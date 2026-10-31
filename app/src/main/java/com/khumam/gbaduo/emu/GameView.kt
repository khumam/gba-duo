package com.khumam.gbaduo.emu

import android.content.Context
import android.content.res.AssetManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "GameView"

/** Largest offscreen texture side for sharp scaling; anything bigger draws directly. */
private const val MAX_PRESCALE_SIDE = 4096

/** The picture height [TiltShift.maxRadius] is given for; the blur scales with the picture. */
private const val TILT_REFERENCE_HEIGHT = 960f

/**
 * Blit of the emulator's RGBA8888 framebuffer (240x160 for GBA, 160x144 for GB/GBC) in the
 * chosen [AspectRatio], with a swappable post-process shader.
 */
class GameView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    @Volatile var frameBuffer: ByteBuffer? = null
    private val renderer = Renderer(context.assets)

    /** Call before setting [frameBuffer]; the size of the core's video output in pixels. */
    fun setVideoSize(width: Int, height: Int) {
        renderer.videoSize = width to height
    }

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Call from the emu thread after each frame; schedules a redraw on the GL thread. */
    fun postFrame() {
        requestRender()
    }

    /** Thread-safe; the switch happens on the GL thread before the next frame draws. */
    fun setShader(preset: ShaderPreset) {
        renderer.pendingShader = preset
        requestRender()
    }

    /** Thread-safe; read by the GL thread on the next frame. */
    fun setColorCorrection(correction: ColorCorrection) {
        renderer.colorMode = correction.mode
        requestRender()
    }

    /** The surface's size in pixels (0 x 0 until it exists), for [WideView]. Thread-safe. */
    val surfaceSize: Pair<Int, Int> get() = renderer.surfaceWidth to renderer.surfaceHeight

    val integerScaling: Boolean get() = renderer.integerScale

    val aspectRatio: AspectRatio get() = renderer.aspect

    /** Thread-safe; read by the GL thread on the next frame. */
    fun setIntegerScaling(enabled: Boolean) {
        renderer.integerScale = enabled
        requestRender()
    }

    /** Thread-safe: shows the picture in [aspect] instead of the chosen one (null: the chosen one). Used by wide view. */
    fun setAspectOverride(aspect: AspectRatio?) {
        renderer.aspectOverride = aspect
        requestRender()
    }

    /** Thread-safe; read by the GL thread on the next frame. */
    fun setAspectRatio(aspect: AspectRatio) {
        renderer.aspect = aspect
        requestRender()
    }

    /** Thread-safe; read by the GL thread on the next frame. */
    fun setTiltShift(tilt: TiltShift) {
        renderer.tiltShift = tilt
        requestRender()
    }

    private inner class Renderer(private val assets: AssetManager) : GLSurfaceView.Renderer {
        @Volatile var pendingShader: ShaderPreset = ShaderPreset.OFF
        @Volatile var colorMode = 0f
        @Volatile var integerScale = false
        @Volatile var aspect = AspectRatio.ORIGINAL
        @Volatile var aspectOverride: AspectRatio? = null
        @Volatile var tiltShift = TiltShift.OFF
        @Volatile var videoSize: Pair<Int, Int> = 240 to 160

        private var activeShader: ShaderPreset? = null
        private var program = 0
        private var texture = 0
        private var positionHandle = 0
        private var texSizeHandle = -1
        private var colorModeHandle = -1
        private var blitProgram = 0
        private var blitPositionHandle = 0
        private var blitSrcSizeHandle = -1
        private var blitDstSizeHandle = -1
        private var prescaleTexture = 0
        private var prescaleFbo = 0
        private var prescaleWidth = 0
        private var prescaleHeight = 0
        private var prescaleReady = false
        private var tiltProgram = 0
        private var tiltPositionHandle = 0
        private var tiltAxisHandle = -1
        private var tiltRadiusHandle = -1
        private var tiltFocusHandle = -1
        private val tiltTextures = IntArray(2)
        private val tiltFbos = IntArray(2)
        private var tiltWidth = 0
        private var tiltHeight = 0
        private var tiltReady = false
        @Volatile var surfaceWidth = 0
        @Volatile var surfaceHeight = 0
        private lateinit var vertexBuffer: FloatBuffer

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Clip-space quad; each preset's vertex shader derives texCoord from `position` itself.
            val values = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            vertexBuffer = ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

            applyShader(ShaderPreset.OFF)
            setUpPrescale()
            setUpTilt()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
        }

        /** Viewport in the chosen [AspectRatio] (whole-number scaled if enabled), centered; black bars on the rest. */
        private fun applyViewport(videoWidth: Int, videoHeight: Int) {
            val (width, height) = ScreenFit.viewport(surfaceWidth, surfaceHeight, videoWidth, videoHeight, aspectOverride ?: aspect, integerScale)
            GLES20.glViewport((surfaceWidth - width) / 2, (surfaceHeight - height) / 2, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val wanted = pendingShader
            if (wanted != activeShader) {
                applyShader(wanted)
            }

            val buffer = frameBuffer ?: return
            val (videoWidth, videoHeight) = videoSize
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            buffer.rewind()
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, videoWidth, videoHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer,
            )

            val size = ScreenFit.viewport(surfaceWidth, surfaceHeight, videoWidth, videoHeight, aspectOverride ?: aspect, integerScale)
            val x = (surfaceWidth - size.width) / 2
            val y = (surfaceHeight - size.height) / 2
            // A picture that isn't a whole-number multiple of the game would get rows and columns of uneven
            // width, and a linear shrink makes edges pop between sharp and soft as it scrolls. Draw it at a whole multiple
            // first and shrink that by exact area, so pixel edges stay crisp and move smoothly.
            val tilt = tiltShift
            if (tilt != TiltShift.OFF && sizeTilt(size.width, size.height)) {
                // Picture into an offscreen texture, then blurred sideways and up-down onto the screen.
                drawPicture(tiltFbos[0], 0, 0, size, videoWidth, videoHeight)
                val radius = tilt.maxRadius * size.height / TILT_REFERENCE_HEIGHT
                tiltPass(tiltTextures[0], tiltFbos[1], 0, 0, size, 1f, 0f, radius, tilt.focus)
                tiltPass(tiltTextures[1], 0, x, y, size, 0f, 1f, radius, tilt.focus)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            } else {
                drawPicture(0, x, y, size, videoWidth, videoHeight)
            }
        }

        /** The game picture, [size] big, with its corner at ([x], [y]) of framebuffer [fbo] (0: the screen). */
        private fun drawPicture(fbo: Int, x: Int, y: Int, size: ViewportSize, videoWidth: Int, videoHeight: Int) {
            val sharp = !integerScale && prescaleReady && !ScreenFit.isWholeNumber(size, videoWidth, videoHeight)
            if (sharp && drawPrescaled(fbo, size, x, y, videoWidth, videoHeight)) return

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(x, y, size.width, size.height)
            drawGame(videoWidth, videoHeight)
        }

        /** Blurs [source] along ([axisX], [axisY]) into [fbo] (0: the screen) at ([x], [y]). */
        private fun tiltPass(source: Int, fbo: Int, x: Int, y: Int, size: ViewportSize, axisX: Float, axisY: Float, radius: Float, focus: Float) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(x, y, size.width, size.height)
            GLES20.glUseProgram(tiltProgram)
            GLES20.glUniform2f(tiltAxisHandle, axisX / size.width, axisY / size.height)
            GLES20.glUniform1f(tiltRadiusHandle, radius)
            GLES20.glUniform1f(tiltFocusHandle, focus)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source)
            drawQuad(tiltPositionHandle)
        }

        /** The chosen shader over the whole viewport; the game texture must be bound. */
        private fun drawGame(videoWidth: Int, videoHeight: Int) {
            GLES20.glUseProgram(program)
            if (colorModeHandle >= 0) {
                GLES20.glUniform1f(colorModeHandle, colorMode)
            }
            if (texSizeHandle >= 0) {
                GLES20.glUniform2f(texSizeHandle, videoWidth.toFloat(), videoHeight.toFloat())
            }
            drawQuad(positionHandle)
        }

        private fun drawQuad(handle: Int) {
            GLES20.glEnableVertexAttribArray(handle)
            GLES20.glVertexAttribPointer(handle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        /** Game at a whole multiple of its size into an offscreen texture, then area-averaged onto the screen. False: couldn't. */
        private fun drawPrescaled(fbo: Int, size: ViewportSize, x: Int, y: Int, videoWidth: Int, videoHeight: Int): Boolean {
            val factor = ScreenFit.prescale(size, videoWidth, videoHeight)
            val width = videoWidth * factor
            val height = videoHeight * factor
            if (width > MAX_PRESCALE_SIDE || height > MAX_PRESCALE_SIDE || !sizePrescale(width, height)) return false

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prescaleFbo)
            GLES20.glViewport(0, 0, width, height)
            drawGame(videoWidth, videoHeight)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)

            GLES20.glViewport(x, y, size.width, size.height)
            GLES20.glUseProgram(blitProgram)
            GLES20.glUniform2f(blitSrcSizeHandle, width.toFloat(), height.toFloat())
            GLES20.glUniform2f(blitDstSizeHandle, size.width.toFloat(), size.height.toFloat())
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prescaleTexture)
            drawQuad(blitPositionHandle)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            return true
        }

        private fun setUpPrescale() {
            prescaleReady = false
            prescaleWidth = 0
            prescaleHeight = 0
            blitProgram = runCatching { buildProgram("shaders/blit/vertex.glsl", "shaders/blit/fragment.glsl", "blit") }
                .getOrElse { error ->
                    Log.e(TAG, "Failed to build blit shader, sharp scaling off", error)
                    return
                }
            blitPositionHandle = GLES20.glGetAttribLocation(blitProgram, "position")
            GLES20.glUseProgram(blitProgram)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(blitProgram, "tex"), 0)
            blitSrcSizeHandle = GLES20.glGetUniformLocation(blitProgram, "srcSize")
            blitDstSizeHandle = GLES20.glGetUniformLocation(blitProgram, "dstSize")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            prescaleTexture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prescaleTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glGenFramebuffers(1, ids, 0)
            prescaleFbo = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            prescaleReady = true
        }

        private fun setUpTilt() {
            tiltReady = false
            tiltWidth = 0
            tiltHeight = 0
            tiltProgram = runCatching { buildProgram("shaders/tilt/vertex.glsl", "shaders/tilt/fragment.glsl", "tilt") }
                .getOrElse { error ->
                    Log.e(TAG, "Failed to build tilt-shift shader, blur off", error)
                    return
                }
            tiltPositionHandle = GLES20.glGetAttribLocation(tiltProgram, "position")
            GLES20.glUseProgram(tiltProgram)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(tiltProgram, "tex"), 0)
            tiltAxisHandle = GLES20.glGetUniformLocation(tiltProgram, "axis")
            tiltRadiusHandle = GLES20.glGetUniformLocation(tiltProgram, "maxRadius")
            tiltFocusHandle = GLES20.glGetUniformLocation(tiltProgram, "focus")

            GLES20.glGenTextures(2, tiltTextures, 0)
            GLES20.glGenFramebuffers(2, tiltFbos, 0)
            for (texId in tiltTextures) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            tiltReady = true
        }

        /** Gives both offscreen textures this size; false (and the blur off) when the GPU won't render into them. */
        private fun sizeTilt(width: Int, height: Int): Boolean {
            if (!tiltReady) return false
            if (width == tiltWidth && height == tiltHeight) return true
            var complete = true
            for (i in 0..1) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tiltTextures[i])
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tiltFbos[i])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tiltTextures[i], 0,
                )
                complete = complete && GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            if (!complete) {
                Log.e(TAG, "Offscreen ${width}x$height not renderable, tilt-shift off")
                tiltReady = false
                return false
            }
            tiltWidth = width
            tiltHeight = height
            return true
        }

        /** Gives the offscreen texture this size; false (and sharp scaling off) when the GPU won't render into it. */
        private fun sizePrescale(width: Int, height: Int): Boolean {
            if (width == prescaleWidth && height == prescaleHeight) return true
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prescaleTexture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prescaleFbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, prescaleTexture, 0,
            )
            val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            if (!complete) {
                Log.e(TAG, "Offscreen ${width}x$height not renderable, sharp scaling off")
                prescaleReady = false
                return false
            }
            prescaleWidth = width
            prescaleHeight = height
            return true
        }

        /** Compiles+links [preset]; on failure logs and keeps whatever program was already active. */
        private fun applyShader(preset: ShaderPreset) {
            val newProgram = runCatching { buildProgram(preset) }.getOrElse { error ->
                Log.e(TAG, "Failed to build shader '${preset.id}', keeping previous", error)
                null
            }
            if (newProgram == null) {
                pendingShader = activeShader ?: ShaderPreset.OFF
                return
            }

            if (program != 0) {
                GLES20.glDeleteProgram(program)
            }
            program = newProgram
            activeShader = preset
            positionHandle = GLES20.glGetAttribLocation(program, "position")

            GLES20.glUseProgram(program)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "tex"), 0)
            // Set per frame in onDrawFrame, since the video size depends on the loaded game.
            texSizeHandle = GLES20.glGetUniformLocation(program, "texSize")
            colorModeHandle = GLES20.glGetUniformLocation(program, "ccMode")
            for ((name, value) in preset.uniforms) {
                val loc = GLES20.glGetUniformLocation(program, name)
                if (loc >= 0) {
                    GLES20.glUniform1f(loc, value)
                }
            }
        }

        private fun buildProgram(preset: ShaderPreset): Int = buildProgram(preset.vertexAsset, preset.fragmentAsset, preset.id)

        private fun buildProgram(vertexAsset: String, fragmentAsset: String, name: String): Int {
            val vertexSource = assets.open(vertexAsset).bufferedReader().use { it.readText() }
            val correction = assets.open("shaders/color_correction.glsl").bufferedReader().use { it.readText() }
            // Every fragment shader opens with this exact line; the correction goes right after it.
            val fragmentSource = assets.open(fragmentAsset).bufferedReader().use { it.readText() }
                .replaceFirst("precision mediump float;", "precision mediump float;\n$correction")

            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)

            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            if (linked[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error("Link failed for '$name': $log")
            }
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Compile failed: $log")
            }
            return shader
        }
    }
}
