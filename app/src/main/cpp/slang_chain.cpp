// The GL half of the slang pass chain (see slang_chain.h for the conventions). OpenGL ES 3.x, GL thread only.
#include "slang_chain.h"

#include <GLES3/gl31.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>

#define TAG "GBADuoSlang"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

constexpr int MAX_UNITS = 16;
constexpr int MAX_HISTORY = 7;
constexpr int ERROR_CHECK_FRAMES = 3;   // glGetError can stall a threaded driver: only the first frames are checked

struct Target {
    GLuint tex = 0;
    int w = 0, h = 0;
};

// What a sampler or a *Size member names, worked out once from its name so the frame loop compares no strings.
enum class RefKind { None, Input, Original, History, PassOutput, PassFeedback, Lut, Output, Viewport };

struct Ref {
    RefKind kind = RefKind::None;
    int index = 0;                  // history depth (1 = previous frame), pass or LUT index
};

enum class MemberKind { Static, FrameCount, Size };

struct MemberRt {
    MemberKind kind = MemberKind::Static;
    int offset = 0;
    Ref ref;                        // for Size
};

struct UboRt {
    SlangBlock block;
    GLuint buffer = 0;
    GLuint point = 0;
    std::vector<uint8_t> data;      // the static members (MVP, parameters, ...) are written once, at instantiate
    std::vector<MemberRt> dynamic;  // what changes from frame to frame
};

struct SamplerRt {
    std::string name;
    int unit = 0;
    GLint loc = -1;
    Ref ref;
};

struct PassRt {
    SlangPass cfg;
    GLuint program = 0;
    std::vector<UboRt> ubos;
    std::vector<SamplerRt> samplers;
    GLuint sampler = 0;             // how this pass reads its inputs (filter, wrap, mipmaps)
    Target out[2];
    int cur = 0;                    // which of out[] this frame writes; the other is last frame's (feedback)
    bool feedback = false;
    GLenum internalFormat = GL_RGBA8;
    GLenum format = GL_RGBA;
    GLenum type = GL_UNSIGNED_BYTE;
    GLuint checkedTex = 0;          // the output last found framebuffer-complete, at checkedW x checkedH
    int checkedW = 0, checkedH = 0;
};

struct TexRef {
    GLuint tex = 0;
    int w = 1, h = 1;
};

// Puts back the GL bindings instantiate() touches, so the app's own drawing (client-side vertex arrays, VAO 0) is unaffected.
struct BindingGuard {
    GLint vao = 0, arrayBuffer = 0, uniformBuffer = 0, program = 0, activeTexture = 0, texture0 = 0, readFbo = 0, drawFbo = 0;
    BindingGuard() {
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &arrayBuffer);
        glGetIntegerv(GL_UNIFORM_BUFFER_BINDING, &uniformBuffer);
        glGetIntegerv(GL_CURRENT_PROGRAM, &program);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &activeTexture);
        glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture0);
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFbo);
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFbo);
    }
    ~BindingGuard() {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(texture0));
        glActiveTexture(static_cast<GLenum>(activeTexture));
        glBindVertexArray(static_cast<GLuint>(vao));
        glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(arrayBuffer));
        glBindBuffer(GL_UNIFORM_BUFFER, static_cast<GLuint>(uniformBuffer));
        glUseProgram(static_cast<GLuint>(program));
        glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(readFbo));
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(drawFbo));
    }
};

int parseEsMinor() {
    const char* v = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    if (!v) return -1;
    const char* p = std::strstr(v, "OpenGL ES ");
    if (!p) return -1;
    int major = 0, minor = 0;
    if (std::sscanf(p + 10, "%d.%d", &major, &minor) < 2) return -1;
    return major * 100 + minor * 10;
}

bool hasExtension(const char* name) {
    GLint n = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &n);
    for (GLint i = 0; i < n; i++) {
        const char* e = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, i));
        if (e && std::strcmp(e, name) == 0) return true;
    }
    return false;
}

std::string firstLine(const std::string& log) {
    size_t end = log.find('\n');
    return end == std::string::npos ? log : log.substr(0, end);
}

