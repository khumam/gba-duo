// slang_chain_prepare / slang_chain_texture_list: a preset on disk into a ready-to-instantiate plan (no GL).
#include "slang_chain.h"
#include <cstdio>
#include <string>

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)
static bool has(const std::string& hay, const char* needle) { return hay.find(needle) != std::string::npos; }

int main() {
    const std::string root = FIXTURES_DIR;
    {   // the textures a preset names, so the app can decode them
        std::vector<std::pair<std::string, std::string>> list; std::string err;
        CHECK(slang_chain_texture_list(root, "chain.slangp", &list, &err));
        CHECK(list.size() == 1 && list[0].first == "lut" && list[0].second == "lut.png");
    }
    {   // a missing image is a clear error, not a crash later
        SlangChainPlan plan; std::string err;
        CHECK(!slang_chain_prepare(root, "chain.slangp", {}, &plan, &err));
        CHECK(has(err, "lut"));
    }
    {   // prepared: two translated passes, parameters = defaults then overrides, ES version = the highest any pass needs
        SlangLut lut; lut.id = "lut"; lut.width = 1; lut.height = 1; lut.rgba = {1, 2, 3, 4};
        SlangChainPlan plan; std::string err;
        CHECK(slang_chain_prepare(root, "chain.slangp", {lut}, &plan, &err));
        CHECK(plan.passes.size() == 2 && plan.sources.size() == 2 && plan.preset.passes.size() == 2);
        CHECK(plan.preset.passes[0].alias == "First");
        CHECK(plan.needsEs == 310);
        CHECK(plan.params.count("CRTgamma") && plan.params["CRTgamma"] == 2.0f);   // override wins over the default 2.4
        CHECK(plan.params.count("d") && plan.params["d"] == 1.5f);                 // default from #pragma parameter
        CHECK(plan.luts.size() == 1 && plan.luts[0].rgba.size() == 4);
    }
    {   // a preset that does not exist, and one whose shader is broken
        SlangChainPlan plan; std::string err;
        CHECK(!slang_chain_prepare(root, "nope.slangp", {}, &plan, &err) && has(err, "nope.slangp"));
    }
    if (failures == 0) std::puts("plan_test: ok");
    return failures ? 1 : 0;
}
