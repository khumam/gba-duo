// The companion pack's Lua sandbox (script.c), without JNI: the JNI entry points in
// script.c wrap these, and the desktop test harness (app/src/test/native) calls them
// directly.

#ifndef GBADUO_SCRIPT_H
#define GBADUO_SCRIPT_H

#include <stdbool.h>

struct lua_State;

/** Most bytes one mem.write* call may write. */
#define SCRIPT_WRITE_MAX_PER_CALL 64u
/** Most bytes one tap (one action call) may write in total. */
#define SCRIPT_WRITE_MAX_PER_TAP 256u

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

/** For tests: whether mem.write* is currently allowed. */
bool gbaduo_script_writes_open(void);

#endif