bool compile(GLenum type, const std::string& source, GLuint* out, std::string* error) {
    GLuint shader = glCreateShader(type);
    const char* text = source.c_str();
    glShaderSource(shader, 1, &text, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetShaderInfoLog(shader, sizeof(log) - 1, nullptr, log);
        *error = std::string(type == GL_VERTEX_SHADER ? "vertex" : "fragment") + " shader: " + firstLine(log);
        glDeleteShader(shader);
        return false;
    }
    *out = shader;
    return true;
}

void putFloats(std::vector<uint8_t>& data, const SlangMember& m, const float* v, int count) {
    int bytes = count * 4;
    if (m.size < bytes || m.offset < 0 || m.offset + bytes > static_cast<int>(data.size())) return;
    std::memcpy(data.data() + m.offset, v, bytes);
}

bool startsWith(const std::string& s, const char* p) { return s.rfind(p, 0) == 0; }
bool endsWith(const std::string& s, const char* suffix) {
    size_t n = std::strlen(suffix);
    return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

}  // namespace

class SlangChain {
public:
    explicit SlangChain(SlangChainPlan&& p) : plan(std::move(p)) {}
    ~SlangChain() { release(); }

    // The GL context died with every object in it: free only the CPU side (GL calls would hit the new context).
    void abandon() { released = true; }

    bool instantiate(std::string* error);
    void render(unsigned inTex, int inW, int inH, unsigned outTex, int outW, int outH, uint32_t frame);

private:
    SlangChainPlan plan;
    std::vector<PassRt> passes;
    std::vector<std::pair<std::string, int>> aliases;   // alias -> pass index
    struct LutRt { std::string id; Target t; GLuint sampler = 0; };
    std::vector<LutRt> luts;
    std::vector<Target> history;                        // history[0] = the previous frame
    int historyW = 0, historyH = 0;
    GLuint vao = 0, vbo = 0, fbo = 0, readFbo = 0, black = 0;
    bool floatOk = false;
    bool released = false;
    int errorChecks = ERROR_CHECK_FRAMES;
    uint32_t unitsUsed = 0;                             // texture units some pass binds, to unbind their samplers after

    TexRef input, original;
    int viewW = 0, viewH = 0;

    void release();
    static GLuint makeTexture(int w, int h, GLenum internalFormat, GLenum format, GLenum type, const void* pixels);
    Ref parseRef(const std::string& name, int pass) const;
    Ref parseSizeRef(const std::string& member, int pass) const;
    void buildBlock(UboRt& u, int pass);
    bool resolve(const Ref& ref, int pass, TexRef* out, GLuint* sampler) const;
    void clearTarget(GLuint tex);
    void allocatePass(PassRt& p, int w, int h, bool last);
    void ensureHistory(int w, int h);
    void fillBlock(UboRt& u, int pass, uint32_t frame);
    void draw(int index, uint32_t frame, unsigned outTex, int w, int h);
};

GLuint SlangChain::makeTexture(int w, int h, GLenum internalFormat, GLenum format, GLenum type, const void* pixels) {
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, pixels);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return tex;
}

static GLint wrapEnum(int wrap) {
    // ES 3.0 has no clamp-to-border; clamp_to_edge is the closest.
    switch (wrap) {
        case 2: return GL_REPEAT;
        case 3: return GL_MIRRORED_REPEAT;
        default: return GL_CLAMP_TO_EDGE;
    }
}

