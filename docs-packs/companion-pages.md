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
  `update(ui)`, with the same instruction and memory limits.
- **Errors:** an error or a missing function is logged
  (`adb logcat -s EmuThread`) and the screen stays as it was.
