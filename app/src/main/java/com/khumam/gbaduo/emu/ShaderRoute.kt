package com.khumam.gbaduo.emu

/** What the renderer should do about a shader choice, given what the device and the chain loading say. */
sealed interface ShaderRoute {
    data class Builtin(val preset: ShaderPreset) : ShaderRoute
    data class Chain(val path: String) : ShaderRoute

    /** Keep drawing with the shader already running; [error] is why, or null while the chain is still loading. */
    data class KeepPrevious(val error: String?) : ShaderRoute

    companion object {
        fun pick(choice: ShaderChoice, es3Available: Boolean, chainCreated: Boolean, error: String?): ShaderRoute = when (choice) {
            is ShaderChoice.Builtin -> Builtin(choice.preset)
            is ShaderChoice.Library -> when {
                !es3Available -> KeepPrevious("Library shaders need OpenGL ES 3.0 or newer")
                chainCreated -> Chain(choice.path)
                else -> KeepPrevious(error)
            }
        }
    }
}