static GLuint makeSampler(bool linear, bool mipmap, int wrap) {
    GLuint s = 0;
    glGenSamplers(1, &s);
    GLint minFilter = linear ? (mipmap ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR) : (mipmap ? GL_NEAREST_MIPMAP_NEAREST : GL_NEAREST);
    glSamplerParameteri(s, GL_TEXTURE_MIN_FILTER, minFilter);
    glSamplerParameteri(s, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
    glSamplerParameteri(s, GL_TEXTURE_WRAP_S, wrapEnum(wrap));
    glSamplerParameteri(s, GL_TEXTURE_WRAP_T, wrapEnum(wrap));
    return s;
}

bool SlangChain::instantiate(std::string* error) {
    BindingGuard guard;
    int es = parseEsMinor();
    if (es < 300) {
        *error = "needs OpenGL ES 3.0 or newer";
        return false;
    }
    if (plan.needsEs > es) {
        *error = "this shader needs OpenGL ES 3.1";
        return false;
    }
    floatOk = es >= 320 || hasExtension("GL_EXT_color_buffer_float");

    const int count = static_cast<int>(plan.preset.passes.size());
    passes.resize(count);
    for (int i = 0; i < count; i++) {
        PassRt& p = passes[i];
        p.cfg = plan.preset.passes[i];
        const TranslatedPass& tr = plan.passes[i];
        if (!p.cfg.alias.empty()) aliases.emplace_back(p.cfg.alias, i);

        GLuint vs = 0, fs = 0;
        if (!compile(GL_VERTEX_SHADER, tr.vertex, &vs, error)) { *error = p.cfg.shader + ": " + *error; return false; }
        if (!compile(GL_FRAGMENT_SHADER, tr.fragment, &fs, error)) { glDeleteShader(vs); *error = p.cfg.shader + ": " + *error; return false; }
        p.program = glCreateProgram();
        glAttachShader(p.program, vs);
        glAttachShader(p.program, fs);
        glLinkProgram(p.program);
        glDeleteShader(vs);
        glDeleteShader(fs);
        GLint linked = 0;
        glGetProgramiv(p.program, GL_LINK_STATUS, &linked);
        if (!linked) {
            char log[1024] = {0};
            glGetProgramInfoLog(p.program, sizeof(log) - 1, nullptr, log);
            *error = p.cfg.shader + ": link: " + firstLine(log);
            return false;
        }

        glUseProgram(p.program);
        GLuint point = 0;
        for (const SlangBlock& b : tr.blocks) {
            GLuint index = glGetUniformBlockIndex(p.program, b.name.c_str());
            if (index == GL_INVALID_INDEX) {            // optimised out of this program
                LOGW("pass %d (%s): uniform block '%s' not in the linked program", i, p.cfg.shader.c_str(), b.name.c_str());
                continue;
            }
            UboRt u;
            u.block = b;
            u.point = point++;
            u.data.assign(static_cast<size_t>((b.size + 15) / 16 * 16), 0);
            glGenBuffers(1, &u.buffer);
            glBindBuffer(GL_UNIFORM_BUFFER, u.buffer);
            glBufferData(GL_UNIFORM_BUFFER, static_cast<GLsizeiptr>(u.data.size()), nullptr, GL_DYNAMIC_DRAW);
            glUniformBlockBinding(p.program, index, u.point);
            p.ubos.push_back(std::move(u));
        }
        for (const SlangSampler& s : tr.samplers) {
            GLint loc = glGetUniformLocation(p.program, s.name.c_str());
            if (loc < 0 || s.binding >= MAX_UNITS) {
                LOGW("pass %d (%s): sampler '%s' (binding %d) not usable (location %d)", i, p.cfg.shader.c_str(), s.name.c_str(), s.binding, loc);
                continue;
            }
            SamplerRt sr;
            sr.name = s.name;
            sr.unit = s.binding;
            sr.loc = loc;
            glUniform1i(loc, sr.unit);
            p.samplers.push_back(sr);
        }
        p.sampler = makeSampler(p.cfg.filterLinear, p.cfg.mipmap, p.cfg.wrap);
        if (p.cfg.srgbFb) {
            p.internalFormat = GL_SRGB8_ALPHA8;
        } else if (p.cfg.floatFb && floatOk) {
            p.internalFormat = GL_RGBA16F;
            p.type = GL_HALF_FLOAT;
        } else if (p.cfg.floatFb) {
            LOGW("pass %d wants a float framebuffer; this GPU lacks GL_EXT_color_buffer_float, using 8 bit", i);
        }
    }

    // Which feedback and history the samplers ask for.
    for (const PassRt& p : passes) {
        for (const SamplerRt& s : p.samplers) {
            if (startsWith(s.name, "OriginalHistory")) {
                int n = std::atoi(s.name.c_str() + 15);
                if (n > static_cast<int>(history.size()) && n <= MAX_HISTORY) history.resize(n);
            } else if (startsWith(s.name, "PassFeedback")) {
                int n = std::atoi(s.name.c_str() + 12);
                if (n >= 0 && n < count) passes[n].feedback = true;
            } else if (endsWith(s.name, "Feedback")) {
                std::string alias = s.name.substr(0, s.name.size() - 8);
                for (const auto& a : aliases) if (a.first == alias) passes[a.second].feedback = true;
            }
        }
    }

    for (const SlangTexture& t : plan.preset.textures) {
        const SlangLut* lut = nullptr;
        for (const SlangLut& l : plan.luts) if (l.id == t.id) lut = &l;
        if (!lut) continue;
        LutRt r;
        r.id = t.id;
        r.t.w = lut->width;
        r.t.h = lut->height;
        r.t.tex = makeTexture(lut->width, lut->height, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, lut->rgba.data());
        if (t.mipmap) glGenerateMipmap(GL_TEXTURE_2D);
        r.sampler = makeSampler(t.filterLinear, t.mipmap, t.wrap);
        luts.push_back(std::move(r));
    }

    // Now that feedback, aliases and LUTs are known: what every sampler and uniform member reads.
    for (int i = 0; i < count; i++) {
        PassRt& p = passes[i];
        for (SamplerRt& s : p.samplers) {
            s.ref = parseRef(s.name, i);
            unitsUsed |= 1u << s.unit;
        }
        for (UboRt& u : p.ubos) buildBlock(u, i);
    }

    const uint8_t blackPixel[4] = {0, 0, 0, 255};
    black = makeTexture(1, 1, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, blackPixel);

    // Position (vec4) and TexCoord (vec2), TexCoord (0,0) at clip (-1,-1): see slang_chain.h.
    const float quad[] = {
        -1.f, -1.f, 0.f, 1.f, 0.f, 0.f,
         1.f, -1.f, 0.f, 1.f, 1.f, 0.f,
        -1.f,  1.f, 0.f, 1.f, 0.f, 1.f,
         1.f,  1.f, 0.f, 1.f, 1.f, 1.f,
    };
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 24, reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 24, reinterpret_cast<void*>(16));
    glGenFramebuffers(1, &fbo);
    glGenFramebuffers(1, &readFbo);
    plan.sources.clear();
    plan.passes.clear();
    return true;
}

