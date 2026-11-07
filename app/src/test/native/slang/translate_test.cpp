// slang_translate: real libretro shaders (fixtures/) become GLSL ES 3.00 with their uniform blocks and samplers described.
#include "slang_translate.h"
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

static bool readFixture(const std::string& path, std::string* out) {
    std::ifstream in(std::string(FIXTURES_DIR) + "/" + path, std::ios::binary);
    if (!in) return false;
    std::stringstream ss;
    ss << in.rdbuf();
    *out = ss.str();
    return true;
}
static bool has(const std::string& hay, const char* needle) { return hay.find(needle) != std::string::npos; }

static const SlangBlock* block(const TranslatedPass& t, const char* name) {
    for (const SlangBlock& b : t.blocks) if (b.name == name) return &b;
    return nullptr;
}
static const SlangMember* member(const SlangBlock* b, const char* name) {
    if (!b) return nullptr;
    for (const SlangMember& m : b->members) if (m.name == name) return &m;
    return nullptr;
}
static int samplerBinding(const TranslatedPass& t, const char* name) {
    for (const SlangSampler& s : t.samplers) if (s.name == name) return s.binding;
    return -1;
}

static bool translateFixture(const char* path, TranslatedPass* out, std::string* err) {
    SlangSource src;
    if (!slang_load_source(path, readFixture, &src, err)) return false;
    bool ok = slang_translate(src, out, err);
    if (!ok) std::fprintf(stderr, "translate %s: %s\n", path, err->c_str());
    return ok;
}

int main() {
    {   // stock: push block + UBO + one sampler
        TranslatedPass t; std::string err;
        CHECK(translateFixture("stock.slang", &t, &err));
        if (!err.empty()) std::fprintf(stderr, "%s\n", err.c_str());
        CHECK(t.vertex.rfind("#version 300 es", 0) == 0 && t.fragment.rfind("#version 300 es", 0) == 0);
        CHECK(!has(t.vertex, "push_constant") && !has(t.fragment, "push_constant"));
        const SlangBlock* ubo = block(t, "UBO");
        const SlangBlock* push = block(t, "Push");
        CHECK(ubo && !ubo->push && member(ubo, "MVP") && member(ubo, "MVP")->offset == 0 && member(ubo, "MVP")->size == 64);
        CHECK(push && push->push && member(push, "SourceSize") && member(push, "SourceSize")->size == 16);
        CHECK(member(push, "FrameCount") != nullptr);
        CHECK(samplerBinding(t, "Source") == 2);
        CHECK(t.esVersion == 300);
    }
    {   // crt-geom: many push members; UBO has MVP, OutputSize, SourceSize
        TranslatedPass t; std::string err;
        CHECK(translateFixture("crt/shaders/crt-geom.slang", &t, &err));
        // arrays of arrays: needs GLSL ES 3.10, so the pass records it and the chain can refuse on a 3.0 device
        CHECK(t.esVersion == 310 && t.vertex.rfind("#version 310 es", 0) == 0 && t.fragment.rfind("#version 310 es", 0) == 0);
        const SlangBlock* ubo = block(t, "UBO");
        const SlangBlock* push = block(t, "Push");
        CHECK(member(ubo, "OutputSize") && member(ubo, "OutputSize")->offset == 64);
        CHECK(member(ubo, "SourceSize") && member(ubo, "SourceSize")->offset == 80);
        CHECK(member(push, "CRTgamma") && member(push, "FrameCount"));
    }
    {   // crt-1tap: #include "shared.inc"
        TranslatedPass t; std::string err;
        CHECK(translateFixture("crt/shaders/crt-Ntap/crt-1tap.slang", &t, &err));
        CHECK(samplerBinding(t, "Source") == 1 && samplerBinding(t, "Original") == 2);
    }
    {   // imgborder: a dozen extra samplers
        TranslatedPass t; std::string err;
        CHECK(translateFixture("crt/shaders/crt-maximus-royale/src/imgborder.slang", &t, &err));
        CHECK(samplerBinding(t, "TVBORDER1") == 3 && samplerBinding(t, "LAST_PASS") == 13);
        CHECK(t.samplers.size() >= 12);
    }
    {   // a broken shader: false, and the first line of the compiler's message says which stage
        SlangSource src;
        src.vertex = "#version 450\nlayout(location = 0) in vec4 Position;\nvoid main() { gl_Position = Position; }\n";
        src.fragment = "#version 450\nlayout(location = 0) out vec4 c;\nvoid main() { undeclared(); c = vec4(1.0); }\n";
        TranslatedPass t; std::string err;
        CHECK(!slang_translate(src, &t, &err));
        CHECK(err.rfind("fragment", 0) == 0 && has(err, "undeclared"));
        CHECK(err.find('\n') == std::string::npos);
    }
    if (failures == 0) std::puts("translate_test: ok");
    return failures ? 1 : 0;
}
