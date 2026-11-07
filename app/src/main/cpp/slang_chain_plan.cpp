// The CPU half of the slang chain: read a preset from disk, load and translate every pass. No GL here, so it runs
// on any thread and is tested on the desktop (app/src/test/native/slang/plan_test.cpp).
#include "slang_chain.h"

#include <fstream>
#include <sstream>

namespace {

ReadFile diskReader(const std::string& root) {
    return [root](const std::string& path, std::string* out) {
        std::ifstream in(root + "/" + path, std::ios::binary);
        if (!in) return false;
        std::stringstream ss;
        ss << in.rdbuf();
        *out = ss.str();
        return true;
    };
}

}  // namespace

bool slang_chain_texture_list(const std::string& root, const std::string& presetPath,
                              std::vector<std::pair<std::string, std::string>>* out, std::string* error) {
    out->clear();
    SlangPreset preset;
    if (!slang_load_preset(presetPath, diskReader(root), &preset, error)) return false;
    for (const SlangTexture& t : preset.textures) out->emplace_back(t.id, t.path);
    return true;
}

bool slang_chain_prepare(const std::string& root, const std::string& presetPath, std::vector<SlangLut> luts,
                         SlangChainPlan* out, std::string* error) {
    *out = SlangChainPlan();
    ReadFile read = diskReader(root);
    if (!slang_load_preset(presetPath, read, &out->preset, error)) return false;

    for (const SlangTexture& t : out->preset.textures) {
        bool found = false;
        for (const SlangLut& l : luts) found = found || (l.id == t.id && l.width > 0 && l.height > 0);
        if (!found) {
            if (error) *error = "missing image for texture '" + t.id + "' (" + t.path + ")";
            return false;
        }
    }
    out->luts = std::move(luts);

    for (const SlangPass& pass : out->preset.passes) {
        SlangSource source;
        TranslatedPass translated;
        if (!slang_load_source(pass.shader, read, &source, error)) return false;
        if (!slang_translate(source, &translated, error)) {
            if (error) *error = pass.shader + ": " + *error;
            return false;
        }
        for (const SlangParam& p : source.params) out->params.emplace(p.id, p.initial);
        if (translated.esVersion > out->needsEs) out->needsEs = translated.esVersion;
        out->sources.push_back(std::move(source));
        out->passes.push_back(std::move(translated));
    }
    for (const auto& o : out->preset.paramOverrides) out->params[o.first] = o.second;
    return true;
}
