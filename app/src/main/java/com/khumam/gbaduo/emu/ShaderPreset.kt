package com.khumam.gbaduo.emu

/**
 * A single-pass GLES2 shader preset loaded from `assets/<vertexAsset>` / `assets/<fragmentAsset>`.
 * Adapted from mGBA's own shader presets (third_party/mgba/res/shaders, MPL-2.0).
 * [uniforms] are applied once after linking; no runtime-configurable sliders in v1.
 */
enum class ShaderPreset(
    val id: String,
    val displayName: String,
    val vertexAsset: String,
    val fragmentAsset: String,
    val uniforms: List<Pair<String, Float>>,
) {
    OFF(
        id = "off",
        displayName = "Off",
        vertexAsset = "shaders/common_vertex.glsl",
        fragmentAsset = "shaders/off/fragment.glsl",
        uniforms = emptyList(),
    ),
    GBA_COLOR(
        id = "gba_color",
        displayName = "GBA Color",
        vertexAsset = "shaders/common_vertex.glsl",
        fragmentAsset = "shaders/gba_color/fragment.glsl",
        uniforms = listOf("darken_screen" to 0.5f),
    ),
    LCD(
        id = "lcd",
        displayName = "LCD Grid",
        vertexAsset = "shaders/common_vertex.glsl",
        fragmentAsset = "shaders/lcd/fragment.glsl",
        uniforms = listOf("boundBrightness" to 0.9f),
    ),
    SCANLINES(
        id = "scanlines",
        displayName = "Scanlines",
        vertexAsset = "shaders/common_vertex.glsl",
        fragmentAsset = "shaders/scanlines/fragment.glsl",
        uniforms = listOf("lineBrightness" to 0.5f),
    ),
    // xBR-lv2 was tried first (as originally planned) but mGBA's xbr.fs uses mat4x3/transpose()
    // and implicit int-vec4 math, which GLSL ES 1.00 (what Android's GLES2 actually runs) doesn't
    // support — it fails to compile on-device. Scale2x gives a similar "smoothed upscale" look
    // and is plain vec2/vec4 arithmetic, so it compiles cleanly under GLES2.
    SCALE2X(
        id = "scale2x",
        displayName = "Scale2x",
        vertexAsset = "shaders/common_vertex.glsl",
        fragmentAsset = "shaders/scale2x/fragment.glsl",
        uniforms = emptyList(),
    ),
    ;

    companion object {
        fun byId(id: String?): ShaderPreset = entries.firstOrNull { it.id == id } ?: OFF

        /** Cycles to the next preset, wrapping back to the first after the last. */
        fun next(current: ShaderPreset): ShaderPreset {
            val all = entries
            return all[(all.indexOf(current) + 1) % all.size]
        }
    }
}
