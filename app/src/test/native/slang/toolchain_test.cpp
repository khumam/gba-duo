// Proves the vendored glslang + SPIRV-Cross build and cooperate: a Vulkan-style fragment shader becomes
// SPIR-V, and SPIR-V becomes GLSL ES 3.00.
#include <glslang/Public/ShaderLang.h>
#include <glslang/Public/ResourceLimits.h>
#include <SPIRV/GlslangToSpv.h>
#include <spirv_glsl.hpp>
#include <cstdio>
#include <string>
#include <vector>

int main() {
    const char* source =
        "#version 450\n"
        "layout(location = 0) out vec4 c;\n"
        "void main() { c = vec4(1.0); }\n";

    glslang::InitializeProcess();
    glslang::TShader shader(EShLangFragment);
    shader.setStrings(&source, 1);
    shader.setEnvInput(glslang::EShSourceGlsl, EShLangFragment, glslang::EShClientVulkan, 100);
    shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_0);
    shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_0);
    if (!shader.parse(GetDefaultResources(), 450, false, EShMsgDefault)) {
        std::fprintf(stderr, "parse failed: %s\n", shader.getInfoLog());
        return 1;
    }
    glslang::TProgram program;
    program.addShader(&shader);
    if (!program.link(EShMsgDefault)) {
        std::fprintf(stderr, "link failed: %s\n", program.getInfoLog());
        return 1;
    }
    std::vector<unsigned int> spirv;
    glslang::GlslangToSpv(*program.getIntermediate(EShLangFragment), spirv);
    if (spirv.empty()) {
        std::fprintf(stderr, "no SPIR-V\n");
        return 1;
    }

    spirv_cross::CompilerGLSL glsl(spirv);
    spirv_cross::CompilerGLSL::Options options;
    options.es = true;
    options.version = 300;
    glsl.set_common_options(options);
    std::string out = glsl.compile();
    if (out.find("#version 300 es") == std::string::npos) {
        std::fprintf(stderr, "not ESSL 300:\n%s\n", out.c_str());
        return 1;
    }
    glslang::FinalizeProcess();
    std::puts("toolchain_test: ok");
    return 0;
}
