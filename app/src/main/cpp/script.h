// The companion pack's Lua sandbox (script.c), without JNI: the JNI entry points in
// script.c wrap these, and the desktop test harness (app/src/test/native) calls them
// directly.

#ifndef GBADUO_SCRIPT_H
#define GBADUO_SCRIPT_H

#include <stdbool.h>
#include <stdint.h>

#include "wide.h"

struct lua_State;

/** Most bytes one mem.write* call may write. */
#define SCRIPT_WRITE_MAX_PER_CALL 64u
/** Most bytes one tap (one action call) may write in total. */
#define SCRIPT_WRITE_MAX_PER_TAP 256u
/** Most bytes one game.lua frame() may write in total. */
#define SCRIPT_WRITE_MAX_PER_FRAME 256u

/** Pushes an action's arguments (exactly as many as announced) onto [L]. */
typedef void (*gbaduo_push_args)(struct lua_State* L, void* userdata);

/** Replaces any loaded script with [source]; runs its top level, then init(). False on failure. */
bool gbaduo_script_load(const char* source, const char* packDir);

/** update(ui) as malloc'd JSON (free it), or NULL on failure. */
char* gbaduo_script_tick(void);

/**
 * actions[name](args...). Returns its result as malloc'd JSON when it returned a
 * table, else NULL (failure if gbaduo_script_last_error() is non-empty).
 *
 * [may_write]: whether mem.write* may change the game during this call (the pack has
 * the memory-write permission and the player allowed it). Writes are refused at every
 * other time, whatever this flag: in init(), update(), the top level, between calls.
 */
char* gbaduo_script_action(const char* name, gbaduo_push_args push, void* userdata, int nargs, bool may_write);

/** Why the last load/tick/action failed; "" when it didn't. */
const char* gbaduo_script_last_error(void);

void gbaduo_script_unload(void);

/*
 * The game's script (game.lua): a second sandbox, independent of the companion's
 * (own globals, budgets, errors, image cache). Both run on the emu thread.
 */

/** Replaces any loaded game script with [source]; runs its top level, then init(). False on failure. */
bool gbaduo_game_load(const char* source, const char* packDir);

void gbaduo_game_unload(void);

bool gbaduo_game_loaded(void);

/** Why the last game-script load/hook failed; "" when it didn't. */
const char* gbaduo_game_last_error(void);

/** Whether the game script defines a global function [hook] ("frame" or "wide"). */
bool gbaduo_game_has(const char* hook);

/** False after five hook errors in a row, until the next gbaduo_game_load. */
bool gbaduo_game_enabled(void);

/**
 * Calls frame({frame=, width=, left=}) with its per-frame budget. The write window is
 * open for this call only when [may_write]. False on an error or the budget (the reason
 * in gbaduo_game_last_error()); true when it ran, or the script has no frame().
 */
bool gbaduo_game_frame(int64_t frameCounter, int width, int left, bool may_write);

/**
 * Calls wide({width=, left=, match=}) (read-only). True with [out] filled when it
 * returned a world; false for nil/false (no error) or an error, a wrong field or the
 * budget (the reason in gbaduo_game_last_error()).
 */
bool gbaduo_game_wide(int width, int left, float match, struct WideWorld* out);

/** For tests: a malloc'd copy of the ARGB8888 image for a "gfx:..." key, or NULL. */
uint32_t* gbaduo_script_image(const char* key, int* width, int* height);

/** For tests: how much of its per-call budget the last load/tick/action used (instructions + charged C work; to the nearest hook interval). */
long gbaduo_script_budget_used(void);

/** For tests: whether mem.write* is currently allowed. */
bool gbaduo_script_writes_open(void);

#endif
