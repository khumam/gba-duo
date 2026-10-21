# Styling companion packs with CSS

The companion screen is rendered natively with Jetpack Compose, styled by real CSS
syntax: **structure in `layout.json`, look in `style.css`, logic in Lua.**
Anything a pack doesn't style looks the way layouts always did, so existing
packs need no changes.

## Setup

List your stylesheets in `manifest.json` (needs `formatVersion: 2`). They're
applied in order, after the app's built-in styles
(`app/src/main/assets/companion/default.css`).

```json
{
  "formatVersion": 2,
  "layout": "layout.json",
  "styles": ["style.css"],
  "script": "main.lua"
}
```

`url(...)` in a stylesheet resolves relative to that stylesheet; in a widget's
inline `style` it resolves relative to the pack folder. Paths can't leave the
pack (`../` outside it, absolute paths and `http://` are ignored).

## Examples

[`examples/`](examples/) has two complete packs, each with a
`sample-snapshot.json` showing the data its layout expects:

- [`classic`](examples/classic/): the original format, no CSS.
- [`party-css`](examples/party-css/): a styled party/bag screen (grid, tabs,
  gradients, borders, shadows, pixel sprites, bar labels). Tapping a party
  member opens a detail page ([Taps, pages and detail screens](companion-pages.md)).

Zip a folder's contents to install it as a pack. The app's tests render both
and compare them with reference images (`app/src/test/screenshots`), so their
look is pinned down; after an intended visual change, re-record with
`./gradlew :app:testDebugUnitTest --tests '*PackScreenshotTest' -PrecordScreenshots`.

## Hooking widgets to CSS

Every widget in `layout.json` accepts:

| Field | Purpose | Example |
|---|---|---|
| `id` | `#id` selector | `"id": "hp-panel"` |
| `class` | `.class` selectors, space-separated | `"class": "card party"` |
| `style` | Inline CSS (object or string); beats stylesheet rules | `"style": { "padding": "8px" }` or `"style": "padding: 8px"` |
| `classIf` | Adds a class while a binding is truthy | `"classIf": { "low": "$hpLow" }` |
| `bindStyle` | CSS values from bindings, re-applied each tick; beats `style` | `"bindStyle": { "--type": "{typeColor}" }` |
| `onClick` | What tapping it does: set UI state, change page, call Lua ([details](companion-pages.md)) | `"onClick": { "navigate": "detail" }` |

Element names for selectors are the widget types, plus parts the renderer generates:

| Element | What it is |
|---|---|
| `column`, `row`, `box`, `list`, `tabs`, `pages`, `button`, `text`, `bar`, `image`, `spacer` | Widgets |
| `bar > fill`, `bar > label` | A bar's fill (width = value/max) and its `label` text |
| `tabs > tab-bar > tab` | The tab strip and each tab; the selected one matches `tab:active` |
| `tabs > tab-content` | The area showing the selected tab |
| `list > empty` | A list's `empty` text |
| `button > text.label` | A button's `text` |

Each part also has a class of the same name, so `.fill`, `.tab`, etc. work too.

`box` takes either `"child": {...}` (the original form: a frame around one child)
or `"children": [...]` (a flex column by default). It may also be empty.

## Example

```json
{
  "type": "list", "class": "party", "items": "$party",
  "template": {
    "type": "box", "class": "mon", "classIf": { "fainted": "$fainted" },
    "bindStyle": { "--type": "{typeColor}" },
    "children": [
      { "type": "image", "class": "sprite", "src": "$sprite" },
      { "type": "text", "class": "name", "text": "{name}" },
      { "type": "bar", "value": "$hp", "maxKey": "$maxHp", "label": "{hp}/{maxHp}" }
    ]
  }
}
```

