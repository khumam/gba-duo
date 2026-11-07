// Translating one loaded `.slang` pass (Vulkan-flavoured GLSL) to GLSL ES 3.00 (3.10 when the shader needs it): glslang makes SPIR-V,
// SPIRV-Cross writes the ES source and reports the uniform blocks and samplers the chain has to bind.
// Push constants come out as an ordinary uniform block (ES has no push constants). Pure C++, no GL.
#pragma once

#include "slang_source.h"

#include <string>
#include <vector>

struct SlangMember {
    std::string name;
    int offset = 0, size = 0;       // std140 byte offset and size in the block
};

struct SlangBlock {
    std::string name;               // the GLSL block name (what glGetUniformBlockIndex wants), e.g. "UBO", "Push"
    bool push = false;              // came from a push_constant block
    int size = 0;                   // bytes
    std::vector<SlangMember> members;
};

struct SlangSampler {
    std::string name;               // e.g. "Source", "Original", a LUT's id
    int binding = 0;                // the slang binding; the chain uses it as the texture unit
};

struct TranslatedPass {
    std::string vertex, fragment;   // GLSL ES, both at [esVersion]
    int esVersion = 300;            // 300, or 310 when the shader needs it (arrays of arrays, ...): the chain needs that GL ES
    std::vector<SlangBlock> blocks; // merged over both stages
    std::vector<SlangSampler> samplers;
};

// False with a one-line [error] ("<stage>: <first compiler message>") when glslang or SPIRV-Cross refuses the shader.
bool slang_translate(const SlangSource& src, TranslatedPass* out, std::string* error);
