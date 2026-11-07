// Loading a libretro `.slang` shader: expanding `#include`s, splitting the `#pragma stage` sections into a
// vertex and a fragment source, and collecting the `#pragma name|format|parameter` lines. Pure C++, no GL.
#pragma once

#include <functional>
#include <string>
#include <vector>

struct SlangParam {
    std::string id, label;
    float initial = 0, min = 0, max = 0, step = 0;
};

struct SlangSource {
    std::string vertex, fragment;   // complete sources, each starting with the file's `#version` line
    std::string name, format;       // `#pragma name`, `#pragma format` (empty when absent)
    std::vector<SlangParam> params;
};

// Reads the file at a path relative to the shader root; false when it cannot be read.
using ReadFile = std::function<bool(const std::string& path, std::string* out)>;

// Resolves [rel] against the directory of [baseFile] (both relative to the shader root). Absolute paths and any
// path that climbs above the root are refused: false, with [error] naming [rel] as written.
bool slang_resolve_path(const std::string& baseFile, const std::string& rel, std::string* out, std::string* error);

bool slang_load_source(const std::string& path, const ReadFile& read, SlangSource* out, std::string* error);