void SlangChain::release() {
    if (released) return;
    released = true;
    for (PassRt& p : passes) {
        if (p.program) glDeleteProgram(p.program);
        if (p.sampler) glDeleteSamplers(1, &p.sampler);
        for (UboRt& u : p.ubos) if (u.buffer) glDeleteBuffers(1, &u.buffer);
        for (Target& t : p.out) if (t.tex) glDeleteTextures(1, &t.tex);
    }
    for (LutRt& l : luts) {
        if (l.t.tex) glDeleteTextures(1, &l.t.tex);
        if (l.sampler) glDeleteSamplers(1, &l.sampler);
    }
    for (Target& t : history) if (t.tex) glDeleteTextures(1, &t.tex);
    if (black) glDeleteTextures(1, &black);
    if (vbo) glDeleteBuffers(1, &vbo);
    if (vao) glDeleteVertexArrays(1, &vao);
    if (fbo) glDeleteFramebuffers(1, &fbo);
    if (readFbo) glDeleteFramebuffers(1, &readFbo);
}

Ref SlangChain::parseRef(const std::string& name, int pass) const {
    const int count = static_cast<int>(passes.size());
    if (name == "Source") return pass == 0 ? Ref{RefKind::Input, 0} : Ref{RefKind::PassOutput, pass - 1};
    if (name == "Original") return {RefKind::Original, 0};
    if (startsWith(name, "OriginalHistory")) {
        int n = std::atoi(name.c_str() + 15);
        return n >= 1 ? Ref{RefKind::History, n} : Ref{RefKind::Original, 0};
    }
    if (startsWith(name, "PassOutput")) {
        int n = std::atoi(name.c_str() + 10);
        return n >= 0 && n < pass ? Ref{RefKind::PassOutput, n} : Ref{};
    }
    if (startsWith(name, "PassFeedback")) {
        int n = std::atoi(name.c_str() + 12);
        return n >= 0 && n < count && passes[n].feedback ? Ref{RefKind::PassFeedback, n} : Ref{};
    }
    for (const auto& a : aliases) {
        if (a.first == name && a.second < pass) return {RefKind::PassOutput, a.second};
        if (a.first + "Feedback" == name && passes[a.second].feedback) return {RefKind::PassFeedback, a.second};
    }
    for (size_t i = 0; i < luts.size(); i++) {
        if (luts[i].id == name) return {RefKind::Lut, static_cast<int>(i)};
    }
    return {};
}

