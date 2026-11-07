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

struct Target {
    GLuint tex = 0;
    int w = 0, h = 0;
};

struct UboRt {
    SlangBlock block;
    GLuint buffer = 0;
    GLuint point = 0;
    std::vector<uint8_t> data;
};

struct SamplerRt {
    std::string name;
    int unit = 0;
    GLint loc = -1;
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
};

struct TexRef {
    GLuint tex = 0;
    int w = 1, h = 1;
};

struct Saved {
    GLint drawFbo, readFbo, program, activeTexture, arrayBuffer, uniformBuffer, vao, viewport[4];
    GLint tex[MAX_UNITS];
    GLboolean blend, depth, cull, scissor;
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

void putSize(std::vector<uint8_t>& data, const SlangMember& m, int w, int h) {
    float v[4] = {static_cast<float>(w), static_cast<float>(h), 1.0f / std::max(w, 1), 1.0f / std::max(h, 1)};
    putFloats(data, m, v, 4);
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

    TexRef input, original;
    int viewW = 0, viewH = 0;

    void release();
    static GLuint makeTexture(int w, int h, GLenum internalFormat, GLenum format, GLenum type, const void* pixels);
    bool resolve(const std::string& name, int pass, TexRef* ref, GLuint* sampler);
    bool sizeFor(const std::string& member, int pass, int* w, int* h);
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

bool SlangChain::resolve(const std::string& name, int pass, TexRef* ref, GLuint* sampler) {
    *sampler = passes[pass].sampler;
    auto fromTarget = [&](const Target& t) { ref->tex = t.tex; ref->w = t.w; ref->h = t.h; return t.tex != 0; };
    if (name == "Source") {
        if (pass == 0) { *ref = input; return true; }
        return fromTarget(passes[pass - 1].out[passes[pass - 1].cur]);
    }
    if (name == "Original") { *ref = original; return true; }
    if (startsWith(name, "OriginalHistory")) {
        int n = std::atoi(name.c_str() + 15);
        if (n >= 1 && n <= static_cast<int>(history.size()) && history[n - 1].tex && history[n - 1].w == original.w) {
            return fromTarget(history[n - 1]);
        }
        *ref = original;    // not enough history yet
        return true;
    }
    if (startsWith(name, "PassOutput")) {
        int n = std::atoi(name.c_str() + 10);
        if (n >= 0 && n < pass) return fromTarget(passes[n].out[passes[n].cur]);
        return false;
    }
    if (startsWith(name, "PassFeedback")) {
        int n = std::atoi(name.c_str() + 12);
        if (n >= 0 && n < static_cast<int>(passes.size()) && passes[n].feedback) return fromTarget(passes[n].out[1 - passes[n].cur]);
        return false;
    }
    for (const auto& a : aliases) {
        if (a.first == name && a.second < pass) return fromTarget(passes[a.second].out[passes[a.second].cur]);
        if (a.first + "Feedback" == name && passes[a.second].feedback) return fromTarget(passes[a.second].out[1 - passes[a.second].cur]);
    }
    for (const LutRt& l : luts) {
        if (l.id == name) { *sampler = l.sampler; return fromTarget(l.t); }
    }
    return false;
}

bool SlangChain::sizeFor(const std::string& member, int pass, int* w, int* h) {
    TexRef ref;
    GLuint unused;
    std::string sampler;
    if (member == "SourceSize") sampler = "Source";
    else if (member == "OriginalSize") sampler = "Original";
    else if (member == "OutputSize") { *w = passes[pass].out[passes[pass].cur].w; *h = passes[pass].out[passes[pass].cur].h; return true; }
    else if (member == "FinalViewportSize") { *w = viewW; *h = viewH; return true; }
    else if (startsWith(member, "OriginalHistorySize")) sampler = "OriginalHistory" + member.substr(19);
    else if (startsWith(member, "PassOutputSize")) sampler = "PassOutput" + member.substr(14);
    else if (startsWith(member, "PassFeedbackSize")) sampler = "PassFeedback" + member.substr(16);
    else if (endsWith(member, "FeedbackSize")) sampler = member.substr(0, member.size() - 12) + "Feedback";
    else if (endsWith(member, "Size")) sampler = member.substr(0, member.size() - 4);
    else return false;
    if (!resolve(sampler, pass, &ref, &unused)) return false;
    *w = ref.w;
    *h = ref.h;
    return true;
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
    }
}

void SlangChain::ensureHistory(int w, int h) {
    if (history.empty()) return;
    if (historyW == w && historyH == h && history[0].tex) return;
    for (Target& t : history) {
        if (t.tex) glDeleteTextures(1, &t.tex);
        t.tex = makeTexture(w, h, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        t.w = w;
        t.h = h;
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);                 // start from black, not whatever the memory held
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.tex, 0);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
    }
    historyW = w;
    historyH = h;
}

