// slang_load_preset: a .slangp into passes, textures and parameter overrides.
#include "slang_preset.h"
#include <cstdio>
#include <map>
#include <string>

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

using Files = std::map<std::string, std::string>;
static ReadFile reader(const Files& files) {
    return [&files](const std::string& path, std::string* out) {
        auto it = files.find(path);
        if (it == files.end()) return false;
        *out = it->second;
        return true;
    };
}
static bool has(const std::string& hay, const char* needle) { return hay.find(needle) != std::string::npos; }

int main() {
    {   // (a) two passes, every field
        Files f{{"crt/p.slangp",
            "shaders = 2\n"
            "shader0 = shaders/a.slang\n"
            "filter_linear0 = true\n"
            "scale_type0 = source\n"
            "scale0 = 2.0\n"
            "alias0 = \"A\"\n"
            "shader1 = \"shaders/b.slang\"\n"
            "scale_type1 = viewport\n"
            "wrap_mode1 = repeat\n"
            "float_framebuffer1 = true\n"
            "srgb_framebuffer1 = false\n"
            "mipmap_input1 = true\n"}};
        SlangPreset p; std::string err;
        CHECK(slang_load_preset("crt/p.slangp", reader(f), &p, &err));
        CHECK(p.passes.size() == 2);
        if (p.passes.size() == 2) {
            const SlangPass& a = p.passes[0];
            CHECK(a.shader == "crt/shaders/a.slang" && a.alias == "A" && a.filterLinear);
            CHECK(a.scaleX == SlangScale::Source && a.scaleY == SlangScale::Source && a.factorX == 2.0f && a.factorY == 2.0f);
            const SlangPass& b = p.passes[1];
            CHECK(b.shader == "crt/shaders/b.slang");
            CHECK(b.scaleX == SlangScale::Viewport && b.scaleY == SlangScale::Viewport);
            CHECK(b.wrap == 2 && b.floatFb && !b.srgbFb && b.mipmap && !b.filterLinear);
        }
    }
    {   // (b) defaults
        Files f{{"p.slangp", "shaders = 1\nshader0 = a.slang\n"}};
        SlangPreset p; std::string err;
        CHECK(slang_load_preset("p.slangp", reader(f), &p, &err));
        const SlangPass& a = p.passes[0];
        CHECK(a.scaleX == SlangScale::Source && a.factorX == 1.0f && a.factorY == 1.0f);
        CHECK(!a.filterLinear && !a.mipmap && !a.floatFb && !a.srgbFb && a.wrap == 0 && a.alias.empty());
    }
    {   // per-axis scale keys
        Files f{{"p.slangp", "shaders = 1\nshader0 = a.slang\nscale_type_x0 = absolute\nscale_x0 = 320\nscale_type_y0 = viewport\nscale_y0 = 0.5\n"}};
        SlangPreset p; std::string err;
        CHECK(slang_load_preset("p.slangp", reader(f), &p, &err));
        const SlangPass& a = p.passes[0];
        CHECK(a.scaleX == SlangScale::Absolute && a.factorX == 320.0f && a.scaleY == SlangScale::Viewport && a.factorY == 0.5f);
    }
    {   // (c) textures
        Files f{{"x/p.slangp", "shaders = 1\nshader0 = a.slang\ntextures = \"lut\"\nlut = luts/l.png\nlut_linear = true\nlut_wrap_mode = repeat\nlut_mipmap = true\n"}};
        SlangPreset p; std::string err;
        CHECK(slang_load_preset("x/p.slangp", reader(f), &p, &err));
        CHECK(p.textures.size() == 1);
        if (p.textures.size() == 1) {
            CHECK(p.textures[0].id == "lut" && p.textures[0].path == "x/luts/l.png");
            CHECK(p.textures[0].filterLinear && p.textures[0].mipmap && p.textures[0].wrap == 2);
        }
    }
    {   // (d) parameter overrides
        Files f{{"p.slangp", "shaders = 1\nshader0 = a.slang\nparameters = \"A;B\"\nA = 0.5\nB = 2\n"}};
        SlangPreset p; std::string err;
        CHECK(slang_load_preset("p.slangp", reader(f), &p, &err));
        CHECK(p.paramOverrides.size() == 2 && p.paramOverrides["A"] == 0.5f && p.paramOverrides["B"] == 2.0f);
    }
    {   // (e) #reference: referenced first, then overridden; its shader paths are relative to its own folder
        Files f{
            {"presets/top.slangp", "#reference \"../base/base.slangp\"\nparameters = \"GAMMA\"\nGAMMA = 1.5\nfilter_linear0 = false\n"},
            {"base/base.slangp", "shaders = 1\nshader0 = s/a.slang\nfilter_linear0 = true\nparameters = \"GAMMA\"\nGAMMA = 2.2\n"}};
        SlangPreset p; std::string err;
        CHECK(slang_load_preset("presets/top.slangp", reader(f), &p, &err));
        CHECK(p.passes.size() == 1 && p.passes[0].shader == "base/s/a.slang");
        CHECK(!p.passes[0].filterLinear);
        CHECK(p.paramOverrides["GAMMA"] == 1.5f);
    }
    {   // (f) cycle and escaping paths (Review Focus 3)
        Files f{{"a.slangp", "#reference \"b.slangp\"\n"}, {"b.slangp", "#reference \"a.slangp\"\n"},
                {"c.slangp", "shaders = 1\nshader0 = ../../evil.slang\n"},
                {"d.slangp", "#reference \"../../x.slangp\"\n"}};
        SlangPreset p; std::string err;
        CHECK(!slang_load_preset("a.slangp", reader(f), &p, &err));
        CHECK(has(err, "cycle") || has(err, "recursive"));
        CHECK(!slang_load_preset("c.slangp", reader(f), &p, &err));
        CHECK(has(err, "evil.slang"));
        CHECK(!slang_load_preset("d.slangp", reader(f), &p, &err));
    }
    {   // (g) no shaders / missing shaderN
        Files f{{"a.slangp", "shaders = 0\n"}, {"b.slangp", "shaders = 2\nshader0 = a.slang\n"}, {"c.slangp", "textures = \"x\"\n"}};
        SlangPreset p; std::string err;
        CHECK(!slang_load_preset("a.slangp", reader(f), &p, &err) && !err.empty());
        CHECK(!slang_load_preset("b.slangp", reader(f), &p, &err) && has(err, "shader1"));
        CHECK(!slang_load_preset("c.slangp", reader(f), &p, &err));
        CHECK(!slang_load_preset("missing.slangp", reader(f), &p, &err) && has(err, "missing.slangp"));
    }
    if (failures == 0) std::puts("preset_test: ok");
    return failures ? 1 : 0;
}
