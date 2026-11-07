package com.khumam.gbaduo.emu

/**
 * The look a pack gives its game while Ambient is on: game.lua's `ambient()` read into a flat array of [FIELDS] floats
 * (native `ambient_look.h` has the order and the meaning of each). The app has the effects, the pack the numbers.
 */
class AmbientLook(val values: FloatArray) {
    init {
        require(values.size >= FIELDS)
    }

    /** The colour grade for `ccLook[3]` in color_correction.glsl. */
    val grade: FloatArray = floatArrayOf(
        values[SATURATION], values[CONTRAST], values[BRIGHTNESS], values[SUN_SLOPE],
        values[TINT_R], values[TINT_G], values[TINT_B], values[VIGNETTE],
        values[VIGNETTE_R], values[VIGNETTE_G], values[VIGNETTE_B], values[SUN_X],
    )

    /** The light for `look[4]` in shaders/ambient/fragment.glsl. */
    val light: FloatArray = floatArrayOf(
        values[SHADOW], values[SHADOW_LENGTH], values[RIM], values[CREASE],
        values[SHADOW_R], values[SHADOW_G], values[SHADOW_B], values[BLOOM],
        values[RIM_R], values[RIM_G], values[RIM_B], values[HAZE],
        values[CLOUDS], values[CLOUD_SIZE], values[CLOUD_SPEED], values[SUN_X],
    )

    /** The light pass has something to do. */
    val lit: Boolean
        get() = values[SHADOW] > 0f || values[RIM] > 0f || values[CREASE] > 0f || values[BLOOM] > 0f ||
            values[HAZE] > 0f || values[CLOUDS] > 0f

    /** Something needs to know which of the game's layers drew each pixel (the layer probe). */
    val needsDepth: Boolean
        get() = values[SHADOW] > 0f || values[RIM] > 0f || values[CREASE] > 0f || values[HAZE] > 0f || values[CLOUDS] > 0f

    override fun equals(other: Any?): Boolean = other is AmbientLook && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    companion object {
        const val SATURATION = 0
        const val CONTRAST = 1
        const val BRIGHTNESS = 2
        const val TINT_R = 3
        const val TINT_G = 4
        const val TINT_B = 5
        const val VIGNETTE = 6
        const val VIGNETTE_R = 7
        const val VIGNETTE_G = 8
        const val VIGNETTE_B = 9
        const val SUN_SLOPE = 10
        const val SHADOW = 11
        const val SHADOW_LENGTH = 12
        const val SHADOW_R = 13
        const val SHADOW_G = 14
        const val SHADOW_B = 15
        const val RIM = 16
        const val RIM_R = 17
        const val RIM_G = 18
        const val RIM_B = 19
        const val CREASE = 20
        const val BLOOM = 21
        const val HAZE = 22
        const val CLOUDS = 23
        const val CLOUD_SIZE = 24
        const val CLOUD_SPEED = 25
        const val SUN_X = 26
        const val FIELDS = 27
    }
}
