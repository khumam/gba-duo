#include "slang_preset.h"

#include <cstdlib>
#include <sstream>

namespace {

constexpr int MAX_REFERENCE_DEPTH = 8;

struct Entry {
    std::string value;
    std::string origin;     // the preset file that set it, for resolving paths
};
using Keys = std::map<std::string, Entry>;

std::string trim(const std::string& s) {
    size_t a = s.find_first_not_of(" \t\r");
    if (a == std::string::npos) return "";
    size_t b = s.find_last_not_of(" \t\r");
    return s.substr(a, b - a + 1);
}

std::string unquote(const std::string& s) {
    if (s.size() >= 2 && s.front() == '"' && s.back() == '"') return s.substr(1, s.size() - 2);
    return s;
}

struct Parser {
    const ReadFile& read;
    std::string* error;
    std::vector<std::string> stack;
    Keys keys;

    bool fail(const std::string& message) {
        if (error) *error = message;
        return false;
    }

    bool collect(const std::string& path) {
        if (stack.size() >= MAX_REFERENCE_DEPTH) return fail("#reference nested too deeply at " + path);
        for (const std::string& open : stack) {
            if (open == path) return fail("#reference cycle through " + path);
        }
        std::string text;
        if (!read(path, &text)) return fail("cannot read " + path);
        stack.push_back(path);
        std::istringstream lines(text);
        std::string line;
        while (std::getline(lines, line)) {
            std::string t = trim(line);
            if (t.rfind("#reference", 0) == 0) {
                std::string rel = unquote(trim(t.substr(10))), resolved;
                if (!slang_resolve_path(path, rel, &resolved, error)) return false;
                if (!collect(resolved)) return false;
                continue;
            }
            if (t.empty() || t[0] == '#') continue;
            size_t eq = t.find('=');
            if (eq == std::string::npos) continue;
            keys[trim(t.substr(0, eq))] = Entry{unquote(trim(t.substr(eq + 1))), path};
        }
        stack.pop_back();
        return true;
    }

    bool has(const std::string& key) const { return keys.count(key) != 0; }
    std::string get(const std::string& key, const std::string& fallback = "") const {
        auto it = keys.find(key);
        return it == keys.end() ? fallback : it->second.value;
    }
    bool flag(const std::string& key) const {
        std::string v = get(key);
        return v == "true" || v == "1";
    }
    float number(const std::string& key, float fallback) const {
        auto it = keys.find(key);
        return it == keys.end() ? fallback : std::strtof(it->second.value.c_str(), nullptr);
    }
    static int wrapMode(const std::string& v) {
        if (v == "clamp_to_edge") return 1;
        if (v == "repeat") return 2;
        if (v == "mirrored_repeat") return 3;
        return 0;
    }
    static SlangScale scaleType(const std::string& v) {
        if (v == "viewport") return SlangScale::Viewport;
        if (v == "absolute") return SlangScale::Absolute;
        return SlangScale::Source;
    }
    bool resolvePath(const std::string& key, std::string* out) {
        const Entry& e = keys.at(key);
        return slang_resolve_path(e.origin, e.value, out, error);
    }
};

std::vector<std::string> split(const std::string& s, char sep) {
    std::vector<std::string> parts;
    std::istringstream in(s);
    std::string part;
    while (std::getline(in, part, sep)) {
        part = trim(part);
        if (!part.empty()) parts.push_back(part);
    }
    return parts;
}

}  // namespace

bool slang_load_preset(const std::string& path, const ReadFile& read, SlangPreset* out, std::string* error) {
    *out = SlangPreset();
    Parser p{read, error, {}, {}};
    if (!p.collect(path)) return false;

    int count = static_cast<int>(p.number("shaders", 0));
    if (count <= 0) return p.fail("no shaders in " + path);
    for (int i = 0; i < count; i++) {
        std::string n = std::to_string(i);
        if (!p.has("shader" + n)) return p.fail("missing shader" + n + " in " + path);
        SlangPass pass;
        if (!p.resolvePath("shader" + n, &pass.shader)) return false;
        pass.alias = p.get("alias" + n);
        pass.filterLinear = p.flag("filter_linear" + n);
        pass.mipmap = p.flag("mipmap_input" + n);
        pass.floatFb = p.flag("float_framebuffer" + n);
        pass.srgbFb = p.flag("srgb_framebuffer" + n);
        pass.wrap = Parser::wrapMode(p.get("wrap_mode" + n));
        std::string both = p.get("scale_type" + n, "source");
        pass.scaleX = Parser::scaleType(p.get("scale_type_x" + n, both));
        pass.scaleY = Parser::scaleType(p.get("scale_type_y" + n, both));
        float factor = p.number("scale" + n, 1.0f);
        pass.factorX = p.number("scale_x" + n, factor);
        pass.factorY = p.number("scale_y" + n, factor);
        out->passes.push_back(pass);
    }

    for (const std::string& id : split(p.get("textures"), ';')) {
        if (!p.has(id)) return p.fail("texture '" + id + "' has no path in " + path);
        SlangTexture t;
        t.id = id;
        if (!p.resolvePath(id, &t.path)) return false;
        t.filterLinear = p.flag(id + "_linear");
        t.mipmap = p.flag(id + "_mipmap");
        t.wrap = Parser::wrapMode(p.get(id + "_wrap_mode", p.get(id + "_wrap")));
        out->textures.push_back(t);
    }

    for (const std::string& name : split(p.get("parameters"), ';')) {
        if (p.has(name)) out->paramOverrides[name] = p.number(name, 0);
    }
    return true;
}
