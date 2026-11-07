// The libretro slang pass chain on OpenGL ES 3.x: a plan (parsed + translated, no GL, any thread) is
// instantiated on the GL thread into programs, FBOs and uniform buffers, then rendered every frame.
//
// Conventions (so one image orientation holds all the way through): textures have row 0 = the image's top, as the
// emulator frame is uploaded; a pass draws a full-screen quad whose TexCoord (0,0) sits at clip (-1,-1), so what it
// writes keeps that orientation and the next pass (and the app's own draw) can sample it unchanged. MVP is identity.
#pragma once

#include "slang_preset.h"
#include "slang_translate.h"

#include <cstdint>
#include <map>
#include <string>
#include <utility>
#include <vector>

struct SlangLut {
    std::string id;
    int width = 0, height = 0;
    std::vector<uint8_t> rgba;      // width * height * 4, straight alpha
};

struct SlangChainPlan {
    SlangPreset preset;
    std::vector<SlangSource> sources;       // one per pass
    std::vector<TranslatedPass> passes;     // one per pass
    std::map<std::string, float> params;    // every #pragma parameter default, then the preset's overrides
    std::vector<SlangLut> luts;             // images for preset.textures, matched by id
    int needsEs = 300;                      // highest GLSL ES version any pass needs (300 or 310)
};

// The (id, path) of every lookup texture the preset names, paths relative to [root].
bool slang_chain_texture_list(const std::string& root, const std::string& presetPath,
                              std::vector<std::pair<std::string, std::string>>* out, std::string* error);

// Parses and translates the preset under [root]. Pure CPU. Every texture the preset names needs an entry in [luts].
bool slang_chain_prepare(const std::string& root, const std::string& presetPath, std::vector<SlangLut> luts,
                         SlangChainPlan* out, std::string* error);

class SlangChain;

// GL thread only. Null (with [error]) when the context is too old or a program fails to compile or link.
SlangChain* slang_chain_instantiate(SlangChainPlan&& plan, std::string* error);

// GL thread only. Runs the chain on [inputTexture] (inputWidth x inputHeight) into [outputTexture]
// (outputWidth x outputHeight, RGBA8, already allocated). Queries no GL state; leaves it at fixed defaults instead:
// texture unit 0 active with [inputTexture] bound, no sampler objects, VAO 0, no array or uniform buffer, program 0,
// framebuffer 0, blend, depth test, cull face and scissor test off. The viewport is the last pass's: set your own.
void slang_chain_render(SlangChain* chain, unsigned inputTexture, int inputWidth, int inputHeight,
                        unsigned outputTexture, int outputWidth, int outputHeight, uint32_t frame);

void slang_chain_destroy(SlangChain* chain);   // GL thread only

// After the GL context was lost: frees the chain without any GL call (its objects went with the context). Any thread.
void slang_chain_abandon(SlangChain* chain);