Ref SlangChain::parseSizeRef(const std::string& member, int pass) const {
    if (member == "SourceSize") return parseRef("Source", pass);
    if (member == "OriginalSize") return parseRef("Original", pass);
    if (member == "OutputSize") return {RefKind::Output, pass};
    if (member == "FinalViewportSize") return {RefKind::Viewport, 0};
    if (startsWith(member, "OriginalHistorySize")) return parseRef("OriginalHistory" + member.substr(19), pass);
    if (startsWith(member, "PassOutputSize")) return parseRef("PassOutput" + member.substr(14), pass);
    if (startsWith(member, "PassFeedbackSize")) return parseRef("PassFeedback" + member.substr(16), pass);
    if (endsWith(member, "FeedbackSize")) return parseRef(member.substr(0, member.size() - 12) + "Feedback", pass);
    if (endsWith(member, "Size")) return parseRef(member.substr(0, member.size() - 4), pass);
    return {};
}

// Writes the members that never change and lists the ones the frame loop updates.
void SlangChain::buildBlock(UboRt& u, int pass) {
    static const float identity[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    const int bytes = static_cast<int>(u.data.size());
    for (const SlangMember& m : u.block.members) {
        if (m.name == "MVP") { putFloats(u.data, m, identity, 16); continue; }
        if (m.name == "FrameCount") {
            if (m.size >= 4 && m.offset >= 0 && m.offset + 4 <= bytes) u.dynamic.push_back({MemberKind::FrameCount, m.offset, {}});
            continue;
        }
        if (m.name == "FrameDirection") {
            int32_t one = 1;
            if (m.size >= 4 && m.offset >= 0 && m.offset + 4 <= bytes) std::memcpy(u.data.data() + m.offset, &one, 4);
            continue;
        }
        if (m.size == 16 && m.offset >= 0 && m.offset + 16 <= bytes) {
            Ref ref = parseSizeRef(m.name, pass);
            if (ref.kind != RefKind::None) { u.dynamic.push_back({MemberKind::Size, m.offset, ref}); continue; }
        }
        auto it = plan.params.find(m.name);
        if (it != plan.params.end()) putFloats(u.data, m, &it->second, 1);
    }
}

bool SlangChain::resolve(const Ref& ref, int pass, TexRef* out, GLuint* sampler) const {
    *sampler = passes[pass].sampler;
    auto fromTarget = [&](const Target& t) { out->tex = t.tex; out->w = t.w; out->h = t.h; return t.tex != 0; };
    switch (ref.kind) {
        case RefKind::Input: *out = input; return true;
        case RefKind::Original: *out = original; return true;
        case RefKind::History: {
            int n = ref.index;
            if (n <= static_cast<int>(history.size()) && history[n - 1].tex && history[n - 1].w == original.w) {
                return fromTarget(history[n - 1]);
            }
            *out = original;    // not enough history yet
            return true;
        }
        case RefKind::PassOutput: return fromTarget(passes[ref.index].out[passes[ref.index].cur]);
        case RefKind::PassFeedback: return fromTarget(passes[ref.index].out[1 - passes[ref.index].cur]);
        case RefKind::Lut: *sampler = luts[ref.index].sampler; return fromTarget(luts[ref.index].t);
        case RefKind::Output: fromTarget(passes[ref.index].out[passes[ref.index].cur]); return true;  // the last pass has no texture of its own
        case RefKind::Viewport: out->tex = 0; out->w = viewW; out->h = viewH; return true;
        case RefKind::None: return false;
    }
    return false;
}

void SlangChain::allocatePass(PassRt& p, int w, int h, bool last) {
    for (int k = 0; k < 2; k++) {
        Target& t = p.out[k];
        bool wanted = k == 0 || p.feedback;
        if (last) {         // the last pass draws into the caller's texture; only the size is kept
            t.w = w; t.h = h;
            continue;
        }
        if (!wanted) continue;
        if (t.tex && t.w == w && t.h == h) continue;
        if (t.tex) glDeleteTextures(1, &t.tex);
        t.tex = makeTexture(w, h, p.internalFormat, p.format, p.type, nullptr);
        t.w = w;
        t.h = h;
        if (p.feedback) clearTarget(t.tex);     // a feedback pass reads it next frame: black, not old memory
    }
}

void SlangChain::clearTarget(GLuint tex) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glClearColor(0, 0, 0, 0);                   // the GL default, which the app's own clears rely on
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
}

