# game.lua: a script that runs against the game

A pack's `main.lua` feeds the companion screen. A pack can also ship a **`game.lua`** that
runs against the game itself, every emulated frame, in its own Lua sandbox (its own globals,
budgets and errors: a broken `game.lua` never touches the companion screen).

```json
{
  "formatVersion": 4,
  "gameScript": "game.lua",
  ...
}
```

`gameScript` needs `formatVersion` 4 (older apps refuse the pack; set `minAppVersion` in the
catalog). The file must exist in the pack. It is part of the pack's fingerprint, so changing it
asks the player again before it may write.

## Hooks

All optional. The sandbox is the same as `main.lua`'s (`mem`, `gfx`, `lz77`, `log`,
`pack.require`).

| Hook | When | What it's for |
|---|---|---|
| `init()` | once, after load | set up state. Read-only. |
| `frame(view)` | after each emulated frame | read and, if allowed, write the game's RAM |
| `wide(view)` | after each emulated frame, only while **Wide view** is on | describe the world for the wide margins. Read-only. |

`view` is a table: `frame` (the frame counter, `frame()` only), `width` (the picture's width in
pixels), `left` (columns before the game's own 240), and for `wide()` also `match`
(0 to 1, how well last frame's world predicted the game's edge columns; handy while calibrating).

Each hook gets 100,000 Lua instructions and 2 ms per call. An error or an overrun skips that
frame's call (`adb logcat -s GBADuoScript GBADuoCore EmuThread`); five in a row switch `game.lua`
off until the next game start.

## Writing the game's memory from frame()

Same rules as actions (see [Changing the game](companion-pages.md#changing-the-game)):
`"permissions": ["memory-write"]`, **Settings > Packs can change games**, and the player's answer
for this pack and game. Until they have answered, `frame()` runs with writes refused
(`mem.write8` and friends return `false` and a reason). The same RAM-only regions apply.
What differs: at most **256 bytes per frame**, and only the first write is logged.
`init()`, the top level and `wide()` can never write.

## wide(view): painting more of the world

Wide view draws the game's own 240x160 frame in the middle of a wider picture (up to 400 px, set
from the screen) and paints the margins from what `wide()` describes. Return `nil` or `false`
for "no world right now" (battle, menu, unknown map): the margins stay black. Otherwise return a
table, every field an integer (bus addresses are plain numbers):

| Field | Meaning |
|---|---|
| `x`, `y` | world pixel of the screen's top-left |
| `grid`, `gridW`, `gridH` | the map: u16 cells, row-major, one 16x16 metatile each |
| `idMask` | metatile id = cell & `idMask` |
| `border`, `borderW`, `borderH` | block repeated outside the map |
| `borderShift` (optional, 0) | the block index of cell `c` is `(c - borderShift) mod size` |
| `undefinedId` (optional, none) | a cell with this id is outside the map: use the border |
| `metatiles`, `metatiles2`, `split` | ids below `split` read from `metatiles`, the rest from `metatiles2` (16 bytes each) |
| `tiles` | 4bpp tile data, 32 bytes a tile (normally VRAM `0x06000000`) |
| `palette` | 16 banks of 16 BGR555 colours (normally `0x05000000`) |

A metatile is 8 u16 tile entries: bottom layer TL, TR, BL, BR, then top layer TL, TR, BL, BR.
A tile entry: bits 0-9 tile, 10 h-flip, 11 v-flip, 12-15 palette bank. A pixel is the top
layer's if that is not colour 0, else the bottom layer's, else the backdrop.

The app checks your answer against the game: the 16 columns on the frame's edges are predicted
and compared with what the emulator drew. Below 85% for four frames in a row the margins hide,
and they come back after eight good ones. So a wrong world looks like "no wide view", not like
garbage. NPCs and the player are not part of the world, so they don't appear in the margins.

### Turning Wide view on and off

Wide view is a per-game setting the **player** switches from the pack's companion screen, so give
the layout a button. The app handles the reserved action `wideView` itself (your Lua is not
involved): `"args"` is `"on"`, `"off"` or `"toggle"` (the default). Every snapshot carries the
state as built-in values: `_wide` (1 or 0) and `_wideLabel` (`ON`, `OFF`, or `N/A` when the
pack has no `wide()`).

```json
{
  "type": "box", "class": "wide-pill",
  "onClick": { "call": "wideView", "args": ["toggle"] },
  "children": [ { "type": "text", "text": "WIDE {_wideLabel}" } ]
}
```

`pokemon-firered-companion/game.lua` is a worked example (FireRed 1.0): it reads the map the
game keeps in RAM (`gBackupMapLayout`) and the tilesets from the ROM layout. It follows the camera
from the BG scroll registers (`mem.u16(0x04000014)`), anchored whenever the player stands still,
and keeps following across a map connection: the game rebases the tile coordinates there (the
player's current and previous tile, by the same whole number of tiles) while the scroll carries
on, so the script recovers the rebase from where the camera sits between those two tiles. Set
`DEBUG = true` at the top to log the frames where the prediction misses (`adb logcat -s GBADuoPack`).
