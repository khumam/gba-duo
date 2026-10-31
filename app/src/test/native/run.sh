#!/bin/sh
# Builds the Lua sandbox (script.c) and the checked memory writes (memwrite.c) for the
# desktop, with a fake machine behind them, and runs script_harness.c under
# AddressSanitizer and UndefinedBehaviorSanitizer. Needs clang.
#
#   app/src/test/native/run.sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../../.." && pwd)
cpp="$root/app/src/main/cpp"
lua="$root/engine/lua/src"
out="${TMPDIR:-/tmp}/gbaduo-native-harness"
mkdir -p "$out"

lua_sources=$(ls "$lua"/*.c | grep -v -e '/lua\.c$' -e '/luac\.c$')
flags="-g -O1 -fno-omit-frame-pointer -fsanitize=address,undefined -fno-sanitize-recover=undefined -Wall -Wno-unused-function"

clang $flags -DGBADUO_HOST_TEST -DLUA_PATTERN_STEP_HOOK=gbaduo_pattern_step -I"$cpp" -I"$lua" -I"$here" \
    "$here/script_harness.c" "$here/fake_bus.c" "$cpp/script.c" "$cpp/memwrite.c" $lua_sources \
    -lm -lpthread -o "$out/script_harness"

clang $flags -I"$cpp" -I"$here" \
    "$here/wide_harness.c" "$here/fake_bus.c" "$cpp/wide.c" \
    -lm -o "$out/wide_harness"

ASAN_OPTIONS=detect_leaks=${DETECT_LEAKS:-0} UBSAN_OPTIONS=print_stacktrace=1 "$out/wide_harness"
ASAN_OPTIONS=detect_leaks=${DETECT_LEAKS:-0} UBSAN_OPTIONS=print_stacktrace=1 "$out/script_harness"
