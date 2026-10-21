cmake_minimum_required(VERSION 3.22.1)
project(gbaduo C)

get_filename_component(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/mgba" ABSOLUTE)

if(NOT EXISTS "${MGBA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "mGBA source missing at ${MGBA_DIR}")
endif()

# Bare GBA + GB/GBC cores only: no frontends, no optional deps (zlib/png/sqlite/lua/ffmpeg/...).
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
set(M_CORE_GB           ON  CACHE BOOL "" FORCE)
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

# ---------------------------------------------------------------------------
# Vendored rcheevos 12.5.0 (RetroAchievements runtime, MIT — see engine/rcheevos).
# rc_client + rapi + rhash for ROM files. Left out: disc / encrypted / zip hashing
# (GBA and GB/GBC hash the ROM itself), RAIntegration (Windows) and the libretro
# helpers. rcheevos makes no network calls itself: achievements.c hands them to Kotlin.
# ---------------------------------------------------------------------------
get_filename_component(RCHEEVOS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/rcheevos" ABSOLUTE)

if(NOT EXISTS "${RCHEEVOS_DIR}/include/rc_client.h")
    message(FATAL_ERROR "rcheevos source missing at ${RCHEEVOS_DIR}")
endif()

file(GLOB RCHEEVOS_SOURCES
    "${RCHEEVOS_DIR}/src/rcheevos/*.c"
    "${RCHEEVOS_DIR}/src/rapi/*.c"
    "${RCHEEVOS_DIR}/src/rhash/hash.c"
    "${RCHEEVOS_DIR}/src/rhash/hash_rom.c"
    "${RCHEEVOS_DIR}/src/rhash/md5.c"
    "${RCHEEVOS_DIR}/src/rc_client.c"
    "${RCHEEVOS_DIR}/src/rc_compat.c"
    "${RCHEEVOS_DIR}/src/rc_util.c"
    "${RCHEEVOS_DIR}/src/rc_version.c")

add_library(rcheevos STATIC ${RCHEEVOS_SOURCES})
target_include_directories(rcheevos PUBLIC "${RCHEEVOS_DIR}/include")
target_compile_definitions(rcheevos PUBLIC
    RC_CLIENT_SUPPORTS_HASH
    RC_HASH_NO_DISC
    RC_HASH_NO_ENCRYPTED
    RC_HASH_NO_ZIP)

add_library(gbaduo SHARED core.c script.c memwrite.c achievements.c)

target_compile_definitions(gbaduo PRIVATE USE_PTHREADS M_CORE_GBA M_CORE_GB)

target_include_directories(gbaduo PRIVATE
    "${MGBA_DIR}/include"
    "${MGBA_DIR}/src"
    "${CMAKE_CURRENT_BINARY_DIR}/mgba/include"
    "${LUA_DIR}/src")

target_link_libraries(gbaduo mgba lua rcheevos android log)