```css
:root        { --accent: #e63946; --panel: #1d1f2b; background: #0b0c10; }
@font-face   { font-family: "Pixel"; src: url("fonts/Pixel.ttf"); }

.party       { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; padding: 12px; }
.mon         { gap: 4px; padding: 8px; align-items: center;
               background: var(--panel); border: 1px solid var(--type, #2e3245);
               border-radius: 10px; box-shadow: 0 2px 6px rgba(0, 0, 0, .5); }
.mon.fainted { opacity: .4; }
.mon:nth-child(odd) { background: #22263a; }
.sprite      { width: 64px; height: 64px; }
.name        { font-family: "Pixel"; text-transform: uppercase; }
bar          { width: 100%; height: 6px; border-radius: 3px; overflow: hidden; background: #333; }
bar > fill   { background: linear-gradient(90deg, var(--accent), #ffb703); transition: width 300ms ease-out; }
bar > label  { font-size: 9px; }
```

## Selectors

| Supported | Notes |
|---|---|
| `type`, `*`, `.class`, `#id`, compounds (`.mon.fainted`) | |
| Descendant `A B` and child `A > B` | |
| `:root`, `:empty`, `:first-child`, `:last-child`, `:nth-child()`, `:nth-last-child()` | `odd`, `even`, `3`, `2n+1`, `-n+3` |
| `:pressed`, `:active`, `:disabled` | `:pressed` = held down (tabs and widgets with `onClick`); `:active` = selected tab |

Cascade: built-in styles < pack stylesheets (by specificity, then source order)
< inline `style` < `bindStyle`. `!important` is ignored. Not supported: sibling
combinators (`+`, `~`), attribute selectors, pseudo-elements, `@import`,
`@supports`, `@container` (the rule or block is skipped with a warning).

## Properties

