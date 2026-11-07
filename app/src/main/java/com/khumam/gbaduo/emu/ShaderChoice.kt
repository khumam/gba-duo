package com.khumam.gbaduo.emu

/** A shader the player can pick: one of the built-in presets, or a libretro slang preset downloaded to the device. */
sealed interface ShaderChoice {
    /** What is saved: a built-in's own id (`lcd`), or `slang:` plus the preset's path in the libretro repo. */
    val id: String
    val displayName: String

    data class Builtin(val preset: ShaderPreset) : ShaderChoice {
        override val id get() = preset.id
        override val displayName get() = preset.displayName
    }

    data class Library(val path: String) : ShaderChoice {
        override val id get() = LIBRARY_PREFIX + path
        override val displayName get() = path.substringAfterLast('/').removeSuffix(".slangp")
    }

    companion object {
        const val LIBRARY_PREFIX = "slang:"
        val OFF: ShaderChoice = Builtin(ShaderPreset.OFF)

        /** Anything unknown, empty or unsafe (a path climbing out of the library) reads as Off. */
        fun parse(id: String?): ShaderChoice {
            if (id.isNullOrBlank()) return OFF
            if (id.startsWith(LIBRARY_PREFIX)) {
                val path = id.removePrefix(LIBRARY_PREFIX)
                return if (ShaderLibrary.isSafePath(path)) Library(path) else OFF
            }
            return Builtin(ShaderPreset.byId(id))
        }
    }
}

/** The shader a game runs with, and why. */
data class InUse(val choice: ShaderChoice, val source: Source, val missing: ShaderChoice.Library? = null) {
    enum class Source { GAME, DEFAULT, NONE }
}
