cmake_minimum_required(VERSION 3.22.1)
project(gbaduo C)

get_filename_component(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/mgba" ABSOLUTE)

if(NOT EXISTS "${MGBA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "mGBA source missing at ${MGBA_DIR}")
endif()

# Bare GBA core only: no frontends, no optional deps (zlib/png/sqlite/lua/ffmpeg/...).
set(BUILD_STATIC        ON  CACHE BOOL "" FORCE)
set(BUILD_SHARED        OFF CACHE BOOL "" FORCE)
set(DISABLE_FRONTENDS   ON  CACHE BOOL "" FORCE)
set(DISABLE_DEPS        ON  CACHE BOOL "" FORCE)
set(SKIP_LIBRARY        OFF CACHE BOOL "" FORCE)
set(BUILD_GL            OFF CACHE BOOL "" FORCE)
set(BUILD_GLES2         OFF CACHE BOOL "" FORCE)
set(BUILD_GLES3         OFF CACHE BOOL "" FORCE)
set(BUILD_PERF          OFF CACHE BOOL "" FORCE)
set(BUILD_TEST          OFF CACHE BOOL "" FORCE)
set(BUILD_SUITE         OFF CACHE BOOL "" FORCE)
set(BUILD_LIBRETRO      OFF CACHE BOOL "" FORCE)
set(BUILD_PYTHON        OFF CACHE BOOL "" FORCE)
set(USE_DEBUGGERS       OFF CACHE BOOL "" FORCE)
set(USE_GDB_STUB        OFF CACHE BOOL "" FORCE)
set(USE_EDITLINE        OFF CACHE BOOL "" FORCE)
set(USE_FFMPEG          OFF CACHE BOOL "" FORCE)
set(USE_DISCORD_RPC     OFF CACHE BOOL "" FORCE)
set(USE_LZMA            OFF CACHE BOOL "" FORCE)
set(USE_LUA             OFF CACHE BOOL "" FORCE)
set(USE_ELF             OFF CACHE BOOL "" FORCE)
set(USE_SQLITE3         OFF CACHE BOOL "" FORCE)
set(USE_LIBZIP          OFF CACHE BOOL "" FORCE)
set(USE_MINIZIP         OFF CACHE BOOL "" FORCE)
set(USE_PNG             OFF CACHE BOOL "" FORCE)
set(USE_ZLIB            OFF CACHE BOOL "" FORCE)
set(M_CORE_GBA          ON  CACHE BOOL "" FORCE)
set(M_CORE_GB           OFF CACHE BOOL "" FORCE)
set(BUILD_LTO           OFF CACHE BOOL "" FORCE)
set(SKIP_INSTALL_ALL    ON  CACHE BOOL "" FORCE)

add_subdirectory("${MGBA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/mgba" EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# Vendored Lua 5.4.7 (plain source tree, not a submodule — see engine/lua).
# Compiled generic/ANSI (no LUA_USE_* define): no dynamic C module loading,
# no OS-specific features. lua.c/luac.c are the standalone CLI tools (each has
# its own main()) and are excluded from the library.
# ---------------------------------------------------------------------------
get_filename_component(LUA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/lua" ABSOLUTE)

if(NOT EXISTS "${LUA_DIR}/src/lua.h")
    message(FATAL_ERROR "Lua source missing at ${LUA_DIR}")
endif()

file(GLOB LUA_SOURCES "${LUA_DIR}/src/*.c")
list(FILTER LUA_SOURCES EXCLUDE REGEX "/lua\\.c$|/luac\\.c$")

add_library(lua STATIC ${LUA_SOURCES})
target_include_directories(lua PUBLIC "${LUA_DIR}/src")

add_library(gbaduo SHARED core.c script.c)

target_compile_definitions(gbaduo PRIVATE USE_PTHREADS M_CORE_GBA)

target_include_directories(gbaduo PRIVATE
    "${MGBA_DIR}/include"
    "${MGBA_DIR}/src"
    "${CMAKE_CURRENT_BINARY_DIR}/mgba/include"
    "${LUA_DIR}/src")

target_link_libraries(gbaduo mgba lua android log)
