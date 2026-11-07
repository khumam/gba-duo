#!/bin/sh
# Builds the slang shader code (glslang + SPIRV-Cross + app/src/main/cpp/slang_*.cpp) for the desktop and
# runs every app/src/test/native/slang/*_test.cpp. Needs clang and cmake + ninja (installed into a venv
# under $TMPDIR if missing).
#
#   app/src/test/native/run-slang.sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
out="${TMPDIR:-/tmp}/gbaduo-slang-tests"
mkdir -p "$out"

if ! command -v cmake >/dev/null 2>&1 || ! command -v ninja >/dev/null 2>&1; then
    [ -x "$out/venv/bin/cmake" ] || { python3 -m venv "$out/venv" && "$out/venv/bin/pip" install -q cmake ninja; }
    PATH="$out/venv/bin:$PATH"
fi

cmake -S "$here/slang" -B "$out/build" -G Ninja -DCMAKE_BUILD_TYPE=Debug -DCMAKE_POLICY_VERSION_MINIMUM=3.5 >"$out/configure.log" 2>&1 \
    || { tail -30 "$out/configure.log"; exit 1; }
ninja -C "$out/build" >"$out/build.log" 2>&1 || { tail -40 "$out/build.log"; exit 1; }

status=0
for source in "$here"/slang/*_test.cpp; do
    t="$out/build/$(basename "$source" .cpp)"      # only tests whose source still exists (no stale binaries)
    [ -x "$t" ] || continue
    echo "== $(basename "$t")"
    "$t" || status=1
done
exit $status