void SlangChain::fillBlock(UboRt& u, int pass, uint32_t frame) {
    std::fill(u.data.begin(), u.data.end(), 0);
    static const float identity[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    for (const SlangMember& m : u.block.members) {
        if (m.name == "MVP") { putFloats(u.data, m, identity, 16); continue; }
        if (m.name == "FrameCount") {
            if (m.size >= 4 && m.offset + 4 <= static_cast<int>(u.data.size())) std::memcpy(u.data.data() + m.offset, &frame, 4);
            continue;
        }
        if (m.name == "FrameDirection") {
            int32_t one = 1;
            if (m.size >= 4 && m.offset + 4 <= static_cast<int>(u.data.size())) std::memcpy(u.data.data() + m.offset, &one, 4);
            continue;
        }
        int w = 0, h = 0;
        if (m.size == 16 && sizeFor(m.name, pass, &w, &h)) { putSize(u.data, m, w, h); continue; }
        auto it = plan.params.find(m.name);
        if (it != plan.params.end()) putFloats(u.data, m, &it->second, 1);
    }
}

void SlangChain::draw(int index, uint32_t frame, unsigned outTex, int w, int h) {
    PassRt& p = passes[index];
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outTex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGW("pass %d: framebuffer incomplete (%dx%d)", index, w, h);
        return;
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
        if (!resolve(s.name, index, &ref, &sampler)) { ref.tex = black; ref.w = ref.h = 1; sampler = p.sampler; }
        glActiveTexture(GL_TEXTURE0 + s.unit);
        glBindTexture(GL_TEXTURE_2D, ref.tex);
        glBindSampler(s.unit, sampler);
        if (p.cfg.mipmap && ref.tex != black && s.name != "Original" && sampler == p.sampler) glGenerateMipmap(GL_TEXTURE_2D);
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    static int errorsLogged = 0;
    for (GLenum e; (e = glGetError()) != GL_NO_ERROR && errorsLogged < 20; errorsLogged++) {
        LOGW("pass %d (%s): GL error 0x%x after draw (%dx%d)", index, p.cfg.shader.c_str(), e, w, h);
    }
    // Do not leave the texture attached: the caller re-specifies the output when the viewport changes, and some GPU
    // drivers keep rendering into the old storage of a texture that is re-specified while attached.
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
}

void SlangChain::render(unsigned inTex, int inW, int inH, unsigned outTex, int outW, int outH, uint32_t frame) {
    if (passes.empty() || inW <= 0 || inH <= 0 || outW <= 0 || outH <= 0) return;

    Saved s;
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &s.drawFbo);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &s.readFbo);
    glGetIntegerv(GL_CURRENT_PROGRAM, &s.program);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &s.activeTexture);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &s.arrayBuffer);
    glGetIntegerv(GL_UNIFORM_BUFFER_BINDING, &s.uniformBuffer);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &s.vao);
    glGetIntegerv(GL_VIEWPORT, s.viewport);
    for (int u = 0; u < MAX_UNITS; u++) {
        glActiveTexture(GL_TEXTURE0 + u);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &s.tex[u]);
    }
    s.blend = glIsEnabled(GL_BLEND);
    s.depth = glIsEnabled(GL_DEPTH_TEST);
    s.cull = glIsEnabled(GL_CULL_FACE);
    s.scissor = glIsEnabled(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);

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

    // Put the GL state back the way the app's own drawing expects it.
    for (int u = 0; u < MAX_UNITS; u++) {
        glActiveTexture(GL_TEXTURE0 + u);
        glBindSampler(u, 0);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(s.tex[u]));
    }
    glActiveTexture(static_cast<GLenum>(s.activeTexture));
    glBindVertexArray(static_cast<GLuint>(s.vao));
    glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(s.arrayBuffer));
    glBindBuffer(GL_UNIFORM_BUFFER, static_cast<GLuint>(s.uniformBuffer));
    glUseProgram(static_cast<GLuint>(s.program));
    glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(s.readFbo));
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(s.drawFbo));
    glViewport(s.viewport[0], s.viewport[1], s.viewport[2], s.viewport[3]);
    if (s.blend) glEnable(GL_BLEND);
    if (s.depth) glEnable(GL_DEPTH_TEST);
    if (s.cull) glEnable(GL_CULL_FACE);
    if (s.scissor) glEnable(GL_SCISSOR_TEST);
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