void SlangChain::ensureHistory(int w, int h) {
    if (history.empty()) return;
    if (historyW == w && historyH == h && history[0].tex) return;
    for (Target& t : history) {
        if (t.tex) glDeleteTextures(1, &t.tex);
        t.tex = makeTexture(w, h, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ONE);   // copies of the input: its alpha is no colour (see render)
        t.w = w;
        t.h = h;
        clearTarget(t.tex);                                     // start from black, not whatever the memory held
    }
    historyW = w;
    historyH = h;
}

// Only the members that change per frame; buildBlock wrote the rest once. Offsets were bounds-checked there.
void SlangChain::fillBlock(UboRt& u, int pass, uint32_t frame) {
    for (const MemberRt& m : u.dynamic) {
        uint8_t* at = u.data.data() + m.offset;
        if (m.kind == MemberKind::FrameCount) {
            std::memcpy(at, &frame, 4);
            continue;
        }
        TexRef ref;
        GLuint unused;
        if (resolve(m.ref, pass, &ref, &unused)) {
            float v[4] = {static_cast<float>(ref.w), static_cast<float>(ref.h), 1.0f / std::max(ref.w, 1), 1.0f / std::max(ref.h, 1)};
            std::memcpy(at, v, sizeof(v));
        } else {
            std::memset(at, 0, 16);
        }
    }
}

void SlangChain::draw(int index, uint32_t frame, unsigned outTex, int w, int h) {
    PassRt& p = passes[index];
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outTex, 0);
    // The status check can make the driver wait, so it runs only when the output is a new texture or size.
    if (outTex != p.checkedTex || w != p.checkedW || h != p.checkedH) {
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            LOGW("pass %d: framebuffer incomplete (%dx%d)", index, w, h);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
            p.checkedTex = 0;
            return;
        }
        p.checkedTex = outTex;
        p.checkedW = w;
        p.checkedH = h;
    }
    glViewport(0, 0, w, h);
    glUseProgram(p.program);

    for (UboRt& u : p.ubos) {
        fillBlock(u, index, frame);
        glBindBuffer(GL_UNIFORM_BUFFER, u.buffer);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, static_cast<GLsizeiptr>(u.data.size()), u.data.data());
        glBindBufferBase(GL_UNIFORM_BUFFER, u.point, u.buffer);
    }
    for (const SamplerRt& s : p.samplers) {
        TexRef ref;
        GLuint sampler = p.sampler;
        if (!resolve(s.ref, index, &ref, &sampler)) { ref.tex = black; ref.w = ref.h = 1; sampler = p.sampler; }
        glActiveTexture(GL_TEXTURE0 + s.unit);
        glBindTexture(GL_TEXTURE_2D, ref.tex);
        glBindSampler(s.unit, sampler);
        if (p.cfg.mipmap && ref.tex != black && s.ref.kind != RefKind::Original && sampler == p.sampler) glGenerateMipmap(GL_TEXTURE_2D);
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (errorChecks > 0) {
        for (GLenum e; (e = glGetError()) != GL_NO_ERROR;) {
            LOGW("pass %d (%s): GL error 0x%x after draw (%dx%d)", index, p.cfg.shader.c_str(), e, w, h);
        }
    }
    // Do not leave the texture attached: the caller re-specifies the output when the viewport changes, and some GPU
    // drivers keep rendering into the old storage of a texture that is re-specified while attached.
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
}