Units: `px` (= Android dp; unitless numbers are px), `%`, `em`, `rem`, `vw`, `vh`,
`vmin`, `vmax`, and `calc()`, `min()`, `max()`, `clamp()` (see
[Fitting any screen](#fitting-any-screen)). Colors: `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()`/`rgba()`
(comma or space syntax), `hsl()`/`hsla()`, named colors, `transparent`,
`currentcolor`. Custom properties (`--x`) and `var(--x, fallback)` work anywhere.

| Group | Properties |
|---|---|
| Layout mode | `display: flex \| grid \| stack \| block \| none` |
| Flexbox | `flex-direction`, `flex-wrap`, `flex-flow`, `justify-content`, `align-items`, `align-self`, `align-content`, `flex`, `flex-grow`, `flex-shrink`, `flex-basis`, `order`, `gap`, `row-gap`, `column-gap` |
| Grid | `grid-template-columns`, `grid-template-rows` (`px`, `%`, `fr`, `auto`, `repeat(n, …)`, `repeat(auto-fill \| auto-fit, …)`, `minmax(min, max)`), `grid-column`, `grid-row` (`span n`, `n`, `n / m`, `n / span m`), `justify-items`, `justify-self`, `place-items`, `place-self` |
| Positioning | `position: static \| relative \| absolute`, `top`, `right`, `bottom`, `left`, `inset`, `z-index` |
| Box | `width`, `height`, `min-*`, `max-*`, `aspect-ratio`, `margin` (incl. `auto`), `padding`, `box-sizing` |
| Background | `background`, `background-color`, `background-image` (`url()`, `linear-gradient()`), `background-size`, `background-position`, `background-repeat` |
| Border | `border`, `border-{top,right,bottom,left}`, `border-width/style/color`, `border-radius` (per corner, px or %) — styles `solid`, `dashed`, `dotted` |
| Effects | `opacity`, `box-shadow` (first shadow, no `inset`), `transform` (`translate`, `scale`, `rotate`), `transform-origin`, `overflow: hidden`, `visibility` |
| Transitions | `transition` for `opacity`, `color`, `background-color`, `transform`, `width`, `height` (`ms`/`s`, `linear`, `ease*`, `cubic-bezier()`, delay) |
| Text | `color`, `font-family` (+ `@font-face`), `font-size`, `font-weight`, `font-style`, `letter-spacing`, `line-height`, `text-align`, `text-transform`, `text-shadow`, `text-decoration`, `white-space`, `text-overflow`, `line-clamp` |
| Images | `object-fit`, `image-rendering: pixelated` (the default for `image`) |
| Bars | `accent-color` on the bar colors its fill (or style `bar > fill` directly) |

`display: stack` is GBADuo-specific: children are layered on top of each other
at the top-left (like Android's FrameLayout); use `place-items` to center or
stretch them.

## Fitting any screen

The companion screen isn't always the same size: the Thor's bottom screen is
about 538 × 468, a phone's second display or the main screen (when the game is
on the bottom one) can be much larger or portrait. Three tools adapt a pack to
the screen it lands on. Everything here is measured on the **companion screen
itself**, in CSS px, not the whole device.

### `@media`

```css
.party { display: grid; grid-template-columns: 1fr 1fr; }

@media (max-width: 400px) {
  .party { grid-template-columns: 1fr; }
}

@media (orientation: portrait) and (min-height: 700px) {
  .header { font-size: 22px; }
}
```

- Features: `width`, `height`, `min-`/`max-width`, `min-`/`max-height`,
  `orientation` (`portrait`, `landscape`), `aspect-ratio`,
  `min-`/`max-aspect-ratio`, and range syntax: `(width >= 400px)`,
  `(400px <= width < 600px)`.
- `and`, comma lists (match any), `not`, `only`, the media types `all` and
  `screen`. `@media` blocks can nest.
- Lengths are px, or `em`/`rem` (14px each, like a browser's default).
- Rules inside a block that matches take part in the cascade like any other
  rule (specificity, then source order). They switch on and off by themselves
  when the screen changes size.
- A condition with anything else (`hover`, `resolution`, `or`, `%`) skips the
  whole block with a warning.

### `calc()`, `min()`, `max()`, `clamp()`

```css
.card    { width: calc(100% - 24px); }
.title   { font-size: clamp(14px, 4vw, 22px); }
.content { padding: max(8px, 2vw); }
```

- `+` and `-` need spaces around them (`calc(100% - 24px)`), like CSS.
- `*` and `/` take a plain number on one side (`calc(2 * 10px)`, `calc(100% / 3)`).
- `%` works in `calc()` for sizes, margins and offsets, resolved against the
  containing box in layout. `min()`, `max()` and `clamp()` can't compare `%`
  with px yet: `min(50%, 300px)` is refused (the declaration is dropped with a
  warning). `min(50%, 80%)` and anything without `%` work.
- A negative result for padding, border widths, gaps, radii or font sizes
  becomes 0, as in CSS.

### Grids that pick their column count

```css
.items {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 8px;
}
```

- `repeat(auto-fill, …)` makes as many columns as fit the container's width
  (at least one). The tracks inside need a fixed size: `120px`, or
  `minmax(120px, …)`.
- `repeat(auto-fit, …)` does the same but drops the columns no item uses, so a
  short list stretches across the row instead of leaving empty columns.
- `minmax(min, max)` keeps a track at least `min` wide; with `1fr` as the max
  it shares the leftover space like `1fr` does.
- One `auto-fill`/`auto-fit` per track list.

## Differences from browsers

- `font-size` is in sp, so the system text-size setting scales text.
- `box-sizing` defaults to `border-box`.
- `min-width`/`min-height: auto` mean 0 (no min-content sizing), baseline
  alignment acts like `start`, `padding` doesn't take `%`.
- Grid uses row-major auto-placement; there's no `grid-template-areas`.
- Unsupported (skipped with a warning): `@keyframes`/`animation`, `filter`,
  radial gradients, multiple backgrounds/shadows.

## Debugging

Every skipped rule, unknown property or invalid value is logged:

```
adb logcat -s Companion PackStyles
```
