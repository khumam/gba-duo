#include "slang_source.h"

#include <cstdlib>
#include <sstream>

namespace {

constexpr int MAX_INCLUDE_DEPTH = 16;

enum class Stage { Common, Vertex, Fragment };

struct Loader {
    const ReadFile& read;
    std::string version;
    std::string sections[3];
    Stage stage = Stage::Common;
    SlangSource* out;
    std::vector<std::string> stack;
    std::string* error;

    bool fail(const std::string& message) {
        if (error) *error = message;
        return false;
    }

    static std::string trim(const std::string& s) {
        size_t a = s.find_first_not_of(" \t\r");
        if (a == std::string::npos) return "";
        size_t b = s.find_last_not_of(" \t\r");
        return s.substr(a, b - a + 1);
    }

    static bool startsWith(const std::string& s, const char* prefix) { return s.rfind(prefix, 0) == 0; }

    bool parameter(const std::string& rest) {
        // ID "label" initial min max [step]
        std::istringstream in(rest);
        SlangParam p;
        if (!(in >> p.id)) return false;
        std::string tail;
        std::getline(in, tail);
        size_t q1 = tail.find('"'), q2 = q1 == std::string::npos ? q1 : tail.find('"', q1 + 1);
        if (q2 == std::string::npos) return false;
        p.label = tail.substr(q1 + 1, q2 - q1 - 1);
        std::istringstream nums(tail.substr(q2 + 1));
        if (!(nums >> p.initial >> p.min >> p.max)) return false;
        if (!(nums >> p.step)) p.step = 0;
        out->params.push_back(p);
        return true;
    }

    bool file(const std::string& path) {
        if (stack.size() >= MAX_INCLUDE_DEPTH) return fail("include nested too deeply at " + path);
        for (const std::string& open : stack) {
            if (open == path) return fail("include cycle through " + path);
        }
        std::string text;
        if (!read(path, &text)) return fail("cannot read " + path);
        stack.push_back(path);
        std::istringstream lines(text);
        std::string line;
        while (std::getline(lines, line)) {
            std::string t = trim(line);
            if (startsWith(t, "#include")) {
                size_t q1 = t.find('"'), q2 = q1 == std::string::npos ? q1 : t.find('"', q1 + 1);
                if (q2 == std::string::npos) return fail("bad #include in " + path + ": " + t);
                std::string rel = t.substr(q1 + 1, q2 - q1 - 1), resolved;
                if (!slang_resolve_path(path, rel, &resolved, error)) return false;
                if (!file(resolved)) return false;
            } else if (startsWith(t, "#version")) {
                if (version.empty()) version = t + "\n";
            } else if (startsWith(t, "#pragma stage")) {
                std::string which = trim(t.substr(13));
                if (which == "vertex") stage = Stage::Vertex;
                else if (which == "fragment") stage = Stage::Fragment;
                else return fail("unknown stage '" + which + "' in " + path);
            } else if (startsWith(t, "#pragma name")) {
                out->name = trim(t.substr(12));
            } else if (startsWith(t, "#pragma format")) {
                out->format = trim(t.substr(14));
            } else if (startsWith(t, "#pragma parameter")) {
                if (!parameter(trim(t.substr(17)))) return fail("bad #pragma parameter in " + path + ": " + t);
            } else {
                sections[static_cast<int>(stage)] += line + "\n";
            }
        }
        stack.pop_back();
        return true;
    }
};

}  // namespace

bool slang_resolve_path(const std::string& baseFile, const std::string& rel, std::string* out, std::string* error) {
    auto refuse = [&]() {
        if (error) *error = "path '" + rel + "' is outside the shader folder";
        return false;
    };
    if (rel.empty() || rel[0] == '/') return refuse();
    std::vector<std::string> parts;
    size_t slash = baseFile.rfind('/');
    std::string joined = (slash == std::string::npos ? "" : baseFile.substr(0, slash + 1)) + rel;
    std::istringstream in(joined);
    std::string part;
    while (std::getline(in, part, '/')) {
        if (part.empty() || part == ".") continue;
        if (part == "..") {
            if (parts.empty()) return refuse();
            parts.pop_back();
        } else {
            parts.push_back(part);
        }
    }
    if (parts.empty()) return refuse();
    std::string result;
    for (size_t i = 0; i < parts.size(); i++) result += (i ? "/" : "") + parts[i];
    *out = result;
    return true;
}

bool slang_load_source(const std::string& path, const ReadFile& read, SlangSource* out, std::string* error) {
    *out = SlangSource();
    Loader loader{read, "", {}, Stage::Common, out, {}, error};
    if (!loader.file(path)) return false;
    if (loader.version.empty()) return loader.fail("no #version in " + path);
    if (loader.sections[1].empty()) return loader.fail("no vertex stage in " + path);
    if (loader.sections[2].empty()) return loader.fail("no fragment stage in " + path);
    out->vertex = loader.version + loader.sections[0] + loader.sections[1];
    out->fragment = loader.version + loader.sections[0] + loader.sections[2];
    return true;
}
