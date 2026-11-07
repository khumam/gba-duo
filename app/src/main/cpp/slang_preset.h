// Parsing a libretro `.slangp` preset: the pass list, the lookup textures and the parameter overrides.
// `#reference` presets are merged first, so the referencing file's keys win. Pure C++, no GL.
#pragma once

#include "slang_source.h"

#include <map>
#include <string>
#include <vector>

enum class SlangScale { Source, Viewport, Absolute };

struct SlangPass {
    std::string shader;                 // path relative to the shader root
    std::string alias;
    bool filterLinear = false, mipmap = false, floatFb = false, srgbFb = false;
    int wrap = 0;                       // 0 clamp_to_border, 1 clamp_to_edge, 2 repeat, 3 mirrored_repeat
    SlangScale scaleX = SlangScale::Source, scaleY = SlangScale::Source;
    float factorX = 1.0f, factorY = 1.0f;   // a multiplier, or pixels for Absolute
};

struct SlangTexture {
    std::string id, path;               // path relative to the shader root
    bool filterLinear = false, mipmap = false;
    int wrap = 0;
};

struct SlangPreset {
    std::vector<SlangPass> passes;
    std::vector<SlangTexture> textures;
    std::map<std::string, float> paramOverrides;
};

bool slang_load_preset(const std::string& path, const ReadFile& read, SlangPreset* out, std::string* error);
