# Taps, pages and detail screens

Any widget on the companion screen can react to a tap (`onClick`). A `pages`
widget shows one screen at a time, so a tap can open a detail page and a
button can go back. The pack's Lua script sees which page is open and what was
tapped, so each page gets the data it needs.

Taps never take the controller away from the game.

## A party list that opens a detail page

```json
{ "type": "pages", "pages": [
  { "name": "party", "child":
    { "type": "list", "items": "$party", "template":
      { "type": "row", "class": "mon",
        "onClick": [ { "set": { "selected": "{@index}" } }, { "navigate": "detail" } ],
        "children": [ { "type": "text", "text": "{name}" } ] } } },
  { "name": "detail", "child":
    { "type": "column", "children": [
      { "type": "button", "text": "‹ Back", "onClick": "back" },
      { "type": "text", "text": "{detail.name}" },
      { "type": "list", "class": "chips", "items": "$detail.weak", "template":
        { "type": "text", "class": "chip", "text": "{name} {label}" } }
    ] } }
] }
```

Tapping a row saves its position as `selected` and opens the `detail` page.
"‹ Back" returns to the party list. The script fills in `detail`
([below](#feeding-a-page-from-lua)).

[`examples/party-css`](examples/party-css/) has a complete version: tap a
party member for its stats, weaknesses and resistances.

## UI state

The screen keeps a small set of values called the UI state:
- which page each `pages` widget shows (`page` by default);
- anything a tap `set`s.

Layouts read it as `$ui.<key>` (e.g. `"{ui.selected}"`, `"visibleIf": "$ui.showAll"`).
The script reads it as `ui.<key>`. It starts empty, apart from each `pages`
widget's initial page, whenever a pack is loaded.

Values are strings, numbers or booleans.

## `onClick`

Every widget accepts `onClick`: one action, a list of actions, or `"back"`.

| Action | Does |
|---|---|
| `{ "set": { "selected": "{@index}" } }` | Sets UI keys. A value that is exactly one `{...}` keeps its type (`{@index}` stays a number); other strings are filled in as text. |
| `{ "navigate": "detail" }` | Shows that page and remembers the current one for `back`. |
| `{ "back": true }`, or `"back"` | Returns to the previous page (the initial page if there's no history). |
| `{ "call": "heal", "args": ["{@index}", "{name}"] }` | Runs `actions.heal(index, name)` in the pack's Lua ([below](#script-actions)). |

`{...}` placeholders resolve like everywhere else in a layout:
- in a list item: the item's fields, `@index` (counting from 0), then the whole snapshot;
- anywhere: `ui.*`.

One object can combine actions; they run in the order `set`, `navigate`/`back`,
`call`. A list runs in order. UI changes from a tap show at once; a `call` runs
on the next frame.

`"state"` picks which `pages` widget `navigate`/`back` drive (`page` by default):
`{ "navigate": "moves", "state": "tab2" }`.

While a widget with `onClick` is held down it matches `:pressed`, so you can style that:
`.mon:pressed { transform: scale(.97); }`.

## `pages`

```json
{ "type": "pages", "state": "page", "initial": "party", "pages": [
  { "name": "party", "child": { ... } },
  { "name": "detail", "child": { ... } }
] }
```

| Field | Default | |
|---|---|---|
| `pages` | (required) | `name` + `child` for each page; names must be unique |
| `state` | `page` | The UI key holding the shown page's name |
| `initial` | the first page | Shown until something navigates; also where `back` ends |

- Only the shown page is laid out and drawn. Hidden pages stay bound to the
  data, so they are up to date when opened.
- A page name that doesn't exist shows the initial page.
- Several `pages` widgets can share a `state` or use their own.
- Selector: `pages`, and `pages > *` for the page roots. Give pages a `class`
  or `id` to style them.

## `button`

```json
{ "type": "button", "text": "Heal all", "onClick": { "call": "healAll" } }
```

A box laid out as a centered row with default padding, a rounded background,
and `:pressed` feedback. `text` becomes a `text` child with the class `label`
(`button > .label`). `children` can add more, such as an icon.

Any other widget with `onClick` works too; `button` just comes styled.

## Feeding a page from Lua

`update()` now receives the UI state, which is also the global `ui`.
Use it to compute only what the open page needs:

```lua
local typechart = pack.require("typechart") -- examples/party-css/typechart.lua (Gen III)

function update(ui)
  local party = readParty()           -- your pack's own memory reading
  local out = { party = party }
  if ui.page == "detail" and ui.selected then
    local mon = party[ui.selected + 1]  -- @index counts from 0, Lua from 1
    if mon then
      local weak, resist, immune = typechart.matchups(mon.types)
      out.detail = {
        name = mon.name, level = mon.level, hp = mon.hp, maxHp = mon.maxHp,
        stats = {
          { name = "Attack", value = mon.attack },
          { name = "Speed", value = mon.speed },
        },
        weak = weak, resist = resist, immune = immune,
      }
    end
  end
  return out
end
```

Where the data comes from is up to the pack:
- **Game RAM:** party stats, moves, HP.
- **ROM:** `mem.*` reads the cartridge too, e.g. species base stats.
- **Data files in the pack:** `.lua` modules loaded with `pack.require`, like
  the type chart, or notes from a guide whose license allows it.

A tap that changes the UI state triggers a fresh `update(ui)` right away, so a
detail page fills in almost instantly instead of waiting for the next `tickHz`.
This also works while the game is paused.

## Changing pages from `update()`

The game itself can switch pages, without a tap: return a `ui` entry from
`update()`. It holds UI actions in the same shape as an `onClick` (`set`,
`navigate`, `back`; not `call`) and runs right after the rest of the returned
data is applied. For example, open a battle page while a battle is on:

```lua
local wasInBattle = false

function update(ui)
  local inBattle = mem.u8(BATTLE_FLAG) ~= 0   -- whatever your game's flag is
  local out = { party = readParty() }
  if inBattle then out.battle = readBattle() end

  if inBattle and not wasInBattle then
    out.ui = { navigate = "battle" }          -- the battle just started
  elseif wasInBattle and not inBattle then
    out.ui = { back = true }                  -- it ended: back to the previous page
  end
  wasInBattle = inBattle
  return out
end
```

- Return `ui` only when something changes, as above, if players should still be
  able to tap away while it lasts. Returning `{ navigate = "battle" }` on every
  tick is harmless (navigating to the page already shown does nothing) but sends
  them back to it each tick.
- `back` pops one page of history each time it's returned, so return it once.
- `ui` is not passed to the layout as data: `$ui.*` is always the UI state.
- It takes effect at the next snapshot, so within one `tickHz` interval.

## Script actions

`{ "call": "name", "args": [...] }` runs `actions.name(...)` from the pack's script:

```lua
actions = {}

function actions.open(index)
  -- Decide in Lua which page to show.
  if index == 0 then return { navigate = "lead" } end
  return { set = { selected = index }, navigate = "detail" }
end
```

- **Arguments:** strings, numbers and booleans; anything else arrives as `nil`.
  At most 16.
- **Return value:** a table in the same shape as an `onClick` action (`set`,
  `navigate`, `back`). It's applied to the screen. Return nothing to leave the
  screen as it is. A returned `call` is ignored; call the Lua function directly.
- **Timing:** actions run on the emulator thread just before the next
  `update(ui)`, with the same limits as `update`:
  - **Per call** (`update`, an action): 1M Lua instructions and 25 ms. Reading memory
    and drawing pictures count too: each byte `mem.read` returns and each pixel
    `gfx.*` draws (not cached) costs one instruction. Over a limit, the call stops with
    an error and the screen keeps its last state; the next call starts fresh.
  - **Loading** (the top level and `init()`, once per game start): 5M instructions and
    250 ms, for building data tables.
  - **Memory:** 16 MB for the whole script.
- **Errors:** an error or a missing function is logged
  (`adb logcat -s EmuThread`) and the screen stays as it was.

## Pictures from the game

A pack can draw the game's own graphics on the companion screen, read from the
player's ROM at runtime, so the pack doesn't ship them. Each function returns an
image key (`"gfx:..."`): return it from `update()` and bind it as an `image`'s
`src` (`"src": "$map.image"`). The image is decoded once and cached; call it
every tick, the result is the same key.

```lua
-- One sprite sheet: 4bpp tiles laid out row by row.
local icon = gfx.decode{ tiles = 0x08ABCDEF, palette = 0x08ABC000, width = 32, height = 32, lz77 = false }

-- A background: tiles placed by a tilemap, like the GBA draws it.
local map = gfx.tilemap{
  tiles = 0x08931DB0,    -- 4bpp tiles, 32 bytes each
  map = 0x08935DB0,      -- u16 entries: tile (bits 0-9), h-flip (10), v-flip (11), palette bank (12-15)
  palette = 0x085B3910,  -- palette bank 0; bank n is 32 bytes further
  stride = 32,           -- map entries per row (default 32)
  x = 5, y = 1,          -- first map cell to draw (default 0, 0)
  width = 160, height = 128, -- in px, multiples of 8, at most 256
  opaque = true,         -- draw colour 0 too (default: transparent)
  lz77 = false,          -- true: tiles and map are both LZ77-compressed (BIOS type 0x10)
}

-- A sprite: 4bpp tile blocks (OBJ-style, 1D mapping) placed on a canvas.
local sword = gfx.sprite{
  tiles = 0x082E5FE0,    -- tile 0; part.tile counts 32-byte tiles from here
  palette = 0x085A23D0,  -- palette bank 0
  width = 16, height = 16,
  parts = {              -- at most 32; an earlier part is drawn over a later one
    { x = 0, y = 0, w = 16, h = 16, tile = 0x1A0, bank = 3, hflip = false, vflip = false },
  },
}
```

- Everything is read raw from emulated memory, or LZ77-compressed with `lz77 = true`
  (`gfx.decode`: the tiles; `gfx.tilemap`: tiles and map).
- Images are at most 256 x 256 px. Up to 96 are kept (fewer when they're large);
  one dropped from the cache is decoded again when asked for.
- Colour 0 is transparent in sprites, as on the GBA.
- Addresses differ between a game's releases: check the game code
  (`mem.u8(0x080000AC)` onward) and keep a table per release, as
  `zelda-the-minish-cap-revamp-companion`'s `rom_gfx.lua` does for its world map
  and item icons (`pokemon-firered-revamp-companion`'s `town_map.lua` draws the
  compressed Town Map).
- Call these inside `pcall` and fall back to the pack's own art on failure, so a
  release you don't know (or a ROM hack) still gets a page.

## Changing the game

A pack's actions can also write to the game's memory: equip an item from the
companion screen, set a value without opening a menu. Reading needs nothing;
writing needs a permission, and the player's say-so.

```json
{
  "formatVersion": 3,
  "permissions": ["memory-write"],
  ...
}
```

```lua
function actions.equip(button, itemId)
  if mem.u8(SAFE_FLAG) == 1 then
    local ok, why = mem.write8(B_SLOT, itemId)
    if not ok then return { set = { message = "Couldn't equip: " .. why } } end
  end
end
```

| Function | Writes |
|---|---|
| `mem.write8(addr, value)` | 1 byte (the low 8 bits of `value`) |
| `mem.write16(addr, value)` | 2 bytes, little-endian |
| `mem.write32(addr, value)` | 4 bytes, little-endian |
| `mem.write(addr, bytes)` | a Lua string of raw bytes, 1 to 64 |

Each returns `true` when the write happened, or `false` and a reason when it
was refused, so the pack can say so on the companion screen. Nothing is written
when it returns `false`.

The checks live in the app, not in the pack's Lua, so a pack can't skip them:

- **Permission.** Without `"permissions": ["memory-write"]` (formatVersion 3)
  every write is refused. An unknown permission makes the manifest invalid.
- **Only from a tap.** Writes work only while an `onClick` action is running.
  `update()`, `init()` and the script's top level can't write, so a pack can't
  change the game on its own. A function saved during one tap can't write later.
- **Only RAM.** GBA EWRAM `0x02000000`-`0x0203FFFF` and IWRAM
  `0x03000000`-`0x03007FFF`; GB/GBC work RAM `0xC000`-`0xDFFF` and high RAM
  `0xFF80`-`0xFFFE`. ROM, I/O registers, video memory, the save, and mirrors of
  RAM are refused. A write must fit entirely inside one region.
- **Size.** At most 64 bytes per call and 256 bytes per tap.
- **The player.** Writing is off until the player turns on **Settings > Packs
  can change games**. Then the first time a pack is about to change a game, the
  app asks, naming the pack and the game; the answer is kept for that pack and
  game. If they allow it, a save state is made just before (**Load state >
  Before pack changes**). The pack picker marks packs that "can modify the game".
- **Timing.** Writes happen on the emulator thread between frames, so they never
  race the emulator, and the game sees them on its next frame.
- **Logged.** Every write is logged with its address and size:
  `adb logcat -s Companion`.

Writes don't roll back: if an action writes and then errors, what it wrote
stays. Write last, after every check.

Find real addresses before you write anything, and check a "safe to act"
condition first: a wrong write can crash the game or spoil the save. See
[`examples/zelda-items`](examples/zelda-items) and its notes on finding addresses.

