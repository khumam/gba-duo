// slang_load_source: stage splitting, includes, pragmas, and refusing paths that leave the shader root.
#include "slang_source.h"
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
    {   // (a) stages are split; shared code goes to both
        Files f{{"a.slang",
            "#version 450\n"
            "layout(push_constant) uniform Push { vec4 SourceSize; } params;\n"
            "#pragma stage vertex\n"
            "void main() { gl_Position = vec4(0.0); }\n"
            "#pragma stage fragment\n"
            "layout(location = 0) out vec4 c;\n"
            "void main() { c = vec4(1.0); }\n"}};
        SlangSource s; std::string err;
        CHECK(slang_load_source("a.slang", reader(f), &s, &err));
        CHECK(has(s.vertex, "gl_Position") && !has(s.vertex, "location = 0) out"));
        CHECK(has(s.fragment, "location = 0) out") && !has(s.fragment, "gl_Position"));
        CHECK(has(s.vertex, "push_constant") && has(s.fragment, "push_constant"));
        CHECK(s.vertex.rfind("#version 450", 0) == 0 && s.fragment.rfind("#version 450", 0) == 0);
        CHECK(!has(s.vertex, "#pragma stage"));
    }
    {   // (b) includes: relative to the including file, nested
        Files f{
            {"crt/a.slang", "#version 450\n#include \"inc/common.inc\"\n#pragma stage vertex\nvoid main(){}\n#pragma stage fragment\nvoid main(){}\n"},
            {"crt/inc/common.inc", "#include \"deeper.inc\"\nvec4 shared_fn();\n"},
            {"crt/inc/deeper.inc", "const float DEEP = 1.0;\n"}};
        SlangSource s; std::string err;
        CHECK(slang_load_source("crt/a.slang", reader(f), &s, &err));
        CHECK(has(s.vertex, "DEEP") && has(s.fragment, "shared_fn"));
        CHECK(!has(s.fragment, "#include"));
    }
    {   // (c) name and format
        Files f{{"a.slang", "#version 450\n#pragma name MyPass\n#pragma format R8G8B8A8_UNORM\n#pragma stage vertex\nvoid main(){}\n#pragma stage fragment\nvoid main(){}\n"}};
        SlangSource s; std::string err;
        CHECK(slang_load_source("a.slang", reader(f), &s, &err));
        CHECK(s.name == "MyPass" && s.format == "R8G8B8A8_UNORM");
        CHECK(!has(s.vertex, "#pragma name") && !has(s.fragment, "#pragma format"));
    }
    {   // (d) parameters
        Files f{{"a.slang", "#version 450\n#pragma parameter GAMMA \"Gamma\" 2.2 1.0 3.0 0.1\n#pragma stage vertex\nvoid main(){}\n#pragma stage fragment\nvoid main(){}\n"}};
        SlangSource s; std::string err;
        CHECK(slang_load_source("a.slang", reader(f), &s, &err));
        CHECK(s.params.size() == 1);
        if (s.params.size() == 1) {
            CHECK(s.params[0].id == "GAMMA" && s.params[0].label == "Gamma");
            CHECK(s.params[0].initial == 2.2f && s.params[0].min == 1.0f && s.params[0].max == 3.0f && s.params[0].step == 0.1f);
        }
        CHECK(!has(s.vertex, "#pragma parameter") && !has(s.fragment, "#pragma parameter"));
    }
    {   // (e) paths leaving the root are refused (Review Focus 3)
        Files f{{"a.slang", "#version 450\n#include \"../../etc/x.inc\"\n"}, {"b.slang", "#version 450\n#include \"/abs/x.inc\"\n"},
                {"etc/x.inc", "x"}, {"sub/c.slang", "#version 450\n#include \"../../outside.inc\"\n"}};
        SlangSource s; std::string err;
        CHECK(!slang_load_source("a.slang", reader(f), &s, &err));
        CHECK(has(err, "../../etc/x.inc"));
        err.clear();
        CHECK(!slang_load_source("b.slang", reader(f), &s, &err));
        CHECK(has(err, "/abs/x.inc"));
        CHECK(!slang_load_source("sub/c.slang", reader(f), &s, &err));
    }
    {   // (f) include cycle
        Files f{{"a.slang", "#version 450\n#include \"b.inc\"\n"}, {"b.inc", "#include \"a.slang\"\n"}};
        SlangSource s; std::string err;
        CHECK(!slang_load_source("a.slang", reader(f), &s, &err));
        CHECK(has(err, "cycle") || has(err, "recursive"));
    }
    {   // a missing file is an error that names it
        Files f;
        SlangSource s; std::string err;
        CHECK(!slang_load_source("nope.slang", reader(f), &s, &err));
        CHECK(has(err, "nope.slang"));
    }
    if (failures == 0) std::puts("source_test: ok");
    return failures ? 1 : 0;
}
