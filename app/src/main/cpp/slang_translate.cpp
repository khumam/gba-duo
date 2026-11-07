#include "slang_translate.h"

#include <SPIRV/GlslangToSpv.h>
#include <glslang/Public/ResourceLimits.h>
#include <glslang/Public/ShaderLang.h>
#include <spirv_glsl.hpp>

#include <algorithm>
#include <exception>
#include <mutex>
#include <sstream>

namespace {

std::string firstLine(const std::string& log) {
    std::istringstream in(log);
    std::string line, fallback;
    while (std::getline(in, line)) {
        if (line.empty()) continue;
        if (fallback.empty()) fallback = line;
        if (line.find("ERROR") != std::string::npos) return line;
    }
    return fallback;
}

bool compileStage(const std::string& source, EShLanguage stage, const char* stageName, std::vector<unsigned int>* spirv, std::string* error) {
    static std::once_flag once;
    std::call_once(once, [] { glslang::InitializeProcess(); });

    const char* text = source.c_str();
    glslang::TShader shader(stage);
    shader.setStrings(&text, 1);
    shader.setEnvInput(glslang::EShSourceGlsl, stage, glslang::EShClientVulkan, 100);
    shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_0);
    shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_0);
    const EShMessages messages = static_cast<EShMessages>(EShMsgSpvRules | EShMsgVulkanRules);
    if (!shader.parse(GetDefaultResources(), 450, false, messages)) {
        *error = std::string(stageName) + ": " + firstLine(shader.getInfoLog());
        return false;
    }
    glslang::TProgram program;
    program.addShader(&shader);
    if (!program.link(messages)) {
        *error = std::string(stageName) + ": " + firstLine(program.getInfoLog());
        return false;
    }
    glslang::GlslangToSpv(*program.getIntermediate(stage), *spirv);
    if (spirv->empty()) {
        *error = std::string(stageName) + ": no SPIR-V produced";
        return false;
    }
    return true;
}

void addBlock(spirv_cross::CompilerGLSL& glsl, const spirv_cross::Resource& r, bool push, std::vector<SlangBlock>* blocks) {
    const spirv_cross::SPIRType& type = glsl.get_type(r.base_type_id);
    SlangBlock b;
    b.name = glsl.get_name(r.base_type_id);
    if (b.name.empty()) b.name = r.name;
    b.push = push;
    b.size = static_cast<int>(glsl.get_declared_struct_size(type));
    for (uint32_t i = 0; i < type.member_types.size(); i++) {
        SlangMember m;
        m.name = glsl.get_member_name(r.base_type_id, i);
        m.offset = static_cast<int>(glsl.type_struct_member_offset(type, i));
        m.size = static_cast<int>(glsl.get_declared_struct_member_size(type, i));
        b.members.push_back(m);
    }
    for (const SlangBlock& have : *blocks) {
        if (have.name == b.name) return;    // the other stage declared the same block
    }
    blocks->push_back(b);
}

bool crossCompile(const std::vector<unsigned int>& spirv, int esVersion, const char* stageName, std::string* glslOut,
                  std::vector<SlangBlock>* blocks, std::vector<SlangSampler>* samplers, std::string* error) {
    try {
        spirv_cross::CompilerGLSL glsl(spirv);
        spirv_cross::CompilerGLSL::Options options;
        options.es = true;
        options.version = esVersion;
        options.emit_push_constant_as_uniform_buffer = true;
        glsl.set_common_options(options);

        spirv_cross::ShaderResources res = glsl.get_shader_resources();
        for (const auto& r : res.uniform_buffers) addBlock(glsl, r, false, blocks);
        for (const auto& r : res.push_constant_buffers) addBlock(glsl, r, true, blocks);
        for (const auto& r : res.sampled_images) {
            SlangSampler s;
            s.name = r.name;
            s.binding = static_cast<int>(glsl.get_decoration(r.id, spv::DecorationBinding));
            bool known = false;
            for (const SlangSampler& have : *samplers) known = known || have.name == s.name;
            if (!known) samplers->push_back(s);
        }
        *glslOut = glsl.compile();
        return true;
    } catch (const std::exception& e) {
        *error = std::string(stageName) + ": " + firstLine(e.what());
        return false;
    }
}

}  // namespace

bool slang_translate(const SlangSource& src, TranslatedPass* out, std::string* error) {
    *out = TranslatedPass();
    std::vector<unsigned int> vertexSpirv, fragmentSpirv;
    std::string err;
    if (!compileStage(src.vertex, EShLangVertex, "vertex", &vertexSpirv, &err) ||
        !compileStage(src.fragment, EShLangFragment, "fragment", &fragmentSpirv, &err)) {
        if (error) *error = err;
        return false;
    }
    // GLSL ES 3.00 first; 3.10 only for shaders that use something 3.00 lacks. Both stages must agree.
    for (int version : {300, 310}) {
        TranslatedPass attempt;
        attempt.esVersion = version;
        err.clear();
        if (crossCompile(vertexSpirv, version, "vertex", &attempt.vertex, &attempt.blocks, &attempt.samplers, &err) &&
            crossCompile(fragmentSpirv, version, "fragment", &attempt.fragment, &attempt.blocks, &attempt.samplers, &err)) {
            *out = attempt;
            break;
        }
    }
    if (out->vertex.empty()) {
        if (error) *error = err;
        return false;
    }
    std::sort(out->samplers.begin(), out->samplers.end(),
              [](const SlangSampler& a, const SlangSampler& b) { return a.binding < b.binding; });
    return true;
}
