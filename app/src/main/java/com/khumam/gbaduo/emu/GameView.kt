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

/**
 * Aspect-fit blit of the emulator's RGBA8888 framebuffer (240x160 for GBA,
 * 160x144 for GB/GBC), with a swappable post-process shader.
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

    private inner class Renderer(private val assets: AssetManager) : GLSurfaceView.Renderer {
        @Volatile var pendingShader: ShaderPreset = ShaderPreset.OFF
        @Volatile var videoSize: Pair<Int, Int> = 240 to 160

        private var activeShader: ShaderPreset? = null
        private var program = 0
        private var texture = 0
        private var positionHandle = 0
        private var texSizeHandle = -1
        private var surfaceWidth = 0
        private var surfaceHeight = 0
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
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
        }

        /** Largest viewport with the game's aspect ratio, centered (black bars on the rest). */
        private fun applyAspectFitViewport(videoWidth: Int, videoHeight: Int) {
            val scale = minOf(surfaceWidth.toFloat() / videoWidth, surfaceHeight.toFloat() / videoHeight)
            val width = (videoWidth * scale).toInt()
            val height = (videoHeight * scale).toInt()
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
            applyAspectFitViewport(videoWidth, videoHeight)
            GLES20.glUseProgram(program)
            if (texSizeHandle >= 0) {
                GLES20.glUniform2f(texSizeHandle, videoWidth.toFloat(), videoHeight.toFloat())
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            buffer.rewind()
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, videoWidth, videoHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer,
            )

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
            for ((name, value) in preset.uniforms) {
                val loc = GLES20.glGetUniformLocation(program, name)
                if (loc >= 0) {
                    GLES20.glUniform1f(loc, value)
                }
            }
        }

        private fun buildProgram(preset: ShaderPreset): Int {
            val vertexSource = assets.open(preset.vertexAsset).bufferedReader().use { it.readText() }
            val fragmentSource = assets.open(preset.fragmentAsset).bufferedReader().use { it.readText() }

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
                error("Link failed for '${preset.id}': $log")
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