void SlangChain::render(unsigned inTex, int inW, int inH, unsigned outTex, int outW, int outH, uint32_t frame) {
    if (passes.empty() || inW <= 0 || inH <= 0 || outW <= 0 || outH <= 0) return;

    // No glGet* here: on a threaded driver each one can wait for the GPU thread. The state is set, then reset to the
    // known defaults the header promises.
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    // The app's frame can carry the layer probe's depth in alpha; to the shaders the picture is opaque.
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, inTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ONE);

    viewW = outW;
    viewH = outH;
    input.tex = inTex; input.w = inW; input.h = inH;
    original = input;
    ensureHistory(inW, inH);
    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);     // the attribute setup is cheap; redo it so nothing else's state can leak in
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 24, reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 24, reinterpret_cast<void*>(16));

    const int count = static_cast<int>(passes.size());
    int srcW = inW, srcH = inH;
    for (int i = 0; i < count; i++) {
        PassRt& p = passes[i];
        const bool last = i == count - 1;
        auto axis = [&](SlangScale type, float factor, int source, int viewport) {
            float v = type == SlangScale::Source ? source * factor : type == SlangScale::Viewport ? viewport * factor : factor;
            return std::max(1, static_cast<int>(std::lround(v)));
        };
        int w = last ? outW : axis(p.cfg.scaleX, p.cfg.factorX, srcW, outW);
        int h = last ? outH : axis(p.cfg.scaleY, p.cfg.factorY, srcH, outH);
        allocatePass(p, w, h, last);
        draw(i, frame, last ? outTex : p.out[p.cur].tex, w, h);
        srcW = w;
        srcH = h;
    }
    for (PassRt& p : passes) if (p.feedback) p.cur = 1 - p.cur;

    // The input becomes history[0]; older frames move down one.
    if (!history.empty() && history[0].tex) {
        std::rotate(history.begin(), history.end() - 1, history.end());
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, inTex, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, history[0].tex, 0);
        glBlitFramebuffer(0, 0, inW, inH, 0, 0, inW, inH, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    }

    // The defaults the app's own drawing expects (see slang_chain_render in the header).
    for (int u = MAX_UNITS - 1; u >= 0; u--) {
        if (!(unitsUsed & (1u << u))) continue;
        glActiveTexture(GL_TEXTURE0 + u);
        glBindSampler(u, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, inTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ALPHA);
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_UNIFORM_BUFFER, 0);
    glUseProgram(0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (errorChecks > 0) errorChecks--;
}

SlangChain* slang_chain_instantiate(SlangChainPlan&& plan, std::string* error) {
    std::unique_ptr<SlangChain> chain(new SlangChain(std::move(plan)));
    if (!chain->instantiate(error)) return nullptr;     // the destructor frees whatever was built
    return chain.release();
}

void slang_chain_render(SlangChain* chain, unsigned inputTexture, int inputWidth, int inputHeight,
                        unsigned outputTexture, int outputWidth, int outputHeight, uint32_t frame) {
    if (chain) chain->render(inputTexture, inputWidth, inputHeight, outputTexture, outputWidth, outputHeight, frame);
}

void slang_chain_destroy(SlangChain* chain) { delete chain; }

void slang_chain_abandon(SlangChain* chain) {
    if (!chain) return;
    chain->abandon();
    delete chain;
}
